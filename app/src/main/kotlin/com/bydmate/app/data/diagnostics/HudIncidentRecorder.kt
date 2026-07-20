package com.bydmate.app.data.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.hud.HudController
import com.bydmate.app.hud.hasRenderableHudGuidance
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavGuidanceHub
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class HudIncidentCause {
    SERVICE_RESTART,
    ACCESSIBILITY,
    WAZE_WINDOW,
    WAZE_DATA,
    SOME_IP,
    HUD_PIPELINE,
}

data class HudIncident(
    val detectedAtMs: Long,
    val cause: HudIncidentCause,
    val outageBeforeDetectionMs: Long,
    val recoveredAtMs: Long? = null,
    val hudStatus: String,
    val resultCode: Int?,
    val failure: String?,
    val routeSource: String?,
    val routeAgeMs: Long?,
    val routeObservedAgeMs: Long?,
    val routeManeuverGaode: Int,
    val routeRenderable: Boolean,
    val routeEndReason: String?,
    val accessibilityConnected: Boolean,
    val feedEnabled: Boolean,
    val wazeWindowReachable: Boolean?,
    val wazeEventAgeMs: Long?,
    val wazeGuidanceAgeMs: Long?,
    val wazeNoGuidanceAgeMs: Long?,
    val wazeWindowUnreachableAgeMs: Long?,
    val wazeUnreadableAgeMs: Long?,
    val wazeProbeResult: String?,
)

/** One immutable technical sample. Kept Android-free so cause selection can be unit-tested. */
internal data class HudHealthSample(
    val nowMs: Long,
    val serviceUptimeMs: Long,
    val hudEnabled: Boolean,
    val hudStatus: HudController.Status,
    val lastFrameSuccessAgeMs: Long?,
    val resultCode: Int?,
    val failure: String?,
    val routeActive: Boolean,
    val routeSource: String?,
    val routeAgeMs: Long?,
    val routeObservedAgeMs: Long?,
    val routeManeuverGaode: Int,
    val routeRenderable: Boolean,
    val routeEndReason: NavGuidanceHub.RouteEndReason?,
    val routeEndAgeMs: Long?,
    val accessibilityConnected: Boolean,
    val feedEnabled: Boolean,
    val wazeWindowReachable: Boolean?,
    val wazeEventAgeMs: Long?,
    val wazeGuidanceAgeMs: Long?,
    val wazeNoGuidanceAgeMs: Long?,
    val wazeWindowUnreachableAgeMs: Long?,
    val wazeUnreadableAgeMs: Long?,
    val wazeProbeResult: NavA11yFeed.ProbeResult?,
    val lastDeliveryKind: String? = null,
    val lastClearAttemptAgeMs: Long? = null,
    val lastClearSuccessAgeMs: Long? = null,
    val reconnectAttempt: Int = 0,
    val nextReconnectAtMs: Long? = null,
)

internal object HudIncidentClassifier {
    const val OUTAGE_CONFIRM_MS = 5_000L
    const val RECENT_WAZE_EVENT_MS = 15_000L
    const val WAZE_DATA_STALE_MS = NavA11yFeed.REFRESH_INTERVAL_MS * 3

    /**
     * The HUD controller deliberately repeats the current maneuver between event-driven Waze
     * updates. A successful recent SOME/IP frame proves that the HUD did not disappear, even if
     * accessibility is temporarily blind. Input degradation is retained as evidence and becomes
     * the cause if the route lease later expires; it is not itself a delivery outage.
     */
    fun deliveryHealthy(sample: HudHealthSample): Boolean {
        val frameHealthy = sample.hudStatus == HudController.Status.ON &&
            (sample.lastFrameSuccessAgeMs ?: Long.MAX_VALUE) < 1_500L
        return sample.routeRenderable && frameHealthy
    }

