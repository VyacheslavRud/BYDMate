package com.bydmate.app.hud

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.data.diagnostics.DiagnosticEvidenceStore
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavGuidanceHub
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** HUD output lifecycle. Ordering rules (Codex fixes 1/2/4):
 *  - the SOME/IP package probe runs BEFORE any helper-daemon work: cars without the
 *    factory HUD see zero side effects; runtime status and the a11y self-heal always use the
 *    current package probe, so a stale pre-OTA support cache cannot affect behaviour;
 *  - bind (up to ~71 s of retries) runs OUTSIDE the mutex in a cancellable job, so
 *    toggle-off never blocks on it;
 *  - teardown sends a bounded clear sequence, then stopService, then unbind;
 *  - transient bind/gateway failures recover with bounded backoff while TrackingService owns the
 *    controller, instead of leaving the HUD dead until the next ignition cycle. */
@Singleton
class HudController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helperClient: HelperClient,
    private val helperBootstrap: HelperBootstrap,
) {
    enum class Status { OFF, UNSUPPORTED, CONNECTING, ON, BIND_FAILED, SEND_FAILED }

    data class DeliveryDiagnostics(
        val bindStartedAtMs: Long? = null,
        val bindDurationMs: Long? = null,
        val lastFrameAttemptAtMs: Long? = null,
        val lastFrameSuccessAtMs: Long? = null,
        val lastGuidanceFrameSuccessAtMs: Long? = null,
        val lastClearAttemptAtMs: Long? = null,
        val lastClearSuccessAtMs: Long? = null,
        val lastDeliveryKind: HudFrameKind? = null,
        val lastResultCode: Int? = null,
        val lastFailure: String? = null,
        val reconnectAttempt: Int = 0,
        val nextReconnectAtMs: Long? = null,
        val lastRecoveredAtMs: Long? = null,
    )

    companion object {
        private const val TAG = "HudController"
        const val PREFS_NAME = "hud"
        const val KEY_ENABLED = "hud_enabled"
        const val KEY_SUPPORTED = "hud_supported"
        const val KEY_SPEED_SIGN = "hud_speed_sign"
        private const val KEY_LAST_FRAME_ATTEMPT_AT = "hud_last_frame_attempt_at"
        private const val KEY_LAST_FRAME_SUCCESS_AT = "hud_last_frame_success_at"
        private const val KEY_LAST_GUIDANCE_FRAME_SUCCESS_AT = "hud_last_guidance_frame_success_at"
        private const val KEY_LAST_FRAME_RC = "hud_last_frame_rc"
        private const val DELIVERY_PERSIST_INTERVAL_MS = 60_000L
        internal const val SEND_FAILURES_BEFORE_RECONNECT = 20

        /** 5 s, 15 s, 30 s, then 60 s. Long enough not to hammer a waking DiLink gateway, short
         * enough to restore an active route without requiring driver action. */
        internal fun reconnectDelayForAttempt(attempt: Int): Long = when (attempt) {
            0 -> 5_000L
            1 -> 15_000L
            2 -> 30_000L
            else -> 60_000L
        }

        internal fun shouldReconnectAfterSendFailures(consecutiveFailures: Int): Boolean =
            consecutiveFailures >= SEND_FAILURES_BEFORE_RECONNECT
    }

    /** Single lane: stop()/startIfEnabled() launched across a service restart must
     *  execute in submission order - guards alone cannot give that (final-review fix 2). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal var scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    internal var bridgeFactory: (Context, (String) -> Unit) -> HudSomeIpBridge =
        { ctx, onLost -> HudSomeIpBridge(ctx, onLost) }
    internal var reconnectDelayMs: (Int) -> Long = ::reconnectDelayForAttempt

    private val mutex = Mutex()
    private var startJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private var consecutiveSendFailures: Int = 0
    @Volatile private var pendingClearAfterReconnect: Boolean = false
    @Volatile private var lifecycleActive: Boolean = false
    @Volatile private var bridge: HudSomeIpBridge? = null
    @Volatile private var loop: HudPushLoop? = null
    @Volatile private var lastDeliveryPersistElapsedMs = 0L

    private val _status = MutableStateFlow(
        if (isEnabled() && !HudSomeIpBridge.isServicePresent(context.packageManager)) {
            Status.UNSUPPORTED
        } else {
            Status.OFF
        }
    )
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _deliveryDiagnostics = MutableStateFlow(
        DeliveryDiagnostics(
            lastFrameAttemptAtMs = prefs().getLong(
                diagnosticPrefKey(KEY_LAST_FRAME_ATTEMPT_AT),
                0L,
            ).takeIf { it > 0L },
            lastFrameSuccessAtMs = prefs().getLong(
                diagnosticPrefKey(KEY_LAST_FRAME_SUCCESS_AT),
                0L,
            ).takeIf { it > 0L },
            lastGuidanceFrameSuccessAtMs = prefs().getLong(
                diagnosticPrefKey(KEY_LAST_GUIDANCE_FRAME_SUCCESS_AT),
                0L,
            ).takeIf { it > 0L },
            lastResultCode = prefs().getInt(
                diagnosticPrefKey(KEY_LAST_FRAME_RC),
                Int.MIN_VALUE,
            )
                .takeIf { it != Int.MIN_VALUE },
        )
    )
    val deliveryDiagnostics: StateFlow<DeliveryDiagnostics> = _deliveryDiagnostics.asStateFlow()

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A transport result from older firmware must not be presented as evidence after a DiLink OTA. */
    private fun diagnosticPrefKey(base: String): String = "$base|${Build.FINGERPRINT}"

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    fun isSpeedSignEnabled(): Boolean = prefs().getBoolean(KEY_SPEED_SIGN, true)

    fun setSpeedSignEnabled(on: Boolean) {
        prefs().edit().putBoolean(KEY_SPEED_SIGN, on).apply()
    }

    /** True only when the feature is on AND the gateway probe confirmed support -
     *  the a11y keep-alive gate must not fire on the raw pref (Codex fix 1). */
    fun requiresA11y(): Boolean = isEnabled() &&
        HudSomeIpBridge.isServicePresent(context.packageManager)

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            lifecycleActive = true
            scope.launch { startSequence() }
        } else {
            lifecycleActive = false
            NavA11yFeed.disable()   // stop tree reads immediately; teardown is async
            scope.launch { stopSequence() }
        }
    }

    /** TrackingService.onCreate hook. */
    fun startIfEnabled() {
        lifecycleActive = true
        if (isEnabled()) scope.launch { startSequence() }
    }

    /** Cheap idempotent live-mode watchdog hook. A healthy channel is untouched; a dead channel
     * resumes its bounded reconnect sequence. SEND_FAILED gets a clean rebind because the Android
     * ServiceConnection can remain technically bound while its remote SOME/IP endpoint is stale. */
    fun ensureRunning(reason: String) {
        if (!isEnabled()) return
        lifecycleActive = true
        scope.launch {
            if (reason.startsWith("wake:")) {
                // A long backoff belongs to a gateway that stayed broken. SCREEN_ON/USER_PRESENT
                // is new evidence that DiLink services were recreated, so retry immediately.
                mutex.withLock {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
                        nextReconnectAtMs = null,
                    )
                }
            }
            val currentBridge = bridge
            when {
                _status.value == Status.ON && currentBridge?.isConnected() == true -> {
                    // A grant/wake race can disable the feed without killing the SOME/IP binder.
                    if (!NavA11yFeed.isEnabled) NavA11yFeed.enable()
                }
                _status.value == Status.ON && currentBridge != null ->
                    rebuildChannel("watchdog:$reason:transport_unavailable")
                _status.value == Status.SEND_FAILED && currentBridge != null ->
                    rebuildChannel("watchdog:$reason")
                else -> startSequence()
            }
        }
    }

    /** TrackingService.onDestroy hook. */
    fun stop() {
        lifecycleActive = false
        NavA11yFeed.disable()
        scope.launch { stopSequence() }
    }

    private suspend fun startSequence() = mutex.withLock {
        if (!lifecycleActive || !isEnabled()) return
        if (reconnectJob?.isActive == true || startJob?.isActive == true || bridge != null) return
        // Probe BEFORE any helper-daemon work: unsupported cars must see zero side effects.
        if (!HudSomeIpBridge.isServicePresent(context.packageManager)) {
            prefs().edit().putBoolean(KEY_SUPPORTED, false).apply()
            _status.value = Status.UNSUPPORTED
            _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
                lastResultCode = null,
                lastFailure = "gateway_missing",
            )
            Log.i(TAG, "SOME/IP gateway absent; HUD output stays unloaded")
            return
        }
        prefs().edit().putBoolean(KEY_SUPPORTED, true).apply()
        _status.value = Status.CONNECTING
        val bindStartedAt = System.currentTimeMillis()
        val bindStartedElapsed = SystemClock.elapsedRealtime()
        _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
            bindStartedAtMs = bindStartedAt,
            bindDurationMs = null,
        )
        // Self-enable the a11y data source via the helper daemon (DiLink has no a11y UI).
        if (helperBootstrap.ensureRunning()) helperClient.enableAccessibilityService()
        // Bind OUTSIDE the mutex: up to ~71 s and must not block toggle-off (Codex fix 2).
        startJob = scope.launch {
            val b = bridgeFactory(context) { reason -> onBindingLost(reason) }
            try {
                if (!b.bind()) {
                    b.unbind()
                    _status.value = Status.BIND_FAILED
                    _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
                        bindDurationMs = SystemClock.elapsedRealtime() - bindStartedElapsed,
                        lastFailure = "bind_timeout",
                    )
                    scheduleReconnect("bind_timeout")
                    return@launch
                }
                val rc = b.startService(HudSomeIpBridge.SERVICE_ID_NAVI)
                if (rc < 0) {
                    b.unbind()
                    _status.value = Status.BIND_FAILED
                    _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
                        bindDurationMs = SystemClock.elapsedRealtime() - bindStartedElapsed,
                        lastResultCode = rc,
                        lastFailure = "start_service_rejected",
                    )
                    scheduleReconnect("start_service_rejected")
                    return@launch
                }
                HudIconLoader.init(context)
                bridge = b
                NavA11yFeed.enable()
                _status.value = Status.ON
                // A successful bind/startService proves only control-channel availability. After
                // a send/clear failure, keep exponential backoff state until an actual HUD frame
                // is accepted; otherwise a gateway that rejects every clear loops at 5 s forever.
                val deliveryProofRequired = pendingClearAfterReconnect ||
                    hasRenderableHudGuidance(NavGuidanceHub.snapshot())
                val channelRecovered = reconnectAttempt > 0 ||
                    _deliveryDiagnostics.value.lastFailure != null
                if (!deliveryProofRequired) reconnectAttempt = 0
                consecutiveSendFailures = 0
                _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
                    bindDurationMs = SystemClock.elapsedRealtime() - bindStartedElapsed,
                    lastResultCode = rc,
                    lastFailure = if (deliveryProofRequired) {
                        _deliveryDiagnostics.value.lastFailure
                    } else {
                        null
                    },
                    reconnectAttempt = reconnectAttempt,
                    nextReconnectAtMs = null,
                    lastRecoveredAtMs = if (channelRecovered && !deliveryProofRequired) {
                        System.currentTimeMillis()
                    } else {
                        _deliveryDiagnostics.value.lastRecoveredAtMs
                    },
                )
                loop = HudPushLoop(
                    b,
                    speedSignEnabled = { isSpeedSignEnabled() },
                    onDeliveryAttempt = { kind, rc ->
                        val now = System.currentTimeMillis()
                        val previous = _deliveryDiagnostics.value
                        val wasSendFailed = _status.value == Status.SEND_FAILED
                        val wasReconnecting = reconnectAttempt > 0
                        if (kind == HudFrameKind.CLEAR) {
                            pendingClearAfterReconnect = rc < 0
                        } else if (rc >= 0) {
                            // A current guidance frame supersedes any stale HUD contents just as a
                            // clear would, so no deferred clear is needed after channel recovery.
                            pendingClearAfterReconnect = false
                        }
                        if (rc < 0) {
                            consecutiveSendFailures++
                            if (!wasSendFailed) {
                                Log.w(TAG, "HUD frame rejected by SOME/IP gateway rc=$rc")
                            }
                            _status.value = Status.SEND_FAILED
                        } else if (wasSendFailed) {
                            consecutiveSendFailures = 0
                            _status.value = Status.ON
                            Log.i(TAG, "HUD frame delivery recovered rc=$rc")
                        } else {
                            consecutiveSendFailures = 0
                        }
                        if (rc >= 0) reconnectAttempt = 0

                        // Keep every live result in memory, but persist only periodically or on a
                        // failure/recovery edge. HudPushLoop calls this roughly every 300 ms; writing
                        // SharedPreferences on every accepted frame caused continuous flash churn.
                        val forcePersist = when {
                            rc < 0 -> !wasSendFailed || previous.lastResultCode != rc
                            else -> wasSendFailed
                        }
                        val persisted = kind == HudFrameKind.GUIDANCE &&
                            persistDeliveryResult(now, rc, forcePersist)
                        if (kind == HudFrameKind.GUIDANCE && rc >= 0 && persisted) {
                            DiagnosticEvidenceStore.record(
                                context,
                                DiagnosticEvidenceStore.Evidence.FACTORY_HUD_FRAME,
                                now,
                            )
                        }
                        _deliveryDiagnostics.value = previous.copy(
                            lastFrameAttemptAtMs = now,
                            lastFrameSuccessAtMs = now.takeIf { rc >= 0 }
                                ?: previous.lastFrameSuccessAtMs,
                            lastGuidanceFrameSuccessAtMs = now.takeIf {
                                kind == HudFrameKind.GUIDANCE && rc >= 0
                            } ?: previous.lastGuidanceFrameSuccessAtMs,
                            lastClearAttemptAtMs = now.takeIf { kind == HudFrameKind.CLEAR }
                                ?: previous.lastClearAttemptAtMs,
                            lastClearSuccessAtMs = now.takeIf {
                                kind == HudFrameKind.CLEAR && rc >= 0
                            } ?: previous.lastClearSuccessAtMs,
                            lastDeliveryKind = kind,
                            lastResultCode = rc,
                            reconnectAttempt = reconnectAttempt,
                            nextReconnectAtMs = null,
                            lastFailure = when {
                                rc < 0 && kind == HudFrameKind.CLEAR -> "clear_frame_rejected"
                                rc < 0 -> "frame_rejected"
                                kind == HudFrameKind.CLEAR &&
                                    previous.lastFailure == "clear_frame_rejected" -> null
                                kind == HudFrameKind.CLEAR -> previous.lastFailure
                                else -> null
                            },
                            lastRecoveredAtMs = now.takeIf {
                                rc >= 0 && (wasSendFailed || wasReconnecting)
                            }
                                ?: previous.lastRecoveredAtMs,
                        )
                        if (rc < 0 &&
                            shouldReconnectAfterSendFailures(consecutiveSendFailures)
                        ) {
                            consecutiveSendFailures = 0
                            scope.launch { rebuildChannel("frame_rejected:$rc") }
                        }
                    },
                    onLoopFailure = { error ->
                        val now = System.currentTimeMillis()
                        val previous = _deliveryDiagnostics.value
                        _status.value = Status.SEND_FAILED
                        _deliveryDiagnostics.value = previous.copy(
                            lastFrameAttemptAtMs = now,
                            lastResultCode = HudSomeIpBridge.RESULT_LOCAL_ERROR,
                            lastFailure = "push_loop:${error.javaClass.simpleName}",
                        )
                    },
                    initialClearPending = pendingClearAfterReconnect,
                    onClearExhausted = { rc ->
                        pendingClearAfterReconnect = true
                        scope.launch { rebuildChannel("transport:clear_rejected:$rc") }
                    },
                )
                    .also { it.start(scope) }
                Log.i(TAG, "HUD output active")
            } catch (ce: CancellationException) {
                b.unbind()
                throw ce
            }
        }
    }

    /** Gateway binding died (crash/update). Clean up and reconnect automatically; waiting for the
     * next ignition left an otherwise healthy Waze route without HUD for the rest of the drive. */
    private fun onBindingLost(reason: String) {
        NavA11yFeed.disable()
        _status.value = Status.BIND_FAILED
        _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
            lastFailure = "transport:$reason",
        )
        scope.launch { rebuildChannel("transport:$reason") }
    }

    private suspend fun rebuildChannel(reason: String) {
        var shouldRetry = false
        mutex.withLock {
            if (!lifecycleActive || !isEnabled()) return@withLock
            if (reconnectJob?.isActive == true) return@withLock
            // Once a live channel is rebuilt, its last on-glass contents are unknown. Require an
            // accepted clear on the replacement channel before declaring recovery; active
            // guidance follows immediately on the next loop tick.
            if (bridge != null) pendingClearAfterReconnect = true
            loop?.stop()
            loop = null
            bridge?.let { old ->
                // These are best-effort on a broken channel; unbind is the important part.
                runCatching { old.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
                runCatching { old.unbind() }
            }
            bridge = null
            _status.value = Status.BIND_FAILED
            _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(lastFailure = reason)
            shouldRetry = true
        }
        if (shouldRetry) scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (!lifecycleActive || !isEnabled() || reconnectJob?.isActive == true) return
        val attemptIndex = reconnectAttempt
        val delayMs = reconnectDelayMs(attemptIndex).coerceAtLeast(0L)
        reconnectAttempt = attemptIndex + 1
        val nextAt = System.currentTimeMillis() + delayMs
        _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
            reconnectAttempt = reconnectAttempt,
            nextReconnectAtMs = nextAt,
            lastFailure = reason,
        )
        Log.w(
            TAG,
            "HUD reconnect scheduled in ${delayMs}ms " +
                "(attempt $reconnectAttempt, reason=$reason)",
        )
        reconnectJob = scope.launch {
            // A minimum suspension also keeps test/diagnostic zero-delay policies from re-entering
            // startSequence while the failed startJob is still completing.
            delay(delayMs.coerceAtLeast(1L))
            reconnectJob = null
            if (lifecycleActive && isEnabled()) {
                Log.i(TAG, "HUD reconnect attempt $reconnectAttempt ($reason)")
                startSequence()
            }
        }
    }

    /** Returns true when this result was durably scheduled for persistence. */
    private fun persistDeliveryResult(atMs: Long, rc: Int, force: Boolean): Boolean {
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && lastDeliveryPersistElapsedMs != 0L &&
            elapsed - lastDeliveryPersistElapsedMs < DELIVERY_PERSIST_INTERVAL_MS
        ) {
            return false
        }
        return runCatching {
            prefs().edit()
                .putLong(diagnosticPrefKey(KEY_LAST_FRAME_ATTEMPT_AT), atMs)
                .putInt(diagnosticPrefKey(KEY_LAST_FRAME_RC), rc)
                .apply {
                    if (rc >= 0) {
                        putLong(diagnosticPrefKey(KEY_LAST_FRAME_SUCCESS_AT), atMs)
                        putLong(diagnosticPrefKey(KEY_LAST_GUIDANCE_FRAME_SUCCESS_AT), atMs)
                    }
                }
                .apply()
            lastDeliveryPersistElapsedMs = elapsed
            true
        }.getOrElse {
            Log.w(TAG, "HUD delivery diagnostics persistence failed: ${it.message}")
            false
        }
    }

    private suspend fun stopSequence() {
        NavA11yFeed.disable()
        mutex.withLock {
            reconnectJob?.let { it.cancel(); it.join() }
            reconnectJob = null
            startJob?.let { it.cancel(); it.join() }
            startJob = null
            // startJob may have flipped the feed back on between our first write and its
            // completion (no suspension points after bind()) - re-clear (final-review fix 3).
            NavA11yFeed.disable()
            loop?.stop()
            loop = null
            bridge?.let {
                // Leave the HUD clean before tearing the channel down. A gateway can reject the
                // first transaction while waking up, so retry a bounded number of times; never
                // keep stop() hostage to a broken remote service.
                sendClearBeforeStop(it)
                runCatching { it.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
                runCatching { it.unbind() }
            }
            bridge = null
            reconnectAttempt = 0
            consecutiveSendFailures = 0
            pendingClearAfterReconnect = false
            _deliveryDiagnostics.value = _deliveryDiagnostics.value.copy(
                reconnectAttempt = 0,
                nextReconnectAtMs = null,
            )
            _status.value = Status.OFF
            // NavGuidanceHub is intentionally NOT reset: the voice agent keeps using it.
        }
    }

    private suspend fun sendClearBeforeStop(sink: HudEventSink) {
        repeat(HudPushLoop.CLEAR_MAX_ATTEMPTS) { attempt ->
            val rc = try {
                sink.fireEvent(
                    HudSomeIpBridge.TOPIC_NAVI,
                    HudProtobufBuilder.buildClearFrame(attempt),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                Log.w(TAG, "teardown clear threw: ${error.message}")
                HudSomeIpBridge.RESULT_LOCAL_ERROR
            }
            recordTeardownClearResult(System.currentTimeMillis(), rc)
            if (rc >= 0) return
            if (attempt + 1 < HudPushLoop.CLEAR_MAX_ATTEMPTS) delay(100L)
        }
        Log.w(TAG, "HUD clear was not accepted before transport teardown")
    }

    private fun recordTeardownClearResult(atMs: Long, rc: Int) {
        val previous = _deliveryDiagnostics.value
        _deliveryDiagnostics.value = previous.copy(
            lastFrameAttemptAtMs = atMs,
            lastFrameSuccessAtMs = atMs.takeIf { rc >= 0 } ?: previous.lastFrameSuccessAtMs,
            lastClearAttemptAtMs = atMs,
            lastClearSuccessAtMs = atMs.takeIf { rc >= 0 } ?: previous.lastClearSuccessAtMs,
            lastDeliveryKind = HudFrameKind.CLEAR,
            lastResultCode = rc,
            lastFailure = when {
                rc < 0 -> "teardown_clear_rejected"
                previous.lastFailure == "teardown_clear_rejected" -> null
                else -> previous.lastFailure
            },
        )
    }
}
