package com.bydmate.app.media

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import com.bydmate.app.navdata.NavManeuverCodes
import com.bydmate.app.navdata.WazeVisualManeuverReader

/**
 * Reads semantic and bounded in-memory maneuver evidence from Waze notification [RemoteViews].
 *
 * The Sea Lion DiLink build exposes distance/street as text, but can keep the arrow in an
 * ImageView action. Newer Waze builds put the arrow in a bitmap or non-semantic [Icon], so the
 * caller may classify that image without requiring Waze's accessibility window. We retain only a
 * recognized maneuver code and shape metrics. Notification text, streets, addresses, bitmaps and
 * rendered views never enter diagnostics or persistent storage.
 */
object WazeRemoteViewsManeuverReader {
    data class Diagnostics(
        val inspectedAtMs: Long? = null,
        val remoteViewsPresent: Boolean = false,
        val actionsInspected: Int = 0,
        val imageResourcesInspected: Int = 0,
        val imagePayloadsInspected: Int = 0,
        val visualClassifications: Int = 0,
        val maneuverGaode: Int = 0,
        val maneuverResource: String? = null,
        val visualSource: String? = null,
        val horizontalShift: Float? = null,
        val foregroundRatio: Float? = null,
    )

    internal data class ResourceCandidate(
        val viewName: String,
        val resourceName: String,
    )

    internal data class Inspection(
        val maneuverGaode: Int,
        val maneuverResource: String?,
        val semanticTextHints: List<String>,
        val actionsInspected: Int,
        val imageResourcesInspected: Int,
        val imagePayloadsInspected: Int,
        val visualClassifications: Int,
        val remoteViewsPresent: Boolean,
        val visualSource: String?,
        val horizontalShift: Float?,
        val foregroundRatio: Float?,
    )

    internal data class VisualCandidate(
        val classification: WazeVisualManeuverReader.Classification,
        val source: String,
    )

    @Volatile private var latest = Diagnostics()

    fun diagnostics(): Diagnostics = latest

