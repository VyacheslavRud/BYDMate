package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo

/** Extracts Waze guidance widgets from its accessibility tree into numeric HUD data. */
object NavA11yExtractor {

    sealed class ReadResult {
        object NotNavigator : ReadResult()
        object NoGuidance : ReadResult()
        data class Guidance(val data: NavGuidance) : ReadResult()

        override fun toString(): String = this::class.java.simpleName
    }

    fun read(root: AccessibilityNodeInfo?): ReadResult {
        if (root == null) return ReadResult.NotNavigator
        val pkg = root.packageName?.toString() ?: return ReadResult.NotNavigator
        if (!NavPackages.isNavigationPackage(pkg)) return ReadResult.NotNavigator
        val fields = WazeAccessibilityReader.read(root) ?: return ReadResult.NoGuidance
        val raw = NavGuidanceParser.RawFields(
            maneuverDesc = fields.maneuver,
            exitNumber = fields.exitNumber,
            distance = fields.maneuverDistance,
            nextStreet = fields.street,
            etaTime = fields.remainingTime,
            etaDistance = fields.remainingDistance,
            speedLimit = fields.speedLimit,
        )
        val parsed = NavGuidanceParser.parse(raw) ?: return ReadResult.NoGuidance
        return ReadResult.Guidance(parsed)
    }
}