    fun cause(sample: HudHealthSample): HudIncidentCause {
        return when {
            sample.hudStatus != HudController.Status.ON ||
                (sample.resultCode ?: 0) < 0 ||
                sample.failure?.contains("bind", ignoreCase = true) == true ||
                sample.failure?.contains("frame", ignoreCase = true) == true ||
                sample.failure?.contains("gateway", ignoreCase = true) == true ->
                HudIncidentCause.SOME_IP
            sample.routeSource == NavGuidanceHub.Source.A11Y.name &&
                (!sample.accessibilityConnected || !sample.feedEnabled) ->
                HudIncidentCause.ACCESSIBILITY
            sample.routeSource == NavGuidanceHub.Source.A11Y.name &&
                sample.wazeWindowReachable == false &&
                (sample.wazeProbeResult == NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE ||
                    sample.wazeWindowUnreachableAgeMs?.let {
                        it <= RECENT_WAZE_EVENT_MS
                    } == true) ->
                HudIncidentCause.WAZE_WINDOW
            !sample.routeRenderable -> HudIncidentCause.WAZE_DATA
            sample.wazeProbeResult == NavA11yFeed.ProbeResult.ROUTE_UNREADABLE &&
                sample.wazeUnreadableAgeMs != null &&
                sample.wazeUnreadableAgeMs <= RECENT_WAZE_EVENT_MS ->
                HudIncidentCause.WAZE_DATA
            sample.routeAgeMs == null || sample.routeAgeMs > WAZE_DATA_STALE_MS ||
                (sample.routeSource == NavGuidanceHub.Source.A11Y.name &&
                    (sample.wazeGuidanceAgeMs == null ||
                        sample.wazeGuidanceAgeMs > WAZE_DATA_STALE_MS)) ->
                HudIncidentCause.WAZE_DATA
            else -> HudIncidentCause.HUD_PIPELINE
        }
    }

    /** When the hub itself goes inactive, only call it an incident if surrounding evidence says
     * the route feed was lost. A reachable Waze window with a recent no-guidance event is a normal
     * route end and must not create a false alarm. */
    fun routeLossCause(
        sample: HudHealthSample,
        previousRouteSource: String?,
        previousRouteAgeMs: Long?,
    ): HudIncidentCause? = when {
        sample.routeEndAgeMs != null &&
            sample.routeEndAgeMs <= RECENT_WAZE_EVENT_MS &&
            sample.routeEndReason == NavGuidanceHub.RouteEndReason.EXPLICIT_NO_ROUTE -> null
        sample.routeEndAgeMs != null &&
            sample.routeEndAgeMs <= RECENT_WAZE_EVENT_MS &&
            sample.routeEndReason == NavGuidanceHub.RouteEndReason.NOTIFICATION_REMOVED -> null
        previousRouteSource == NavGuidanceHub.Source.A11Y.name &&
            (!sample.accessibilityConnected || !sample.feedEnabled) ->
            HudIncidentCause.ACCESSIBILITY
        previousRouteSource == NavGuidanceHub.Source.A11Y.name &&
            sample.wazeWindowReachable == false &&
            (sample.wazeProbeResult == NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE ||
                sample.wazeWindowUnreachableAgeMs?.let {
                    it <= RECENT_WAZE_EVENT_MS
                } == true) -> HudIncidentCause.WAZE_WINDOW
        sample.routeEndReason == NavGuidanceHub.RouteEndReason.LEASE_EXPIRED ->
            HudIncidentCause.WAZE_DATA
        (previousRouteAgeMs ?: 0L) > WAZE_DATA_STALE_MS ->
            HudIncidentCause.WAZE_DATA
        else -> null
    }
}

/**
 * Persistent HUD black box. While a route is active, an accepted SOME/IP frame is expected every
 * 300 ms. Five seconds without one is long enough to ignore normal hand-offs but short enough to
 * capture an outage while the evidence is still fresh. The recorder never starts Waze, changes a
 * permission or touches the car; it only samples existing in-process state.
 */
