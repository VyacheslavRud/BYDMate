package com.bydmate.app.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.vehicle.VehicleProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class ClusterLabEventKind {
    START,
    SAFETY,
    INVENTORY,
    SNAPSHOT,
    MUTATION_ARMED,
    OVERLAY_ADDED,
    OVERLAY_REMOVED,
    PROJECTION_REQUESTED,
    PROJECTION_STATE,
    CLEANUP_REQUESTED,
    CLEANUP_CONFIRMED,
    OBSERVATION,
    FAILURE,
    COMPLETE,
}

data class ClusterLabEvent(
    val atMs: Long,
    val elapsedMs: Long,
    val kind: ClusterLabEventKind,
    val detail: String,
    val displays: List<ClusterLabDisplaySnapshot> = emptyList(),
    val gear: Int? = null,
    val speedKmh: Int? = null,
    val projectionMode: String? = null,
    val projectionPhase: String? = null,
)

data class ClusterLabRecord(
    val schemaVersion: Int = 1,
    val id: String,
    val scenarioId: String,
    val scenarioTitle: String,
    val mutation: ClusterLabMutation,
    val startedAtMs: Long,
    val autoContainerEnabled: Boolean,
    val compositorOwnershipPending: Boolean,
    val initialGear: Int?,
    val initialSpeedKmh: Int?,
    val events: List<ClusterLabEvent> = emptyList(),
    val finishedAtMs: Long? = null,
    val failure: ClusterLabFailure? = null,
    val cleanupConfirmed: Boolean? = null,
    val observed: ClusterLabObservation? = null,
    val observedAtMs: Long? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val buildFingerprint: String = Build.FINGERPRINT,
)

/**
 * Crash-durable, privacy-safe journal for the instrument-cluster lab.
 *
 * Each event is committed before the next mutating step. The journal contains display metadata,
 * projection phases and parked-safety values only; it never stores Waze route or location text.
 */
object ClusterLabLogStore {
    private const val PREFS_NAME = "cluster_lab_log"
    private const val KEY_RECORDS = "records_json"
    private const val MAX_RECORDS = 60
    private const val MAX_EVENTS_PER_RECORD = 200
    private const val MAX_DETAIL_CHARS = 1_200

    @Synchronized
    fun begin(
        context: Context,
        scenario: ClusterLabScenario,
        autoContainerEnabled: Boolean,
        compositorOwnershipPending: Boolean,
        gear: Int?,
        speedKmh: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ): ClusterLabRecord {
        val record = ClusterLabRecord(
            id = UUID.randomUUID().toString(),
            scenarioId = scenario.id,
            scenarioTitle = scenario.title,
            mutation = scenario.mutation,
            startedAtMs = nowMs,
            autoContainerEnabled = autoContainerEnabled,
            compositorOwnershipPending = compositorOwnershipPending,
            initialGear = gear,
            initialSpeedKmh = speedKmh,
            events = listOf(
                ClusterLabEvent(
                    atMs = nowMs,
                    elapsedMs = 0L,
                    kind = ClusterLabEventKind.START,
                    detail = "scenario=${scenario.id} mutation=${scenario.mutation}",
                    gear = gear,
                    speedKmh = speedKmh,
                ),
            ),
        )
        persist(context, (records(context) + record).takeLast(MAX_RECORDS))
        return record
    }

    @Synchronized
    fun append(
        context: Context,
        id: String,
        event: ClusterLabEvent,
    ): ClusterLabRecord? = update(context, id) { record ->
        record.copy(
            events = (record.events + event.copy(detail = event.detail.take(MAX_DETAIL_CHARS)))
                .takeLast(MAX_EVENTS_PER_RECORD),
        )
    }

    @Synchronized
    fun finish(
        context: Context,
        id: String,
        failure: ClusterLabFailure?,
        cleanupConfirmed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): ClusterLabRecord? = update(context, id) { record ->
        val kind = if (failure == null) ClusterLabEventKind.COMPLETE else ClusterLabEventKind.FAILURE
        val terminal = ClusterLabEvent(
            atMs = nowMs,
            elapsedMs = (nowMs - record.startedAtMs).coerceAtLeast(0L),
            kind = kind,
            detail = failure?.name ?: "completed",
            gear = record.events.lastOrNull()?.gear,
            speedKmh = record.events.lastOrNull()?.speedKmh,
            projectionMode = record.events.lastOrNull()?.projectionMode,
            projectionPhase = record.events.lastOrNull()?.projectionPhase,
        )
        record.copy(
            events = (record.events + terminal).takeLast(MAX_EVENTS_PER_RECORD),
            finishedAtMs = nowMs,
            failure = failure,
            cleanupConfirmed = cleanupConfirmed,
        )
    }

