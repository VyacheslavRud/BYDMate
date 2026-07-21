package com.bydmate.app.navdata

import android.util.Log

/**
 * Source-aware route state shared by HUD and the voice agent.
 *
 * A valid guidance value is retained while the route itself is still observable. This separation
 * matters on long straight sections: Waze may not emit a content-change event for minutes even
 * though the route window and notification are still alive. A low-rate accessibility refresh
 * renews [ROUTE_LEASE_TIMEOUT_MS] without fabricating a new maneuver update.
 */
object NavGuidanceHub {
    private const val TAG = "NavGuidanceHub"

    /** Hard safety bound when neither source nor the periodic window probe can observe a route. */
    const val ROUTE_LEASE_TIMEOUT_MS = 60_000L
    /** Compatibility name used by diagnostics and the voice route holder. */
    const val ACTIVE_TIMEOUT_MS = ROUTE_LEASE_TIMEOUT_MS
    const val SPEED_LIMIT_TIMEOUT_MS = 30_000L

    /** A visible Waze window must remain explicitly route-free across multiple reads. */
    const val NO_GUIDANCE_DEACTIVATE_MS = 4_000L
    const val NO_GUIDANCE_REQUIRED_OBSERVATIONS = 2
    private const val NO_GUIDANCE_SEQUENCE_MAX_GAP_MS = 30_000L

    /** Notification replacement/removal is not atomic on all Waze builds. */
    const val NOTIFICATION_END_GRACE_MS = 5_000L
    /** A recent route-bearing A11Y window can outlive removal of the notification source. */
    const val A11Y_ALIVE_GRACE_MS = 30_000L

    /** A11Y is richer, but only wins briefly when a notification event is newer. */
    const val A11Y_PRIORITY_GRACE_MS = 5_000L
    /** Never fill a current route with fields from a source that has stopped updating. */
    const val CROSS_SOURCE_FALLBACK_MAX_AGE_MS = 5_000L
    internal const val MANEUVER_HOLD_MS = 30_000L
    /** Waze often publishes the semantic arrow once, then updates only the numeric distance. */
    private const val MANEUVER_DISTANCE_JITTER_METERS = 75
    // Two 15 s refresh periods leave room for normal Handler jitter and one temporarily
    // unreadable Waze tree. Matching the refresh interval exactly makes distance hit zero between
    // the probe and the next 300 ms HUD tick, which can visibly rebuild or hide the factory card.
    internal const val DISTANCE_HOLD_MS = 30_000L
    internal const val ROUTE_TEXT_HOLD_MS = ROUTE_LEASE_TIMEOUT_MS
    internal const val ETA_HOLD_MS = ROUTE_LEASE_TIMEOUT_MS

    enum class Source { A11Y, NOTIFICATION }

    enum class RouteEndReason {
        EXPLICIT_NO_ROUTE,
        NOTIFICATION_REMOVED,
        LEASE_EXPIRED,
    }

    enum class NoGuidanceResult {
        IGNORED,
        PENDING,
        A11Y_CLEARED_NOTIFICATION_RETAINED,
        ROUTE_ENDED,
    }

    data class Snapshot(
        val active: Boolean = false,
        val source: Source? = null,
        val maneuverGaode: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val arrivalTime: String = "",
        val etaUpdatedAtMs: Long = 0L,
        val totalDistMeters: Int = 0,
        val speedLimit: Int = 0,
        /** Monotonic request to clear and redraw the factory HUD after another system overlay. */
        val hudRefreshGeneration: Long = 0L,
        /** Last parsed field update, intentionally not refreshed by a route-presence probe. */
        val lastUpdateMs: Long = 0L,
        /** Last positive evidence that the route itself still exists. */
        val lastRouteObservedMs: Long = 0L,
    )

    data class Diagnostics(
        val lastRouteObservedAtMs: Long?,
        val explicitNoRouteSinceMs: Long?,
        val explicitNoRouteObservationCount: Int,
        val notificationEndedAtMs: Long?,
        val lastRouteEndedAtMs: Long?,
        val lastRouteEndReason: RouteEndReason?,
    )