@Singleton
class HudIncidentRecorder @Inject constructor(
    @ApplicationContext context: Context,
    private val hudController: HudController,
) {
    companion object {
        private const val TAG = "HudIncidentRecorder"
        private const val PREFS = "hud_incident_recorder"
        private const val KEY_INCIDENTS = "incidents_json"
        private const val KEY_RUNTIME_ACTIVE = "runtime_active"
        private const val KEY_ROUTE_ACTIVE = "route_active"
        private const val KEY_HUD_ENABLED = "hud_enabled"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        private const val KEY_LAST_FRAME_AT = "last_frame_at"
        private const val MARKER_PERSIST_INTERVAL_MS = 15_000L
        private const val RESTART_INCIDENT_MAX_AGE_MS = 120_000L
        internal const val MAX_INCIDENTS = 30

        internal fun appendBounded(
            current: List<HudIncident>,
            incident: HudIncident,
        ): List<HudIncident> = (current + incident).takeLast(MAX_INCIDENTS)

        internal fun restartReferenceAt(
            nowMs: Long,
            previousRuntimeActive: Boolean,
            previousRouteActive: Boolean,
            previousHudEnabled: Boolean,
            previousReferenceAtMs: Long?,
        ): Long? = previousReferenceAtMs?.takeIf {
            previousRuntimeActive && previousRouteActive && previousHudEnabled &&
                nowMs - it in 0..RESTART_INCIDENT_MAX_AGE_MS
        }

        internal fun ownsGeneration(expected: Long, current: Long): Boolean = expected == current
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val lifecycleLock = Any()
    @Volatile private var monitorJob: Job? = null
    @Volatile private var monitorGeneration: Long = 0L
    @Volatile private var cachedIncidents: List<HudIncident> = loadIncidents()

    private var serviceStartedAtElapsedMs: Long = 0L
    private var outageSinceElapsedMs: Long? = null
    private var activeIncidentAtMs: Long? = null
    private var lastRouteActive: Boolean = false
    private var lastRouteSource: String? = null
    private var lastRouteUpdatedAtMs: Long? = null
    private var suspectedRouteLoss: Boolean = false
    private var pendingRestartReferenceAtMs: Long? = null
    private var lastMarkerPersistElapsedMs: Long = 0L
    private var lastPersistedRouteActive: Boolean? = null

    fun incidents(): List<HudIncident> = cachedIncidents

    fun start(scope: CoroutineScope) {
        val generation = synchronized(lifecycleLock) {
            monitorJob?.cancel()
            monitorGeneration++
            serviceStartedAtElapsedMs = SystemClock.elapsedRealtime()
            outageSinceElapsedMs = null
            // Preserve an unresolved incident across service/HUD lifecycle boundaries. It is
            // recovered only after a fresh route frame succeeds.
            activeIncidentAtMs = cachedIncidents.lastOrNull {
                it.recoveredAtMs == null
            }?.detectedAtMs
            lastRouteActive = false
            lastRouteSource = null
            lastRouteUpdatedAtMs = null
            suspectedRouteLoss = false

            val now = System.currentTimeMillis()
            val previousRuntimeActive = prefs.getBoolean(KEY_RUNTIME_ACTIVE, false)
            val previousRouteActive = prefs.getBoolean(KEY_ROUTE_ACTIVE, false)
            val previousHudEnabled = prefs.getBoolean(KEY_HUD_ENABLED, false)
            val previousReference = maxOf(
                prefs.getLong(KEY_LAST_FRAME_AT, 0L),
                prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L),
            ).takeIf { it > 0L }
            pendingRestartReferenceAtMs = restartReferenceAt(
                nowMs = now,
                previousRuntimeActive = previousRuntimeActive,
                previousRouteActive = previousRouteActive,
                previousHudEnabled = previousHudEnabled,
                previousReferenceAtMs = previousReference,
            )
            persistRuntimeMarker(
                runtimeActive = true,
                routeActive = false,
                hudEnabled = hudController.isEnabled(),
                lastFrameAtMs =
                    hudController.deliveryDiagnostics.value.lastGuidanceFrameSuccessAtMs,
                force = true,
            )
            monitorGeneration
        }

        val job = scope.launch(Dispatchers.Default) {
            while (isActive && ownsGeneration(generation, monitorGeneration)) {
                runCatching { tick(generation) }
                    .onFailure { Log.w(TAG, "HUD incident sample failed: ${it.message}") }
                delay(500L)
            }
        }
        synchronized(lifecycleLock) {
            if (ownsGeneration(generation, monitorGeneration)) monitorJob = job else job.cancel()
        }
    }

    /** Called before HudController.stop(), while route and delivery evidence are still intact. */
    fun stop(expectRestart: Boolean = false) {
        synchronized(lifecycleLock) {
            monitorGeneration++
            monitorJob?.cancel()
            monitorJob = null
            val now = System.currentTimeMillis()
            val routeActive = runCatching {
                NavGuidanceHub.snapshot(now).active
            }.getOrDefault(false)
            persistRuntimeMarker(
                // A process kill or planned live self-heal must leave a restart breadcrumb. An
                // intentional terminal stop cannot become a SERVICE_RESTART incident.
                runtimeActive = expectRestart,
                routeActive = routeActive && hudController.isEnabled(),
                hudEnabled = hudController.isEnabled(),
                lastFrameAtMs =
                    hudController.deliveryDiagnostics.value.lastGuidanceFrameSuccessAtMs,
                force = true,
            )
        }
    }

    private fun tick(generation: Long) = synchronized(lifecycleLock) {
        if (!ownsGeneration(generation, monitorGeneration)) return@synchronized
        val nowElapsed = SystemClock.elapsedRealtime()
        val sample = runtimeSample()
        persistRuntimeMarker(
            runtimeActive = true,
            routeActive = sample.routeActive,
            hudEnabled = sample.hudEnabled,
            lastFrameAtMs = hudController.deliveryDiagnostics.value.lastGuidanceFrameSuccessAtMs,
            force = lastPersistedRouteActive != sample.routeActive ||
                nowElapsed - lastMarkerPersistElapsedMs >= MARKER_PERSIST_INTERVAL_MS,
        )

        if (!sample.hudEnabled) {
            resetObservation(nowElapsed)
            lastRouteActive = false
            lastRouteSource = null
            lastRouteUpdatedAtMs = null
            pendingRestartReferenceAtMs = null
            return@synchronized
        }

        pendingRestartReferenceAtMs?.let { referenceAt ->
            val restartOutageMs = (sample.nowMs - referenceAt).coerceAtLeast(0L)
            val deliveryRecovered = sample.routeActive &&
                sample.hudStatus == HudController.Status.ON &&
                (sample.lastFrameSuccessAgeMs ?: Long.MAX_VALUE) <
                HudIncidentClassifier.OUTAGE_CONFIRM_MS
            when {
                deliveryRecovered -> pendingRestartReferenceAtMs = null
                restartOutageMs >= HudIncidentClassifier.OUTAGE_CONFIRM_MS -> {
                    record(sample, HudIncidentCause.SERVICE_RESTART, restartOutageMs)
                    pendingRestartReferenceAtMs = null
                }
            }
        }

        if (sample.routeActive) {
            lastRouteActive = true
            lastRouteSource = sample.routeSource
            lastRouteUpdatedAtMs = sample.routeAgeMs?.let { ageMs ->
                sample.nowMs - ageMs.coerceAtLeast(0L)
            }
            suspectedRouteLoss = false
            if (HudIncidentClassifier.deliveryHealthy(sample)) {
                markRuntimeRecovered(sample.nowMs, nowElapsed)
                return@synchronized
            }

            val since = outageSinceElapsedMs ?: nowElapsed.also { outageSinceElapsedMs = it }
            // Start the five-second confirmation window when this monitor first observes the
            // missing frame. An old success from a previous route must not make a newly started
            // route look as if it had already been broken for hours.
            val observedOutageMs = nowElapsed - since
            if (observedOutageMs >= HudIncidentClassifier.OUTAGE_CONFIRM_MS &&
                activeIncidentAtMs == null
            ) {
                record(sample, HudIncidentClassifier.cause(sample), observedOutageMs)
            }
            return@synchronized
        }

        if (lastRouteActive) {
            lastRouteActive = false
            suspectedRouteLoss = true
            outageSinceElapsedMs = nowElapsed
        }
        if (suspectedRouteLoss) {
            val cause = HudIncidentClassifier.routeLossCause(
                sample,
                lastRouteSource,
                lastRouteUpdatedAtMs?.let { updatedAtMs ->
                    (sample.nowMs - updatedAtMs).coerceAtLeast(0L)
                },
            )
            if (cause == null) {
                // Recent readable Waze evidence says the route ended normally.
                suspectedRouteLoss = false
                lastRouteSource = null
                lastRouteUpdatedAtMs = null
                resetObservation(nowElapsed)
                return
            }
            val since = outageSinceElapsedMs ?: nowElapsed.also { outageSinceElapsedMs = it }
            val outageMs = nowElapsed - since
            if (outageMs >= HudIncidentClassifier.OUTAGE_CONFIRM_MS &&
                activeIncidentAtMs == null
            ) {
                record(sample, cause, outageMs)
                suspectedRouteLoss = false
                lastRouteSource = null
                lastRouteUpdatedAtMs = null
            }
        }
    }

    internal fun runtimeSample(): HudHealthSample {
        val now = System.currentTimeMillis()
        val route = NavGuidanceHub.snapshot(now)
        val routeDiagnostics = NavGuidanceHub.diagnostics()
        val delivery = hudController.deliveryDiagnostics.value
        val a11y = NavA11yFeed.diagnostics()
        fun age(timestamp: Long?): Long? = timestamp?.let { (now - it).coerceAtLeast(0L) }
        return HudHealthSample(
            nowMs = now,
            serviceUptimeMs = (SystemClock.elapsedRealtime() - serviceStartedAtElapsedMs)
                .coerceAtLeast(0L),
            hudEnabled = hudController.isEnabled(),
            hudStatus = hudController.status.value,
            lastFrameSuccessAgeMs = age(delivery.lastGuidanceFrameSuccessAtMs),
            resultCode = delivery.lastResultCode,
            failure = delivery.lastFailure,
            routeActive = route.active,
            routeSource = route.source?.name,
            routeAgeMs = age(route.lastUpdateMs.takeIf { it > 0L }),
            routeObservedAgeMs = age(route.lastRouteObservedMs.takeIf { it > 0L }),
            routeManeuverGaode = route.maneuverGaode,
            routeRenderable = hasRenderableHudGuidance(route),
            routeEndReason = routeDiagnostics.lastRouteEndReason,
            routeEndAgeMs = age(routeDiagnostics.lastRouteEndedAtMs),
            accessibilityConnected = SteeringWheelKeyService.isConnected,
            feedEnabled = a11y.enabled,
            wazeWindowReachable = a11y.windowReachable,
            wazeEventAgeMs = age(a11y.lastWazeEventAtMs),
            wazeGuidanceAgeMs = age(a11y.lastGuidanceAtMs),
            wazeNoGuidanceAgeMs = age(a11y.lastNoGuidanceAtMs),
            wazeWindowUnreachableAgeMs = age(a11y.lastWindowUnreachableAtMs),
            wazeUnreadableAgeMs = age(a11y.lastUnreadableAtMs),
            wazeProbeResult = a11y.lastProbeResult,
            lastDeliveryKind = delivery.lastDeliveryKind?.name,
            lastClearAttemptAgeMs = age(delivery.lastClearAttemptAtMs),
            lastClearSuccessAgeMs = age(delivery.lastClearSuccessAtMs),
            reconnectAttempt = delivery.reconnectAttempt,
            nextReconnectAtMs = delivery.nextReconnectAtMs,
        )
    }

    private fun record(sample: HudHealthSample, cause: HudIncidentCause, outageMs: Long) {
        // A pending restart marker and the live monitor can converge on the same 500 ms tick.
        if (activeIncidentAtMs != null) return
        val incident = HudIncident(
            detectedAtMs = sample.nowMs,
            cause = cause,
            outageBeforeDetectionMs = outageMs,
            hudStatus = sample.hudStatus.name,
            resultCode = sample.resultCode,
            failure = sample.failure,
            routeSource = sample.routeSource,
            routeAgeMs = sample.routeAgeMs,
            routeObservedAgeMs = sample.routeObservedAgeMs,
            routeManeuverGaode = sample.routeManeuverGaode,
            routeRenderable = sample.routeRenderable,
            routeEndReason = sample.routeEndReason?.name,
            accessibilityConnected = sample.accessibilityConnected,
            feedEnabled = sample.feedEnabled,
            wazeWindowReachable = sample.wazeWindowReachable,
            wazeEventAgeMs = sample.wazeEventAgeMs,
            wazeGuidanceAgeMs = sample.wazeGuidanceAgeMs,
            wazeNoGuidanceAgeMs = sample.wazeNoGuidanceAgeMs,
            wazeWindowUnreachableAgeMs = sample.wazeWindowUnreachableAgeMs,
            wazeUnreadableAgeMs = sample.wazeUnreadableAgeMs,
            wazeProbeResult = sample.wazeProbeResult?.name,
        )
        synchronized(lock) {
            cachedIncidents = appendBounded(cachedIncidents, incident)
            persistIncidents(cachedIncidents)
        }
        activeIncidentAtMs = incident.detectedAtMs
        Log.w(
            TAG,
            "HUD incident: cause=$cause outage=${outageMs}ms status=${sample.hudStatus} " +
                "rc=${sample.resultCode} failure=${sample.failure} route=${sample.routeSource} " +
                "a11y=${sample.accessibilityConnected} window=${sample.wazeWindowReachable}",
        )
    }

    /** An incident is recovered only after a fresh route frame is accepted again. A route ending,
     * HUD toggle-off or service teardown merely stops observation; none proves that the broken
     * channel recovered. */
    private fun markRuntimeRecovered(nowMs: Long, nowElapsed: Long) {
        if (activeIncidentAtMs != null) markRecovered(nowMs)
        resetObservation(nowElapsed)
    }

    private fun resetObservation(nowElapsed: Long) {
        outageSinceElapsedMs = null
        suspectedRouteLoss = false
        if (lastMarkerPersistElapsedMs > nowElapsed) lastMarkerPersistElapsedMs = nowElapsed
    }

    private fun markRecovered(recoveredAtMs: Long) {
        val target = activeIncidentAtMs ?: return
        synchronized(lock) {
            cachedIncidents = cachedIncidents.map { incident ->
                if (incident.detectedAtMs == target && incident.recoveredAtMs == null) {
                    incident.copy(recoveredAtMs = recoveredAtMs)
                } else {
                    incident
                }
            }
            persistIncidents(cachedIncidents)
        }
        activeIncidentAtMs = null
    }

    private fun persistRuntimeMarker(
        runtimeActive: Boolean,
        routeActive: Boolean,
        hudEnabled: Boolean,
        lastFrameAtMs: Long?,
        force: Boolean,
    ) {
        if (!force) return
        runCatching {
            prefs.edit()
                .putBoolean(KEY_RUNTIME_ACTIVE, runtimeActive)
                .putBoolean(KEY_ROUTE_ACTIVE, routeActive)
                .putBoolean(KEY_HUD_ENABLED, hudEnabled)
                .putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
                .apply {
                    if (lastFrameAtMs != null) putLong(KEY_LAST_FRAME_AT, lastFrameAtMs)
                    else remove(KEY_LAST_FRAME_AT)
                }
                .apply()
            lastMarkerPersistElapsedMs = SystemClock.elapsedRealtime()
            lastPersistedRouteActive = routeActive
        }.onFailure { Log.w(TAG, "Cannot persist HUD runtime marker: ${it.message}") }
    }

    private fun loadIncidents(): List<HudIncident> = runCatching {
        val raw = prefs.getString(KEY_INCIDENTS, null) ?: return@runCatching emptyList()
        HudIncidentJson.decode(raw).takeLast(MAX_INCIDENTS)
    }.getOrElse {
        Log.w(TAG, "Cannot load HUD incidents: ${it.message}")
        emptyList()
    }

    private fun persistIncidents(incidents: List<HudIncident>) {
        runCatching { prefs.edit().putString(KEY_INCIDENTS, HudIncidentJson.encode(incidents)).apply() }
            .onFailure { Log.w(TAG, "Cannot persist HUD incidents: ${it.message}") }
    }
}

