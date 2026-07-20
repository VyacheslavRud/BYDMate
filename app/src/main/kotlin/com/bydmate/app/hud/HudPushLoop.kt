package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HudFrameKind { GUIDANCE, CLEAR }

/**
 * Whether an active route has enough information to keep the factory navigation card alive.
 *
 * Some DiLink/Waze combinations expose an active maneuver widget whose localized description is
 * not parseable yet, and a background/projected Waze window can temporarily hide every optional
 * field. NavGuidanceHub only becomes active from positive route UI/notification evidence, so the
 * active flag itself is the compatibility gate. For maneuver 0 the encoder omits f28 and the PNG
 * icon, keeping distance/road guidance alive without inventing a direction. The previous hard
 * requirement for maneuverGaode > 0 therefore turned a recoverable partial update into a complete
 * HUD outage on the Sea Lion 07.
 */
internal fun hasRenderableHudGuidance(snapshot: NavGuidanceHub.Snapshot): Boolean =
    snapshot.active

/** 300 ms push loop: NavGuidanceHub snapshot -> protobuf frame -> SOME/IP fireEvent.
 *  When guidance ends (hub goes inactive), a clear frame is retried up to a small fixed bound. */
class HudPushLoop(
    private val sink: HudEventSink,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val onDeliveryResult: (Int) -> Unit = {},
    private val onDeliveryAttempt: (HudFrameKind, Int) -> Unit = { _, _ -> },
    private val onLoopFailure: (Throwable) -> Unit = {},
    initialClearPending: Boolean = false,
    private val onClearExhausted: (Int) -> Unit = {},
    private val outputSuspended: () -> Boolean = { false },
) {
    companion object {
        private const val TAG = "HudPushLoop"
        const val PERIOD_MS = 300L
        internal const val CLEAR_MAX_ATTEMPTS = 3
        /** Local sentinel: the shared native topic is currently owned by parked HUD Lab. */
        internal const val RESULT_OUTPUT_SUSPENDED = Int.MIN_VALUE
    }

    private var job: Job? = null
    private var counter = 0   // clear frames only; guidance frames carry the constant 2 in f2
    /** Recovery/process-death clear is independent from a route-end clear and always runs first. */
    private var recoveryClearAttempts = if (initialClearPending) CLEAR_MAX_ATTEMPTS else 0
    private var pendingClearAttempts = 0
    private var refreshClearAttempts = 0
    private var lastRefreshGeneration: Long? = null

    fun start(scope: CoroutineScope, periodMs: Long = PERIOD_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            var wasActive = false
            while (isActive) {
                wasActive = tickSafely(wasActive)
                delay(periodMs)
            }
        }
    }

    internal fun tickSafely(wasActive: Boolean): Boolean = try {
        tick(wasActive)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Exception) {
        Log.e(TAG, "HUD push tick failed: ${t.message}", t)
        onLoopFailure(t)
        wasActive
    }

    fun stop() {
        job?.cancel()
        job = null
        recoveryClearAttempts = 0
        pendingClearAttempts = 0
        refreshClearAttempts = 0
        lastRefreshGeneration = null
    }

    /** One tick; returns whether guidance was active (input for the next tick). Clear retry state
     * is retained internally because subsequent inactive ticks receive wasActive=false. */
    internal fun tick(wasActive: Boolean): Boolean {
        // HUD Lab owns the same native topic for a bounded parked calibration. Keep the normal
        // loop's state intact and do not overwrite its raw f28 frame before the user can see it.
        if (outputSuspended()) return wasActive
        // A persisted HUD Lab marker means the on-glass contents are unknown. This clear must win
        // even when Waze already has an active route: a rejected guidance frame does not supersede
        // a stale synthetic arrow. Guidance resumes only on a later tick after zero confirmation.
        if (recoveryClearAttempts > 0) {
            val rc = deliver(
                HudFrameKind.CLEAR,
                HudProtobufBuilder.buildClearFrame(counter++),
            ) ?: return wasActive
            recoveryClearAttempts--
            if (rc == 0) {
                recoveryClearAttempts = 0
                Log.i(TAG, "recovery clear frame accepted; guidance redraw pending")
            } else if (recoveryClearAttempts > 0) {
                Log.w(TAG, "recovery clear frame rejected rc=$rc; retry pending")
            } else {
                Log.e(TAG, "recovery clear frame rejected rc=$rc after $CLEAR_MAX_ATTEMPTS attempts")
                onClearExhausted(rc)
            }
            return wasActive
        }
        val s = NavGuidanceHub.snapshot(nowMsProvider())
        val previousRefreshGeneration = lastRefreshGeneration
        lastRefreshGeneration = s.hudRefreshGeneration
        if (!hasRenderableHudGuidance(s)) {
            refreshClearAttempts = 0
            if (wasActive) pendingClearAttempts = CLEAR_MAX_ATTEMPTS
            if (pendingClearAttempts > 0) {
                val rc = deliver(
                    HudFrameKind.CLEAR,
                    HudProtobufBuilder.buildClearFrame(counter++),
                ) ?: return false
                // Decrement after every attempted transaction, including a throwing sink. Without
                // this guarantee a local Binder wrapper exception retries forever at 300 ms.
                pendingClearAttempts--
                if (rc == 0) {
                    pendingClearAttempts = 0
                    Log.i(TAG, "guidance ended, clear frame accepted")
                } else if (pendingClearAttempts > 0) {
                    Log.w(TAG, "clear frame rejected rc=$rc; retry pending")
                } else {
                    Log.e(TAG, "clear frame rejected rc=$rc after $CLEAR_MAX_ATTEMPTS attempts")
                    onClearExhausted(rc)
                }
            }
            return false
        }
        if (wasActive && previousRefreshGeneration != null &&
            previousRefreshGeneration != s.hudRefreshGeneration
        ) {
            refreshClearAttempts = CLEAR_MAX_ATTEMPTS
        }
        if (refreshClearAttempts > 0) {
            val rc = deliver(
                HudFrameKind.CLEAR,
                HudProtobufBuilder.buildClearFrame(counter++),
            ) ?: return true
            refreshClearAttempts--
            if (rc == 0) {
                refreshClearAttempts = 0
                // Guidance is sent on the next 300 ms tick. A small gap is intentional: sending
                // clear+guidance back-to-back is coalesced by the Sea Lion gateway and does not
                // restore the card erased by the system notification overlay.
                Log.i(TAG, "HUD overlay recovery clear accepted; guidance redraw pending")
                return true
            }
            if (refreshClearAttempts > 0) {
                Log.w(TAG, "HUD overlay recovery clear unconfirmed rc=$rc; retry pending")
            } else {
                Log.e(TAG, "HUD overlay recovery clear failed rc=$rc after $CLEAR_MAX_ATTEMPTS attempts")
                onClearExhausted(rc)
            }
            return true
        }
        // A new active frame supersedes any clear that was waiting for a retry.
        pendingClearAttempts = 0
        // Do not feed donor PNG fields or uncalibrated maneuver metadata to the Sea Lion gateway.
        // The Waze parser, route snapshot and service lifecycle stay untouched; only the native
        // SOME/IP payload is narrowed to the fields confirmed by parked tests on this car.
        val frame = HudProtobufBuilder.buildSeaLionGuidanceFrame(
            maneuverGaode = s.maneuverGaode,
            distanceMeters = s.distanceMeters,
            road = s.road,
            etaString = s.arrivalTime.ifBlank { etaString(s.etaSeconds, s.etaUpdatedAtMs) },
        )
        deliver(HudFrameKind.GUIDANCE, frame)
        return true
    }

    /** Null means the atomic controller gate suppressed the transaction for HUD Lab ownership. */
    private fun deliver(kind: HudFrameKind, payload: ByteArray): Int? {
        var failure: Throwable? = null
        val rc = try {
            sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, payload)
        } catch (t: Exception) {
            if (t is CancellationException) throw t
            failure = t
            HudSomeIpBridge.RESULT_LOCAL_ERROR
        }
        if (rc == RESULT_OUTPUT_SUSPENDED) return null
        onDeliveryResult(rc)
        onDeliveryAttempt(kind, rc)
        failure?.let {
            Log.e(TAG, "$kind delivery threw: ${it.message}", it)
            onLoopFailure(it)
        }
        return rc
    }

    /** Remaining seconds -> wall-clock arrival "HH:MM" (f26); null when unknown. */
    internal fun etaString(etaSeconds: Int, updatedAtMs: Long = nowMsProvider()): String? {
        if (etaSeconds <= 0) return null
        val anchorMs = updatedAtMs.takeIf { it > 0L } ?: nowMsProvider()
        val cal = Calendar.getInstance().apply { timeInMillis = anchorMs + etaSeconds * 1000L }
        return String.format(Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
