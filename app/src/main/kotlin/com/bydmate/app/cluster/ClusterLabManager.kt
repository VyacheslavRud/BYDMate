package com.bydmate.app.cluster

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.automation.VehicleSafetySnapshot
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SystemDisplayInfo
import com.bydmate.app.data.vehicle.TaskProjectionQueryResult
import com.bydmate.app.data.vehicle.VehicleProfile
import com.bydmate.app.navdata.NavGuidanceHub
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Atomic ownership slot used by C03 so a stale overlay can never be overwritten or orphaned. */
internal class ExclusiveOverlaySlot<T : Any> {
    @Volatile private var owned: T? = null

    fun current(): T? = owned

    @Synchronized
    fun claim(candidate: T): Boolean {
        if (owned != null) return false
        owned = candidate
        return true
    }

    @Synchronized
    fun replaceIfOwned(expected: T, replacement: T): Boolean {
        if (owned !== expected) return false
        owned = replacement
        return true
    }

    @Synchronized
    fun releaseIfOwned(candidate: T): Boolean {
        if (owned !== candidate) return false
        owned = null
        return true
    }
}

/** Keeps the first durable-journal failure while allowing safety cleanup to continue. */
internal class ClusterLabBestEffortJournal {
    var failure: Throwable? = null
        private set

    fun record(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (failure == null) failure = error
        }
    }
}

/** Cleanup is authoritative only for an explicit no-task result or a restored main fullscreen task. */
internal fun isProjectionTaskCleanupConfirmed(result: TaskProjectionQueryResult): Boolean =
    when (result) {
        is TaskProjectionQueryResult.Found ->
            result.state.displayId == 0 &&
                result.state.windowingMode == TASK_WINDOWING_MODE_FULLSCREEN
        TaskProjectionQueryResult.NotRunning -> true
        TaskProjectionQueryResult.Unavailable -> false
    }

/** Privacy-safe export line for the exact Waze ATMS snapshot used by C02 and cleanup checks. */
internal fun wazeTaskProjectionDetail(
    stage: String,
    task: TaskProjectionQueryResult,
): String = when (task) {
    is TaskProjectionQueryResult.Found ->
        "$stage target=$NAVI_PACKAGE status=FOUND taskId=${task.state.taskId} " +
            "displayId=${task.state.displayId} windowingMode=${task.state.windowingMode}"
    TaskProjectionQueryResult.NotRunning ->
        "$stage target=$NAVI_PACKAGE status=NOT_RUNNING"
    TaskProjectionQueryResult.Unavailable ->
        "$stage target=$NAVI_PACKAGE status=UNAVAILABLE"
}

enum class ClusterLabOutcomeType {
    COMPLETED,
    REJECTED,
    OBSERVATION_SAVED,
    CANCELLED,
}

data class ClusterLabOutcome(
    val type: ClusterLabOutcomeType,
    val scenarioId: String? = null,
    val failure: ClusterLabFailure? = null,
    val cleanupConfirmed: Boolean? = null,
)

data class ClusterLabState(
    val busy: Boolean = false,
    val currentScenarioId: String? = null,
    val currentStep: String? = null,
    val progress: Float = 0f,
    val pendingObservationRecordId: String? = null,
    val pendingObservationScenarioId: String? = null,
    val recordsCount: Int = 0,
    val clusterDisplayAvailable: Boolean = false,
    val lastOutcome: ClusterLabOutcome? = null,
)

/**
 * Safe runner for C01-C08. All visual mutation is dev-only, route-free, gear-P/speed-0 gated and
 * continuously rechecked. Projection tests use an exclusive awaitable lease. Only explicit C06/C07
 * may force the daemon-whitelisted auto_container calibration sequence; both use durable ownership
 * and the same awaitable lease as cleanup.
 */
