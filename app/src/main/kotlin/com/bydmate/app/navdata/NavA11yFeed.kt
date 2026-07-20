package com.bydmate.app.navdata

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.diagnostics.DiagnosticEvidenceStore
import com.bydmate.app.navigation.WazeNavigation

/**
 * Passive accessibility feed plus a bounded route-only refresh.
 *
 * Waze guidance widgets normally update through events. During a long straight or a projection
 * hand-off Waze may remain completely silent, so one tree read every [REFRESH_INTERVAL_MS] keeps
 * an already active route lease alive. Apart from one bootstrap probe, no polling is performed
 * before a route has been discovered.
 */
object NavA11yFeed {
    private const val TAG = "NavA11yFeed"
    private const val DEBOUNCE_MS = 500L
    internal const val REFRESH_INTERVAL_MS = 15_000L
    private const val EVIDENCE_PERSIST_INTERVAL_MS = 60_000L

    enum class ProbeResult {
        GUIDANCE,
        ROUTE_UNREADABLE,
        EXPLICIT_NO_ROUTE,
        WINDOW_UNREACHABLE,
        ACCESSIBILITY_UNAVAILABLE,
        NOT_NAVIGATOR,
    }

    @Volatile private var enabled: Boolean = false
    val isEnabled: Boolean get() = enabled

