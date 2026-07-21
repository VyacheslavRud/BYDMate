package com.bydmate.app.navdata

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the public accessibility surface of the installed Waze route screen.
 *
 * The ids below are present in Waze 5.21.0.0 and deliberately cover both the normal route bar
 * and the minimized ETA bar. Every value is optional: Waze can hide individual panels, change
 * layout for landscape/cluster displays, or expose a container whose useful text is in a child.
 */
object WazeAccessibilityReader {
    private const val MAX_FALLBACK_TREE_NODES = 256
    private val COMPOSE_TEST_TAG = Regex("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$")
    private val SPEED_LIMIT = Regex("""(?<![-+\d])(\d{1,3})(?!\d)""")
    private val NUMBERED_EXIT = Regex(
        """(?<!\d)(\d{1,2})(?:[-‑ ]?(?:й|я|е|st|nd|rd|th))?\s+(?:съезд|exit)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT_DIRECTIONS = setOf("LEFT", "RIGHT", "STRAIGHT", "U-TURN", "U TURN")
    private val MANEUVER_RESOURCE_HINT = Regex(
        "(?:^|_)(?:nav|navigation|maneuver|direction|turn|uturn|u_turn|keep|bear|fork|" +
            "straight|roundabout|exit|arrow)(?:_|$)",
        RegexOption.IGNORE_CASE,
    )
    private val routeAnchorSuffixes = listOf(
        "navBarDistance",
        "navBarStreetLine",
        "navBarDirectionText",
        "navBarInstructionText",
        "navBarInstruction",
        "instructionView",
        "navBarDirection",
    )

    data class Fields(
        val maneuver: String?,
        val maneuverDistance: String?,
        val street: String?,
        val remainingDistance: String?,
        val remainingTime: String?,
        val arrivalTime: String?,
        val speedLimit: String?,
        val exitNumber: String?,
    )

    /**
     * Privacy-safe shape census of the last route-tree read performed on the chosen Waze window.
     *
     * The live HUD keeps distance and street but never a maneuver, which means this reader returns
     * [Fields] whose `maneuver` is absent or unrecognized. Only counts and booleans distinguish
     * "the maneuver id does not exist on this Waze build" from "the node exists but exposes no
     * semantic value" from "the bounded fallback scan hit its node cap". No parsed string, street
     * or instruction is retained.
     */
    data class Census(
        val readAtMs: Long = 0L,
        val maneuverNodes: Int = 0,
        val maneuverVisibleNodes: Int = 0,
        val maneuverValues: Int = 0,
        val maneuverRecognized: Boolean = false,
        val distancePresent: Boolean = false,
        val streetPresent: Boolean = false,
        val fallbackScanned: Boolean = false,
        val fallbackNodesVisited: Int = 0,
        val fallbackCapped: Boolean = false,
        val fallbackValues: Int = 0,
        val fallbackRecognized: Boolean = false,
        /**
         * Values the scan parsed to a real maneuver code but refused because that code is not a
         * steering direction. A non-zero count is the signature of the Sea Lion 07 defect: a text
         * panel that used to be published as the route's maneuver.
         */
        val fallbackNonDirectional: Int = 0,
    )

    @Volatile private var latestCensus = Census()

    fun census(): Census = latestCensus

    /** Test hook; the reader never clears its own census during a route. */
    internal fun resetCensus() {
        latestCensus = Census()
    }

    private data class ManeuverScan(
        val text: String?,
        val nodes: Int,
        val visibleNodes: Int,
        val values: Int,
        val recognized: Boolean,
    )

    private data class FallbackScan(
        val text: String?,
        val visited: Int,
        val capped: Boolean,
        val values: Int,
        val nonDirectional: Int,
    )

    /** Stable ranking for choosing between multiple Waze windows/displays. An anchor-only
     *  surface stays eligible, while a complete current route bar wins over a stale icon or
     *  minimized shell. No field contents are retained or logged. */
    internal fun guidanceScore(fields: Fields?, hasAnchor: Boolean): Int {
        if (fields == null) return if (hasAnchor) 1 else 0
        var score = if (hasAnchor) 1 else 0
        if (!fields.maneuver.isNullOrBlank()) {
            score += 40
            if (NavManeuverCodes.fromInstructionText(fields.maneuver) != 0) score += 5
        }
        if (!fields.maneuverDistance.isNullOrBlank()) score += 30
        if (!fields.street.isNullOrBlank()) score += 20
        if (!fields.remainingDistance.isNullOrBlank()) score += 6
        if (!fields.remainingTime.isNullOrBlank()) score += 6
        if (!fields.arrivalTime.isNullOrBlank()) score += 4
        if (!fields.speedLimit.isNullOrBlank()) score += 2
        if (!fields.exitNumber.isNullOrBlank()) score += 2
        return score
    }

    /**
     * [recordCensus] is set only for the single window the feed actually consumes. Window scoring
     * in `findNavigatorRoot` reads every Waze surface and must not overwrite the census with a
     * minimized or stale shell.
     */
    fun read(root: AccessibilityNodeInfo?, recordCensus: Boolean = false): Fields? {
        if (root == null) return null
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            ?.takeIf(NavPackages::isNavigationPackage)
            ?: return null
        val maneuverRead = maneuverScan(
            root,
            "$pkg:id/navBarDirectionText",
            "$pkg:id/navBarInstructionText",
            "$pkg:id/navBarInstruction",
            "$pkg:id/instructionView",
            "$pkg:id/navBarDirection",
        )
        val exactManeuver = maneuverRead.text
        val maneuverDistance = firstValue(root, "$pkg:id/navBarDistance")
        val street = firstValue(
            root,
            "$pkg:id/navBarStreetLine",
            "$pkg:id/navBarTowardStreetLine",
        )
        // Waze resource ids vary between standalone, Automotive and vendor builds. The Sea Lion
        // build exposes distance/street under known ids but keeps the direction on an unlisted
        // child node, which previously left every route at maneuver=0. Once a known route anchor
        // is present, perform one bounded visible-tree scan for a semantically valid direction.
        val exactManeuverRecognized = maneuverRead.recognized
        val fallbackScan = if (!exactManeuverRecognized &&
            (maneuverDistance != null || street != null)
        ) {
            fallbackManeuverScan(root)
        } else {
            null
        }
        val fallbackManeuver = fallbackScan?.text
        if (recordCensus) {
            latestCensus = Census(
                readAtMs = System.currentTimeMillis(),
                maneuverNodes = maneuverRead.nodes,
                maneuverVisibleNodes = maneuverRead.visibleNodes,
                maneuverValues = maneuverRead.values,
                maneuverRecognized = exactManeuverRecognized,
                distancePresent = maneuverDistance != null,
                streetPresent = street != null,
                fallbackScanned = fallbackScan != null,
                fallbackNodesVisited = fallbackScan?.visited ?: 0,
                fallbackCapped = (fallbackScan?.capped == true),
                fallbackValues = fallbackScan?.values ?: 0,
                fallbackRecognized = fallbackManeuver != null,
                fallbackNonDirectional = fallbackScan?.nonDirectional ?: 0,
            )
        }
        // Keep the exact raw value as a final fallback so the route anchor remains observable,
        // but prefer a semantic direction found elsewhere when the known id contains only a
        // generic label or inaccessible icon name.
        val maneuver = fallbackManeuver ?: exactManeuver
        val remainingDistance = firstValue(
            root,
            "$pkg:id/minimizedEtaBarDistanceToDestination",
            "$pkg:id/minimizedEtaBarOfflineDistanceToDestination",
            "$pkg:id/etaBarDistanceToDestination",
            "$pkg:id/lblDistanceToDestination",
        )
        val remainingTime = firstValue(
            root,
            "$pkg:id/minimizedEtaBarTimeToDestination",
            "$pkg:id/etaBarTimeToDestination",
            "$pkg:id/lblTimeToDestination",
        )
        val arrivalTime = firstValue(
            root,
            "$pkg:id/minimizedEtaBarArrivalTime",
            "$pkg:id/etaBarArrivalTime",
            "$pkg:id/lblArrivalTime",
        )
        val speedLimit = firstValue(
            root,
            "$pkg:id/speedLimitWarn",
            "$pkg:id/speedLimitWarnUsOverlay",
        )?.let(::numericSpeedLimit)

        // ETA may remain visible in preview/search UI. A maneuver bar field is the honest signal
        // that active guidance is available for HUD output.
        if (maneuver == null && maneuverDistance == null && street == null) return null
        return Fields(
            maneuver = maneuver,
            maneuverDistance = maneuverDistance,
            street = street,
            remainingDistance = remainingDistance,
            remainingTime = remainingTime,
            arrivalTime = arrivalTime,
            speedLimit = speedLimit,
            exitNumber = numberedExit(maneuver),
        )
    }

    /**
     * Direction carried by the event that changed Waze's route bar.
     *
     * Several Waze/DiLink layouts update the distance node first and emit the arrow as a separate
     * event whose semantic value is no longer present by the time the whole window is reread. The
     * event is accepted only from the exact navigation package; raw values never leave memory.
     */
    internal fun maneuverFromEvent(event: AccessibilityEvent?): Int {
        if (event == null) return 0
        val pkg = runCatching { event.packageName?.toString() }.getOrNull()
            ?.takeIf(NavPackages::isNavigationPackage)
            ?: return 0
        val values = linkedSetOf<String>()
        runCatching { event.text }.getOrNull().orEmpty().forEach { value ->
            value?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
        }
        runCatching { event.contentDescription }.getOrNull()
            ?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
        val source = runCatching { event.source }.getOrNull()
        if (source != null) {
            try {
                values += maneuverNodeValues(source, depth = EVENT_SOURCE_SCAN_DEPTH)
            } finally {
                recycle(source)
            }
        }
        return values.asSequence()
            .map(NavManeuverCodes::parseInstructionText)
            .filter { it.gaode != 0 }
            .maxWithOrNull(
                compareBy<NavManeuverCodes.ParseResult> { it.recognizedCodes.size }
                    .thenBy { it.gaode != NavManeuverCodes.GAODE_STRAIGHT },
            )
            ?.gaode
            ?: 0
    }

    /** Shared route-window discriminator used by both the reader and window selection. */
    fun hasRouteAnchor(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            ?.takeIf(NavPackages::isNavigationPackage)
            ?: return false
        for (suffix in routeAnchorSuffixes) {
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix")
            }.getOrNull() ?: continue
            val found = nodes.any { node -> runCatching { node.isVisibleToUser }.getOrDefault(false) }
            nodes.forEach(::recycle)
            if (found) return true
        }
        return false
    }

    private fun firstValue(root: AccessibilityNodeInfo, vararg ids: String): String? {
        for (id in ids) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
                ?: continue
            var found: String? = null
            for (node in nodes) {
                if (found == null && runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                    found = runCatching { nodeValue(node, depth = 2) }.getOrNull()
                }
                recycle(node)
            }
            if (found != null) return found
        }
        return null
    }

    /** Prefer a full spoken instruction over a short arrow description when both are visible.
     *  On Waze 5.x the icon and text can coexist briefly during a reroute; the text describes the
     *  current first maneuver while the icon node may still expose its previous LEFT/RIGHT value. */
    private fun maneuverScan(root: AccessibilityNodeInfo, vararg ids: String): ManeuverScan {
        data class Value(
            val text: String,
            val idIndex: Int,
            val nodeIndex: Int,
            val valueIndex: Int,
            val parsed: NavManeuverCodes.ParseResult,
        )
        val values = mutableListOf<Value>()
        var nodeTotal = 0
        var visibleTotal = 0
        ids.forEachIndexed { idIndex, id ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
                ?: return@forEachIndexed
            nodeTotal += nodes.size
            nodes.forEachIndexed { nodeIndex, node ->
                if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                    visibleTotal++
                    runCatching { maneuverNodeValues(node, depth = 2) }
                        .getOrDefault(emptyList())
                        .forEachIndexed { valueIndex, value ->
                            values += Value(
                                value,
                                idIndex,
                                nodeIndex,
                                valueIndex,
                                NavManeuverCodes.parseInstructionText(value),
                            )
                        }
                }
                recycle(node)
            }
        }
        val recognized = values.filter { it.parsed.gaode != 0 }
        val text = when {
            values.isEmpty() -> null
            recognized.isEmpty() -> values.minWith(
                compareBy<Value> { it.idIndex }.thenBy { it.nodeIndex }.thenBy { it.valueIndex },
            ).text
            else -> recognized.maxWith(
                compareBy<Value> { isDetailedInstruction(it.text) }
                    .thenBy { it.parsed.recognizedCodes.size }
                    .thenBy { it.text.length }
                    .thenBy { -it.idIndex }
                    .thenBy { -it.nodeIndex }
                    .thenBy { -it.valueIndex },
            ).text
        }
        return ManeuverScan(
            text = text,
            nodes = nodeTotal,
            visibleNodes = visibleTotal,
            values = values.size,
            recognized = recognized.isNotEmpty(),
        )
    }

    /**
     * Unlike [maneuverScan], this walks the whole visible Waze window rather than the maneuver ids,
     * so it must accept only a value whose primary meaning is a steering direction.
     *
     * Sea Lion 07 runtime evidence (2026-07-21, three exports across one drive): the maneuver ids
     * carry no semantic value at all on Waze 4.105 (`maneuverValues=0`), the arrow is image-only,
     * and this scan reached a non-maneuver panel whose text parsed as ARRIVE(48). That fake
     * terminal maneuver held for the whole drive — it mapped to `f28=OMITTED` so the windshield
     * card kept distance, street and speed but never an arrow, and because it is non-zero it also
     * suppressed the visual arrow classifier, which is the only source that can read an image-only
     * arrow. Restricting the scan to directional codes leaves `maneuver=0`, which is both honest
     * and what re-enables the visual path.
     */
    private fun fallbackManeuverScan(root: AccessibilityNodeInfo): FallbackScan {
        var visited = 0
        var seenValues = 0
        var nonDirectional = 0
        var result: String? = null

        fun visit(node: AccessibilityNodeInfo) {
            if (result != null || visited++ >= MAX_FALLBACK_TREE_NODES) return
            if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return
            val values = maneuverSemanticValues(node)
            seenValues += values.size
            values.forEach { value ->
                val parsed = NavManeuverCodes.parseInstructionText(value)
                if (result != null) return@forEach
                when {
                    NavManeuverCodes.isDirectionalManeuver(parsed.gaode) -> result = value
                    parsed.gaode != 0 -> nonDirectional++
                }
            }
            if (result != null) return
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                if (result != null || visited >= MAX_FALLBACK_TREE_NODES) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                try {
                    visit(child)
                } finally {
                    recycle(child)
                }
            }
        }

        visit(root)
        // Accessibility traversal order mirrors the visual route hierarchy; return the first
        // semantic maneuver found inside that anchored route tree.
        return FallbackScan(
            text = result,
            visited = visited,
            capped = result == null && visited >= MAX_FALLBACK_TREE_NODES,
            values = seenValues,
            nonDirectional = nonDirectional,
        )
    }

    /** Unlike generic text fields, maneuver nodes need every public semantic channel. Depending
     *  on the Waze/DiLink build, the arrow can be exposed as text, content/state description,
     *  tooltip, action label or a Compose test tag promoted to viewIdResourceName. Detailed text
     *  still wins later in [maneuverScan]. */
    private fun maneuverNodeValues(node: AccessibilityNodeInfo, depth: Int): List<String> {
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return emptyList()
        val values = maneuverSemanticValues(node)
        if (depth <= 0) return values.toList()
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                values += maneuverNodeValues(child, depth - 1)
            } finally {
                recycle(child)
            }
        }
        return values.toList()
    }

    /** Privacy-safe direction discovery: values are parsed in memory and are never logged. A
     *  resource id is accepted only when its tail looks navigation-specific, so unrelated Waze
     *  controls such as LEFT_PANEL cannot become a maneuver. */
    private fun maneuverSemanticValues(node: AccessibilityNodeInfo): LinkedHashSet<String> =
        linkedSetOf<String>().apply {
            fun addValue(value: CharSequence?, requireUsefulDescription: Boolean = false) {
                value?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
                    if (!requireUsefulDescription || isUsefulDescription(it)) add(it)
                }
            }

            addValue(runCatching { node.text }.getOrNull())
            addValue(runCatching { node.contentDescription }.getOrNull(), true)
            addValue(runCatching { node.hintText }.getOrNull(), true)
            addValue(runCatching { node.paneTitle }.getOrNull(), true)
            addValue(runCatching { node.tooltipText }.getOrNull(), true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                addValue(runCatching { node.stateDescription }.getOrNull(), true)
            }
            runCatching { node.actionList }.getOrNull().orEmpty().forEach { action ->
                addValue(runCatching { action.label }.getOrNull(), true)
            }
            runCatching { node.viewIdResourceName }.getOrNull()
                ?.substringAfterLast('/')
                ?.takeIf { MANEUVER_RESOURCE_HINT.containsMatchIn(it) }
                ?.let(::add)
        }

    private fun isDetailedInstruction(value: String): Boolean =
        value.trim().uppercase() !in SHORT_DIRECTIONS

    private fun nodeValue(node: AccessibilityNodeInfo, depth: Int): String? {
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return null
        runCatching { node.text?.toString()?.trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { node.contentDescription?.toString()?.trim() }.getOrNull()
            ?.takeIf(::isUsefulDescription)?.let { return it }
        if (depth <= 0) return null
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                nodeValue(child, depth - 1)?.let { return it }
            } finally {
                recycle(child)
            }
        }
        return null
    }

    /** Compose-style test tags contain at least one underscore; LEFT and BRNO remain valid text. */
    private fun isUsefulDescription(value: String): Boolean =
        value.isNotEmpty() && (
            !COMPOSE_TEST_TAG.matches(value) ||
                NavManeuverCodes.fromInstructionText(value) != 0
            )

    private fun recycle(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }

    private fun numericSpeedLimit(value: String): String? = SPEED_LIMIT
        .find(value)?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 5..250 }
        ?.toString()

    private fun numberedExit(value: String?): String? = value?.let {
        NUMBERED_EXIT.find(it)?.groupValues?.get(1)
    }

    private const val EVENT_SOURCE_SCAN_DEPTH = 4
}