    private data class SourceSnapshot(
        val data: NavGuidance,
        val updatedAtMs: Long,
        val maneuverAtMs: Long,
        val distanceAtMs: Long,
        val roadAtMs: Long,
        val etaAtMs: Long,
        val arrivalAtMs: Long,
        val totalDistAtMs: Long,
        val speedLimitAtMs: Long,
    )

    @Volatile private var a11y: SourceSnapshot? = null
    @Volatile private var notification: SourceSnapshot? = null
    @Volatile private var lastRouteObservedAtMs = 0L
    @Volatile private var lastA11yObservedAtMs = 0L
    @Volatile private var lastNotificationObservedAtMs = 0L
    @Volatile private var explicitNoRouteSinceMs = 0L
    @Volatile private var explicitNoRouteLastAtMs = 0L
    @Volatile private var explicitNoRouteObservationCount = 0
    @Volatile private var notificationEndedAtMs = 0L
    @Volatile private var lastRouteEndedAtMs = 0L
    @Volatile private var lastRouteEndReason: RouteEndReason? = null
    @Volatile private var hudRefreshGeneration = 0L

    @Synchronized
    fun diagnostics(): Diagnostics = Diagnostics(
        lastRouteObservedAtMs = lastRouteObservedAtMs.takeIf { it > 0L },
        explicitNoRouteSinceMs = explicitNoRouteSinceMs.takeIf { it > 0L },
        explicitNoRouteObservationCount = explicitNoRouteObservationCount,
        notificationEndedAtMs = notificationEndedAtMs.takeIf { it > 0L },
        lastRouteEndedAtMs = lastRouteEndedAtMs.takeIf { it > 0L },
        lastRouteEndReason = lastRouteEndReason,
    )

    /** Used only to decide whether the low-rate probe should run before evaluating lease expiry. */
    @Synchronized
    internal fun hasRouteCandidate(): Boolean = a11y != null || notification != null

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (expireRouteIfNeeded(nowMs)) return Snapshot()
        if (finishNotificationRemovalIfNeeded(nowMs)) return Snapshot()

        val a11ySnapshot = a11y
        val notificationSnapshot = notification
        if (a11ySnapshot == null && notificationSnapshot == null) return Snapshot()

        val primarySource = when {
            a11ySnapshot == null -> Source.NOTIFICATION
            notificationSnapshot == null -> Source.A11Y
            notificationSnapshot.updatedAtMs <= a11ySnapshot.updatedAtMs -> Source.A11Y
            age(nowMs, a11ySnapshot.updatedAtMs) <= A11Y_PRIORITY_GRACE_MS -> Source.A11Y
            else -> Source.NOTIFICATION
        }
        val first = if (primarySource == Source.A11Y) a11ySnapshot!! else notificationSnapshot!!
        val other = if (primarySource == Source.A11Y) notificationSnapshot else a11ySnapshot
        val second = other?.takeIf {
            age(nowMs, it.updatedAtMs) <= CROSS_SOURCE_FALLBACK_MAX_AGE_MS
        }

