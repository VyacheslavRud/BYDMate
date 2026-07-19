package com.bydmate.app.data.remote

import android.util.Log
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.VehicleApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps a value scoped to one lifecycle generation and rejects stale publications. */
internal class LatestDataGeneration<T>(
    private val maxAgeMs: Long,
) {
    private var generation = 0L
    private var value: T? = null
    private var updatedAtMs: Long? = null

    @Synchronized
    fun begin(): Long {
        generation++
        value = null
        updatedAtMs = null
        return generation
    }

    @Synchronized
    fun publish(candidateGeneration: Long, newValue: T, nowMs: Long = System.currentTimeMillis()) {
        if (candidateGeneration != generation) return
        value = newValue
        updatedAtMs = nowMs
    }

    @Synchronized
    fun current(nowMs: Long = System.currentTimeMillis()): T? {
        val timestamp = updatedAtMs ?: return null
        val ageMs = nowMs - timestamp
        return value?.takeIf { ageMs in 0L..maxAgeMs }
    }

    @Synchronized
    fun clear() {
        generation++
        value = null
        updatedAtMs = null
    }
}

@Singleton
class AlicePollingManager @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val sharedAdaptiveLoop: com.bydmate.app.data.loop.SharedAdaptiveLoop,
    private val vehicleApi: VehicleApi,
) {
    internal data class RemoteCommand(val id: String, val command: String)

    // Fast client with short timeouts for polling (main httpClient has 15s)
    private val pollClient = httpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    companion object {
        private const val TAG = "AlicePolling"
        private const val POLL_INTERVAL_MS = 2500L
        private const val STATE_REPORT_EVERY = 10 // every 10th poll (~25s)
        internal const val MAX_COMMANDS_PER_POLL = 20
        private const val MAX_COMMAND_ENTRIES_SCANNED = MAX_COMMANDS_PER_POLL * 4

        /** Selects at most [MAX_COMMANDS_PER_POLL] commands and executes each response ID once. */
        internal fun selectUniqueCommands(
            commands: Sequence<RemoteCommand>,
            limit: Int = MAX_COMMANDS_PER_POLL,
        ): List<RemoteCommand> {
            require(limit > 0)
            val selected = ArrayList<RemoteCommand>(limit)
            val seenIds = HashSet<String>()
            var scanned = 0
            for (candidate in commands) {
                if (++scanned > MAX_COMMAND_ENTRIES_SCANNED) break
                val id = candidate.id.trim()
                val command = candidate.command.trim()
                if (id.isEmpty() || command.isEmpty() || !seenIds.add(id)) continue
                selected += RemoteCommand(id, command)
                if (selected.size == limit) break
            }
            return selected
        }
    }

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private var pollCount = 0
    private val callLock = Any()
    private var activeCall: Call? = null
    @Volatile private var stopped = true

    private val latestDataState = LatestDataGeneration<DiParsData>(maxAgeMs = 5_000L)
    val latestData: DiParsData? get() = latestDataState.current()

    @Synchronized
    fun start() {
        if (pollingJob?.isActive == true) return
        val dataGeneration = latestDataState.begin()
        synchronized(callLock) { stopped = false }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        // Flow collector: Alice owns a generation-scoped subscription to the shared loop.
        // A late emission from a cancelled collector can never repopulate a restarted manager.
        s.launch {
            sharedAdaptiveLoop.flow.collect { data -> latestDataState.publish(dataGeneration, data) }
        }
        pollingJob = s.launch {
            Log.i(TAG, "Polling started")
            while (true) {
                try {
                    poll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        // Invalidate data before cancellation: a vendor-backed producer may return after stop().
        latestDataState.clear()
        pollingJob?.cancel()
        pollingJob = null
        synchronized(callLock) {
            stopped = true
            activeCall?.cancel()
            activeCall = null
        }
        scope?.cancel()
        scope = null
        Log.i(TAG, "Polling stopped")
    }

    val isRunning: Boolean get() = pollingJob?.isActive == true

    private suspend fun poll() {
        val endpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "")
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
        if (endpoint.isBlank() || apiKey.isBlank()) return

        val request = Request.Builder()
            .url("$endpoint/api/poll")
            .header("X-Api-Key", apiKey)
            .build()

        val t0 = System.currentTimeMillis()
        val responseData = executeRequest(request) { response ->
            val ms = System.currentTimeMillis() - t0
            if (!response.isSuccessful) {
                Log.w(TAG, "Poll HTTP ${response.code} (${ms}ms)")
                return@executeRequest null
            }
            val body = response.body?.string() ?: return@executeRequest null
            val json = JSONObject(body)
            ms to (json.optJSONArray("commands") ?: return@executeRequest null)
        } ?: return
        val (elapsed, commands) = responseData

        if (commands.length() == 0) {
            Log.d(TAG, "Poll OK (${elapsed}ms) - empty")
            // Report real device state every Nth poll
            pollCount++
            if (pollCount >= STATE_REPORT_EVERY) {
                pollCount = 0
                reportState(endpoint, apiKey)
            }
            return
        }
        val selected = selectUniqueCommands(
            (0 until commands.length()).asSequence().mapNotNull { index ->
                commands.optJSONObject(index)?.let { command ->
                    RemoteCommand(
                        id = command.optString("id"),
                        command = command.optString("command"),
                    )
                }
            }
        )
        Log.i(TAG, "Received ${commands.length()} command(s), selected ${selected.size} (${elapsed}ms)")

        val ackIds = mutableListOf<String>()
        for ((id, command) in selected) {
            Log.i(TAG, "Executing: '$command' (id=$id)")
            // Alice still uses the raw VehicleApi transport, but every command must pass the
            // same complete safety policy as voice, manual actions and automations.
            val blockReason = ActionDispatcher.safetyBlockReason(
                command = command,
                data = latestData,
            )
            if (blockReason != null) {
                Log.w(TAG, "Blocked: '$command' → $blockReason")
                ackIds.add(id)
                continue
            }
            val result = vehicleApi.dispatch(command)
            val success = result.isSuccess
            Log.i(TAG, "Result: $command → ${if (success) "OK" else "FAIL: ${result.exceptionOrNull()?.message}"}")
            // Crowd-validation: ack regardless of success. Unmapped/Unsupported commands
            // get a "done" signal to VPS so Alice does not retry forever. The vehicle_write_log
            // DAO row carries the actual outcome for diagnostics.
            ackIds.add(id)
        }

        if (ackIds.isNotEmpty()) {
            ack(endpoint, apiKey, ackIds)
        }
    }

    private fun reportState(endpoint: String, apiKey: String) {
        val data = latestData ?: return
        try {
            val json = JSONObject().apply {
                data.windowFL?.let { put("windowFL", it) }
                data.windowFR?.let { put("windowFR", it) }
                data.windowRL?.let { put("windowRL", it) }
                data.windowRR?.let { put("windowRR", it) }
                data.sunroof?.let { put("sunroof", it) }
                data.trunk?.let { put("trunk", it) }
                data.lockFL?.let { put("lockFL", it) }
                data.acStatus?.let { put("acStatus", it) }
                data.acTemp?.let { put("acTemp", it) }
                data.acCirc?.let { put("acCirc", it) }
                data.insideTemp?.let { put("insideTemp", it) }
            }
            val request = Request.Builder()
                .url("$endpoint/api/state")
                .header("X-Api-Key", apiKey)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            executeRequest(request) { Unit }
            Log.d(TAG, "State reported")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "State report failed: ${e.message}")
        }
    }

    private fun ack(endpoint: String, apiKey: String, ids: List<String>) {
        try {
            val json = JSONObject().apply {
                put("ids", JSONArray(ids))
            }
            val request = Request.Builder()
                .url("$endpoint/api/ack")
                .header("X-Api-Key", apiKey)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            executeRequest(request) { Unit }
            Log.i(TAG, "Acked ${ids.size} command(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ack failed: ${e.message}")
        }
    }

    /** Owns the whole response lifetime so stop() can cancel body reads as well as connect(). */
    private fun <T> executeRequest(request: Request, block: (Response) -> T): T {
        val call = pollClient.newCall(request)
        synchronized(callLock) {
            if (stopped) {
                call.cancel()
                throw CancellationException("Alice polling stopped")
            }
            activeCall = call
        }
        return try {
            call.execute().use(block)
        } finally {
            synchronized(callLock) {
                if (activeCall === call) activeCall = null
            }
        }
    }
}
