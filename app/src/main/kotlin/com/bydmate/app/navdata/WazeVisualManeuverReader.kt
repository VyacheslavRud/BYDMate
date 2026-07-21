package com.bydmate.app.navdata

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Last-resort, privacy-safe direction reader for Waze builds whose arrow has no accessibility or
 * notification semantics. Only the small maneuver ImageView rectangle is sampled in memory. The
 * screenshot, crop and pixels are released immediately and are never logged or persisted.
 */
object WazeVisualManeuverReader {
    data class Diagnostics(
        val attemptedAtMs: Long? = null,
        val displayId: Int? = null,
        val targetSource: String? = null,
        val targetWidth: Int? = null,
        val targetHeight: Int? = null,
        val maneuverGaode: Int = 0,
        val horizontalShift: Float? = null,
        val foregroundRatio: Float? = null,
        val failure: String? = null,
    )

    internal data class Target(val displayId: Int, val bounds: Rect, val source: String)

    private data class Candidate(val rect: Rect, val source: String, val score: Int)

    data class Classification(
        val maneuverGaode: Int,
        val horizontalShift: Float,
        val foregroundRatio: Float,
    )

    /**
     * Attempt counters plus the last *completed* attempt.
     *
     * [diagnostics] is overwritten at request time with `failure="pending"`, so an export taken
     * while a screenshot is in flight loses the previous verdict. Distinguishing "the visual path
     * is never requested" from "it is requested and the screenshot fails" from "it succeeds but
     * the shape is unrecognized" is exactly what a single instrumented route has to answer.
     */
    data class Counters(
        val requested: Int = 0,
        val started: Int = 0,
        val completed: Int = 0,
        val lastCompleted: Diagnostics? = null,
    )

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastAttemptElapsedMs: Long = 0L
    @Volatile private var latest = Diagnostics()
    @Volatile private var latestCounters = Counters()

    fun diagnostics(): Diagnostics = latest

    fun counters(): Counters = latestCounters

    /** Test hook; the reader never clears its own counters during a route. */
    internal fun resetCounters() {
        latestCounters = Counters()
        latest = Diagnostics()
    }

    @Synchronized
    private fun countRequest() {
        latestCounters = latestCounters.copy(requested = latestCounters.requested + 1)
    }

    @Synchronized
    private fun countStart() {
        latestCounters = latestCounters.copy(started = latestCounters.started + 1)
    }

    @Synchronized
    private fun countCompletion(result: Diagnostics) {
        latestCounters = latestCounters.copy(
            completed = latestCounters.completed + 1,
            lastCompleted = result,
        )
    }