    @Suppress("DEPRECATION")
    internal fun inspect(
        notification: Notification,
        resolveName: (Int) -> String?,
        classifyImage: ((Any, Int?) -> WazeVisualManeuverReader.Classification?)? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Inspection {
        val views = listOfNotNull(
            notification.bigContentView,
            notification.contentView,
            notification.headsUpContentView,
        ).distinctBy { System.identityHashCode(it) }
        if (views.isEmpty()) {
            latest = Diagnostics(inspectedAtMs = nowMs)
            return Inspection(0, null, emptyList(), 0, 0, 0, 0, false, null, null, null)
        }

        val resources = ArrayList<ResourceCandidate>()
        val visualCandidates = ArrayList<VisualCandidate>()
        val textHints = LinkedHashSet<String>()
        var actionCount = 0
        var imagePayloadCount = 0
        views.forEach { remoteViews ->
            extractActions(remoteViews).take(MAX_ACTIONS - actionCount).forEach actionLoop@{ action ->
                actionCount++
                val fields = collectFields(action.javaClass)
                val viewId = intFieldValue(fields, "viewId", action) ?: 0
                val viewName = safeResourceName(resolveName, viewId)
                val methodName = stringFieldValue(fields, "methodName", action).orEmpty()
                val value = valueObject(fields, action)

                val semanticTextAction = methodName.contains("text", ignoreCase = true) ||
                    methodName.contains("description", ignoreCase = true)
                if (value is CharSequence && textHints.size < MAX_TEXT_HINTS &&
                    semanticTextAction
                ) {
                    value.toString().trim()
                        .takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_HINT_CHARS }
                        ?.let(textHints::add)
                }

                if (!isImageMethod(methodName)) return@actionLoop
                val resourceId: Int? = when (value) {
                    is Int -> value
                    is Icon -> runCatching {
                        value.takeIf { it.type == Icon.TYPE_RESOURCE }?.resId
                    }.getOrNull()
                    else -> null
                }
                resourceId?.let { id ->
                    val resourceName = safeResourceName(resolveName, id)
                    if (resourceName.isNotEmpty() && resources.size < MAX_IMAGE_RESOURCES) {
                        resources += ResourceCandidate(viewName, resourceName)
                    }
                }

                if (classifyImage != null && imagePayloadCount < MAX_IMAGE_PAYLOADS) {
                    val payload = when {
                        value is Bitmap || value is Icon || value is Int ->
                            value to imagePayloadSource(value)
                        else -> bitmapPayload(remoteViews, fields, action)?.let { it to "bitmap_cache" }
                    }
                    if (payload != null) {
                        imagePayloadCount++
                        runCatching { classifyImage(payload.first, resourceId) }
                            .getOrNull()
                            ?.takeIf { it.maneuverGaode != 0 }
                            ?.let { visualCandidates += VisualCandidate(it, payload.second) }
                    }
                }
            }
        }

        val semantic = selectManeuver(resources)
        val visual = selectVisualManeuver(visualCandidates)
        // A resource name carrying an explicit navigation verb is stronger than shape inference.
        val selectedCode = semantic?.first ?: visual?.classification?.maneuverGaode ?: 0
        val selectedResource = semantic?.second ?: visual?.let { "visual:${it.source}" }
        latest = Diagnostics(
            inspectedAtMs = nowMs,
            remoteViewsPresent = true,
            actionsInspected = actionCount,
            imageResourcesInspected = resources.size,
            imagePayloadsInspected = imagePayloadCount,
            visualClassifications = visualCandidates.size,
            maneuverGaode = selectedCode,
            maneuverResource = selectedResource,
            visualSource = visual?.source,
            horizontalShift = visual?.classification?.horizontalShift,
            foregroundRatio = visual?.classification?.foregroundRatio,
        )
        return Inspection(
            maneuverGaode = selectedCode,
            maneuverResource = selectedResource,
            semanticTextHints = textHints.toList(),
            actionsInspected = actionCount,
            imageResourcesInspected = resources.size,
            imagePayloadsInspected = imagePayloadCount,
            visualClassifications = visualCandidates.size,
            remoteViewsPresent = true,
            visualSource = visual?.source,
            horizontalShift = visual?.classification?.horizontalShift,
            foregroundRatio = visual?.classification?.foregroundRatio,
        )
    }

    /** Repeated notification layouts must agree; conflicting left/right shapes are rejected. */
    internal fun selectVisualManeuver(candidates: List<VisualCandidate>): VisualCandidate? {
        val directional = candidates.filter { it.classification.maneuverGaode != 0 }
        val codes = directional.map { it.classification.maneuverGaode }.distinct()
        if (codes.size != 1) return null
        return directional.maxByOrNull { kotlin.math.abs(it.classification.horizontalShift) }
    }

    /**
     * Conflicting resources at the same confidence are rejected instead of guessing a direction.
     * A value-side `turn_right` is stronger than a generic view-side direction tag.
     */
    internal fun selectManeuver(candidates: List<ResourceCandidate>): Pair<Int, String>? {
        data class Match(val code: Int, val resource: String, val confidence: Int)

        val matches = candidates.mapNotNull { candidate ->
            val resourceCode = semanticResourceManeuver(candidate.resourceName)
            val viewCode = semanticResourceManeuver(candidate.viewName)
            when {
                resourceCode != 0 -> Match(resourceCode, candidate.resourceName, 3)
                viewCode != 0 -> Match(viewCode, candidate.viewName, 1)
                else -> null
            }
        }
        val confidence = matches.maxOfOrNull(Match::confidence) ?: return null
        val strongest = matches.filter { it.confidence == confidence }
        val codes = strongest.map(Match::code).distinct()
        if (codes.size != 1) return null
        return codes.single() to strongest.first().resource
    }

    /** Resource names must carry an explicit navigation verb/tag; `chevron_right` is not enough. */
    internal fun semanticResourceManeuver(resourceName: String?): Int {
        val value = resourceName?.trim()?.takeIf { it.length in 1..MAX_RESOURCE_NAME_CHARS }
            ?: return 0
        val normalized = value.lowercase()
        if (!STRONG_MANEUVER_RESOURCE.containsMatchIn(normalized)) return 0
        return NavManeuverCodes.fromInstructionText(value.uppercase())
    }

