package com.bydmate.app.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HudLabPending(
    val record: HudLabRecord,
    val autoCleared: Boolean = false,
)

enum class HudLabOutcomeType {
    FRAME_SENT,
    SEND_REJECTED,
    CLEARED,
    OBSERVATION_SAVED,
    EXPORTED,
    EXPORT_FAILED,
    RECORDS_DELETED,
    RECORDS_DELETE_FAILED,
}

data class HudLabOutcome(
    val type: HudLabOutcomeType,
    val rc: Int? = null,
    val failure: HudLabSendFailure? = null,
    val path: String? = null,
)

data class HudLabState(
    val busy: Boolean = false,
    val pending: HudLabPending? = null,
    val recordsCount: Int = 0,
    val lastOutcome: HudLabOutcome? = null,
    val currentScenarioId: String? = null,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentPush: Int = 0,
    val totalPushes: Int = 0,
    val completedExplorerScenarioIds: Set<String> = emptySet(),
)

/**
 * Runs one bounded parked scenario at a time. The production loop is emulated with 300 ms bursts,
 * safety is re-evaluated for every packet, every result is committed immediately, and all paths
 * release the live HUD channel.
 */
@Singleton
class HudLabManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hudController: HudController,
) {
    companion object {
        private const val TAG = "HudLab"
        private const val CLEAR_ATTEMPT_CADENCE_MS = 80L
        private const val SAFETY_WATCHDOG_MS = 250L
        internal const val AUTO_CLEAR_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val actionLock = Any()
    private var autoClearJob: Job? = null
    internal var autoClearDelayMs: Long = AUTO_CLEAR_MS
    internal var scenarioDelay: suspend (Long) -> Unit = { delay(it) }
    private val _state = MutableStateFlow(
        HudLabState(
            recordsCount = HudLabLogStore.records(context).size,
            completedExplorerScenarioIds = HudLabLogStore.completedExplorerScenarioIds(context),
        ),
    )
    val state: StateFlow<HudLabState> = _state.asStateFlow()

    fun sendScenario(scenarioId: String, parkConfirmedByUser: Boolean) {
        val scenario = HudLabScenarioCatalog.byId(scenarioId) ?: return
        synchronized(actionLock) {
            if (_state.value.busy || _state.value.pending != null) return
            _state.update {
                it.copy(
                    busy = true,
                    lastOutcome = null,
                    currentScenarioId = scenario.id,
                    currentStep = 0,
                    totalSteps = scenario.steps.size,
                    currentPush = 0,
                    totalPushes = scenario.steps.sumOf {
                        when (it) {
                            is HudLabScenarioStep.Clear -> it.attempts
                            is HudLabScenarioStep.Send -> it.repeatCount
                        }
                    },
                )
            }
        }
        scope.launch {
            operationMutex.withLock {
                runScenario(scenario, parkConfirmedByUser)
            }
        }
    }

    private suspend fun runScenario(
        scenario: HudLabScenario,
        parkConfirmedByUser: Boolean,
    ) {
        var record: HudLabRecord? = null
        var ownsHudOutput = false
        var aborted: HudLabSendFailure? = null
        var lastRc: Int? = null
        var completedPushes = 0
        val startedElapsedMs = SystemClock.elapsedRealtime()
        var completedNormally = false
        try {
            // Commit intent before the first clear/send so a process death is diagnosable.
            record = HudLabLogStore.beginScenario(context, scenario)
            HudIconLoader.init(context)

            scenario.steps.forEachIndexed { stepIndex, step ->
                if (aborted != null) return@forEachIndexed
                if (step.gapBeforeMs > 0L) scenarioDelay(step.gapBeforeMs)
                _state.update { it.copy(currentStep = stepIndex + 1) }

                when (step) {
                    is HudLabScenarioStep.Clear -> {
                        var clearConfirmed = false
                        repeat(step.attempts) { attempt ->
                            if (aborted != null) return@repeat
                            if (attempt > 0) scenarioDelay(CLEAR_ATTEMPT_CADENCE_MS)
                            val safety = hudController.checkHudLabSafety(parkConfirmedByUser)
                            val attemptId = UUID.randomUUID().toString()
                            val baseEvent = HudLabEvent(
                                type = HudLabEventType.CLEAR,
                                stepIndex = stepIndex,
                                label = "scenario_clear",
                                pushIndex = attempt,
                                atMs = System.currentTimeMillis(),
                                elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs,
                                gear = safety.gear,
                                speedKmh = safety.speedKmh,
                                phase = if (safety.failure == null) {
                                    HudLabEventPhase.INTENT
                                } else {
                                    HudLabEventPhase.RESULT
                                },
                                attemptId = attemptId.takeIf { safety.failure == null },
                            )
                            if (safety.failure == null) {
                                record = requireNotNull(
                                    HudLabLogStore.appendEvent(
                                        context,
                                        requireNotNull(record).id,
                                        baseEvent,
                                    ),
                                )
                            }
                            val clearResult = if (safety.failure == null) {
                                hudController.clearHudLabFrameSafely(parkConfirmedByUser)
                            } else {
                                safety
                            }
                            if (clearResult.outputMayBeOwned) ownsHudOutput = true
                            val rc = clearResult.rc
                            val event = baseEvent.copy(
                                atMs = System.currentTimeMillis(),
                                elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs,
                                rc = rc,
                                failure = clearResult.failure?.name,
                                gear = clearResult.gear,
                                speedKmh = clearResult.speedKmh,
                                phase = HudLabEventPhase.RESULT,
                            )
                            record = requireNotNull(
                                HudLabLogStore.appendEvent(context, requireNotNull(record).id, event),
                            )
                            lastRc = rc
                            if (rc == 0) {
                                clearConfirmed = true
                                ownsHudOutput = false
                            }
                            completedPushes += 1
                            _state.update { it.copy(currentPush = completedPushes) }
                            if (clearResult.failure != null) aborted = clearResult.failure
                        }
                        if (aborted == null && !clearConfirmed) {
                            hudController.recoverHudLabAfterFailedClear("scenario_clear")
                            aborted = HudLabSendFailure.HUD_CLEAR_FAILED
                        }
                    }

                    is HudLabScenarioStep.Send -> {
                        val built = buildPayload(step.frame)
                        if (built == null) {
                            aborted = HudLabSendFailure.HUD_ICON_UNAVAILABLE
                            val event = HudLabEvent(
                                type = HudLabEventType.SEND,
                                stepIndex = stepIndex,
                                label = step.label,
                                pushIndex = 0,
                                atMs = System.currentTimeMillis(),
                                elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs,
                                fieldManifest = step.frame.fieldManifest,
                                failure = aborted?.name,
                            )
                            record = requireNotNull(
                                HudLabLogStore.appendEvent(context, requireNotNull(record).id, event),
                            )
                            return@forEachIndexed
                        }
                        val (payload, pngBytes) = built
                        val payloadHash = payload.sha256()
                        repeat(step.repeatCount) { push ->
                            if (aborted != null) return@repeat
                            // Fixed delay intentionally mirrors HudPushLoop and never catches up
                            // with back-to-back native calls after slow durable journal IO.
                            if (push > 0) scenarioDelay(step.cadenceMs)
                            val attemptId = UUID.randomUUID().toString()
                            val intent = HudLabEvent(
                                type = HudLabEventType.SEND,
                                stepIndex = stepIndex,
                                label = step.label,
                                pushIndex = push,
                                atMs = System.currentTimeMillis(),
                                elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs,
                                payloadBytes = payload.size,
                                payloadSha256 = payloadHash,
                                fieldManifest = step.frame.fieldManifest + ",pngBytes=$pngBytes",
                                phase = HudLabEventPhase.INTENT,
                                attemptId = attemptId,
                            )
                            record = requireNotNull(
                                HudLabLogStore.appendEvent(
                                    context,
                                    requireNotNull(record).id,
                                    intent,
                                ),
                            )
                            val result = hudController.sendHudLabFrame(payload, parkConfirmedByUser)
                            // Claim output before the journal append: if that commit throws, the
                            // outer catch must still know that a frame needs an emergency clear.
                            // Any fireEvent result means the native mutation was attempted. Even a
                            // Binder error can arrive after dispatch, so cleanup remains mandatory.
                            if (result.outputMayBeOwned || result.rc != null) ownsHudOutput = true
                            val event = intent.copy(
                                atMs = System.currentTimeMillis(),
                                elapsedMs = SystemClock.elapsedRealtime() - startedElapsedMs,
                                rc = result.rc,
                                failure = result.failure?.name,
                                gear = result.gear,
                                speedKmh = result.speedKmh,
                                phase = HudLabEventPhase.RESULT,
                                outputMayBeOwned = result.outputMayBeOwned,
                            )
                            record = requireNotNull(
                                HudLabLogStore.appendEvent(context, requireNotNull(record).id, event),
                            )
                            lastRc = result.rc
                            completedPushes += 1
                            _state.update { it.copy(currentPush = completedPushes) }
                            if (!result.accepted) {
                                aborted = result.failure ?: if (result.rc != null) {
                                    HudLabSendFailure.HUD_SEND_FAILED
                                } else {
                                    HudLabSendFailure.HUD_NOT_READY
                                }
                            }
                        }
                    }
                }
            }

            if (aborted != null && ownsHudOutput) {
                val cleanup = appendClearEvent(
                    record = requireNotNull(record),
                    label = "abort_cleanup",
                    stepIndex = scenario.steps.size,
                    autoCleared = true,
                    startedElapsedMs = startedElapsedMs,
                )
                record = cleanup.record
                ownsHudOutput = !cleanup.clearConfirmed
                if (ownsHudOutput) {
                    hudController.recoverHudLabAfterFailedClear("abort_cleanup")
                    aborted = HudLabSendFailure.HUD_CLEAR_FAILED
                } else if (!cleanup.journalSucceeded) {
                    aborted = HudLabSendFailure.JOURNAL_WRITE_FAILED
                }
            }
            record = requireNotNull(
                HudLabLogStore.completeDelivery(
                    context,
                    requireNotNull(record).id,
                    abortedFailure = aborted?.name,
                ),
            )
            completedNormally = true

            Log.i(
                TAG,
                "HUD Lab scenario=${scenario.id} id=${record!!.id} events=${record!!.events.size} " +
                    "lastRc=$lastRc aborted=$aborted",
            )
            if (aborted == null) {
                val noOutputOwned = !ownsHudOutput
                _state.value = _state.value.copy(
                    busy = false,
                    pending = HudLabPending(record!!, autoCleared = noOutputOwned),
                    recordsCount = HudLabLogStore.records(context).size,
                    lastOutcome = HudLabOutcome(HudLabOutcomeType.FRAME_SENT, rc = lastRc),
                )
                if (ownsHudOutput) scheduleAutoClear(record!!.id, parkConfirmedByUser)
            } else {
                _state.value = finishedState(
                    HudLabOutcome(
                        HudLabOutcomeType.SEND_REJECTED,
                        rc = lastRc,
                        failure = aborted,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            if (ownsHudOutput) withContext(NonCancellable) { emergencyClear("cancelled") }
            throw cancelled
        } catch (error: Throwable) {
            if (ownsHudOutput) withContext(NonCancellable) { emergencyClear("exception") }
            Log.e(TAG, "HUD Lab scenario failed: ${error.message}", error)
            record?.let {
                runCatching {
                    HudLabLogStore.completeDelivery(
                        context,
                        it.id,
                        abortedFailure = HudLabSendFailure.JOURNAL_WRITE_FAILED.name,
                    )
                }
            }
            _state.value = finishedState(
                HudLabOutcome(
                    HudLabOutcomeType.SEND_REJECTED,
                    failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                ),
            )
        } finally {
            if (!completedNormally && _state.value.busy) {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun buildPayload(spec: HudLabFrameSpec): Pair<ByteArray, Int>? {
        val maneuverPng = spec.iconCode?.let(HudIconLoader::labIconFor)
        if (spec.iconCode != null && maneuverPng == null) return null
        val signPng = if (spec.includeSpeedSign) HudSpeedSign.render(spec.speedLimit) else null
        if (spec.includeSpeedSign && signPng == null) return null
        return runCatching {
            HudProtobufBuilder.buildHudLabScenarioFrame(spec, maneuverPng, signPng) to
                ((maneuverPng?.size ?: 0) + (signPng?.size ?: 0))
        }.onFailure { Log.e(TAG, "HUD Lab payload build failed: ${it.message}", it) }
            .getOrNull()
    }

    fun recordObservation(observed: HudLabObserved, userLabel: String? = null) {
        if (observed == HudLabObserved.NAMED_INDICATOR && userLabel.isNullOrBlank()) return
        synchronized(actionLock) {
            val current = _state.value
            if (current.busy || current.pending == null) return
            if (observed == HudLabObserved.NAMED_INDICATOR &&
                !HudLabScenarioCatalog.isExplorerScenario(current.pending.record.scenarioId)
            ) {
                return
            }
            _state.update { it.copy(busy = true, lastOutcome = null) }
        }
        scope.launch {
            operationMutex.withLock {
                autoClearJob?.cancel()
                autoClearJob = null
                val pending = _state.value.pending
                if (pending == null) {
                    _state.update { it.copy(busy = false) }
                    return@withLock
                }
                var clearFailed = false
                val saved = runCatching {
                    var latest = pending.record
                    if (!pending.autoCleared) {
                        val clear = appendClearEvent(
                            record = latest,
                            label = "observation_clear",
                            stepIndex = latest.events.maxOfOrNull { it.stepIndex }?.plus(1) ?: 0,
                            autoCleared = false,
                            startedElapsedMs = null,
                        )
                        latest = clear.record
                        clearFailed = !clear.clearConfirmed
                        if (clearFailed) {
                            hudController.recoverHudLabAfterFailedClear("observation_clear")
                        }
                        check(clear.journalSucceeded) { "hud_lab_clear_journal_failed" }
                    }
                    checkNotNull(
                        HudLabLogStore.recordObservation(
                            context,
                            latest.id,
                            observed,
                            userLabel = userLabel,
                        ),
                    ) { "hud_lab_record_missing_on_observation" }
                }.isSuccess
                Log.i(
                    TAG,
                    "HUD Lab observation id=${pending.record.id} expected=${pending.record.expected} " +
                        "observed=$observed labelChars=${userLabel?.length ?: 0} saved=$saved",
                )
                _state.value = if (saved) {
                    finishedState(
                        if (clearFailed) {
                            HudLabOutcome(
                                HudLabOutcomeType.SEND_REJECTED,
                                failure = HudLabSendFailure.HUD_CLEAR_FAILED,
                            )
                        } else {
                            HudLabOutcome(HudLabOutcomeType.OBSERVATION_SAVED)
                        },
                    )
                } else {
                    _state.value.copy(
                        busy = false,
                        pending = pending.copy(autoCleared = false),
                        lastOutcome = HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                        ),
                    )
                }
            }
        }
    }

    /** Emergency/manual clear intentionally bypasses the parked preflight. */
    fun clear() {
        synchronized(actionLock) {
            if (_state.value.busy) return
            _state.update { it.copy(busy = true, lastOutcome = null) }
        }
        scope.launch {
            operationMutex.withLock {
                autoClearJob?.cancel()
                autoClearJob = null
                val pending = _state.value.pending
                var clearRc: Int? = null
                var clearConfirmed = false
                var clearAttempted = false
                var fallbackHandledRecovery = false
                val saved = runCatching {
                    val initial = pending?.record ?: HudLabLogStore.beginScenario(
                        context,
                        HudLabScenario(
                            id = "MANUAL_CLEAR",
                            group = HudLabScenarioGroup.CONTROL,
                            title = "Manual emergency CLEAR",
                            command = null,
                            expected = HudLabObserved.NOTHING,
                            steps = listOf(HudLabScenarioStep.Clear(attempts = 3)),
                        ),
                    )
                    val clear = appendClearEvent(
                        record = initial,
                        label = "manual_clear",
                        stepIndex = initial.events.maxOfOrNull { it.stepIndex }?.plus(1) ?: 0,
                        autoCleared = false,
                        startedElapsedMs = null,
                    )
                    clearRc = clear.lastRc
                    clearConfirmed = clear.clearConfirmed
                    clearAttempted = true
                    check(clear.journalSucceeded) { "hud_lab_clear_journal_failed" }
                    checkNotNull(
                        HudLabLogStore.completeDelivery(
                            context,
                            initial.id,
                            abortedFailure = if (clearConfirmed) null
                            else HudLabSendFailure.HUD_CLEAR_FAILED.name,
                        ),
                    )
                    checkNotNull(
                        HudLabLogStore.recordObservation(
                            context,
                            initial.id,
                            HudLabObserved.NOT_REPORTED,
                        ),
                    )
                }.isSuccess
                if (!saved && !clearAttempted) {
                    clearConfirmed = emergencyClear("manual_clear_journal_init")
                    clearAttempted = true
                    fallbackHandledRecovery = true
                }
                if (clearAttempted && !clearConfirmed && !fallbackHandledRecovery) {
                    hudController.recoverHudLabAfterFailedClear("manual_clear")
                }
                Log.i(TAG, "HUD Lab manual clear id=${pending?.record?.id} rc=$clearRc saved=$saved")
                _state.value = finishedState(
                    when {
                        !saved -> HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                        )
                        !clearConfirmed -> HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            rc = clearRc,
                            failure = HudLabSendFailure.HUD_CLEAR_FAILED,
                        )
                        else -> HudLabOutcome(HudLabOutcomeType.CLEARED, rc = 0)
                    },
                )
            }
        }
    }

    fun export() {
        synchronized(actionLock) {
            if (_state.value.busy) return
            _state.update { it.copy(busy = true, lastOutcome = null) }
        }
        scope.launch {
            operationMutex.withLock {
                val result = runCatching { HudLabLogStore.export(context) }
                _state.value = _state.value.copy(
                    busy = false,
                    recordsCount = HudLabLogStore.records(context).size,
                    completedExplorerScenarioIds =
                        HudLabLogStore.completedExplorerScenarioIds(context),
                    lastOutcome = result.fold(
                        onSuccess = { file: File ->
                            Log.i(TAG, "HUD Lab exported path=${file.absolutePath}")
                            HudLabOutcome(HudLabOutcomeType.EXPORTED, path = file.absolutePath)
                        },
                        onFailure = {
                            Log.e(TAG, "HUD Lab export failed: ${it.message}", it)
                            HudLabOutcome(HudLabOutcomeType.EXPORT_FAILED)
                        },
                    ),
                )
            }
        }
    }

    /** Deletes only the in-app windshield HUD journal; exported files and cluster logs remain. */
    fun deleteRecords() {
        synchronized(actionLock) {
            if (_state.value.busy || _state.value.pending != null) return
            _state.update { it.copy(busy = true, lastOutcome = null) }
        }
        scope.launch {
            operationMutex.withLock {
                val result = runCatching { HudLabLogStore.clearRecords(context) }
                _state.value = _state.value.copy(
                    busy = false,
                    recordsCount = HudLabLogStore.records(context).size,
                    completedExplorerScenarioIds =
                        HudLabLogStore.completedExplorerScenarioIds(context),
                    lastOutcome = if (result.isSuccess) {
                        HudLabOutcome(HudLabOutcomeType.RECORDS_DELETED)
                    } else {
                        Log.e(TAG, "HUD Lab journal delete failed", result.exceptionOrNull())
                        HudLabOutcome(HudLabOutcomeType.RECORDS_DELETE_FAILED)
                    },
                )
            }
        }
    }

    private fun scheduleAutoClear(recordId: String, parkConfirmedByUser: Boolean) {
        autoClearJob?.cancel()
        autoClearJob = scope.launch {
            var remaining = autoClearDelayMs.coerceAtLeast(0L)
            var watchdogFailure: HudLabSendFailure? = null
            while (remaining > 0L) {
                val wait = minOf(remaining, SAFETY_WATCHDOG_MS)
                scenarioDelay(wait)
                remaining -= wait
                val safety = hudController.checkHudLabSafety(parkConfirmedByUser)
                if (safety.failure != null) {
                    watchdogFailure = safety.failure
                    break
                }
            }
            operationMutex.withLock {
                val pending = _state.value.pending
                if (pending?.record?.id != recordId || pending.autoCleared) return@withLock
                var clearConfirmed = false
                var clearAttempted = false
                val persisted = runCatching {
                    val clear = appendClearEvent(
                        record = pending.record,
                        label = if (watchdogFailure == null) "timeout_clear" else "safety_watchdog_clear",
                        stepIndex = pending.record.events.maxOfOrNull { it.stepIndex }?.plus(1) ?: 0,
                        autoCleared = true,
                        startedElapsedMs = null,
                    )
                    clearConfirmed = clear.clearConfirmed
                    clearAttempted = true
                    check(clear.journalSucceeded) { "hud_lab_clear_journal_failed" }
                    // Delivery was already completed before the pending observation window began.
                    // A later watchdog clear is a safety cleanup, not an aborted SEND sequence.
                    // Keep the reason in the CLEAR event label/log without corrupting the verdict.
                    clear.record
                }
                val updated = HudLabLogStore.records(context).firstOrNull { it.id == recordId }
                    ?: pending.record
                _state.update {
                    it.copy(
                        pending = HudLabPending(
                            updated,
                            autoCleared = persisted.isSuccess && clearConfirmed,
                        ),
                        lastOutcome = if (!persisted.isSuccess) {
                            HudLabOutcome(
                                HudLabOutcomeType.SEND_REJECTED,
                                failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                            )
                        } else if (clearConfirmed) {
                            HudLabOutcome(HudLabOutcomeType.CLEARED, rc = 0)
                        } else {
                            HudLabOutcome(
                                HudLabOutcomeType.SEND_REJECTED,
                                rc = updated.clearRc,
                                failure = HudLabSendFailure.HUD_CLEAR_FAILED,
                            )
                        },
                    )
                }
                Log.i(
                    TAG,
                    "HUD Lab auto-clear id=$recordId rc=${updated.clearRc} watchdog=$watchdogFailure",
                )
                if (clearAttempted && !clearConfirmed) {
                    hudController.recoverHudLabAfterFailedClear("auto_clear")
                }
            }
        }
    }

    private data class ClearSequenceResult(
        val record: HudLabRecord,
        val clearConfirmed: Boolean,
        val lastRc: Int,
        val journalSucceeded: Boolean,
    )

    private suspend fun appendClearEvent(
        record: HudLabRecord,
        label: String,
        stepIndex: Int,
        autoCleared: Boolean,
        startedElapsedMs: Long?,
    ): ClearSequenceResult {
        var updated = record
        val results = mutableListOf<Int>()
        var lastNow = System.currentTimeMillis()
        var journalSucceeded = true
        repeat(3) { attempt ->
            if (attempt > 0) scenarioDelay(CLEAR_ATTEMPT_CADENCE_MS)
            val attemptId = UUID.randomUUID().toString()
            val elapsed = startedElapsedMs?.let {
                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
            } ?: (System.currentTimeMillis() - record.requestedAtMs).coerceAtLeast(0L)
            runCatching {
                checkNotNull(HudLabLogStore.appendEvent(
                    context,
                    record.id,
                    HudLabEvent(
                        type = HudLabEventType.CLEAR,
                        stepIndex = stepIndex,
                        label = label,
                        pushIndex = attempt,
                        atMs = System.currentTimeMillis(),
                        elapsedMs = elapsed,
                        gear = record.gear,
                        speedKmh = record.speedKmh,
                        phase = HudLabEventPhase.INTENT,
                        attemptId = attemptId,
                    ),
                ))
            }.onSuccess { updated = it }.onFailure {
                journalSucceeded = false
                Log.e(TAG, "HUD Lab clear intent journal failed: ${it.message}", it)
            }
            val rc = hudController.clearHudLabFrame()
            val now = System.currentTimeMillis()
            lastNow = now
            results += rc
            runCatching {
                checkNotNull(HudLabLogStore.appendEvent(
                    context,
                    record.id,
                    HudLabEvent(
                        type = HudLabEventType.CLEAR,
                        stepIndex = stepIndex,
                        label = label,
                        pushIndex = attempt,
                        atMs = now,
                        elapsedMs = startedElapsedMs?.let {
                            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
                        } ?: (now - record.requestedAtMs).coerceAtLeast(0L),
                        rc = rc,
                        gear = record.gear,
                        speedKmh = record.speedKmh,
                        phase = HudLabEventPhase.RESULT,
                        attemptId = attemptId,
                    ),
                ))
            }.onSuccess { updated = it }.onFailure {
                journalSucceeded = false
                Log.e(TAG, "HUD Lab clear result journal failed: ${it.message}", it)
            }
        }
        val confirmed = results.any { it == 0 }
        runCatching {
            checkNotNull(HudLabLogStore.recordClear(
                context,
                record.id,
                results.last(),
                autoCleared = autoCleared && confirmed,
                clearConfirmed = confirmed,
                nowMs = lastNow,
            ))
        }.onSuccess { updated = it }.onFailure {
            journalSucceeded = false
            Log.e(TAG, "HUD Lab clear summary journal failed: ${it.message}", it)
        }
        return ClearSequenceResult(updated, confirmed, results.last(), journalSucceeded)
    }

    private suspend fun emergencyClear(reason: String): Boolean {
        var confirmed = false
        repeat(3) { attempt ->
            if (attempt > 0) scenarioDelay(CLEAR_ATTEMPT_CADENCE_MS)
            if (hudController.clearHudLabFrame() == 0) confirmed = true
        }
        if (!confirmed) hudController.recoverHudLabAfterFailedClear(reason)
        return confirmed
    }

    private fun finishedState(outcome: HudLabOutcome): HudLabState = _state.value.copy(
        busy = false,
        pending = null,
        recordsCount = HudLabLogStore.records(context).size,
        lastOutcome = outcome,
        currentScenarioId = null,
        currentStep = 0,
        totalSteps = 0,
        currentPush = 0,
        totalPushes = 0,
        completedExplorerScenarioIds = HudLabLogStore.completedExplorerScenarioIds(context),
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }
}
