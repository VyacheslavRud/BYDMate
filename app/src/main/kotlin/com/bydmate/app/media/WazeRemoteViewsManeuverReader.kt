package com.bydmate.app.media

import android.app.Notification
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import com.bydmate.app.navdata.NavManeuverCodes

/**
 * Reads only semantic maneuver evidence from Waze notification [RemoteViews].
 *
 * The Sea Lion DiLink build exposes distance/street as text, but can keep the arrow in an
 * ImageView resource action. We inspect the bounded action list in memory and retain only Android
 * resource entry names and recognized maneuver codes. Notification text, streets, addresses,
 * bitmaps and rendered views never enter diagnostics or persistent storage.
 */
object WazeRemoteViewsManeuverReader {
    data class Diagnostics(
        val inspectedAtMs: Long? = null,
        val remoteViewsPresent: Boolean = false,
        val actionsInspected: Int = 0,
        val imageResourcesInspected: Int = 0,
        val maneuverGaode: Int = 0,
        val maneuverResource: String? = null,
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
        val remoteViewsPresent: Boolean,
    )

    @Volatile private var latest = Diagnostics()

    fun diagnostics(): Diagnostics = latest

    @Suppress("DEPRECATION")
    internal fun inspect(
        notification: Notification,
        resolveName: (Int) -> String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Inspection {
        val views = listOfNotNull(
            notification.bigContentView,
            notification.contentView,
            notification.headsUpContentView,
        ).distinctBy { System.identityHashCode(it) }
        if (views.isEmpty()) {
            latest = Diagnostics(inspectedAtMs = nowMs)
            return Inspection(0, null, emptyList(), 0, 0, false)
        }

        val resources = ArrayList<ResourceCandidate>()
        val textHints = LinkedHashSet<String>()
        var actionCount = 0
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
                val resourceId = when (value) {
                    is Int -> value
                    is Icon -> runCatching {
                        value.takeIf { it.type == Icon.TYPE_RESOURCE }?.resId
                    }.getOrNull()
                    else -> null
                } ?: return@actionLoop
                val resourceName = safeResourceName(resolveName, resourceId)
                if (resourceName.isNotEmpty() && resources.size < MAX_IMAGE_RESOURCES) {
                    resources += ResourceCandidate(viewName, resourceName)
                }
            }
        }

        val selected = selectManeuver(resources)
        latest = Diagnostics(
            inspectedAtMs = nowMs,
            remoteViewsPresent = true,
            actionsInspected = actionCount,
            imageResourcesInspected = resources.size,
            maneuverGaode = selected?.first ?: 0,
            maneuverResource = selected?.second,
        )
        return Inspection(
            maneuverGaode = selected?.first ?: 0,
            maneuverResource = selected?.second,
            semanticTextHints = textHints.toList(),
            actionsInspected = actionCount,
            imageResourcesInspected = resources.size,
            remoteViewsPresent = true,
        )
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
            if (value is CharSequence || value is Int || value is Icon) return value
        }
        return null
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
    private const val MAX_TEXT_HINTS = 24
    private const val MAX_TEXT_HINT_CHARS = 256
    private const val MAX_RESOURCE_NAME_CHARS = 160
}
