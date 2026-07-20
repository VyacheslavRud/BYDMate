package com.bydmate.app.hud

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

/** Coordinates one bounded parked calibration at a time and guarantees an automatic clear. */
@Singleton
class HudLabManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hudController: HudController,
) {
    companion object {
        private const val TAG = "HudLab"
        internal const val AUTO_CLEAR_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val actionLock = Any()
    private var autoClearJob: Job? = null
    internal var autoClearDelayMs: Long = AUTO_CLEAR_MS
    private val _state = MutableStateFlow(
        HudLabState(recordsCount = HudLabLogStore.records(context).size),
    )
    val state: StateFlow<HudLabState> = _state.asStateFlow()

    fun send(command: HudLabCommand, parkConfirmedByUser: Boolean) {
        synchronized(actionLock) {
            if (_state.value.busy || _state.value.pending != null) return
            _state.update { it.copy(busy = true, lastOutcome = null) }
        }
        scope.launch {
            operationMutex.withLock {
                val payload = HudProtobufBuilder.buildHudLabFrame(command.rawF28)
                val result = hudController.sendHudLabFrame(payload, parkConfirmedByUser)
                val record = runCatching {
                    HudLabLogStore.createAttempt(
                        context = context,
                        command = command,
                        payloadBytes = payload.size,
                        sendRc = result.rc,
                        sendFailure = result.failure?.name,
                        gear = result.gear,
                        speedKmh = result.speedKmh,
                    )
                }.getOrElse { error ->
                    // Never leave the live loop suspended when durable journaling fails. A test
                    // that cannot be recorded is cleared immediately and reported as rejected.
                    if (result.accepted) hudController.clearHudLabFrame()
                    Log.e(TAG, "HUD Lab attempt journal failed: ${error.message}", error)
                    _state.value = _state.value.copy(
                        busy = false,
                        lastOutcome = HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                        ),
                    )
                    return@withLock
                }
                Log.i(
                    TAG,
                    "HUD Lab attempt id=${record.id} command=$command rawF28=${command.rawF28} " +
                        "rc=${result.rc} failure=${result.failure} gear=${result.gear} " +
                        "speed=${result.speedKmh}",
                )
                if (result.accepted) {
                    _state.value = _state.value.copy(
                        busy = false,
                        pending = HudLabPending(record),
                        recordsCount = HudLabLogStore.records(context).size,
                        lastOutcome = HudLabOutcome(HudLabOutcomeType.FRAME_SENT, rc = result.rc),
                    )
                    scheduleAutoClear(record.id)
                } else {
                    _state.value = _state.value.copy(
                        busy = false,
                        recordsCount = HudLabLogStore.records(context).size,
                        lastOutcome = HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            rc = result.rc,
                            failure = result.failure,
                        ),
                    )
                }
            }
        }
    }

    fun recordObservation(observed: HudLabObserved) {
        synchronized(actionLock) {
            if (_state.value.busy || _state.value.pending == null) return
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
                val clearRc = if (!pending.autoCleared) hudController.clearHudLabFrame() else null
                val saved = runCatching {
                    if (clearRc != null) {
                        checkNotNull(
                            HudLabLogStore.recordClear(
                                context,
                                pending.record.id,
                                clearRc,
                                autoCleared = false,
                            ),
                        ) { "hud_lab_record_missing_on_clear" }
                    }
                    checkNotNull(
                        HudLabLogStore.recordObservation(
                            context,
                            pending.record.id,
                            observed,
                        ),
                    ) { "hud_lab_record_missing_on_observation" }
                }.isSuccess
                Log.i(
                    TAG,
                    "HUD Lab observation id=${pending.record.id} " +
                        "expected=${pending.record.command.expected} observed=$observed " +
                        "clearRc=$clearRc saved=$saved",
                )
                _state.value = if (saved) {
                    _state.value.copy(
                        busy = false,
                        pending = null,
                        recordsCount = HudLabLogStore.records(context).size,
                        lastOutcome = HudLabOutcome(HudLabOutcomeType.OBSERVATION_SAVED),
                    )
                } else {
                    _state.value.copy(
                        busy = false,
                        pending = pending.copy(autoCleared = true),
                        lastOutcome = HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                        ),
                    )
                }
            }
        }
    }

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
                val clearRc = hudController.clearHudLabFrame()
                val saved = runCatching {
                    if (pending != null) {
                        checkNotNull(
                            HudLabLogStore.recordClear(
                                context,
                                pending.record.id,
                                clearRc,
                                autoCleared = false,
                            ),
                        ) { "hud_lab_record_missing_on_manual_clear" }
                        checkNotNull(
                            HudLabLogStore.recordObservation(
                                context,
                                pending.record.id,
                                HudLabObserved.NOT_REPORTED,
                            ),
                        ) { "hud_lab_record_missing_on_manual_clear_observation" }
                    }
                }.isSuccess
                Log.i(
                    TAG,
                    "HUD Lab manual clear id=${pending?.record?.id} rc=$clearRc saved=$saved",
                )
                _state.value = _state.value.copy(
                    busy = false,
                    pending = null,
                    recordsCount = HudLabLogStore.records(context).size,
                    lastOutcome = if (saved) {
                        HudLabOutcome(HudLabOutcomeType.CLEARED, rc = clearRc)
                    } else {
                        HudLabOutcome(
                            HudLabOutcomeType.SEND_REJECTED,
                            failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                        )
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

    private fun scheduleAutoClear(recordId: String) {
        autoClearJob?.cancel()
        autoClearJob = scope.launch {
            delay(autoClearDelayMs.coerceAtLeast(0L))
            operationMutex.withLock {
                val pending = _state.value.pending
                if (pending?.record?.id != recordId || pending.autoCleared) return@withLock
                val clearRc = hudController.clearHudLabFrame()
                val persisted = runCatching {
                    checkNotNull(
                        HudLabLogStore.recordClear(
                            context,
                            recordId,
                            clearRc,
                            autoCleared = true,
                        ),
                    ) { "hud_lab_record_missing_on_auto_clear" }
                }
                val updated = persisted.getOrNull() ?: pending.record
                _state.update {
                    it.copy(
                        pending = HudLabPending(updated, autoCleared = true),
                        lastOutcome = if (persisted.isSuccess) {
                            HudLabOutcome(HudLabOutcomeType.CLEARED, rc = clearRc)
                        } else {
                            HudLabOutcome(
                                HudLabOutcomeType.SEND_REJECTED,
                                failure = HudLabSendFailure.JOURNAL_WRITE_FAILED,
                            )
                        },
                    )
                }
                Log.i(TAG, "HUD Lab auto-clear id=$recordId rc=$clearRc")
            }
        }
    }
}
