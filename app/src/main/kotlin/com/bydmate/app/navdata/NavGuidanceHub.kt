package com.bydmate.app.navdata

import android.util.Log

/**
 * Source-aware route state shared by HUD and the voice agent.
 *
 * Accessibility and notifications intentionally keep independent snapshots. A current Waze
 * window is more precise and wins field-by-field; a fresh notification fills only values that
 * the window does not expose. Replacing each source snapshot (instead of merging it forever)
 * ensures a reroute can clear an old street, distance or maneuver.
 */
object NavGuidanceHub {
    private const val TAG = "NavGuidanceHub"
    const val ACTIVE_TIMEOUT_MS = 90_000L
    const val SPEED_LIMIT_TIMEOUT_MS = 30_000L
    const val NO_GUIDANCE_DEACTIVATE_MS = 10_000L
    /** A11Y is richer, but only wins briefly when a notification event is newer. */
    const val A11Y_PRIORITY_GRACE_MS = 5_000L
    /** Never fill a current route with fields from a source that has stopped updating. */
    const val CROSS_SOURCE_FALLBACK_MAX_AGE_MS = 5_000L

    enum class Source { A11Y, NOTIFICATION }

    data class Snapshot(
        val active: Boolean = false,
        val source: Source? = null,
        val maneuverGaode: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val totalDistMeters: Int = 0,
        val speedLimit: Int = 0,
        val lastUpdateMs: Long = 0L,
    )

    private data class SourceSnapshot(
        val data: NavGuidance,
        val updatedAtMs: Long,
        val speedLimitAtMs: Long,
    )

    @Volatile private var a11y: SourceSnapshot? = null
    @Volatile private var notification: SourceSnapshot? = null
    @Volatile private var noGuidanceSinceMs = 0L

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (noGuidanceSinceMs != 0L && nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            a11y = null
            notification = null
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: route ended (no-guidance deadline)")
            return Snapshot()
        }

        a11y = a11y.freshOrNull(nowMs)
        notification = notification.freshOrNull(nowMs)
        val a11ySnapshot = a11y
        val notificationSnapshot = notification
        if (a11ySnapshot == null && notificationSnapshot == null) return Snapshot()

