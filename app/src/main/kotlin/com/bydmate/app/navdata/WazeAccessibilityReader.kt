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
    private val routeAnchorSuffixes = listOf(
        "navBarDistance",
        "navBarStreetLine",
        "navBarDirectionText",
        "instructionView",
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

    fun read(root: AccessibilityNodeInfo?): Fields? {
        if (root == null || !NavPackages.isNavigationPackage(root.packageName?.toString())) return null
        val pkg = root.packageName.toString()
        val maneuver = firstValue(
            root,
            "$pkg:id/navBarDirectionText",
            "$pkg:id/instructionView",
            "$pkg:id/navBarDirection",
        )
        val maneuverDistance = firstValue(root, "$pkg:id/navBarDistance")
        val street = firstValue(
            root,
            "$pkg:id/navBarStreetLine",
            "$pkg:id/navBarTowardStreetLine",
        )
        val remainingDistance = firstValue(
            root,
            "$pkg:id/minimizedEtaBarDistanceToDestination",
            "$pkg:id/minimizedEtaBarOfflineDistanceToDestination",
            "$pkg:id/lblDistanceToDestination",
        )
        val remainingTime = firstValue(
            root,
            "$pkg:id/minimizedEtaBarTimeToDestination",
            "$pkg:id/lblTimeToDestination",
        )
        val arrivalTime = firstValue(
            root,
            "$pkg:id/minimizedEtaBarArrivalTime",
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
        if (root == null || !NavPackages.isNavigationPackage(root.packageName?.toString())) return false
        val pkg = root.packageName.toString()
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
                    found = nodeValue(node, depth = 2)
                }
                recycle(node)
            }
            if (found != null) return found
        }
        return null
    }

    private fun nodeValue(node: AccessibilityNodeInfo, depth: Int): String? {
        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return null
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.contentDescription?.toString()?.trim()?.takeIf(::isUsefulDescription)?.let { return it }
        if (depth <= 0) return null
        for (index in 0 until node.childCount) {
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
        value.isNotEmpty() && !Regex("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+$").matches(value)

    private fun recycle(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }

    private fun numericSpeedLimit(value: String): String? = Regex("""\b(\d{1,3})\b""")
        .find(value)?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 5..200 }
        ?.toString()

    private fun numberedExit(value: String?): String? = value?.let {
        Regex("""\b(\d{1,2})(?:[-‑ ]?(?:й|я|е|st|nd|rd|th))?\s+(?:съезд|exit)\b""", RegexOption.IGNORE_CASE)
            .find(it)?.groupValues?.get(1)
    }
}