internal object HudIncidentJson {
    fun encode(incidents: List<HudIncident>): String = JSONArray().apply {
        incidents.forEach { incident ->
            put(JSONObject().apply {
                put("detectedAtMs", incident.detectedAtMs)
                put("cause", incident.cause.name)
                put("outageBeforeDetectionMs", incident.outageBeforeDetectionMs)
                putNullable("recoveredAtMs", incident.recoveredAtMs)
                put("hudStatus", incident.hudStatus)
                putNullable("resultCode", incident.resultCode)
                putNullable("failure", incident.failure)
                putNullable("routeSource", incident.routeSource)
                putNullable("routeAgeMs", incident.routeAgeMs)
                putNullable("routeObservedAgeMs", incident.routeObservedAgeMs)
                put("routeManeuverGaode", incident.routeManeuverGaode)
                put("routeRenderable", incident.routeRenderable)
                putNullable("routeEndReason", incident.routeEndReason)
                put("accessibilityConnected", incident.accessibilityConnected)
                put("feedEnabled", incident.feedEnabled)
                putNullable("wazeWindowReachable", incident.wazeWindowReachable)
                putNullable("wazeEventAgeMs", incident.wazeEventAgeMs)
                putNullable("wazeGuidanceAgeMs", incident.wazeGuidanceAgeMs)
                putNullable("wazeNoGuidanceAgeMs", incident.wazeNoGuidanceAgeMs)
                putNullable("wazeWindowUnreachableAgeMs", incident.wazeWindowUnreachableAgeMs)
                putNullable("wazeUnreadableAgeMs", incident.wazeUnreadableAgeMs)
                putNullable("wazeProbeResult", incident.wazeProbeResult)
            })
        }
    }.toString()