    @Synchronized
    fun recordObservation(
        context: Context,
        id: String,
        observed: ClusterLabObservation,
        nowMs: Long = System.currentTimeMillis(),
    ): ClusterLabRecord? = update(context, id) { record ->
        val event = ClusterLabEvent(
            atMs = nowMs,
            elapsedMs = (nowMs - record.startedAtMs).coerceAtLeast(0L),
            kind = ClusterLabEventKind.OBSERVATION,
            detail = observed.name,
        )
        record.copy(
            events = (record.events + event).takeLast(MAX_EVENTS_PER_RECORD),
            observed = observed,
            observedAtMs = nowMs,
        )
    }

    @Synchronized
    fun records(context: Context): List<ClusterLabRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeRecord(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun renderDiagnosticSection(context: Context): String = buildString {
        val saved = records(context)
        appendLine("--- Instrument Cluster Lab ---")
        appendLine("saved_tests: ${saved.size}")
        saved.forEachIndexed { index, record ->
            appendLine("  #${index + 1} ${recordSummary(record)}")
            record.events.forEachIndexed { eventIndex, event ->
                appendLine("    event#${eventIndex + 1} ${eventLine(event)}")
            }
        }
    }

    fun export(context: Context, nowMs: Long = System.currentTimeMillis()): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val saveDir = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            context.getExternalFilesDir(null),
        ).firstOrNull { directory ->
            directory != null && (directory.exists() || directory.mkdirs()) && directory.canWrite()
        } ?: error("no_writable_export_directory")
        return File(saveDir, "bydmate_cluster_lab_$timestamp.txt").also { target ->
            target.writeText(
                buildString {
                    appendLine("=== BYDMate Instrument Cluster Lab export ===")
                    appendLine(
                        "exported_at: " +
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs)),
                    )
                    appendLine(
                        "app: ${context.packageName} v${BuildConfig.VERSION_NAME} " +
                            "(code=${BuildConfig.VERSION_CODE})",
                    )
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("fingerprint: ${Build.FINGERPRINT}")
                    appendLine("vehicle: ${VehicleProfile.CURRENT.model} ${VehicleProfile.CURRENT.trim}")
                    append(renderDiagnosticSection(context))
                    appendLine("=============================================")
                },
                Charsets.UTF_8,
            )
        }
    }

    @Synchronized
    internal fun clearForTest(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Synchronized
    private fun update(
        context: Context,
        id: String,
        transform: (ClusterLabRecord) -> ClusterLabRecord,
    ): ClusterLabRecord? {
        var changed: ClusterLabRecord? = null
        val updated = records(context).map { record ->
            if (record.id == id) transform(record).also { changed = it } else record
        }
        if (changed != null) persist(context, updated)
        return changed
    }

    @Suppress("ApplySharedPref") // crash-durable lab journal: the next mutation waits for disk
    @SuppressLint("ApplySharedPref") // crash-durable journal: mutation must follow confirmed write
    private fun persist(context: Context, records: List<ClusterLabRecord>) {
        val array = JSONArray()
        records.forEach { array.put(encodeRecord(it)) }
        check(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECORDS, array.toString())
                .commit(),
        ) { "cluster_lab_log_persist_failed" }
    }

    private fun encodeRecord(record: ClusterLabRecord) = JSONObject().apply {
        put("schemaVersion", record.schemaVersion)
        put("id", record.id)
        put("scenarioId", record.scenarioId)
        put("scenarioTitle", record.scenarioTitle)
        put("mutation", record.mutation.name)
        put("startedAtMs", record.startedAtMs)
        put("autoContainerEnabled", record.autoContainerEnabled)
        put("compositorOwnershipPending", record.compositorOwnershipPending)
        putNullable("initialGear", record.initialGear)
        putNullable("initialSpeedKmh", record.initialSpeedKmh)
        put("events", JSONArray().apply { record.events.forEach { put(encodeEvent(it)) } })
        putNullable("finishedAtMs", record.finishedAtMs)
        putNullable("failure", record.failure?.name)
        putNullable("cleanupConfirmed", record.cleanupConfirmed)
        putNullable("observed", record.observed?.name)
        putNullable("observedAtMs", record.observedAtMs)
        put("appVersion", record.appVersion)
        put("appVersionCode", record.appVersionCode)
        put("buildFingerprint", record.buildFingerprint)
    }

    private fun encodeEvent(event: ClusterLabEvent) = JSONObject().apply {
        put("atMs", event.atMs)
        put("elapsedMs", event.elapsedMs)
        put("kind", event.kind.name)
        put("detail", event.detail)
        put("displays", JSONArray().apply { event.displays.forEach { put(encodeDisplay(it)) } })
        putNullable("gear", event.gear)
        putNullable("speedKmh", event.speedKmh)
        putNullable("projectionMode", event.projectionMode)
        putNullable("projectionPhase", event.projectionPhase)
    }

    private fun encodeDisplay(display: ClusterLabDisplaySnapshot) = JSONObject().apply {
        put("id", display.id)
        put("name", display.name)
        put("widthPx", display.widthPx)
        put("heightPx", display.heightPx)
        put("densityDpi", display.densityDpi)
        put("state", display.state)
        put("clusterCandidate", display.clusterCandidate)
    }

    private fun decodeRecord(json: JSONObject): ClusterLabRecord? = runCatching {
        ClusterLabRecord(
            schemaVersion = json.optInt("schemaVersion", 1),
            id = json.getString("id"),
            scenarioId = json.getString("scenarioId"),
            scenarioTitle = json.optString("scenarioTitle", json.getString("scenarioId")),
            mutation = ClusterLabMutation.valueOf(json.getString("mutation")),
            startedAtMs = json.getLong("startedAtMs"),
            autoContainerEnabled = json.optBoolean("autoContainerEnabled", false),
            compositorOwnershipPending = json.optBoolean("compositorOwnershipPending", false),
            initialGear = json.optNullableInt("initialGear"),
            initialSpeedKmh = json.optNullableInt("initialSpeedKmh"),
            events = json.optJSONArray("events")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        decodeEvent(array.optJSONObject(index) ?: continue)?.let(::add)
                    }
                }
            }.orEmpty(),
            finishedAtMs = json.optNullableLong("finishedAtMs"),
            failure = json.optNullableString("failure")?.let(ClusterLabFailure::valueOf),
            cleanupConfirmed = json.optNullableBoolean("cleanupConfirmed"),
            observed = json.optNullableString("observed")?.let(ClusterLabObservation::valueOf),
            observedAtMs = json.optNullableLong("observedAtMs"),
            appVersion = json.optString("appVersion", "?"),
            appVersionCode = json.optInt("appVersionCode", 0),
            buildFingerprint = json.optString("buildFingerprint", "?"),
        )
    }.getOrNull()

    private fun decodeEvent(json: JSONObject): ClusterLabEvent? = runCatching {
        ClusterLabEvent(
            atMs = json.getLong("atMs"),
            elapsedMs = json.optLong("elapsedMs", 0L),
            kind = ClusterLabEventKind.valueOf(json.getString("kind")),
            detail = json.optString("detail", ""),
            displays = json.optJSONArray("displays")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        decodeDisplay(array.optJSONObject(index) ?: continue)?.let(::add)
                    }
                }
            }.orEmpty(),
            gear = json.optNullableInt("gear"),
            speedKmh = json.optNullableInt("speedKmh"),
            projectionMode = json.optNullableString("projectionMode"),
            projectionPhase = json.optNullableString("projectionPhase"),
        )
    }.getOrNull()

    private fun decodeDisplay(json: JSONObject): ClusterLabDisplaySnapshot? = runCatching {
        ClusterLabDisplaySnapshot(
            id = json.getInt("id"),
            name = json.optString("name", "?"),
            widthPx = json.optInt("widthPx", 0),
            heightPx = json.optInt("heightPx", 0),
            densityDpi = json.optInt("densityDpi", 0),
            state = json.optInt("state", 0),
            clusterCandidate = json.optBoolean("clusterCandidate", false),
        )
    }.getOrNull()

    private fun recordSummary(record: ClusterLabRecord): String =
        "id=${record.id} scenario=${record.scenarioId} title=${record.scenarioTitle.quote()} " +
            "mutation=${record.mutation} startedAtMs=${record.startedAtMs} " +
            "finishedAtMs=${record.finishedAtMs} failure=${record.failure} " +
            "cleanupConfirmed=${record.cleanupConfirmed} observed=${record.observed ?: "PENDING"} " +
            "autoContainer=${record.autoContainerEnabled} " +
            "compositorOwnershipPending=${record.compositorOwnershipPending} " +
            "initialGear=${record.initialGear} initialSpeedKmh=${record.initialSpeedKmh} " +
            "app=${record.appVersion}(${record.appVersionCode}) fingerprint=${record.buildFingerprint}"

    private fun eventLine(event: ClusterLabEvent): String = buildString {
        append("atMs=${event.atMs} elapsedMs=${event.elapsedMs} kind=${event.kind} ")
        append("gear=${event.gear} speedKmh=${event.speedKmh} ")
        append("mode=${event.projectionMode} phase=${event.projectionPhase} ")
        append("detail=${event.detail.quote()}")
        if (event.displays.isNotEmpty()) {
            append(" displays=")
            append(event.displays.joinToString(prefix = "[", postfix = "]") { display ->
                "${display.id}:${display.name.quote()}:${display.widthPx}x${display.heightPx}" +
                    "@${display.densityDpi}:state=${display.state}:candidate=${display.clusterCandidate}"
            })
        }
    }

    private fun String.quote(): String = replace("\n", " ").let { "\"$it\"" }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        takeUnless { isNull(key) }?.optString(key)?.takeIf(String::isNotEmpty)

    private fun JSONObject.optNullableInt(key: String): Int? =
        takeUnless { isNull(key) }?.optInt(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        takeUnless { isNull(key) }?.optLong(key)

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        takeUnless { isNull(key) }?.optBoolean(key)
}