    /** Schedules at most one bounded screenshot per second. Returns false when no safe crop exists. */
    fun request(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        callback: (Int) -> Unit,
    ): Boolean {
        countRequest()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val target = findTarget(root) ?: run {
            val result = Diagnostics(
                attemptedAtMs = System.currentTimeMillis(),
                failure = "maneuver_bounds_not_found",
            )
            latest = result
            countCompletion(result)
            return false
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (nowElapsed - lastAttemptElapsedMs < MIN_ATTEMPT_INTERVAL_MS) return false
            if (!inFlight.compareAndSet(false, true)) return false
            lastAttemptElapsedMs = nowElapsed
        }
        latest = Diagnostics(
            attemptedAtMs = System.currentTimeMillis(),
            displayId = target.displayId,
            targetSource = target.source,
            targetWidth = target.bounds.width(),
            targetHeight = target.bounds.height(),
            failure = "pending",
        )
        countStart()
        return runCatching {
            service.takeScreenshot(
                target.displayId,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        var wrapped: Bitmap? = null
                        var software: Bitmap? = null
                        try {
                            wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            val classification = software?.let { classify(it, target.bounds) }
                            val result = Diagnostics(
                                attemptedAtMs = System.currentTimeMillis(),
                                displayId = target.displayId,
                                targetSource = target.source,
                                targetWidth = target.bounds.width(),
                                targetHeight = target.bounds.height(),
                                maneuverGaode = classification?.maneuverGaode ?: 0,
                                horizontalShift = classification?.horizontalShift,
                                foregroundRatio = classification?.foregroundRatio,
                                failure = if (classification == null) "shape_unrecognized" else null,
                            )
                            latest = result
                            countCompletion(result)
                            classification?.maneuverGaode?.takeIf { it != 0 }?.let(callback)
                        } catch (_: Throwable) {
                            val result = latest.copy(failure = "bitmap_processing_failed")
                            latest = result
                            countCompletion(result)
                        } finally {
                            software?.recycle()
                            wrapped?.recycle()
                            buffer.close()
                            inFlight.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val result = latest.copy(failure = "screenshot_error_$errorCode")
                        latest = result
                        countCompletion(result)
                        inFlight.set(false)
                    }
                },
            )
            true
        }.getOrElse {
            val result = latest.copy(failure = "screenshot_request_failed")
            latest = result
            countCompletion(result)
            inFlight.set(false)
            false
        }
    }

    internal fun findTarget(root: AccessibilityNodeInfo): Target? {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            ?.takeIf(NavPackages::isNavigationPackage)
            ?: return null
        val displayId = runCatching {
            val window = root.window ?: return@runCatching 0
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.displayId else 0
            } finally {
                @Suppress("DEPRECATION") runCatching { window.recycle() }
            }
        }.getOrDefault(0)

        val candidates = mutableListOf<Candidate>()
        TARGET_IDS.forEachIndexed { index, suffix ->
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix")
            }.getOrNull().orEmpty()
            nodes.forEach { node ->
                try {
                    collectCandidateRects(node, "$suffix#exact", 200 - index, candidates)
                } finally {
                    recycle(node)
                }
            }
        }

        // Vendor builds rename the view but usually preserve a direction/maneuver token.
        if (candidates.isEmpty()) {
            var visited = 0
            fun visit(node: AccessibilityNodeInfo) {
                if (visited++ >= MAX_TREE_NODES) return
                val id = runCatching { node.viewIdResourceName }.getOrNull()
                    ?.substringAfterLast('/')
                    .orEmpty()
                if (MANEUVER_ID.containsMatchIn(id)) {
                    collectCandidateRects(node, "$id#semantic", 100, candidates)
                }
                val count = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until count) {
                    if (visited >= MAX_TREE_NODES) break
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    try {
                        visit(child)
                    } finally {
                        recycle(child)
                    }
                }
            }
            visit(root)
        }

        // The route distance is often a sibling of a drawable-only arrow. Walk its small parent
        // subtree before resorting to a coordinate guess; no text or pixels leave memory.
        if (candidates.isEmpty()) {
            val distanceNodes = runCatching {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/navBarDistance")
            }.getOrNull().orEmpty()
            distanceNodes.forEach { node ->
                try {
                    val parent = runCatching { node.parent }.getOrNull()
                    if (parent != null) {
                        try {
                            collectCandidateRects(
                                parent,
                                "navBarDistance#parent",
                                140,
                                candidates,
                                maxDepth = 3,
                            )
                        } finally {
                            recycle(parent)
                        }
                    }
                } finally {
                    recycle(node)
                }
            }
        }

        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { -abs(it.rect.width() - it.rect.height()) }
                .thenBy { -it.rect.width() * it.rect.height() },
        )
        if (best != null) return Target(displayId, best.rect, best.source)

        // Final bounded fallback: Waze places the arrow immediately above navBarDistance. Crop
        // only that square, excluding the distance text itself.
        val distanceNodes = runCatching {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/navBarDistance")
        }.getOrNull().orEmpty()
        var fallback: Rect? = null
        distanceNodes.forEach { node ->
            try {
                if (fallback == null && runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                    val distance = Rect()
                    node.getBoundsInScreen(distance)
                    val size = max(distance.width(), distance.height() * 3)
                        .coerceIn(MIN_TARGET_PX, MAX_TARGET_PX)
                    val left = distance.centerX() - size / 2
                    fallback = Rect(left, distance.top - size, left + size, distance.top)
                        .takeIf(::validBounds)
                }
            } finally {
                recycle(node)
            }
        }
        return fallback?.let { Target(displayId, it, "navBarDistance#above") }
    }

    private fun collectCandidateRects(
        node: AccessibilityNodeInfo,
        source: String,
        baseScore: Int,
        out: MutableList<Candidate>,
        maxDepth: Int = 2,
    ) {
        fun addCandidate(target: AccessibilityNodeInfo, depth: Int) {
            if (!runCatching { target.isVisibleToUser }.getOrDefault(false)) return
            val rect = Rect().also(target::getBoundsInScreen)
            if (!validBounds(rect)) return
            val image = runCatching { target.className?.toString() }.getOrNull()
                ?.contains("ImageView", ignoreCase = true) == true
            val score = baseScore + (if (image) 40 else 0) - depth * 5
            out += Candidate(rect, source, score)
        }
        fun visit(target: AccessibilityNodeInfo, depth: Int) {
            addCandidate(target, depth)
            if (depth >= maxDepth) return
            val count = min(runCatching { target.childCount }.getOrDefault(0), 12)
            for (i in 0 until count) {
                val child = runCatching { target.getChild(i) }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    recycle(child)
                }
            }
        }
        visit(node, 0)
    }

    private fun validBounds(rect: Rect): Boolean =
        rect.width() in MIN_TARGET_PX..MAX_TARGET_PX &&
            rect.height() in MIN_TARGET_PX..MAX_TARGET_PX &&
            rect.left >= 0 && rect.top >= 0

    private fun classify(bitmap: Bitmap, crop: Rect): Classification? {
        val bounded = Rect(
            crop.left.coerceIn(0, bitmap.width),
            crop.top.coerceIn(0, bitmap.height),
            crop.right.coerceIn(0, bitmap.width),
            crop.bottom.coerceIn(0, bitmap.height),
        )
        if (bounded.width() < MIN_TARGET_PX || bounded.height() < MIN_TARGET_PX) return null
        val sampleW = min(MAX_SAMPLE_SIDE, bounded.width())
        val sampleH = min(MAX_SAMPLE_SIDE, bounded.height())
        val pixels = IntArray(sampleW * sampleH)
        for (y in 0 until sampleH) {
            val sourceY = bounded.top + y * bounded.height() / sampleH
            for (x in 0 until sampleW) {
                val sourceX = bounded.left + x * bounded.width() / sampleW
                pixels[y * sampleW + x] = bitmap.getPixel(sourceX, sourceY)
            }
        }
        return classifyPixels(sampleW, sampleH, pixels)
    }

    /**
     * Classifies a standalone notification icon without requiring an accessibility window.
     * The caller retains ownership of [bitmap]; pixels are sampled in memory and never stored.
     */
    internal fun classifyBitmap(bitmap: Bitmap): Classification? {
        if (bitmap.isRecycled || bitmap.width < MIN_TARGET_PX || bitmap.height < MIN_TARGET_PX) {
            return null
        }
        return classify(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
    }

    /** Pure shape core used by tests. [pixels] are opaque ARGB samples. */
    internal fun classifyPixels(width: Int, height: Int, pixels: IntArray): Classification? {
        if (width < 16 || height < 16 || pixels.size != width * height) return null
        val border = ArrayList<Int>((width + height) * 2)
        for (x in 0 until width) {
            border += pixels[x]
            border += pixels[(height - 1) * width + x]
        }
        for (y in 1 until height - 1) {
            border += pixels[y * width]
            border += pixels[y * width + width - 1]
        }
        val backgroundBin = border.groupingBy(::colorBin).eachCount().maxByOrNull { it.value }?.key
            ?: return null
        val background = border.filter { colorBin(it) == backgroundBin }
        val bgR = background.map { it shr 16 and 0xff }.average()
        val bgG = background.map { it shr 8 and 0xff }.average()
        val bgB = background.map { it and 0xff }.average()
        val borderContrastMask = BooleanArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            val alpha = color ushr 24 and 0xff
            val dr = (color shr 16 and 0xff) - bgR
            val dg = (color shr 8 and 0xff) - bgG
            val db = (color and 0xff) - bgB
            borderContrastMask[index] = alpha >= 96 &&
                dr * dr + dg * dg + db * db >= COLOR_DISTANCE_SQ
        }
        val candidates = mutableListOf<Classification>()
        classifyForegroundMask(width, height, borderContrastMask)?.let { candidates += it }

        // Waze commonly draws a white/black arrow inside a coloured maneuver disc. Relative to
        // the surrounding panel the whole disc is one larger symmetric component, so the basic
        // background mask sees a circle and loses the turn. Isolate high- and low-luminance ink
        // as independent masks and prefer a directional shape when one is present.
        val bgLuma = luma(bgR, bgG, bgB)
        val brightThreshold = max(175.0, bgLuma + 55.0)
        val darkThreshold = min(80.0, bgLuma - 55.0)
        val brightMask = BooleanArray(pixels.size)
        val darkMask = BooleanArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            val alpha = color ushr 24 and 0xff
            if (alpha < 96) return@forEachIndexed
            val value = luma(
                (color shr 16 and 0xff).toDouble(),
                (color shr 8 and 0xff).toDouble(),
                (color and 0xff).toDouble(),
            )
            brightMask[index] = value >= brightThreshold
            darkMask[index] = value <= darkThreshold
        }
        classifyForegroundMask(width, height, brightMask)?.let { candidates += it }
        classifyForegroundMask(width, height, darkMask)?.let { candidates += it }

        return candidates.filter { it.maneuverGaode != 0 }
            .maxByOrNull { abs(it.horizontalShift) }
            ?: candidates.firstOrNull()
    }

    private fun luma(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

    internal fun classifyForegroundMask(
        width: Int,
        height: Int,
        mask: BooleanArray,
    ): Classification? {
        if (mask.size != width * height) return null
        val visited = BooleanArray(mask.size)
        var largest = IntArray(0)
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            val component = ArrayList<Int>()
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                val x = current % width
                val y = current / width
                fun add(nx: Int, ny: Int) {
                    if (nx !in 0 until width || ny !in 0 until height) return
                    val next = ny * width + nx
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        queue.add(next)
                    }
                }
                add(x - 1, y); add(x + 1, y); add(x, y - 1); add(x, y + 1)
            }
            if (component.size > largest.size) largest = component.toIntArray()
        }
        if (largest.size < MIN_FOREGROUND_PIXELS) return null
        val minX = largest.minOf { it % width }
        val maxX = largest.maxOf { it % width }
        val minY = largest.minOf { it / width }
        val maxY = largest.maxOf { it / width }
        val componentW = (maxX - minX + 1).coerceAtLeast(1)
        val componentH = (maxY - minY + 1).coerceAtLeast(1)
        if (componentW < 8 || componentH < 12) return null
        val upperCut = minY + componentH * 58 / 100
        val lowerCut = minY + componentH * 68 / 100
        val upper = largest.filter { it / width <= upperCut }
        val lower = largest.filter { it / width >= lowerCut }
        if (upper.size < MIN_SECTION_PIXELS || lower.size < MIN_SECTION_PIXELS) return null
        val upperX = upper.map { it % width }.average()
        val lowerX = lower.map { it % width }.average()
        val shift = ((upperX - lowerX) / componentW).toFloat()
        val ratio = largest.size.toFloat() / (width * height)
        val head = largest.filter { it / width <= minY + componentH * 45 / 100 }
        val tail = largest.filter { it / width >= minY + componentH * 62 / 100 }
        fun horizontalSpan(points: List<Int>): Int = if (points.isEmpty()) 0 else {
            points.maxOf { it % width } - points.minOf { it % width } + 1
        }
        val headSpan = horizontalSpan(head)
        val tailSpan = horizontalSpan(tail)
        val looksStraight = componentH * 100 >= componentW * 125 &&
            tailSpan >= 3 && headSpan * 100 >= tailSpan * 140
        val code = when {
            shift >= MIN_DIRECTION_SHIFT -> NavManeuverCodes.GAODE_RIGHT
            shift <= -MIN_DIRECTION_SHIFT -> NavManeuverCodes.GAODE_LEFT
            looksStraight -> NavManeuverCodes.GAODE_STRAIGHT
            else -> 0
        }
        return Classification(code, shift, ratio)
    }

    private fun colorBin(color: Int): Int =
        ((color shr 19) and 0x1f) shl 10 or
            (((color shr 11) and 0x1f) shl 5) or
            ((color shr 3) and 0x1f)

    private fun recycle(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION") runCatching { node.recycle() }
    }

    private val TARGET_IDS = listOf(
        "navBarDirection",
        "navBarDirectionIcon",
        "navBarManeuver",
        "navBarManeuverIcon",
        "navBarInstructionIcon",
        "instructionView",
    )
    private val MANEUVER_ID = Regex(
        "(?:direction|maneuver|instruction).*(?:icon|image|arrow)|" +
            "(?:icon|image|arrow).*(?:direction|maneuver|instruction)",
        RegexOption.IGNORE_CASE,
    )
    private const val MIN_ATTEMPT_INTERVAL_MS = 900L
    private const val MAX_TREE_NODES = 256
    private const val MIN_TARGET_PX = 24
    private const val MAX_TARGET_PX = 420
    private const val MAX_SAMPLE_SIDE = 128
    private const val COLOR_DISTANCE_SQ = 5_625.0 // Euclidean RGB distance >= 75.
    private const val MIN_FOREGROUND_PIXELS = 28
    private const val MIN_SECTION_PIXELS = 8
    private const val MIN_DIRECTION_SHIFT = 0.075f
}