    fun decode(raw: String): List<HudIncident> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                // One partially written/old object must not erase every healthy incident around
                // it. Required fields fail only this entry; newly added evidence is optional.
                val incident = runCatching {
                    val item = array.optJSONObject(index) ?: return@runCatching null
                    val cause = HudIncidentCause.valueOf(item.getString("cause"))
                    HudIncident(
                        detectedAtMs = item.getLong("detectedAtMs"),
                        cause = cause,
                        outageBeforeDetectionMs = item.getLong("outageBeforeDetectionMs"),
                        recoveredAtMs = item.optNullableLong("recoveredAtMs"),
                        hudStatus = item.optString("hudStatus", "UNKNOWN"),
                        resultCode = item.optNullableInt("resultCode"),
                        failure = item.optNullableString("failure"),
                        routeSource = item.optNullableString("routeSource"),
                        routeAgeMs = item.optNullableLong("routeAgeMs"),
                        routeObservedAgeMs = item.optNullableLong("routeObservedAgeMs"),
                        routeManeuverGaode = item.optInt("routeManeuverGaode", 0),
                        routeRenderable = item.optBoolean("routeRenderable", false),
                        routeEndReason = item.optNullableString("routeEndReason"),
                        accessibilityConnected = item.optBoolean("accessibilityConnected", false),
                        feedEnabled = item.optBoolean("feedEnabled", false),
                        wazeWindowReachable = item.optNullableBoolean("wazeWindowReachable"),
                        wazeEventAgeMs = item.optNullableLong("wazeEventAgeMs"),
                        wazeGuidanceAgeMs = item.optNullableLong("wazeGuidanceAgeMs"),
                        wazeNoGuidanceAgeMs = item.optNullableLong("wazeNoGuidanceAgeMs"),
                        wazeWindowUnreachableAgeMs =
                            item.optNullableLong("wazeWindowUnreachableAgeMs"),
                        wazeUnreadableAgeMs = item.optNullableLong("wazeUnreadableAgeMs"),
                        wazeProbeResult = item.optNullableString("wazeProbeResult"),
                    )
                }.getOrNull()
                if (incident != null) add(incident)
            }
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else getBoolean(key)
}