    @Volatile internal var lastProcessMs = 0L
    @Volatile private var lastProcessElapsedMs = 0L
    // Transition-only log guard: agent blindness can repeat every debounce/refresh tick.
    @Volatile private var rootReachable: Boolean? = null
    @Volatile private var lastWazeEventAtMs: Long = 0L
    @Volatile private var lastReadableAtMs: Long = 0L
    @Volatile private var lastGuidanceAtMs: Long = 0L
    @Volatile private var lastNoGuidanceAtMs: Long = 0L
    @Volatile private var lastWindowUnreachableAtMs: Long = 0L
    @Volatile private var lastUnreadableAtMs: Long = 0L
    @Volatile private var lastRefreshAtMs: Long = 0L
    @Volatile private var lastProbeResult: ProbeResult? = null
    @Volatile private var lastGuidanceEvidencePersistElapsedMs: Long = 0L
    @Volatile private var lastGuidanceEvidenceScope: String? = null

    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!enabled) return
            try {
                val nowMs = System.currentTimeMillis()
                // Probe before snapshot() evaluates lease expiry. If the main thread was delayed
                // for an unusually long time, a still-readable route can renew itself instead of
                // being cleared solely because the scheduled callback ran late.
                if (NavGuidanceHub.hasRouteCandidate()) {
                    lastRefreshAtMs = nowMs
                    val service = SteeringWheelKeyService.instance
                    if (service == null) {
                        lastProbeResult = ProbeResult.ACCESSIBILITY_UNAVAILABLE
                        NavGuidanceHub.markRouteIndeterminate()
                    } else {
                        processWindow(service, nowMs, SystemClock.elapsedRealtime())
                    }
                }
            } catch (error: Exception) {
                // A failed probe must never terminate the only lease-refresh loop.
                Log.w(TAG, "Periodic Waze refresh failed: ${error.message}")
                NavGuidanceHub.markRouteIndeterminate()
            } finally {
                if (enabled) mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }
    private val noRouteConfirmationRunnable = object : Runnable {
        override fun run() {
            if (!enabled || !NavGuidanceHub.snapshot().active) return
            val service = SteeringWheelKeyService.instance
            if (service == null) {
                lastProbeResult = ProbeResult.ACCESSIBILITY_UNAVAILABLE
                NavGuidanceHub.markRouteIndeterminate()
                return
            }
            runCatching {
                processWindow(service, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }.onFailure {
                Log.w(TAG, "No-route confirmation probe failed: ${it.message}")
                NavGuidanceHub.markRouteIndeterminate()
            }
        }
    }
    private val immediateProbeRunnable = Runnable {
        if (!enabled) return@Runnable
        val service = SteeringWheelKeyService.instance ?: run {
            lastProbeResult = ProbeResult.ACCESSIBILITY_UNAVAILABLE
            return@Runnable
        }
        runCatching {
            processWindow(service, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        }.onFailure { Log.w(TAG, "Immediate Waze probe failed: ${it.message}") }
    }

    data class Diagnostics(
        val enabled: Boolean,
        val lastWazeEventAtMs: Long?,
        val windowReachable: Boolean?,
        val lastReadableAtMs: Long?,
        val lastGuidanceAtMs: Long?,
        /** Last explicit route-free Waze tree, never a missing/unreadable window. */
        val lastNoGuidanceAtMs: Long?,
        val lastWindowUnreachableAtMs: Long?,
        val lastUnreadableAtMs: Long?,
        val lastRefreshAtMs: Long?,
        val lastProbeResult: ProbeResult?,
        val lastGuidanceEvidenceScope: String?,
    )

    fun diagnostics(): Diagnostics = Diagnostics(
        enabled = enabled,
        lastWazeEventAtMs = lastWazeEventAtMs.takeIf { it > 0L },
        windowReachable = rootReachable,
        lastReadableAtMs = lastReadableAtMs.takeIf { it > 0L },
        lastGuidanceAtMs = lastGuidanceAtMs.takeIf { it > 0L },
        lastNoGuidanceAtMs = lastNoGuidanceAtMs.takeIf { it > 0L },
        lastWindowUnreachableAtMs = lastWindowUnreachableAtMs.takeIf { it > 0L },
        lastUnreadableAtMs = lastUnreadableAtMs.takeIf { it > 0L },
        lastRefreshAtMs = lastRefreshAtMs.takeIf { it > 0L },
        lastProbeResult = lastProbeResult,
        lastGuidanceEvidenceScope = lastGuidanceEvidenceScope,
    )

    fun enable() {
        if (enabled) return
        enabled = true
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(noRouteConfirmationRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        requestImmediateProbe()
    }

    /** Bootstrap hook for HUD enable and a later AccessibilityService connection. */
    fun requestImmediateProbe() {
        if (!enabled) return
        mainHandler.removeCallbacks(immediateProbeRunnable)
        mainHandler.post(immediateProbeRunnable)
    }

    fun disable() {
        enabled = false
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(noRouteConfirmationRunnable)
        mainHandler.removeCallbacks(immediateProbeRunnable)
        lastProcessMs = 0L
        lastProcessElapsedMs = 0L
        rootReachable = null
        lastProbeResult = null
        NavGuidanceHub.markRouteIndeterminate()
    }

    fun onEvent(service: SteeringWheelKeyService, event: AccessibilityEvent?) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val eventType = event?.eventType ?: 0
        val pkg = event?.packageName?.toString()
        val allowWindowProbe = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            NavGuidanceHub.snapshot(nowMs).active
        if (!shouldProcess(pkg, eventType, nowElapsed, lastProcessElapsedMs, allowWindowProbe)) {
            return
        }
        lastProcessElapsedMs = nowElapsed
        lastProcessMs = nowMs
        if (NavPackages.isNavigationPackage(pkg)) lastWazeEventAtMs = nowMs
        processWindow(service, nowMs, nowElapsed)
    }

    /** Main-thread tree read shared by event and periodic refresh paths. */
    internal fun processWindow(
        service: SteeringWheelKeyService,
        nowMs: Long,
        nowElapsed: Long,
    ): ProbeResult {
        val root = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: run {
                if (rootReachable != false) {
                    rootReachable = false
                    Log.w(TAG, "Waze window temporarily unreachable (route state indeterminate)")
                }
                lastWindowUnreachableAtMs = nowMs
                lastProbeResult = ProbeResult.WINDOW_UNREACHABLE
                mainHandler.removeCallbacks(noRouteConfirmationRunnable)
                NavGuidanceHub.markRouteIndeterminate()
                return ProbeResult.WINDOW_UNREACHABLE
            }
        if (rootReachable == false) {
            rootReachable = true
            Log.i(TAG, "Waze window reachable again")
        } else {
            rootReachable = true
        }
        lastReadableAtMs = nowMs
        return try {
            when (val result = NavA11yExtractor.read(root)) {
                is NavA11yExtractor.ReadResult.Guidance -> {
                    lastGuidanceAtMs = nowMs
                    lastNoGuidanceAtMs = 0L
                    mainHandler.removeCallbacks(noRouteConfirmationRunnable)
                    recordGuidanceEvidence(service, nowMs, nowElapsed)
                    NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
                    ProbeResult.GUIDANCE
                }
                is NavA11yExtractor.ReadResult.NoGuidance -> {
                    if (hasRouteAnchor(root)) {
                        // Route UI exists but this Waze version/layout exposed no parseable value.
                        // Keep the lease, keep the last good frame, and surface parser blindness.
                        lastUnreadableAtMs = nowMs
                        lastNoGuidanceAtMs = 0L
                        mainHandler.removeCallbacks(noRouteConfirmationRunnable)
                        NavGuidanceHub.markRouteObserved(NavGuidanceHub.Source.A11Y, nowMs)
                        ProbeResult.ROUTE_UNREADABLE
                    } else {
                        lastNoGuidanceAtMs = nowMs
                        val noGuidance = NavGuidanceHub.markNoGuidance(nowMs)
                        mainHandler.removeCallbacks(noRouteConfirmationRunnable)
                        if (noGuidance == NavGuidanceHub.NoGuidanceResult.PENDING) {
                            mainHandler.postDelayed(
                                noRouteConfirmationRunnable,
                                NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS,
                            )
                        }
                        ProbeResult.EXPLICIT_NO_ROUTE
                    }
                }
                is NavA11yExtractor.ReadResult.NotNavigator -> {
                    lastUnreadableAtMs = nowMs
                    mainHandler.removeCallbacks(noRouteConfirmationRunnable)
                    NavGuidanceHub.markRouteIndeterminate()
                    ProbeResult.NOT_NAVIGATOR
                }
            }.also { lastProbeResult = it }
        } finally {
            recycle(root)
        }
    }

    private fun recordGuidanceEvidence(
        service: SteeringWheelKeyService,
        nowMs: Long,
        nowElapsed: Long,
    ) {
        if (lastGuidanceEvidencePersistElapsedMs != 0L &&
            nowElapsed - lastGuidanceEvidencePersistElapsedMs < EVIDENCE_PERSIST_INTERVAL_MS
        ) {
            return
        }
        DiagnosticEvidenceStore.packageIdentity(service, WazeNavigation.PACKAGE_NAME)
            ?.evidenceScope
            ?.let { scope ->
                DiagnosticEvidenceStore.record(
                    service,
                    DiagnosticEvidenceStore.Evidence.WAZE_GUIDANCE,
                    nowMs,
                    scope,
                )
                lastGuidanceEvidenceScope = scope
                lastGuidanceEvidencePersistElapsedMs = nowElapsed
            }
    }

    private fun hasRouteAnchor(root: AccessibilityNodeInfo): Boolean = runCatching {
        WazeAccessibilityReader.hasRouteAnchor(root)
    }.getOrDefault(false)

    private fun recycle(root: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        runCatching { root.recycle() }
    }

    /** Pure gate, unit-tested separately from the framework-bound tree read. */
    fun shouldProcess(
        pkg: String?,
        eventType: Int,
        nowMs: Long,
        lastMs: Long,
        allowWindowProbe: Boolean = false,
    ): Boolean {
        // Waze builds do not consistently use one accessibility event type for maneuver changes:
        // on DiLink the same navigation bar can emit content, selection, scroll or announcement
        // events. Accept every event from the exact Waze package and keep the existing 500 ms
        // debounce. Package-less topology events remain restricted to an already active route.
        val fromWaze = NavPackages.isNavigationPackage(pkg)
        val activeWindowProbe = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            allowWindowProbe
        if (!fromWaze && !activeWindowProbe) return false
        if (eventType == 0) return false
        return lastMs == 0L || nowMs - lastMs >= DEBOUNCE_MS
    }
}
