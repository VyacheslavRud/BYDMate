package com.bydmate.app.navdata

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

    fun read(root: AccessibilityNodeInfo?): Fields? {
        if (root == null) return null
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            ?.takeIf(NavPackages::isNavigationPackage)
            ?: return null
        val exactManeuver = maneuverValue(
            root,
            "$pkg:id/navBarDirectionText",
            "$pkg:id/navBarInstructionText",
            "$pkg:id/navBarInstruction",
            "$pkg:id/instructionView",
            "$pkg:id/navBarDirection",
        )
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
        val exactManeuverRecognized = NavManeuverCodes.fromInstructionText(exactManeuver) != 0
        val fallbackManeuver = if (!exactManeuverRecognized &&
            (maneuverDistance != null || street != null)
        ) {
            fallbackManeuverValue(root)
        } else {
            null
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
    private fun maneuverValue(root: AccessibilityNodeInfo, vararg ids: String): String? {
        data class Value(
            val text: String,
            val idIndex: Int,
            val nodeIndex: Int,
            val valueIndex: Int,
            val parsed: NavManeuverCodes.ParseResult,
        )
        val values = mutableListOf<Value>()
        ids.forEachIndexed { idIndex, id ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
                ?: return@forEachIndexed
            nodes.forEachIndexed { nodeIndex, node ->
                if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
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
        if (values.isEmpty()) return null
        val recognized = values.filter { it.parsed.gaode != 0 }
        if (recognized.isEmpty()) return values.minWith(
            compareBy<Value> { it.idIndex }.thenBy { it.nodeIndex }.thenBy { it.valueIndex },
        ).text
        return recognized.maxWith(
            compareBy<Value> { isDetailedInstruction(it.text) }
                .thenBy { it.parsed.recognizedCodes.size }
                .thenBy { it.text.length }
                .thenBy { -it.idIndex }
                .thenBy { -it.nodeIndex }
                .thenBy { -it.valueIndex },
        ).text
    }

    private fun fallbackManeuverValue(root: AccessibilityNodeInfo): String? {
        var visited = 0
        var result: String? = null

        fun visit(node: AccessibilityNodeInfo) {
            if (result != null || visited++ >= MAX_FALLBACK_TREE_NODES) return
            if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return
            val values = linkedSetOf<String>()
            runCatching { node.text?.toString()?.trim() }.getOrNull()
                ?.takeIf(String::isNotEmpty)?.let(values::add)
            runCatching { node.contentDescription?.toString()?.trim() }.getOrNull()
                ?.takeIf(::isUsefulDescription)?.let(values::add)
            values.forEach { value ->
                val parsed = NavManeuverCodes.parseInstructionText(value)
                if (parsed.gaode != 0 && result == null) result = value
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
        return result
    }

    /** Unlike generic text fields, maneuver nodes need both text and contentDescription: a Waze
     *  icon can expose RIGHT only through accessibility while its sibling text carries the full
     *  instruction. Detailed text still wins later in [maneuverValue]. */
    private fun maneuverNodeValues(node: AccessibilityNodeInfo, depth: Int): List<String> {
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return emptyList()
        val values = linkedSetOf<String>()
        runCatching { node.text?.toString()?.trim() }.getOrNull()
            ?.takeIf(String::isNotEmpty)?.let(values::add)
        runCatching { node.contentDescription?.toString()?.trim() }.getOrNull()
            ?.takeIf(::isUsefulDescription)?.let(values::add)
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
}
