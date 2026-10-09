package com.bydmate.app.hud

import com.bydmate.app.navdata.NavGuidanceHub
import java.util.Calendar
import java.util.Locale

/**
 * The windshield street line (f10), the only text field this Sea Lion 07 firmware draws. The
 * parked HUD Lab run of 2026-10-09 showed a 70-character line, Czech letters and Cyrillic in
 * full, while every other schema field (speed limit, camera, lanes, remaining trip) stayed
 * invisible. Route progress therefore goes in front of the street: `18:45 · 25 мин · 8,4 км | Street`.
 *
 * Within [STREET_ONLY_WITHIN_METERS] of the next maneuver only the street is shown, so the line
 * a driver reads at the turn is the one Waze shows.
 */
internal object HudStreetLine {
    const val STREET_ONLY_WITHIN_METERS = 300

    /** Localized units; supplied by HudController from resources, fixed values in tests. */
    data class Units(
        val minutes: String,
        val hours: String,
        val kilometers: String,
        val meters: String,
        val decimalSeparator: Char,
    )

    fun compose(
        snapshot: NavGuidanceHub.Snapshot,
        nowMs: Long,
        routeInfoEnabled: Boolean,
        units: Units,
    ): String {
        val road = snapshot.road.trim()
        if (!routeInfoEnabled || snapshot.distanceMeters in 1..STREET_ONLY_WITHIN_METERS) return road
        val parts = listOfNotNull(
            arrival(snapshot),
            remainingTime(snapshot.etaSeconds, units),
            remainingDistance(snapshot.totalDistMeters, units),
        )
        if (parts.isEmpty()) return road
        val info = parts.joinToString(" · ")
        return if (road.isEmpty()) info else "$info | $road"
    }

    /** Waze's own arrival clock wins; otherwise the remaining time from when it was read. */
    private fun arrival(snapshot: NavGuidanceHub.Snapshot): String? {
        snapshot.arrivalTime.takeIf { it.isNotBlank() }?.let { return it }
        if (snapshot.etaSeconds <= 0 || snapshot.etaUpdatedAtMs <= 0L) return null
        val calendar = Calendar.getInstance().apply {
            timeInMillis = snapshot.etaUpdatedAtMs + snapshot.etaSeconds * 1_000L
        }
        return String.format(
            Locale.US,
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    private fun remainingTime(etaSeconds: Int, units: Units): String? {
        if (etaSeconds <= 0) return null
        val totalMinutes = (etaSeconds + 59) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes ${units.minutes}"
            minutes == 0 -> "$hours ${units.hours}"
            else -> "$hours ${units.hours} $minutes ${units.minutes}"
        }
    }

    private fun remainingDistance(meters: Int, units: Units): String? {
        if (meters <= 0) return null
        if (meters < 1_000) return "$meters ${units.meters}"
        val tenths = (meters + 50) / 100
        val text = if (tenths >= 1_000) {
            "${tenths / 10}"
        } else {
            "${tenths / 10}${units.decimalSeparator}${tenths % 10}"
        }
        return "$text ${units.kilometers}"
    }
}