    private fun isImageMethod(methodName: String): Boolean =
        methodName.contains("image", ignoreCase = true) ||
            methodName.contains("icon", ignoreCase = true) ||
            methodName.contains("backgroundresource", ignoreCase = true)

    private fun extractActions(remoteViews: RemoteViews): List<Any> = runCatching {
        val field = RemoteViews::class.java.getDeclaredField("mActions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(remoteViews) as? ArrayList<Any>)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun collectFields(type: Class<*>): List<java.lang.reflect.Field> = buildList {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.forEach { field ->
                runCatching { field.isAccessible = true }
                add(field)
            }
            current = current.superclass
        }
    }

    private fun intFieldValue(
        fields: List<java.lang.reflect.Field>,
        name: String,
        target: Any,
    ): Int? {
        val field = fields.firstOrNull {
            it.name == name && (it.type == Int::class.javaPrimitiveType ||
                it.type == Integer::class.java)
        } ?: return null
        return runCatching {
            if (field.type == Int::class.javaPrimitiveType) field.getInt(target)
            else field.get(target) as? Int
        }.getOrNull()
    }

    private fun stringFieldValue(
        fields: List<java.lang.reflect.Field>,
        name: String,
        target: Any,
    ): String? = fields.firstOrNull { it.name == name && it.type == String::class.java }
        ?.let { runCatching { it.get(target) as? String }.getOrNull() }

    private fun valueObject(fields: List<java.lang.reflect.Field>, target: Any): Any? {
        fields.firstOrNull { it.name == "value" }?.let { field ->
            runCatching { field.get(target) }.getOrNull()?.let { return it }
        }
        fields.forEach { field ->
            if (field.name == "viewId" || field.name == "methodName" || field.type.isPrimitive) {
                return@forEach
            }
            val value = runCatching { field.get(target) }.getOrNull()
            if (value is CharSequence || value is Int || value is Icon || value is Bitmap) {
                return value
            }
        }
        return null
    }

    /** Android 12 stores setImageViewBitmap payloads in RemoteViews.mBitmapCache by bitmapId. */
    private fun bitmapPayload(
        remoteViews: RemoteViews,
        actionFields: List<java.lang.reflect.Field>,
        action: Any,
    ): Bitmap? {
        val bitmapId = intFieldValue(actionFields, "bitmapId", action) ?: return null
        val cache = collectFields(remoteViews.javaClass)
            .firstOrNull { it.name == "mBitmapCache" }
            ?.let { runCatching { it.get(remoteViews) }.getOrNull() }
            ?: return null
        val getter = generateSequence(cache.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.name == "getBitmapForId" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
            }
            ?: return null
        return runCatching {
            getter.isAccessible = true
            getter.invoke(cache, bitmapId) as? Bitmap
        }.getOrNull()
    }

    private fun imagePayloadSource(value: Any): String = when (value) {
        is Bitmap -> "bitmap"
        is Icon -> "icon_type_${runCatching { value.type }.getOrDefault(-1)}"
        is Int -> "resource"
        else -> "unknown"
    }

    private fun safeResourceName(resolveName: (Int) -> String?, resourceId: Int): String =
        if (resourceId == 0) "" else runCatching {
            resolveName(resourceId)?.lowercase()?.take(MAX_RESOURCE_NAME_CHARS).orEmpty()
        }.getOrDefault("")

    private val STRONG_MANEUVER_RESOURCE = Regex(
        "(?:^|_)(?:nav|navigation|maneuver|direction|turn|uturn|u_turn|keep|bear|fork|" +
            "straight|roundabout|exit)(?:_|$)",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_ACTIONS = 96
    private const val MAX_IMAGE_RESOURCES = 32
    private const val MAX_IMAGE_PAYLOADS = 12
    private const val MAX_TEXT_HINTS = 24
    private const val MAX_TEXT_HINT_CHARS = 256
    private const val MAX_RESOURCE_NAME_CHARS = 160
}