@Singleton
class ClusterLabManager @Inject constructor(
    @ApplicationContext context: Context,
    private val helper: HelperClient,
    private val bootstrap: HelperBootstrap,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationMutex = Mutex()
    private val actionLock = Any()
    private var activeJob: Job? = null
    private val activeOverlaySlot = ExclusiveOverlaySlot<OverlayOwnership>()

    internal var debugBuildProvider: () -> Boolean = { BuildConfig.DEBUG }
    internal var vehicleSnapshotProvider: () -> DiParsData? = { VehicleSafetySnapshot.current() }
    internal var routeActiveProvider: () -> Boolean = { NavGuidanceHub.snapshot().active }
    internal var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
    internal var wallClockProvider: () -> Long = { System.currentTimeMillis() }

    private val _state = MutableStateFlow(
        ClusterLabState(recordsCount = ClusterLabLogStore.records(this.context).size),
    )
    val state: StateFlow<ClusterLabState> = _state.asStateFlow()

    init {
        // C07 in the previous build already proved that Sea Lion's display is system-only. Refresh
        // that read-only inventory as soon as the diagnostics singleton is created, so an update or
        // process restart does not force the driver to run the 12-second container probe again just
        // to reveal C06.
        scope.launch {
            val helperReady = runCatching { bootstrap.ensureRunning() }.getOrDefault(false)
            val displays = if (helperReady) inspectAvailableDisplays() else inspectDisplays()
            _state.update {
                it.copy(clusterDisplayAvailable = displays.any { display -> display.clusterCandidate })
            }
        }
    }

    fun runScenario(scenarioId: String, parkConfirmedByUser: Boolean) {
        val scenario = ClusterLabScenarioCatalog.byId(scenarioId) ?: return
        synchronized(actionLock) {
            if (_state.value.busy) return
            val pendingId = _state.value.pendingObservationRecordId
            if (pendingId != null) {
                val closed = runCatching {
                    ClusterLabLogStore.recordObservation(
                        context,
                        pendingId,
                        ClusterLabObservation.NOT_REPORTED,
                    )
                }.getOrNull()
                if (closed == null) {
                    _state.update {
                        it.copy(
                            lastOutcome = ClusterLabOutcome(
                                ClusterLabOutcomeType.REJECTED,
                                scenarioId = scenario.id,
                                failure = ClusterLabFailure.JOURNAL_WRITE_FAILED,
                            ),
                        )
                    }
                    return
                }
            }
            _state.update {
                it.copy(
                    busy = true,
                    currentScenarioId = scenario.id,
                    currentStep = "starting",
                    progress = 0f,
                    pendingObservationRecordId = null,
                    pendingObservationScenarioId = null,
                    lastOutcome = null,
                )
            }
            activeJob = scope.launch {
                operationMutex.withLock {
                    execute(scenario, parkConfirmedByUser)
                }
            }
        }
    }

    fun cancel() {
        synchronized(actionLock) { activeJob?.cancel() }
    }

    fun recordObservation(observed: ClusterLabObservation) {
        synchronized(actionLock) {
            if (_state.value.busy) return
            val id = _state.value.pendingObservationRecordId ?: return
            val scenarioId = _state.value.pendingObservationScenarioId
            val saved = runCatching {
                ClusterLabLogStore.recordObservation(context, id, observed)
            }.getOrNull()
            _state.update {
                if (saved != null) {
                    it.copy(
                        pendingObservationRecordId = null,
                        pendingObservationScenarioId = null,
                        recordsCount = ClusterLabLogStore.records(context).size,
                        lastOutcome = ClusterLabOutcome(
                            ClusterLabOutcomeType.OBSERVATION_SAVED,
                            scenarioId = scenarioId,
                            cleanupConfirmed = saved.cleanupConfirmed,
                        ),
                    )
                } else {
                    it.copy(
                        lastOutcome = ClusterLabOutcome(
                            ClusterLabOutcomeType.REJECTED,
                            scenarioId = scenarioId,
                            failure = ClusterLabFailure.JOURNAL_WRITE_FAILED,
                        ),
                    )
                }
            }
        }
    }

    fun dismissPendingObservation() = recordObservation(ClusterLabObservation.NOT_REPORTED)

    fun export(): File = ClusterLabLogStore.export(context)

    fun deleteRecords() {
        synchronized(actionLock) {
            if (_state.value.busy || _state.value.pendingObservationRecordId != null) return
            _state.update { it.copy(busy = true, lastOutcome = null) }
            activeJob = scope.launch {
                try {
                    operationMutex.withLock {
                        runCatching { ClusterLabLogStore.clearRecords(context) }
                            .onFailure { Log.e(TAG, "Cluster Lab journal delete failed", it) }
                    }
                } finally {
                    synchronized(actionLock) {
                        _state.update {
                            it.copy(
                                busy = false,
                                recordsCount = ClusterLabLogStore.records(context).size,
                            )
                        }
                        activeJob = null
                    }
                }
            }
        }
    }

    private suspend fun execute(
        scenario: ClusterLabScenario,
        parkConfirmedByUser: Boolean,
    ) {
        val startedElapsed = elapsedRealtimeProvider()
        val initialSafety = safety(parkConfirmedByUser)
        val prefs = projectionPrefs()
        val autoContainer = prefs.getBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)
        val compositorOwnershipPending = prefs.getBoolean(KEY_COMPOSITOR_POWERED, false)
        val record = runCatching {
            ClusterLabLogStore.begin(
                context = context,
                scenario = scenario,
                autoContainerEnabled = autoContainer,
                compositorOwnershipPending = compositorOwnershipPending,
                gear = initialSafety.gear,
                speedKmh = initialSafety.speedKmh,
            )
        }.getOrElse {
            completeState(
                scenario,
                ClusterLabFailure.JOURNAL_WRITE_FAILED,
                cleanupConfirmed = true,
                record = null,
            )
            return
        }

        var failure: ClusterLabFailure? = null
        var cleanupConfirmed = true
        var overlay: OverlayHandle? = null
        var projectionTouched = false
        var projectionUsesAutoContainer = false
        var projectionLease: ClusterLabProjectionLease? = null
        try {
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.SAFETY,
                detail = initialSafety.failure?.name ?: "parked_safety_confirmed",
                safety = initialSafety,
            )
            initialSafety.failure?.let { throw ScenarioAbort(it) }

            if (scenario.mutation != ClusterLabMutation.NONE &&
                ClusterProjectionManager.currentMode != ClusterMode.OFF
            ) {
                throw ScenarioAbort(ClusterLabFailure.PROJECTION_ALREADY_ACTIVE)
            }
            if (scenario.mutation == ClusterLabMutation.PROJECTION_PIPELINE) {
                if (compositorOwnershipPending) {
                    throw ScenarioAbort(ClusterLabFailure.COMPOSITOR_OWNERSHIP_PENDING)
                }
                projectionLease = ClusterProjectionManager.acquireClusterLabLease()
                    ?: throw ScenarioAbort(ClusterLabFailure.PROJECTION_ALREADY_ACTIVE)
            }

            when (scenario.id) {
                "C01" -> runInventoryWatch(record, startedElapsed, parkConfirmedByUser, scenario)
                "C02" -> runStateSnapshot(record, startedElapsed)
                "C03" -> {
                    append(
                        record,
                        startedElapsed,
                        ClusterLabEventKind.MUTATION_ARMED,
                        "overlay_only; auto_remove_ms=${scenario.durationMs}",
                        initialSafety,
                    )
                    overlay = showPattern(
                        record,
                        startedElapsed,
                        parkConfirmedByUser,
                    )
                    cleanupConfirmed = false
                    holdWithSafety(
                        record,
                        startedElapsed,
                        parkConfirmedByUser,
                        durationMs = scenario.durationMs,
                        step = "calibration_pattern",
                    )
                }
                "C04" -> {
                    append(
                        record,
                        startedElapsed,
                        ClusterLabEventKind.MUTATION_ARMED,
                        "production_projection_once; target=$NAVI_PACKAGE auto_container=hard_disabled",
                        initialSafety,
                    )
                    projectionTouched = true
                    cleanupConfirmed = false
                    startProjectionAndAwait(
                        record,
                        startedElapsed,
                        parkConfirmedByUser,
                        cycle = 1,
                        lease = checkNotNull(projectionLease),
                        forceAutoContainer = false,
                    )
                    holdWithSafety(
                        record,
                        startedElapsed,
                        parkConfirmedByUser,
                        durationMs = scenario.durationMs,
                        step = "projection_cycle_1_hold",
                    )
                }
                "C05" -> {
                    append(
                        record,
                        startedElapsed,
                        ClusterLabEventKind.MUTATION_ARMED,
                        "production_start_stop_start; target=$NAVI_PACKAGE auto_container=hard_disabled",
                        initialSafety,
                    )
                    projectionTouched = true
                    cleanupConfirmed = false
                    startProjectionAndAwait(
                        record,
                        startedElapsed,
                        parkConfirmedByUser,
                        cycle = 1,
                        lease = checkNotNull(projectionLease),
                        forceAutoContainer = false,
                    )
                    holdWithSafety(record, startedElapsed, parkConfirmedByUser, 3_000L, "cycle_1_hold")
                    val betweenCyclesCleanup = stopProjectionAndAwait(
                        record,
                        startedElapsed,
                        checkNotNull(projectionLease),
                        "between_cycles",
                        forceAutoContainer = false,
                    )
                    betweenCyclesCleanup.journalFailure?.let { throw JournalFailure(it) }
                    if (!betweenCyclesCleanup.confirmed) {
                        throw ScenarioAbort(
                            betweenCyclesCleanup.failure ?: ClusterLabFailure.CLEANUP_TIMEOUT,
                        )
                    }
                    holdWithSafety(record, startedElapsed, parkConfirmedByUser, 1_000L, "between_cycles")
                    startProjectionAndAwait(
                        record,
                        startedElapsed,
                        parkConfirmedByUser,
                        cycle = 2,
                        lease = checkNotNull(projectionLease),
                        forceAutoContainer = false,
                    )
                    holdWithSafety(record, startedElapsed, parkConfirmedByUser, 5_000L, "cycle_2_hold")
                }
                "C06" -> {
                    append(
                        record,
                        startedElapsed,
                        ClusterLabEventKind.MUTATION_ARMED,
                        "sea_lion_waze_end_to_end; target=$NAVI_PACKAGE " +
                            "auto_container=forced_whitelist_16_18_0; auto_remove_ms=${scenario.durationMs}",
                        initialSafety,
                    )
                    projectionTouched = true
                    projectionUsesAutoContainer = true
                    cleanupConfirmed = false
                    startProjectionAndAwait(
                        record = record,
                        startedElapsed = startedElapsed,
                        parkConfirmedByUser = parkConfirmedByUser,
                        cycle = 1,
                        lease = checkNotNull(projectionLease),
                        forceAutoContainer = true,
                    )
                    holdWithSafety(
                        record = record,
                        startedElapsed = startedElapsed,
                        parkConfirmedByUser = parkConfirmedByUser,
                        durationMs = scenario.durationMs,
                        step = "sea_lion_waze_map_visible",
                    )
                }
                "C07" -> {
                    append(
                        record,
                        startedElapsed,
                        ClusterLabEventKind.MUTATION_ARMED,
                        "sea_lion_container_transport; no_waze_launch=true " +
                            "auto_container=forced_whitelist_16_18_0; auto_remove_ms=${scenario.durationMs}",
                        initialSafety,
                    )
                    runContainerTransportProbe(
                        record = record,
                        startedElapsed = startedElapsed,
                        parkConfirmedByUser = parkConfirmedByUser,
                        scenario = scenario,
                        armCleanup = {
                            projectionTouched = true
                            projectionUsesAutoContainer = true
                            cleanupConfirmed = false
                        },
                    )
                }
                "C08" -> runManualFactoryNaviWatch(
                    record = record,
                    startedElapsed = startedElapsed,
                    parkConfirmedByUser = parkConfirmedByUser,
                    scenario = scenario,
                )
                else -> throw ScenarioAbort(ClusterLabFailure.INTERNAL_ERROR)
            }
        } catch (abort: ScenarioAbort) {
            failure = abort.failure
        } catch (_: CancellationException) {
            failure = ClusterLabFailure.CANCELLED
        } catch (journal: JournalFailure) {
            Log.e(TAG, "Cluster Lab journal failure: ${journal.cause?.message}", journal.cause)
            failure = ClusterLabFailure.JOURNAL_WRITE_FAILED
        } catch (error: Throwable) {
            Log.e(TAG, "Cluster Lab ${scenario.id} failed: ${error.message}", error)
            failure = ClusterLabFailure.INTERNAL_ERROR
        } finally {
            // Cleanup must survive cancellation of the runner itself. In particular, a cancelled
            // withContext(Main) would otherwise leave C03's overlay stranded on the cluster.
            withContext(NonCancellable) {
                val slotOwnership = activeOverlaySlot.current()
                val ownedOverlay = overlay ?: (slotOwnership as? OverlayHandle)
                if (ownedOverlay != null) {
                    cleanupConfirmed = removePattern(record, startedElapsed, ownedOverlay)
                } else if (slotOwnership is OverlayReservation) {
                    cleanupConfirmed = activeOverlaySlot.releaseIfOwned(slotOwnership)
                }
                cleanupConfirmed = activeOverlaySlot.current() == null && cleanupConfirmed
                val lease = projectionLease
                if (projectionTouched && lease != null) {
                    val projectionCleanup = stopProjectionAndAwaitBestEffort(
                        record,
                        startedElapsed,
                        lease,
                        "final_cleanup",
                        forceAutoContainer = projectionUsesAutoContainer,
                    )
                    cleanupConfirmed = projectionCleanup.confirmed
                    if (!projectionCleanup.confirmed && failure == null) {
                        failure = projectionCleanup.failure ?: ClusterLabFailure.CLEANUP_TIMEOUT
                    }
                    if (projectionCleanup.journalFailure != null) {
                        Log.e(
                            TAG,
                            "Cluster Lab cleanup journal failed after OFF verification",
                            projectionCleanup.journalFailure,
                        )
                        failure = ClusterLabFailure.JOURNAL_WRITE_FAILED
                    }
                }
                if (lease != null) {
                    cleanupConfirmed = ClusterProjectionManager.releaseClusterLabLease(lease) &&
                        cleanupConfirmed
                }
                if (!cleanupConfirmed && failure == null) {
                    failure = ClusterLabFailure.CLEANUP_TIMEOUT
                }
                val finished = runCatching {
                    ClusterLabLogStore.finish(
                        context = context,
                        id = record.id,
                        failure = failure,
                        cleanupConfirmed = cleanupConfirmed,
                    )
                }.getOrNull()
                if (finished == null) failure = ClusterLabFailure.JOURNAL_WRITE_FAILED
                completeState(scenario, failure, cleanupConfirmed, finished ?: record)
            }
        }
    }

    private suspend fun runInventoryWatch(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        scenario: ClusterLabScenario,
    ) {
        val deadline = elapsedRealtimeProvider() + scenario.durationMs
        var sample = 0
        var priorSignature: String? = null
        var candidateFirstSeen = false
        while (elapsedRealtimeProvider() <= deadline) {
            val safe = requireSafe(parkConfirmedByUser)
            val displays = inspectDisplays()
            val signature = displays.joinToString("|") {
                "${it.id}:${it.name}:${it.widthPx}x${it.heightPx}:${it.state}:${it.clusterCandidate}"
            }
            val changed = signature != priorSignature
            val heartbeat = sample % 10 == 0
            val candidateNow = displays.any { it.clusterCandidate }
            if (changed || heartbeat || (candidateNow && !candidateFirstSeen)) {
                append(
                    record,
                    startedElapsed,
                    ClusterLabEventKind.INVENTORY,
                    detail = buildString {
                        append("sample=$sample changed=$changed")
                        if (candidateNow && !candidateFirstSeen) append(" candidate_first_seen=true")
                    },
                    safety = safe,
                    displays = displays,
                )
            }
            candidateFirstSeen = candidateFirstSeen || candidateNow
            priorSignature = signature
            sample++
            updateProgress("inventory_sample_$sample", (scenario.durationMs - (deadline - elapsedRealtimeProvider())).toFloat() / scenario.durationMs)
            delay(INVENTORY_INTERVAL_MS)
        }
        _state.update {
            it.copy(
                clusterDisplayAvailable = candidateFirstSeen ||
                    inspectDisplays().any { display -> display.clusterCandidate },
            )
        }
    }

    private suspend fun runStateSnapshot(record: ClusterLabRecord, startedElapsed: Long) {
        val displays = inspectAvailableDisplays()
        _state.update {
            it.copy(clusterDisplayAvailable = displays.any { display -> display.clusterCandidate })
        }
        val projection = ClusterProjectionManager.diagnosticState.value
        val prefs = projectionPrefs()
        val helperAlive = withTimeoutOrNull(HELPER_PROBE_TIMEOUT_MS) { helper.isAlive() } ?: false
        // Read-only exact-Waze ATMS query. Keep this as a separate durable event so an unavailable
        // helper transaction cannot be mistaken for NOT_RUNNING in the exported C02 timeline.
        appendTaskState(record, startedElapsed, "snapshot", taskProjectionState())
        val detail = buildString {
            append("helperAlive=$helperAlive ")
            append("mode=${ClusterProjectionManager.currentMode} phase=${projection.phase} ")
            append("failure=${projection.lastFailure ?: ClusterProjectionManager.lastFailure} ")
            append("selectedDisplay=${projection.selectedDisplay?.id} monitoredDisplay=${projection.monitoredDisplayId} ")
            append("renderPath=${projection.renderPath} taskDisplay=${projection.projectedTaskDisplayId} ")
            append("autoContainerRequested=${projection.autoContainerRequested} ")
            append("containerMarkerWritten=${projection.autoContainerMarkerWritten} ")
            append("containerCommandAccepted=${projection.autoContainerCommandAccepted} ")
            append("widthPct=${prefs.getInt(ClusterProjectionManager.KEY_WIDTH_PCT, VehicleProfile.CURRENT.clusterProjectionPreset.widthPct)} ")
            append("heightPct=${prefs.getInt(ClusterProjectionManager.KEY_HEIGHT_PCT, VehicleProfile.CURRENT.clusterProjectionPreset.heightPct)} ")
            append("offsetXPct=${prefs.getInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, VehicleProfile.CURRENT.clusterProjectionPreset.offsetXPct)} ")
            append("offsetYPct=${prefs.getInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, VehicleProfile.CURRENT.clusterProjectionPreset.offsetYPct)} ")
            append("scalePct=${prefs.getInt(ClusterProjectionManager.KEY_SCALE_PCT, VehicleProfile.CURRENT.clusterProjectionPreset.scalePct)} ")
            append("target=${prefs.getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, NAVI_PACKAGE)} ")
            append("autoContainer=${prefs.getBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false)} ")
            append("compositorOwnership=${prefs.getBoolean(KEY_COMPOSITOR_POWERED, false)} ")
            append("directDisplay=${prefs.getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, -1)} ")
            append("lastVd=${prefs.getInt(KEY_LAST_VD_ID, -1)} ")
            append("ownedVd=${prefs.getStringSet(KEY_OWNED_VD_IDS, emptySet()).orEmpty().sorted()}")
        }
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.SNAPSHOT,
            detail,
            safety = null,
            displays = displays,
            projectionMode = ClusterProjectionManager.currentMode.name,
            projectionPhase = projection.phase.name,
        )
        updateProgress("snapshot_complete", 1f)
    }

    private suspend fun runContainerTransportProbe(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        scenario: ClusterLabScenario,
        armCleanup: () -> Unit,
    ) {
        updateProgress("container_probe_preparing", 0.05f)
        if (!bootstrap.ensureRunning()) throw ScenarioAbort(ClusterLabFailure.HELPER_UNAVAILABLE)
        val permissionsGranted = helper.grantOverlayPermission()
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.SNAPSHOT,
            "project_media_grant_process_success=$permissionsGranted",
            safety = requireSafe(parkConfirmedByUser),
        )
        if (!permissionsGranted) {
            throw ScenarioAbort(ClusterLabFailure.OVERLAY_PERMISSION_UNAVAILABLE)
        }
        appendSystemProbe(record, startedElapsed, "before_container_on")
        requireSafe(parkConfirmedByUser)

        val markerWritten = withContext(Dispatchers.IO) {
            projectionPrefs().edit().putBoolean(KEY_COMPOSITOR_POWERED, true).commit()
        }
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.PROJECTION_REQUESTED,
            "container_on markerWritten=$markerWritten serviceProcessResult=pending",
            safety = null,
        )
        if (!markerWritten) throw ScenarioAbort(ClusterLabFailure.COMPOSITOR_MARKER_WRITE_FAILED)
        // From this point every path, including cancellation and Binder death, must execute the
        // existing OFF cleanup (18 -> pause -> 0) under the owned Cluster Lab lease.
        armCleanup()
        val processAccepted = runCatching { helper.setClusterContainerMode(true) }
            .getOrDefault(false)
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.PROJECTION_STATE,
            "container_on serviceProcessResult=$processAccepted hardwareState=UNCONFIRMED",
            safety = requireSafe(parkConfirmedByUser),
        )
        if (!processAccepted) throw ScenarioAbort(ClusterLabFailure.CONTAINER_ON_REJECTED)

        coroutineScope {
            val systemProbe = async { clusterSystemProbe() }
            watchDisplays(
                record = record,
                startedElapsed = startedElapsed,
                parkConfirmedByUser = parkConfirmedByUser,
                durationMs = scenario.durationMs,
                step = "container_display_watch",
                progressStart = 0.25f,
                progressSpan = 0.60f,
            )
            appendSystemProbeReport(
                record,
                startedElapsed,
                "after_container_on",
                systemProbe.await(),
            )
        }
        val appDisplays = inspectDisplays()
        val systemDisplays = inspectSystemDisplays()
        val availableDisplays = mergeDisplaySnapshots(appDisplays, systemDisplays)
        val appCandidateFound = appDisplays.any { it.clusterCandidate }
        val systemCandidateFound = systemDisplays.any { it.clusterCandidate }
        _state.update {
            it.copy(clusterDisplayAvailable = appCandidateFound || systemCandidateFound)
        }
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.VERDICT,
            "containerTransportProcessResult=true " +
                "appVisibleClusterDisplay=$appCandidateFound " +
                "systemClusterDisplay=$systemCandidateFound " +
                "selectedSystemDisplay=${systemDisplays.firstOrNull { it.clusterCandidate }?.id} " +
                "hardwareChangeRequiresVisualObservation=true",
            safety = requireSafe(parkConfirmedByUser),
            displays = availableDisplays,
        )
        updateProgress("container_probe_complete", 0.95f)
    }

    private suspend fun runManualFactoryNaviWatch(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        scenario: ClusterLabScenario,
    ) = coroutineScope {
        updateProgress("switch_factory_navi_now", 0.05f)
        val initialProbe = async { clusterSystemProbe() }
        watchDisplays(
            record = record,
            startedElapsed = startedElapsed,
            parkConfirmedByUser = parkConfirmedByUser,
            durationMs = scenario.durationMs,
            step = "switch_factory_navi_now",
            progressStart = 0.05f,
            progressSpan = 0.80f,
        )
        appendSystemProbeReport(
            record,
            startedElapsed,
            "manual_watch_initial",
            initialProbe.await(),
        )
        appendSystemProbe(record, startedElapsed, "manual_watch_final")
        val displays = inspectAvailableDisplays()
        _state.update {
            it.copy(clusterDisplayAvailable = displays.any { display -> display.clusterCandidate })
        }
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.INVENTORY,
            "manual_watch_structured_system_inventory=true",
            safety = requireSafe(parkConfirmedByUser),
            displays = displays,
        )
        updateProgress("manual_watch_complete", 1f)
    }

    private suspend fun watchDisplays(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        durationMs: Long,
        step: String,
        progressStart: Float,
        progressSpan: Float,
    ) {
        val deadline = elapsedRealtimeProvider() + durationMs
        var sample = 0
        var priorSignature: String? = null
        var candidateFirstSeen = false
        while (elapsedRealtimeProvider() <= deadline) {
            val safe = requireSafe(parkConfirmedByUser)
            val displays = inspectDisplays()
            val signature = displays.joinToString("|") {
                "${it.id}:${it.name}:${it.widthPx}x${it.heightPx}:${it.state}:${it.clusterCandidate}"
            }
            val candidateNow = displays.any { it.clusterCandidate }
            if (signature != priorSignature || sample % 10 == 0 || (candidateNow && !candidateFirstSeen)) {
                append(
                    record,
                    startedElapsed,
                    ClusterLabEventKind.INVENTORY,
                    detail = "$step sample=$sample changed=${signature != priorSignature} " +
                        "candidateFirstSeen=${candidateNow && !candidateFirstSeen}",
                    safety = safe,
                    displays = displays,
                )
            }
            candidateFirstSeen = candidateFirstSeen || candidateNow
            priorSignature = signature
            sample++
            val elapsed = durationMs - (deadline - elapsedRealtimeProvider()).coerceAtLeast(0L)
            updateProgress(step, progressStart + elapsed.toFloat() / durationMs * progressSpan)
            delay(INVENTORY_INTERVAL_MS)
        }
        _state.update {
            it.copy(
                clusterDisplayAvailable = candidateFirstSeen ||
                    inspectDisplays().any { display -> display.clusterCandidate },
            )
        }
    }

    private suspend fun clusterSystemProbe(): String? = try {
        withTimeoutOrNull(SYSTEM_PROBE_TIMEOUT_MS) { helper.getClusterSystemProbe() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w(TAG, "cluster system probe failed: ${error.message}")
        null
    }

    private suspend fun appendSystemProbe(
        record: ClusterLabRecord,
        startedElapsed: Long,
        stage: String,
    ) = appendSystemProbeReport(record, startedElapsed, stage, clusterSystemProbe())

    private fun appendSystemProbeReport(
        record: ClusterLabRecord,
        startedElapsed: Long,
        stage: String,
        report: String?,
    ) {
        val chunks = report?.chunked(SYSTEM_PROBE_EVENT_CHARS).orEmpty()
        if (chunks.isEmpty()) {
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.SNAPSHOT,
                "system_probe stage=$stage status=UNAVAILABLE",
                safety = null,
            )
            return
        }
        chunks.forEachIndexed { index, chunk ->
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.SNAPSHOT,
                "system_probe stage=$stage part=${index + 1}/${chunks.size} $chunk",
                safety = null,
            )
        }
    }

    private suspend fun showPattern(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
    ): OverlayHandle {
        val reservation = OverlayReservation()
        if (!activeOverlaySlot.claim(reservation)) {
            throw ScenarioAbort(ClusterLabFailure.OVERLAY_ALREADY_ACTIVE)
        }
        var handle: OverlayHandle? = null
        try {
            updateProgress("granting_overlay_permission", 0.1f)
            if (!bootstrap.ensureRunning()) throw ScenarioAbort(ClusterLabFailure.HELPER_UNAVAILABLE)
            if (!helper.grantOverlayPermission()) {
                throw ScenarioAbort(ClusterLabFailure.OVERLAY_PERMISSION_UNAVAILABLE)
            }
            repeat(OVERLAY_PERMISSION_POLL_ATTEMPTS) {
                if (Settings.canDrawOverlays(context)) return@repeat
                delay(OVERLAY_PERMISSION_POLL_INTERVAL_MS)
            }
            if (!Settings.canDrawOverlays(context)) {
                throw ScenarioAbort(ClusterLabFailure.OVERLAY_PERMISSION_UNAVAILABLE)
            }
            // Helper startup and permission propagation can take seconds. Re-read fresh telemetry
            // immediately before the first pixel is added to the cluster display.
            requireSafe(parkConfirmedByUser)

            val displaySnapshots = inspectDisplays()
            val selectedId = preferredClusterDisplayId(displaySnapshots.map { it.id to it.name })
            val selected = displaySnapshots.firstOrNull { it.id == selectedId }
                ?: throw ScenarioAbort(ClusterLabFailure.CLUSTER_DISPLAY_NOT_FOUND)
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(selected.id)
                ?: throw ScenarioAbort(ClusterLabFailure.CLUSTER_DISPLAY_NOT_FOUND)
            val geometry = readExpectedGeometry(selected)
            val created = withContext(Dispatchers.Main.immediate) {
                addPatternOverlay(display, selected, geometry)
            }
            if (!activeOverlaySlot.replaceIfOwned(reservation, created)) {
                removeOverlayView(created)
                throw ScenarioAbort(ClusterLabFailure.OVERLAY_ALREADY_ACTIVE)
            }
            handle = created
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.OVERLAY_ADDED,
                detail = "display=${selected.id} expected=${geometry.width}x${geometry.height}+" +
                    "${geometry.xOffset}+${geometry.yOffset}",
                safety = null,
                displays = displaySnapshots,
            )
            updateProgress("pattern_visible", 0.2f)
            return created
        } catch (error: Throwable) {
            // The caller cannot own [handle] until this method returns. If a durable event write
            // fails after addView, remove locally so a journal failure can never strand the grid.
            val installed = handle
            if (installed != null) {
                val removed = withContext(NonCancellable) { removeOverlayView(installed) }
                if (removed) activeOverlaySlot.releaseIfOwned(installed)
            } else {
                activeOverlaySlot.releaseIfOwned(reservation)
            }
            throw error
        }
    }

    private suspend fun removePattern(
        record: ClusterLabRecord,
        startedElapsed: Long,
        handle: OverlayHandle,
    ): Boolean {
        val removed = removeOverlayView(handle)
        if (!removed) Log.w(TAG, "pattern remove could not be confirmed")
        if (removed) activeOverlaySlot.releaseIfOwned(handle)
        runCatching {
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.OVERLAY_REMOVED,
                detail = "display=${handle.displayId} confirmed=$removed",
                safety = null,
            )
        }
        return removed
    }

    private suspend fun removeOverlayView(handle: OverlayHandle): Boolean =
        withContext(Dispatchers.Main.immediate) {
            runCatching { handle.windowManager.removeView(handle.view) }
                .recoverCatching { handle.windowManager.removeViewImmediate(handle.view) }
                .isSuccess || !handle.view.isAttachedToWindow
        }

    private fun addPatternOverlay(
        display: Display,
        snapshot: ClusterLabDisplaySnapshot,
        geometry: ClusterGeometry,
    ): OverlayHandle {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = ClusterLabPatternView(displayContext, snapshot, geometry)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "BYDMate Cluster Lab C03"
        }
        wm.addView(view, params)
        return OverlayHandle(display.displayId, wm, view)
    }

    private suspend fun startProjectionAndAwait(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        cycle: Int,
        lease: ClusterLabProjectionLease,
        forceAutoContainer: Boolean,
    ) {
        requireSafe(parkConfirmedByUser)
        requireProjectionGuards()
        appendTaskState(record, startedElapsed, "cycle=$cycle before", taskProjectionState())
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.PROJECTION_REQUESTED,
            detail = "cycle=$cycle mode=FULLSCREEN target=$NAVI_PACKAGE " +
                "auto_container=${if (forceAutoContainer) "forced" else "disabled"}",
            safety = null,
        )
        updateProgress("projection_cycle_${cycle}_starting", if (cycle == 1) 0.2f else 0.65f)

        val transition = awaitStartTransitionWithSafety(
            record,
            startedElapsed,
            parkConfirmedByUser,
            cycle,
            lease,
            forceAutoContainer,
        )
        appendProjectionState(
            record,
            startedElapsed,
            "cycle=$cycle terminal success=${transition.success} failure=${transition.failure}",
            safety = safety(parkConfirmedByUser),
        )
        val postTransitionSafety = requireSafe(parkConfirmedByUser)
        val activeTask = transition.projectedTaskDisplayId?.let { expectedDisplayId ->
            awaitProjectedTaskState(parkConfirmedByUser, expectedDisplayId)
        } ?: taskProjectionState()
        appendTaskState(record, startedElapsed, "cycle=$cycle active", activeTask)
        val verdict = verifyClusterProjectionStart(transition, activeTask)
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.VERDICT,
            verdict.detail,
            safety = postTransitionSafety,
            projectionMode = ClusterProjectionManager.currentMode.name,
            projectionPhase = ClusterProjectionManager.diagnosticState.value.phase.name,
        )
        verdict.failure?.let { throw ScenarioAbort(it) }
        updateProgress("projection_cycle_${cycle}_active", if (cycle == 1) 0.35f else 0.8f)
    }

    private suspend fun awaitStartTransitionWithSafety(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        cycle: Int,
        lease: ClusterLabProjectionLease,
        forceAutoContainer: Boolean,
    ): ClusterLabProjectionTransitionResult = coroutineScope {
        val transition = async {
            ClusterProjectionManager.setModeForClusterLab(
                context = context,
                mode = ClusterMode.FULLSCREEN,
                helper = helper,
                bootstrap = bootstrap,
                lease = lease,
                targetPackage = NAVI_PACKAGE,
                allowAutoContainerCommands = forceAutoContainer,
                forceAutoContainerCommands = forceAutoContainer,
            )
        }
        val deadline = elapsedRealtimeProvider() + PROJECTION_START_TIMEOUT_MS
        var priorSignature: String? = null
        while (!transition.isCompleted) {
            val safe = requireSafe(parkConfirmedByUser)
            val diagnostic = ClusterProjectionManager.diagnosticState.value
            val mode = ClusterProjectionManager.currentMode
            val signature = projectionSignature(mode, diagnostic)
            if (signature != priorSignature) {
                appendProjectionState(
                    record,
                    startedElapsed,
                    "cycle=$cycle transitioning failure=${diagnostic.lastFailure}",
                    safe,
                )
                priorSignature = signature
            }
            if (elapsedRealtimeProvider() > deadline) {
                throw ScenarioAbort(ClusterLabFailure.PROJECTION_TIMEOUT)
            }
            delay(PROJECTION_POLL_INTERVAL_MS)
        }
        transition.await()
    }

    private suspend fun stopProjectionAndAwait(
        record: ClusterLabRecord,
        startedElapsed: Long,
        lease: ClusterLabProjectionLease,
        reason: String,
        forceAutoContainer: Boolean,
    ): ProjectionCleanupResult {
        val journal = ClusterLabBestEffortJournal()
        journal.record {
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.CLEANUP_REQUESTED,
                detail = "mode=OFF reason=$reason target=$NAVI_PACKAGE",
                safety = null,
            )
        }
        // OFF and the authoritative post-state query are safety work, not logging work. They must
        // still run even when CLEANUP_REQUESTED could not be persisted.
        val transition = try {
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                ClusterProjectionManager.setModeForClusterLab(
                    context = context,
                    mode = ClusterMode.OFF,
                    helper = helper,
                    bootstrap = bootstrap,
                    lease = lease,
                    targetPackage = NAVI_PACKAGE,
                    allowAutoContainerCommands = forceAutoContainer,
                    forceAutoContainerCommands = forceAutoContainer,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Cluster Lab OFF transition failed: ${error.message}", error)
            null
        }
        val afterTask = awaitCleanTaskState()
        journal.record { appendTaskState(record, startedElapsed, "$reason after", afterTask) }
        val ownershipClean = projectionOwnershipMarkersClear()
        val verdict = verifyClusterProjectionCleanup(transition, afterTask, ownershipClean)
        val confirmed = verdict.passed
        val diagnostic = ClusterProjectionManager.diagnosticState.value
        journal.record {
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.CLEANUP_CONFIRMED,
                detail = "reason=$reason transition=${transition?.success} " +
                    "taskStatus=${taskProjectionStatus(afterTask)} " +
                    "ownershipClean=$ownershipClean failure=${transition?.failure}",
                safety = null,
                projectionMode = ClusterProjectionManager.currentMode.name,
                projectionPhase = diagnostic.phase.name,
            )
        }
        journal.record {
            append(
                record,
                startedElapsed,
                ClusterLabEventKind.VERDICT,
                "$reason ${verdict.detail}",
                safety = null,
                projectionMode = ClusterProjectionManager.currentMode.name,
                projectionPhase = diagnostic.phase.name,
            )
        }
        return ProjectionCleanupResult(confirmed, verdict.failure, journal.failure)
    }

    private suspend fun stopProjectionAndAwaitBestEffort(
        record: ClusterLabRecord,
        startedElapsed: Long,
        lease: ClusterLabProjectionLease,
        reason: String,
        forceAutoContainer: Boolean,
    ): ProjectionCleanupResult {
        val first = stopProjectionAndAwait(
            record,
            startedElapsed,
            lease,
            reason,
            forceAutoContainer,
        )
        if (first.confirmed) return first
        val second = stopProjectionAndAwait(
            record,
            startedElapsed,
            lease,
            "${reason}_retry",
            forceAutoContainer,
        )
        return ProjectionCleanupResult(
            confirmed = second.confirmed,
            failure = second.failure ?: first.failure,
            journalFailure = first.journalFailure ?: second.journalFailure,
        )
    }

    private suspend fun holdWithSafety(
        record: ClusterLabRecord,
        startedElapsed: Long,
        parkConfirmedByUser: Boolean,
        durationMs: Long,
        step: String,
    ) {
        val deadline = elapsedRealtimeProvider() + durationMs
        var lastLoggedSecond = -1L
        while (elapsedRealtimeProvider() <= deadline) {
            val safe = requireSafe(parkConfirmedByUser)
            val remaining = (deadline - elapsedRealtimeProvider()).coerceAtLeast(0L)
            val elapsedInStep = durationMs - remaining
            val second = elapsedInStep / 1_000L
            if (second != lastLoggedSecond) {
                append(
                    record,
                    startedElapsed,
                    ClusterLabEventKind.SAFETY,
                    detail = "step=$step heartbeat_second=$second",
                    safety = safe,
                    projectionMode = ClusterProjectionManager.currentMode.name,
                    projectionPhase = ClusterProjectionManager.diagnosticState.value.phase.name,
                )
                lastLoggedSecond = second
            }
            updateProgress(step, 0.2f + (elapsedInStep.toFloat() / durationMs.coerceAtLeast(1L)) * 0.6f)
            delay(SAFETY_POLL_INTERVAL_MS)
        }
    }

    private fun safety(parkConfirmedByUser: Boolean): ClusterLabSafetyResult {
        val vehicle = vehicleSnapshotProvider()
        return evaluateClusterLabSafety(
            ClusterLabSafetyInput(
                isDebugBuild = debugBuildProvider(),
                parkConfirmedByUser = parkConfirmedByUser,
                routeActive = routeActiveProvider(),
                gear = vehicle?.gear,
                speedKmh = vehicle?.speed,
            ),
        )
    }

    private fun requireSafe(parkConfirmedByUser: Boolean): ClusterLabSafetyResult {
        val result = safety(parkConfirmedByUser)
        result.failure?.let { throw ScenarioAbort(it) }
        return result
    }

    private fun inspectDisplays(): List<ClusterLabDisplaySnapshot> =
        runCatching { ClusterProjectionManager.inspectDisplays(context).map { it.toLabSnapshot() } }
            .getOrDefault(emptyList())

    private suspend fun inspectSystemDisplays(): List<ClusterLabDisplaySnapshot> = try {
        withTimeoutOrNull(HELPER_PROBE_TIMEOUT_MS) { helper.getSystemDisplays() }
            .orEmpty()
            .map { it.toLabSnapshot() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w(TAG, "system display inventory failed: ${error.message}")
        emptyList()
    }

    private suspend fun inspectAvailableDisplays(): List<ClusterLabDisplaySnapshot> =
        mergeDisplaySnapshots(inspectDisplays(), inspectSystemDisplays())

    private fun mergeDisplaySnapshots(
        appDisplays: List<ClusterLabDisplaySnapshot>,
        systemDisplays: List<ClusterLabDisplaySnapshot>,
    ): List<ClusterLabDisplaySnapshot> {
        val appIds = appDisplays.mapTo(hashSetOf()) { it.id }
        return appDisplays + systemDisplays.filterNot { it.id in appIds }
    }

    private fun SystemDisplayInfo.toLabSnapshot() = ClusterLabDisplaySnapshot(
        id = id,
        name = name,
        widthPx = widthPx,
        heightPx = heightPx,
        densityDpi = densityDpi,
        state = state,
        clusterCandidate = name.contains("XDJAScreenProjection", ignoreCase = true) || id == 2,
    )

    private fun readExpectedGeometry(display: ClusterLabDisplaySnapshot): ClusterGeometry {
        val prefs = projectionPrefs()
        val preset = VehicleProfile.CURRENT.clusterProjectionPreset
        return checkNotNull(
            geometryFor(
                mode = ClusterMode.FULLSCREEN,
                clusterW = display.widthPx,
                clusterH = display.heightPx,
                widthPct = prefs.getInt(ClusterProjectionManager.KEY_WIDTH_PCT, preset.widthPct),
                heightPct = prefs.getInt(ClusterProjectionManager.KEY_HEIGHT_PCT, preset.heightPct),
                offsetXPct = prefs.getInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, preset.offsetXPct),
                offsetYPct = prefs.getInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, preset.offsetYPct),
            ),
        )
    }

    private fun projectionPrefs() = context.getSharedPreferences(
        ClusterProjectionManager.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private fun requireProjectionGuards() {
        val prefs = projectionPrefs()
        if (prefs.getBoolean(KEY_COMPOSITOR_POWERED, false)) {
            throw ScenarioAbort(ClusterLabFailure.COMPOSITOR_OWNERSHIP_PENDING)
        }
    }

    private suspend fun taskProjectionState(): TaskProjectionQueryResult = try {
        withTimeoutOrNull(HELPER_PROBE_TIMEOUT_MS) {
            helper.getTaskProjectionState(NAVI_PACKAGE)
        } ?: TaskProjectionQueryResult.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w(TAG, "Waze task projection query failed: ${error.message}")
        TaskProjectionQueryResult.Unavailable
    }

    /** ATMS can publish a successful move a few frames after the daemon call returns. */
    private suspend fun awaitProjectedTaskState(
        parkConfirmedByUser: Boolean,
        expectedDisplayId: Int,
    ): TaskProjectionQueryResult {
        val deadline = elapsedRealtimeProvider() + TASK_SETTLE_TIMEOUT_MS
        var latest: TaskProjectionQueryResult = TaskProjectionQueryResult.Unavailable
        do {
            requireSafe(parkConfirmedByUser)
            latest = taskProjectionState()
            if (latest is TaskProjectionQueryResult.Found &&
                latest.state.displayId == expectedDisplayId
            ) {
                return latest
            }
            if (elapsedRealtimeProvider() >= deadline) return latest
            delay(TASK_SETTLE_POLL_INTERVAL_MS)
        } while (true)
    }

    /** Cleanup never aborts on changing vehicle state; it keeps observing the safe return to main. */
    private suspend fun awaitCleanTaskState(): TaskProjectionQueryResult {
        val deadline = elapsedRealtimeProvider() + TASK_SETTLE_TIMEOUT_MS
        var latest: TaskProjectionQueryResult = TaskProjectionQueryResult.Unavailable
        do {
            latest = taskProjectionState()
            if (isProjectionTaskCleanupConfirmed(latest)) return latest
            if (elapsedRealtimeProvider() >= deadline) return latest
            delay(TASK_SETTLE_POLL_INTERVAL_MS)
        } while (true)
    }

    private fun appendTaskState(
        record: ClusterLabRecord,
        startedElapsed: Long,
        stage: String,
        task: TaskProjectionQueryResult,
    ) {
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.PROJECTION_STATE,
            detail = wazeTaskProjectionDetail(stage, task),
            safety = null,
            projectionMode = ClusterProjectionManager.currentMode.name,
            projectionPhase = ClusterProjectionManager.diagnosticState.value.phase.name,
        )
    }

    private fun taskProjectionStatus(task: TaskProjectionQueryResult): String = when (task) {
        is TaskProjectionQueryResult.Found -> "FOUND"
        TaskProjectionQueryResult.NotRunning -> "NOT_RUNNING"
        TaskProjectionQueryResult.Unavailable -> "UNAVAILABLE"
    }

    private fun projectionOwnershipMarkersClear(): Boolean {
        val prefs = projectionPrefs()
        return !prefs.getBoolean(KEY_COMPOSITOR_POWERED, false) &&
            prefs.getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, -1) == -1 &&
            prefs.getInt(KEY_LAST_VD_ID, -1) == -1 &&
            prefs.getStringSet(KEY_OWNED_VD_IDS, emptySet()).orEmpty().isEmpty()
    }

    private fun appendProjectionState(
        record: ClusterLabRecord,
        startedElapsed: Long,
        detail: String,
        safety: ClusterLabSafetyResult?,
    ) {
        val diagnostic = ClusterProjectionManager.diagnosticState.value
        append(
            record,
            startedElapsed,
            ClusterLabEventKind.PROJECTION_STATE,
            detail = "$detail selected=${diagnostic.selectedDisplay?.id} " +
                "searchElapsedMs=${diagnostic.displaySearchElapsedMs} " +
                "renderPath=${diagnostic.renderPath} " +
                "expectedTaskDisplay=${diagnostic.projectedTaskDisplayId} " +
                "autoContainerRequested=${diagnostic.autoContainerRequested} " +
                "containerMarkerWritten=${diagnostic.autoContainerMarkerWritten} " +
                "containerCommandAccepted=${diagnostic.autoContainerCommandAccepted}",
            safety = safety,
            displays = diagnostic.visibleDisplays.map { it.toLabSnapshot() },
            projectionMode = ClusterProjectionManager.currentMode.name,
            projectionPhase = diagnostic.phase.name,
        )
    }

    private fun projectionSignature(
        mode: ClusterMode,
        diagnostic: ClusterProjectionDiagnosticState,
    ): String = "$mode|${diagnostic.phase}|${diagnostic.selectedDisplay?.id}|" +
        "${diagnostic.monitoredDisplayId}|${diagnostic.renderPath}|" +
        "${diagnostic.projectedTaskDisplayId}|${diagnostic.autoContainerCommandAccepted}|" +
        "${diagnostic.lastFailure}|${diagnostic.lastDisplayEvent}"

    private fun append(
        record: ClusterLabRecord,
        startedElapsed: Long,
        kind: ClusterLabEventKind,
        detail: String,
        safety: ClusterLabSafetyResult?,
        displays: List<ClusterLabDisplaySnapshot> = emptyList(),
        projectionMode: String? = null,
        projectionPhase: String? = null,
    ) {
        val now = wallClockProvider()
        runCatching {
            checkNotNull(
                ClusterLabLogStore.append(
                    context,
                    record.id,
                    ClusterLabEvent(
                        atMs = now,
                        elapsedMs = (elapsedRealtimeProvider() - startedElapsed).coerceAtLeast(0L),
                        kind = kind,
                        detail = detail,
                        displays = displays,
                        gear = safety?.gear,
                        speedKmh = safety?.speedKmh,
                        projectionMode = projectionMode,
                        projectionPhase = projectionPhase,
                    ),
                ),
            ) { "cluster_lab_record_missing" }
        }.getOrElse { throw JournalFailure(it) }
    }

    private fun completeState(
        scenario: ClusterLabScenario,
        failure: ClusterLabFailure?,
        cleanupConfirmed: Boolean,
        record: ClusterLabRecord?,
    ) {
        val success = failure == null
        synchronized(actionLock) {
            _state.update {
                it.copy(
                    busy = false,
                    currentScenarioId = null,
                    currentStep = null,
                    progress = if (success) 1f else it.progress,
                    pendingObservationRecordId = record?.id.takeIf {
                        success && (scenario.mutation != ClusterLabMutation.NONE || scenario.id == "C08")
                    },
                    pendingObservationScenarioId = scenario.id.takeIf {
                        success && (scenario.mutation != ClusterLabMutation.NONE || scenario.id == "C08")
                    },
                    recordsCount = ClusterLabLogStore.records(context).size,
                    lastOutcome = ClusterLabOutcome(
                        type = when (failure) {
                            null -> ClusterLabOutcomeType.COMPLETED
                            ClusterLabFailure.CANCELLED -> ClusterLabOutcomeType.CANCELLED
                            else -> ClusterLabOutcomeType.REJECTED
                        },
                        scenarioId = scenario.id,
                        failure = failure,
                        cleanupConfirmed = cleanupConfirmed,
                    ),
                )
            }
            activeJob = null
        }
    }

    private fun updateProgress(step: String, progress: Float) {
        _state.update { it.copy(currentStep = step, progress = progress.coerceIn(0f, 1f)) }
    }

    private sealed interface OverlayOwnership

    private class OverlayReservation : OverlayOwnership

    private data class OverlayHandle(
        val displayId: Int,
        val windowManager: WindowManager,
        val view: View,
    ) : OverlayOwnership

    private data class ProjectionCleanupResult(
        val confirmed: Boolean,
        val failure: ClusterLabFailure?,
        val journalFailure: Throwable?,
    )

    private class ScenarioAbort(val failure: ClusterLabFailure) : RuntimeException(failure.name)
    private class JournalFailure(cause: Throwable) : RuntimeException(cause)

    companion object {
        private const val TAG = "ClusterLab"
        private const val KEY_COMPOSITOR_POWERED = "compositor_powered_on"
        private const val KEY_LAST_VD_ID = "last_vd_id"
        private const val KEY_OWNED_VD_IDS = "owned_vd_ids"
        private const val INVENTORY_INTERVAL_MS = 100L
        private const val SAFETY_POLL_INTERVAL_MS = 250L
        private const val PROJECTION_POLL_INTERVAL_MS = 100L
        private const val PROJECTION_START_TIMEOUT_MS = 30_000L
        private const val CLEANUP_TIMEOUT_MS = 35_000L
        private const val HELPER_PROBE_TIMEOUT_MS = 2_500L
        private const val SYSTEM_PROBE_TIMEOUT_MS = 10_000L
        private const val SYSTEM_PROBE_EVENT_CHARS = 900
        private const val TASK_SETTLE_TIMEOUT_MS = 5_000L
        private const val TASK_SETTLE_POLL_INTERVAL_MS = 150L
        private const val OVERLAY_PERMISSION_POLL_ATTEMPTS = 10
        private const val OVERLAY_PERMISSION_POLL_INTERVAL_MS = 200L
    }
}