        val primarySource = when {
            a11ySnapshot == null -> Source.NOTIFICATION
            notificationSnapshot == null -> Source.A11Y
            notificationSnapshot.updatedAtMs <= a11ySnapshot.updatedAtMs -> Source.A11Y
            nowMs - a11ySnapshot.updatedAtMs <= A11Y_PRIORITY_GRACE_MS ->
                Source.A11Y
            else -> Source.NOTIFICATION
        }
        val first = if (primarySource == Source.A11Y) a11ySnapshot!! else notificationSnapshot!!
        val other = if (primarySource == Source.A11Y) notificationSnapshot else a11ySnapshot
        val second = other?.takeIf {
            nowMs - it.updatedAtMs <= CROSS_SOURCE_FALLBACK_MAX_AGE_MS
        }
        // Speed-limit signs update less often than route text, so their independent 30 s TTL may
        // use the other source even when it is too old to supply a street or maneuver.
        val speedSource = choose(first, other) {
            it.data.speedLimit > 0 && nowMs - it.speedLimitAtMs <= SPEED_LIMIT_TIMEOUT_MS
        }
        val data = NavGuidance(
            maneuverGaode = chooseValue(first, second, 0) { maneuverGaode },
            distanceMeters = chooseValue(first, second, 0) { distanceMeters },
            road = chooseValue(first, second, "") { road },
            etaSeconds = chooseValue(first, second, 0) { etaSeconds },
            totalDistMeters = chooseValue(first, second, 0) { totalDistMeters },
            speedLimit = speedSource?.data?.speedLimit ?: 0,
        )
        return Snapshot(
            active = true,
            source = primarySource,
            maneuverGaode = data.maneuverGaode,
            distanceMeters = data.distanceMeters,
            road = data.road,
            etaSeconds = data.etaSeconds,
            totalDistMeters = data.totalDistMeters,
            speedLimit = data.speedLimit,
            // Age of the selected primary, not the newest unrelated fallback event.
            lastUpdateMs = first.updatedAtMs,
        )
    }

    @Synchronized
    fun update(data: NavGuidance, source: Source, nowMs: Long = System.currentTimeMillis()) {
        if (!data.hasUsefulValue()) return
        // Evaluate route-end/expiry before accepting a new event, so a new route cannot inherit
        // state through a deadline that no reader happened to observe.
        snapshot(nowMs)
        if (source == Source.NOTIFICATION && noGuidanceSinceMs != 0L &&
            (a11y?.updatedAtMs ?: 0L) <= noGuidanceSinceMs
        ) {
            // Waze's window was observed without route widgets after the last A11Y update.
            // A valid newer notification resumes guidance, but the pre-loss A11Y maneuver must
            // not regain priority merely because its broad 90 s route TTL has not elapsed.
            a11y = null
        }
        val previous = when (source) {
            Source.A11Y -> a11y
            Source.NOTIFICATION -> notification
        }
        val replacement = SourceSnapshot(
            data = data.copy(
                speedLimit = data.speedLimit.takeIf { it > 0 } ?: previous?.data?.speedLimit ?: 0,
            ),
            updatedAtMs = nowMs,
            speedLimitAtMs = if (data.speedLimit > 0) nowMs else previous?.speedLimitAtMs ?: 0L,
        )
        when (source) {
            Source.A11Y -> a11y = replacement
            Source.NOTIFICATION -> notification = replacement
        }
        // Either source can prove that guidance resumed after a transient empty Waze root.
        noGuidanceSinceMs = 0L
    }

    /** Waze navigation-notification entry point. Blank/community-alert updates are ignored. */
    fun updateFromNotification(data: NavGuidance, nowMs: Long = System.currentTimeMillis()) {
        update(data, Source.NOTIFICATION, nowMs)
    }

    /** A reachable Waze window without route widgets is stronger than a lagging notification. */
    @Synchronized
    fun markNoGuidance(nowMs: Long = System.currentTimeMillis()) {
        if (a11y == null && notification == null) {
            noGuidanceSinceMs = 0L
            return
        }
        if (noGuidanceSinceMs == 0L) noGuidanceSinceMs = nowMs
        if (nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) snapshot(nowMs)
    }

    /** Notification removal clears only that source; fresh accessibility guidance remains live. */
    @Synchronized
    fun markNotificationEnded(nowMs: Long = System.currentTimeMillis()) {
        notification = null
        if (a11y.freshOrNull(nowMs) == null) {
            a11y = null
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: guidance notification removed")
        }
    }

    @Synchronized
    fun reset() {
        a11y = null
        notification = null
        noGuidanceSinceMs = 0L
    }

    private fun NavGuidance.hasUsefulValue(): Boolean =
        maneuverGaode > 0 || distanceMeters > 0 || road.isNotBlank() || etaSeconds > 0 ||
            totalDistMeters > 0 || speedLimit > 0

    private fun SourceSnapshot?.freshOrNull(nowMs: Long): SourceSnapshot? =
        this?.takeIf { nowMs - it.updatedAtMs <= ACTIVE_TIMEOUT_MS }

    private inline fun choose(
        first: SourceSnapshot,
        second: SourceSnapshot?,
        predicate: (SourceSnapshot) -> Boolean,
    ): SourceSnapshot? = when {
        predicate(first) -> first
        second != null && predicate(second) -> second
        else -> null
    }

    private inline fun <T> chooseValue(
        first: SourceSnapshot,
        second: SourceSnapshot?,
        empty: T,
        value: NavGuidance.() -> T,
    ): T {
        val firstValue = first.data.value()
        if (firstValue != empty) return firstValue
        return second?.data?.value()?.takeIf { it != empty } ?: empty
    }
}