        // Speed-limit signs update less often than route text, so their independent 30 s TTL may
        // use the other source even when it is too old to supply a street or maneuver.
        val speedSource = choose(first, other) {
            it.data.speedLimit > 0 && age(nowMs, it.speedLimitAtMs) <= SPEED_LIMIT_TIMEOUT_MS
        }
        val maneuverSource = choose(first, second) {
            it.data.maneuverGaode > 0 && age(nowMs, it.maneuverAtMs) <= MANEUVER_HOLD_MS
        }
        val distanceSource = choose(first, second) {
            it.data.distanceMeters > 0 && age(nowMs, it.distanceAtMs) <= DISTANCE_HOLD_MS
        }
        val roadSource = choose(first, second) {
            it.data.road.isNotBlank() && age(nowMs, it.roadAtMs) <= ROUTE_TEXT_HOLD_MS
        }
        val etaSource = choose(first, second) {
            it.data.etaSeconds > 0 && age(nowMs, it.etaAtMs) <= ETA_HOLD_MS
        }
        val arrivalSource = choose(first, second) {
            it.data.arrivalTime.isNotBlank() && age(nowMs, it.arrivalAtMs) <= ETA_HOLD_MS
        }
        val totalDistSource = choose(first, second) {
            it.data.totalDistMeters > 0 && age(nowMs, it.totalDistAtMs) <= ETA_HOLD_MS
        }
        val data = NavGuidance(
            maneuverGaode = maneuverSource?.data?.maneuverGaode ?: 0,
            distanceMeters = distanceSource?.data?.distanceMeters ?: 0,
            road = roadSource?.data?.road.orEmpty(),
            etaSeconds = etaSource?.data?.etaSeconds ?: 0,
            arrivalTime = arrivalSource?.data?.arrivalTime.orEmpty(),
            totalDistMeters = totalDistSource?.data?.totalDistMeters ?: 0,
            speedLimit = speedSource?.data?.speedLimit ?: 0,
        )
        return Snapshot(
            active = true,
            source = primarySource,
            maneuverGaode = data.maneuverGaode,
            distanceMeters = data.distanceMeters,
            road = data.road,
            etaSeconds = data.etaSeconds,
            arrivalTime = data.arrivalTime,
            etaUpdatedAtMs = etaSource?.etaAtMs ?: 0L,
            totalDistMeters = data.totalDistMeters,
            speedLimit = data.speedLimit,
            hudRefreshGeneration = hudRefreshGeneration,
            // Age of the selected primary, not the newest unrelated fallback event.
            lastUpdateMs = first.updatedAtMs,
            lastRouteObservedMs = lastRouteObservedAtMs,
        )
    }

    @Synchronized
    fun update(data: NavGuidance, source: Source, nowMs: Long = System.currentTimeMillis()) {
        if (!data.hasUsefulValue()) return
        // A new route after a hard lease expiry must never inherit old fields.
        expireRouteIfNeeded(nowMs)
        val startsNewRoute = a11y == null && notification == null

        val previous = when (source) {
            Source.A11Y -> a11y
            Source.NOTIFICATION -> notification
        }
        val maneuverContinuity = maneuverContinuity(previous, data)
        if (data.maneuverGaode > 0 && data.maneuverGaode != previous?.data?.maneuverGaode) {
            Log.i(
                TAG,
                "maneuver transition: source=$source previous=" +
                    "${NavManeuverCodes.codeName(previous?.data?.maneuverGaode ?: 0)} " +
                    "maneuver=${NavManeuverCodes.codeName(data.maneuverGaode)} " +
                    "gaode=${data.maneuverGaode}",
            )
        }
        val replacement = SourceSnapshot(
            data = NavGuidance(
                maneuverGaode = when {
                    data.maneuverGaode > 0 -> data.maneuverGaode
                    maneuverContinuity == ManeuverContinuity.NEW_MANEUVER_UNKNOWN -> 0
                    else -> previous?.data?.maneuverGaode ?: 0
                },
                distanceMeters = data.distanceMeters.takeIf { it > 0 }
                    ?: previous?.data?.distanceMeters ?: 0,
                road = data.road.takeIf { it.isNotBlank() }
                    ?: previous?.data?.road.orEmpty(),
                etaSeconds = data.etaSeconds.takeIf { it > 0 }
                    ?: previous?.data?.etaSeconds ?: 0,
                arrivalTime = data.arrivalTime.takeIf { it.isNotBlank() }
                    ?: previous?.data?.arrivalTime.orEmpty(),
                totalDistMeters = data.totalDistMeters.takeIf { it > 0 }
                    ?: previous?.data?.totalDistMeters ?: 0,
                speedLimit = data.speedLimit.takeIf { it > 0 }
                    ?: previous?.data?.speedLimit ?: 0,
            ),
            updatedAtMs = nowMs,
            maneuverAtMs = when {
                data.maneuverGaode > 0 -> nowMs
                maneuverContinuity == ManeuverContinuity.SAME_MANEUVER -> nowMs
                maneuverContinuity == ManeuverContinuity.NEW_MANEUVER_UNKNOWN -> 0L
                else -> previous?.maneuverAtMs ?: 0L
            },
            distanceAtMs = if (data.distanceMeters > 0) nowMs else previous?.distanceAtMs ?: 0L,
            roadAtMs = if (data.road.isNotBlank()) nowMs else previous?.roadAtMs ?: 0L,
            // Waze exposes rounded remaining minutes. Re-anchoring an unchanged "18 min" on
            // every 15 s accessibility refresh makes the computed arrival clock drift forward.
            // Advance the anchor only when the value itself changes (or this source is new).
            etaAtMs = when {
                data.etaSeconds <= 0 -> previous?.etaAtMs ?: 0L
                previous == null || data.etaSeconds != previous.data.etaSeconds -> nowMs
                else -> previous.etaAtMs
            },
            arrivalAtMs = if (data.arrivalTime.isNotBlank()) nowMs
                else previous?.arrivalAtMs ?: 0L,
            totalDistAtMs = if (data.totalDistMeters > 0) nowMs
                else previous?.totalDistAtMs ?: 0L,
            speedLimitAtMs = if (data.speedLimit > 0) nowMs else previous?.speedLimitAtMs ?: 0L,
        )
        when (source) {
            Source.A11Y -> {
                a11y = replacement
                lastA11yObservedAtMs = nowMs
            }
            Source.NOTIFICATION -> {
                notification = replacement
                lastNotificationObservedAtMs = nowMs
                notificationEndedAtMs = 0L
            }
        }
        if (startsNewRoute) {
            lastRouteEndedAtMs = 0L
            lastRouteEndReason = null
        }
        lastRouteObservedAtMs = nowMs
        clearNoRouteSequence()
    }

    /** Waze navigation-notification entry point. Blank/community-alert updates are ignored. */
    fun updateFromNotification(data: NavGuidance, nowMs: Long = System.currentTimeMillis()) {
        update(data, Source.NOTIFICATION, nowMs)
    }

    /**
     * Positive route-presence evidence without a parseable field update. Used by the low-rate
     * accessibility refresh when Waze's route anchor is visible during a transient layout change.
     * It renews the route lease but deliberately leaves [Snapshot.lastUpdateMs] unchanged.
     */
    @Synchronized
    fun markRouteObserved(source: Source, nowMs: Long = System.currentTimeMillis()) {
        // Positive current evidence wins over a lease that happened to elapse while Android's
        // main thread was blocked; the probe runs before snapshot() specifically for this case.
        if (a11y == null && notification == null) return
        lastRouteObservedAtMs = nowMs
        when (source) {
            Source.A11Y -> lastA11yObservedAtMs = nowMs
            Source.NOTIFICATION -> lastNotificationObservedAtMs = nowMs
        }
        clearNoRouteSequence()
    }

    /**
     * Merges a direction carried by a Waze accessibility event into the current route without
     * replacing richer distance/street data from another source. It can never create a route.
     */
    @Synchronized
    internal fun updateManeuverHint(
        maneuverGaode: Int,
        source: Source,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (maneuverGaode <= 0) return false
        val current = snapshot(nowMs)
        if (!current.active) return false
        update(
            NavGuidance(
                maneuverGaode = maneuverGaode,
                distanceMeters = current.distanceMeters,
                road = current.road,
                etaSeconds = current.etaSeconds,
                arrivalTime = current.arrivalTime,
                totalDistMeters = current.totalDistMeters,
                speedLimit = current.speedLimit,
            ),
            source,
            nowMs,
        )
        return true
    }

    /** A window/probe failure says nothing about whether the route ended. */
    @Synchronized
    fun markRouteIndeterminate() {
        clearNoRouteSequence()
    }

    /**
     * A BYD/Waze system overlay can temporarily replace the factory navigation card and then
     * clear it. Repeating an identical guidance payload is sometimes deduplicated by the gateway,
     * so the push loop needs one explicit clear/redraw cycle after the Waze route window returns.
     */
    @Synchronized
    fun requestHudRefresh() {
        hudRefreshGeneration++
        if (hudRefreshGeneration == Long.MIN_VALUE) hudRefreshGeneration = 1L
    }

    /**
     * Records a reachable Waze window that explicitly has no route anchor. One empty tree is not
     * enough: projection hand-offs briefly expose just such a tree. Route teardown requires two
     * observations spanning [NO_GUIDANCE_DEACTIVATE_MS].
     *
     * A fresh navigation notification is independent positive route evidence. In that case only
     * stale A11Y fields are removed; opening Waze search/settings must not wipe a live HUD route.
     */
    @Synchronized
    fun markNoGuidance(nowMs: Long = System.currentTimeMillis()): NoGuidanceResult {
        if (expireRouteIfNeeded(nowMs) || (a11y == null && notification == null)) {
            clearNoRouteSequence()
            return NoGuidanceResult.IGNORED
        }
        val continueSequence = explicitNoRouteSinceMs != 0L &&
            nowMs >= explicitNoRouteLastAtMs &&
            nowMs - explicitNoRouteLastAtMs <= NO_GUIDANCE_SEQUENCE_MAX_GAP_MS
        if (!continueSequence) {
            explicitNoRouteSinceMs = nowMs
            explicitNoRouteObservationCount = 1
        } else {
            explicitNoRouteObservationCount++
        }
        explicitNoRouteLastAtMs = nowMs

        if (explicitNoRouteObservationCount >= NO_GUIDANCE_REQUIRED_OBSERVATIONS &&
            nowMs - explicitNoRouteSinceMs >= NO_GUIDANCE_DEACTIVATE_MS
        ) {
            val notificationStillLive = notification != null && notificationEndedAtMs == 0L &&
                lastNotificationObservedAtMs > 0L &&
                age(nowMs, lastNotificationObservedAtMs) <= ROUTE_LEASE_TIMEOUT_MS
            if (notificationStillLive) {
                a11y = null
                lastA11yObservedAtMs = 0L
                lastRouteObservedAtMs = maxOf(
                    lastRouteObservedAtMs,
                    lastNotificationObservedAtMs,
                )
                clearNoRouteSequence()
                Log.i(TAG, "A11Y no-route confirmed; live notification guidance retained")
                return NoGuidanceResult.A11Y_CLEARED_NOTIFICATION_RETAINED
            }
            clearRoute(RouteEndReason.EXPLICIT_NO_ROUTE, nowMs)
            Log.i(TAG, "guidance inactive: explicit no-route confirmed")
            return NoGuidanceResult.ROUTE_ENDED
        }
        return NoGuidanceResult.PENDING
    }

    /** Notification removal is delayed because Waze often replaces it remove -> post. */
    @Synchronized
    fun markNotificationEnded(nowMs: Long = System.currentTimeMillis()) {
        if (notification == null) return
        if (notificationEndedAtMs == 0L) notificationEndedAtMs = nowMs
    }

    @Synchronized
    fun reset() {
        a11y = null
        notification = null
        lastRouteObservedAtMs = 0L
        lastA11yObservedAtMs = 0L
        lastNotificationObservedAtMs = 0L
        notificationEndedAtMs = 0L
        lastRouteEndedAtMs = 0L
        lastRouteEndReason = null
        hudRefreshGeneration = 0L
        clearNoRouteSequence()
    }

    private fun finishNotificationRemovalIfNeeded(nowMs: Long): Boolean {
        val endedAt = notificationEndedAtMs
        if (endedAt == 0L || age(nowMs, endedAt) < NOTIFICATION_END_GRACE_MS) return false

        val a11yStillAlive = lastA11yObservedAtMs > 0L &&
            age(nowMs, lastA11yObservedAtMs) <= A11Y_ALIVE_GRACE_MS
        if (a11yStillAlive) {
            // Prefer real A11Y fields when available. If the route anchor is readable but its
            // fields are temporarily unparseable, retain the last notification payload as a
            // bounded fallback instead of flashing a blank HUD.
            if (a11y != null) {
                notification = null
                lastNotificationObservedAtMs = 0L
            }
            notificationEndedAtMs = 0L
            return false
        }

        notification = null
        lastNotificationObservedAtMs = 0L
        notificationEndedAtMs = 0L

        clearRoute(RouteEndReason.NOTIFICATION_REMOVED, nowMs)
        Log.i(TAG, "guidance inactive: guidance notification removed")
        return true
    }

    private fun expireRouteIfNeeded(nowMs: Long): Boolean {
        if (a11y == null && notification == null) return false
        val observedAt = lastRouteObservedAtMs
        if (observedAt > 0L && age(nowMs, observedAt) <= ROUTE_LEASE_TIMEOUT_MS) return false
        clearRoute(RouteEndReason.LEASE_EXPIRED, nowMs)
        Log.w(TAG, "guidance inactive: route-observation lease expired")
        return true
    }

    private fun clearRoute(reason: RouteEndReason, nowMs: Long) {
        a11y = null
        notification = null
        lastRouteObservedAtMs = 0L
        lastA11yObservedAtMs = 0L
        lastNotificationObservedAtMs = 0L
        notificationEndedAtMs = 0L
        clearNoRouteSequence()
        lastRouteEndedAtMs = nowMs
        lastRouteEndReason = reason
    }

    private fun clearNoRouteSequence() {
        explicitNoRouteSinceMs = 0L
        explicitNoRouteLastAtMs = 0L
        explicitNoRouteObservationCount = 0
    }

    private fun NavGuidance.hasUsefulValue(): Boolean =
        maneuverGaode > 0 || distanceMeters > 0 || road.isNotBlank() || etaSeconds > 0 ||
            arrivalTime.isNotBlank() || totalDistMeters > 0 || speedLimit > 0

    private enum class ManeuverContinuity { NO_EVIDENCE, SAME_MANEUVER, NEW_MANEUVER_UNKNOWN }

    /**
     * Keep a recognized arrow alive while Waze sends distance-only progress updates. A clear
     * increase in distance or a next-street replacement means the previous maneuver has been
     * passed/rerouted; in that case blank is safer than a stale left/right arrow.
     */
    private fun maneuverContinuity(
        previous: SourceSnapshot?,
        incoming: NavGuidance,
    ): ManeuverContinuity {
        if (incoming.maneuverGaode > 0 || previous?.data?.maneuverGaode == null ||
            previous.data.maneuverGaode <= 0
        ) {
            return ManeuverContinuity.NO_EVIDENCE
        }
        val oldDistance = previous.data.distanceMeters
        val newDistance = incoming.distanceMeters
        if (oldDistance <= 0 || newDistance <= 0) return ManeuverContinuity.NO_EVIDENCE

        val distanceJumped = newDistance > oldDistance + MANEUVER_DISTANCE_JITTER_METERS
        val oldRoad = previous.data.road.trim()
        val newRoad = incoming.road.trim()
        val nextRoadChanged = oldRoad.isNotEmpty() && newRoad.isNotEmpty() && oldRoad != newRoad &&
            newDistance >= oldDistance - MANEUVER_DISTANCE_JITTER_METERS
        return if (distanceJumped || nextRoadChanged) {
            ManeuverContinuity.NEW_MANEUVER_UNKNOWN
        } else {
            ManeuverContinuity.SAME_MANEUVER
        }
    }

    private fun age(nowMs: Long, timestampMs: Long): Long =
        (nowMs - timestampMs).coerceAtLeast(0L)

    private inline fun choose(
        first: SourceSnapshot,
        second: SourceSnapshot?,
        predicate: (SourceSnapshot) -> Boolean,
    ): SourceSnapshot? = when {
        predicate(first) -> first
        second != null && predicate(second) -> second
        else -> null
    }

}
