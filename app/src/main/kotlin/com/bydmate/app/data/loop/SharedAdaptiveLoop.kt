package com.bydmate.app.data.loop

import android.util.Log
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of NativeParsReader. All read consumers subscribe to [flow].
 * Cadence is decided per tick via [LoopFsm.classify]; backoff applies when
 * fetch() returns null.
 */
@Singleton
class SharedAdaptiveLoop constructor(
    private val parsReader: ParsReader,
    private val lastStateDao: LastStateDao,
    private val energyDataReader: EnergyDataReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cadence: CadenceConfig = CadenceConfig.default(),
) {
    private val _flow = MutableSharedFlow<DiParsData>(
        replay = 1, extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val flow: SharedFlow<DiParsData> = _flow.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var job: Job? = null
    @Volatile private var generation: Long = 0L

    companion object {
        /** Max staleness of last_state.ts between writes; 5× inside TripRecorder's 5-min cold-start gap. */
        const val HEARTBEAT_MS = 60_000L
        private const val TAG = "SharedAdaptiveLoop"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        val myGeneration = ++generation
        // A service restart must wait for a genuinely new vehicle read. Replaying the previous
        // service instance's last value would make stale speed/SOC look freshly sampled.
        _flow.resetReplayCache()
        _connected.value = false
        job = scope.launch(dispatcher) { runLoop(myGeneration) }
        return job!!
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun stop() {
        // Invalidate before cancelling: a vendor fetch can ignore cancellation and return later.
        // Its generation check then prevents it from publishing into a newer service lifecycle.
        generation++
        job?.cancel()
        job = null
        _flow.resetReplayCache()
        _connected.value = false
    }

    private suspend fun runLoop(myGeneration: Long) {
        var consecutiveNull = 0
        while (isCurrent(myGeneration)) {
            val data = try {
                parsReader.fetch()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "fetch failed, loop continues", error)
                null
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(myGeneration)) return
            if (data == null) {
                consecutiveNull++
                if (!markDisconnectedIfCurrent(myGeneration)) return
                val backoff = (cadence.intervalFor(LoopState.IDLE) * pow15(consecutiveNull))
                    .coerceAtMost(CadenceConfig.MAX_POLL_INTERVAL_MS)
                delay(backoff)
                continue
            }
            consecutiveNull = 0
            if (!publishIfCurrent(myGeneration, data)) return
            currentCoroutineContext().ensureActive()
            if (!isCurrent(myGeneration)) return
            try {
                persistSnapshot(data, myGeneration)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "persistSnapshot failed, loop continues", error)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(myGeneration)) return
            delay(cadence.intervalFor(LoopFsm.classify(data)))
        }
    }

    private fun isCurrent(candidate: Long): Boolean = candidate == generation

    /** Atomic with start/stop so an old loop cannot repopulate replay after it was cleared. */
    @Synchronized
    private fun publishIfCurrent(candidate: Long, data: DiParsData): Boolean {
        if (!isCurrent(candidate)) return false
        _connected.value = true
        _flow.tryEmit(data)
        return true
    }

    @Synchronized
    private fun markDisconnectedIfCurrent(candidate: Long): Boolean {
        if (!isCurrent(candidate)) return false
        _connected.value = false
        return true
    }

    private suspend fun persistSnapshot(data: DiParsData, myGeneration: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(myGeneration)) return
        val now = System.currentTimeMillis()
        val prev = lastStateDao.getCurrent()
        currentCoroutineContext().ensureActive()
        if (!isCurrent(myGeneration)) return
        val ignition = data.powerState
        // Skip the fsync write when nothing changed and the heartbeat is fresh.
        // (Spelled as one positive condition so the compiler smart-casts prev.)
        if (prev != null &&
            prev.soc == data.soc &&
            prev.mileage == data.mileage &&
            prev.totalElec == data.totalElecConsumption &&
            prev.ignition == ignition &&
            now - prev.ts < HEARTBEAT_MS
        ) return
        currentCoroutineContext().ensureActive()
        if (!isCurrent(myGeneration)) return
        lastStateDao.upsert(
            LastStateEntity(
                id = 1,
                ts = now,
                soc = data.soc,
                mileage = data.mileage,
                totalElec = data.totalElecConsumption,
                ignition = ignition,
                openTripId = prev?.openTripId,
                tripStartTs = prev?.tripStartTs,
                tripStartSoc = prev?.tripStartSoc,
                tripStartMileage = prev?.tripStartMileage,
                tripStartTotalElec = prev?.tripStartTotalElec,
                energydataAvailable = if (energyDataReader.isAvailable()) 1 else 0,
            )
        )
    }

    private fun pow15(n: Int): Long {
        var v = 1.0
        repeat(n) { v *= 1.5 }
        return v.toLong().coerceAtLeast(1L)
    }
}
