package com.bydmate.app.navdata

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.diagnostics.DiagnosticEvidenceStore
import com.bydmate.app.navigation.WazeNavigation

/** Passive a11y event feed: Waze window events -> NavGuidanceHub.
 *  Gated by [isEnabled] (set by HudController) so that users without the HUD feature
 *  pay a single volatile read per event. Debounced: guidance widgets update ~1/s,
 *  a11y events fire far more often. */
object NavA11yFeed {
    private const val TAG = "NavA11yFeed"
    private const val DEBOUNCE_MS = 500L
    private const val EVIDENCE_PERSIST_INTERVAL_MS = 60_000L

    @Volatile private var enabled: Boolean = false
    val isEnabled: Boolean get() = enabled

    @Volatile internal var lastProcessMs = 0L
    @Volatile private var lastProcessElapsedMs = 0L
    // Transition-only log guard: "events flowing but window unreachable" is exactly the
    // agent-blindness symptom, but it repeats every debounce tick — log edges, not ticks.
    @Volatile private var rootReachable: Boolean? = null
    @Volatile private var lastWazeEventAtMs: Long = 0L
    @Volatile private var lastReadableAtMs: Long = 0L
    @Volatile private var lastGuidanceAtMs: Long = 0L
    @Volatile private var lastNoGuidanceAtMs: Long = 0L
    @Volatile private var lastGuidanceEvidencePersistElapsedMs: Long = 0L
    @Volatile private var lastGuidanceEvidenceScope: String? = null

    data class Diagnostics(
        val enabled: Boolean,
        val lastWazeEventAtMs: Long?,
        val windowReachable: Boolean?,
        val lastReadableAtMs: Long?,
        val lastGuidanceAtMs: Long?,
        val lastNoGuidanceAtMs: Long?,
        val lastGuidanceEvidenceScope: String?,
    )

    fun diagnostics(): Diagnostics = Diagnostics(
        enabled = enabled,
        lastWazeEventAtMs = lastWazeEventAtMs.takeIf { it > 0L },
        windowReachable = rootReachable,
        lastReadableAtMs = lastReadableAtMs.takeIf { it > 0L },
        lastGuidanceAtMs = lastGuidanceAtMs.takeIf { it > 0L },
        lastNoGuidanceAtMs = lastNoGuidanceAtMs.takeIf { it > 0L },
        lastGuidanceEvidenceScope = lastGuidanceEvidenceScope,
    )

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
        lastProcessMs = 0L
        lastProcessElapsedMs = 0L
        rootReachable = null
    }

    fun onEvent(service: SteeringWheelKeyService, event: AccessibilityEvent?) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!shouldProcess(
                event?.packageName?.toString(),
                event?.eventType ?: 0,
                nowElapsed,
                lastProcessElapsedMs,
            )
        ) return
        lastProcessElapsedMs = nowElapsed
        lastProcessMs = nowMs
        lastWazeEventAtMs = nowMs
        // A Waze event with no reachable window is a route-ended signal too: the
        // guidance widgets are gone. Start the no-guidance streak so the hub deadline
        // can expire even if no further events arrive (Codex audit fix 2).
        val root = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: run {
                if (rootReachable != false) {
                    rootReachable = false
                    Log.w(TAG, "Waze events flowing but window unreachable (a11y feed blind)")
                }
                NavGuidanceHub.markNoGuidance(nowMs)
                return
            }
        if (rootReachable == false) {
            rootReachable = true
            Log.i(TAG, "Waze window reachable again")
        } else {
            rootReachable = true
        }
        lastReadableAtMs = nowMs
        try {
            when (val result = NavA11yExtractor.read(root)) {
                is NavA11yExtractor.ReadResult.Guidance -> {
                    lastGuidanceAtMs = nowMs
                    lastNoGuidanceAtMs = 0L
                    if (lastGuidanceEvidencePersistElapsedMs == 0L ||
                        nowElapsed - lastGuidanceEvidencePersistElapsedMs >=
                        EVIDENCE_PERSIST_INTERVAL_MS
                    ) {
                        DiagnosticEvidenceStore.packageIdentity(
                            service,
                            WazeNavigation.PACKAGE_NAME,
                        )?.evidenceScope?.let { scope ->
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
                    NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
                }
                is NavA11yExtractor.ReadResult.NoGuidance -> {
                    lastNoGuidanceAtMs = nowMs
                    NavGuidanceHub.markNoGuidance(nowMs)
                }
                is NavA11yExtractor.ReadResult.NotNavigator -> Unit
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Pure gate, unit-tested separately from the framework-bound onEvent. */
    fun shouldProcess(pkg: String?, eventType: Int, nowMs: Long, lastMs: Long): Boolean {
        if (!NavPackages.isNavigationPackage(pkg)) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        return lastMs == 0L || nowMs - lastMs >= DEBOUNCE_MS
    }
}
