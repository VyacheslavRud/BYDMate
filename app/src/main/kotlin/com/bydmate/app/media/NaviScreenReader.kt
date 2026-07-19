package com.bydmate.app.media

import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.WazeAccessibilityReader

/** On-demand read of Waze route widgets, including a Waze window projected to display 2. */
object NaviScreenReader {
    data class ScreenInfo(
        val maneuver: String?,
        val speedLimit: String?,
        val exitNumber: String?,
        val maneuverDistance: String?,   // "250 м" — distance + metrics joined, trimmed
        val remainingDistance: String?,  // "28 км"
        val remainingTime: String?,      // "27 мин"
        val arrivalTime: String?,        // "10:10"
        val street: String?,             // "ул. Качаны"
    )

    fun read(root: AccessibilityNodeInfo?): ScreenInfo? {
        val fields = WazeAccessibilityReader.read(root) ?: return null
        return ScreenInfo(
            maneuver = fields.maneuver,
            speedLimit = fields.speedLimit,
            exitNumber = fields.exitNumber,
            maneuverDistance = fields.maneuverDistance,
            remainingDistance = fields.remainingDistance,
            remainingTime = fields.remainingTime,
            arrivalTime = fields.arrivalTime,
            street = fields.street,
        )
    }
}
