package com.bydmate.app.hud

import android.content.Context
import android.os.Build
import android.os.Environment
import com.bydmate.app.BuildConfig
import com.bydmate.app.cluster.ClusterLabLogStore
import com.bydmate.app.data.vehicle.VehicleProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Maneuvers offered by the dev-only HUD calibration screen. */
enum class HudLabCommand(
    val gaodeCode: Int,
    val rawF28: Int,
    val expected: HudLabObserved,
) {
    STRAIGHT(11, 1, HudLabObserved.STRAIGHT),
    RIGHT(2, 2, HudLabObserved.RIGHT),
    LEFT(1, 3, HudLabObserved.LEFT),
    UTURN(9, 9, HudLabObserved.UTURN),
    ROUNDABOUT_ENTER(13, 13, HudLabObserved.ROUNDABOUT),
    ROUNDABOUT_EXIT(24, 24, HudLabObserved.ROUNDABOUT),
}

/** Kept for decoding calibration journals written by dev builds 3.6.8 and 3.6.9. */
enum class HudLabFrameVariant { RAW_F28_ONLY, LIVE_PNG_F8, SCENARIO_MATRIX }

/** What the driver actually saw in the factory windshield HUD. */
enum class HudLabObserved {
    LEFT,
    RIGHT,
    STRAIGHT,
    UTURN,
    ROUNDABOUT,
    NOTHING,
    FLASHED,
    INFO_VISIBLE,
    DISTANCE_VISIBLE,
    ROAD_VISIBLE,
    ETA_VISIBLE,
    PROGRESS_VISIBLE,
    SPEED_NUMBER_VISIBLE,
    SPEED_SIGN_OUTLINE_VISIBLE,
    SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
    SPEED_OUTLINE_WITH_MANEUVER_VISIBLE,
    SPEED_MANEUVER_ROAD_VISIBLE,
    SPEED_MANEUVER_ETA_VISIBLE,
    SPEED_MANEUVER_PROGRESS_VISIBLE,
    FULL_SCALAR_VISIBLE,
    SPEED_SIGN_VISIBLE,
    LEFT_THEN_RIGHT,
    RIGHT_CLEARED_AND_REDRAWN,
    CLEAR_NOT_VISIBLE,
    REVERSED_SEQUENCE,
    FIRST_PHASE_ONLY,
    SECOND_PHASE_ONLY,
    VISIBLE_UNDESCRIBED,
    NAMED_INDICATOR,
    OTHER,
    NOT_REPORTED,
}

enum class HudLabEventType { SEND, CLEAR }

/** INTENT is committed before touching the native transport; RESULT closes the same attempt. */
enum class HudLabEventPhase { INTENT, RESULT }

/** Raw gateway codes are deliberately not described as visual success until Sea Lion confirms it. */
enum class HudLabRemoteResult { REMOTE_ZERO, REMOTE_NONZERO_UNCONFIRMED, LOCAL_ERROR, NOT_SENT }

data class HudLabEvent(
    val type: HudLabEventType,
    val stepIndex: Int,
    val label: String,
    val pushIndex: Int,
    val atMs: Long,
    val elapsedMs: Long,
    val payloadBytes: Int = 0,
    val payloadSha256: String? = null,
    val fieldManifest: String? = null,
    val rc: Int? = null,
    val failure: String? = null,
    val gear: Int? = null,
    val speedKmh: Int? = null,
    val phase: HudLabEventPhase = HudLabEventPhase.RESULT,
    val attemptId: String? = null,
    val outputMayBeOwned: Boolean? = null,
) {
    val remoteResult: HudLabRemoteResult
        get() = when {
            rc == null -> HudLabRemoteResult.NOT_SENT
            rc < 0 -> HudLabRemoteResult.LOCAL_ERROR
            rc == 0 -> HudLabRemoteResult.REMOTE_ZERO
            rc > 0 -> HudLabRemoteResult.REMOTE_NONZERO_UNCONFIRMED
            else -> HudLabRemoteResult.NOT_SENT
        }
}

data class HudLabRecord(
    val id: String,
    val requestedAtMs: Long,
    val command: HudLabCommand?,
    val rawF28: Int?,
    val frameVariant: HudLabFrameVariant,
    val includePng: Boolean,
    val iconGaodeCode: Int?,
    val pngBytes: Int?,
    val payloadBytes: Int,
    val sendRc: Int?,
    val sendFailure: String?,
    val gear: Int?,
    val speedKmh: Int?,
    val clearedAtMs: Long? = null,
    val clearRc: Int? = null,
    val autoCleared: Boolean = false,
    val observed: HudLabObserved? = null,
    val observedAtMs: Long? = null,
    val userLabel: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sessionId: String = id,
    val scenarioId: String? = null,
    val scenarioTitle: String? = null,
    val scenarioSummary: String? = null,
    val expected: HudLabObserved = command?.expected ?: HudLabObserved.NOTHING,
    val events: List<HudLabEvent> = emptyList(),
    val deliveryCompletedAtMs: Long? = null,
    val abortedFailure: String? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val buildFingerprint: String = Build.FINGERPRINT,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

/**
 * Privacy-safe durable HUD Lab journal. Every transport mutation is committed synchronously so an
 * app/service restart still leaves enough evidence to reconstruct the exact series.
 */
object HudLabLogStore {
    private const val PREFS_NAME = "hud_lab_log"
    private const val KEY_RECORDS = "records_json"
    private const val MAX_RECORDS = 250
    internal const val MAX_USER_LABEL_CHARS = 160

    @Synchronized
    fun beginScenario(
        context: Context,
        scenario: HudLabScenario,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord {
        val sendSteps = scenario.steps.filterIsInstance<HudLabScenarioStep.Send>()
        val record = HudLabRecord(
            id = UUID.randomUUID().toString(),
            sessionId = UUID.randomUUID().toString(),
            requestedAtMs = nowMs,
            command = scenario.command,
            rawF28 = scenario.command?.rawF28
                ?: sendSteps.mapNotNull { it.frame.f28 }.distinct().singleOrNull(),
            frameVariant = HudLabFrameVariant.SCENARIO_MATRIX,
            includePng = sendSteps.any { it.frame.iconCode != null },
            iconGaodeCode = sendSteps.mapNotNull { it.frame.iconCode }.firstOrNull(),
            pngBytes = null,
            payloadBytes = 0,
            sendRc = null,
            sendFailure = null,
            gear = null,
            speedKmh = null,
            scenarioId = scenario.id,
            scenarioTitle = scenario.title,
            scenarioSummary = scenario.summary,
            expected = scenario.expected,
        )
        persist(context, (records(context) + record).takeLast(MAX_RECORDS))
        return record
    }

    @Synchronized
    fun appendEvent(context: Context, id: String, event: HudLabEvent): HudLabRecord? =
        update(context, id) { record ->
            val isResult = event.phase == HudLabEventPhase.RESULT
            val isSend = isResult && event.type == HudLabEventType.SEND
            val isConfirmedClear = isResult && event.type == HudLabEventType.CLEAR && event.rc == 0
            val isClearResult = isResult && event.type == HudLabEventType.CLEAR
            record.copy(
                events = record.events + event,
                payloadBytes = if (isSend) maxOf(record.payloadBytes, event.payloadBytes)
                    else record.payloadBytes,
                sendRc = if (isSend) event.rc else record.sendRc,
                sendFailure = if (isSend) event.failure else record.sendFailure,
                gear = event.gear ?: record.gear,
                speedKmh = event.speedKmh ?: record.speedKmh,
                clearedAtMs = if (isConfirmedClear) event.atMs else record.clearedAtMs,
                clearRc = if (isClearResult) event.rc else record.clearRc,
            )
        }

    @Synchronized
    fun completeDelivery(
        context: Context,
        id: String,
        abortedFailure: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord? = update(context, id) {
        it.copy(deliveryCompletedAtMs = nowMs, abortedFailure = abortedFailure)
    }

    /** Compatibility entry point used by old tests and old single-frame journal migration. */
    @Synchronized
    fun createAttempt(
        context: Context,
        command: HudLabCommand,
        frameVariant: HudLabFrameVariant = HudLabFrameVariant.RAW_F28_ONLY,
        iconGaodeCode: Int? = null,
        pngBytes: Int? = null,
        payloadBytes: Int,
        sendRc: Int?,
        sendFailure: String?,
        gear: Int?,
        speedKmh: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord {
        val event = HudLabEvent(
            type = HudLabEventType.SEND,
            stepIndex = 0,
            label = "legacy_single",
            pushIndex = 0,
            atMs = nowMs,
            elapsedMs = 0,
            payloadBytes = payloadBytes,
            rc = sendRc,
            failure = sendFailure,
            gear = gear,
            speedKmh = speedKmh,
        )
        val record = HudLabRecord(
            id = UUID.randomUUID().toString(),
            requestedAtMs = nowMs,
            command = command,
            rawF28 = command.rawF28,
            frameVariant = frameVariant,
            includePng = frameVariant == HudLabFrameVariant.LIVE_PNG_F8 &&
                pngBytes != null && pngBytes > 0,
            iconGaodeCode = iconGaodeCode,
            pngBytes = pngBytes,
            payloadBytes = payloadBytes,
            sendRc = sendRc,
            sendFailure = sendFailure,
            gear = gear,
            speedKmh = speedKmh,
            events = listOf(event),
            deliveryCompletedAtMs = nowMs,
        )
        persist(context, (records(context) + record).takeLast(MAX_RECORDS))
        return record
    }

    @Synchronized
    fun recordClear(
        context: Context,
        id: String,
        clearRc: Int,
        autoCleared: Boolean,
        clearConfirmed: Boolean = clearRc == 0,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord? = update(context, id) {
        it.copy(
            clearedAtMs = if (clearConfirmed) nowMs else it.clearedAtMs,
            clearRc = clearRc,
            autoCleared = autoCleared && clearConfirmed,
        )
    }

    @Synchronized
    fun recordObservation(
        context: Context,
        id: String,
        observed: HudLabObserved,
        userLabel: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord? = update(context, id) {
        it.copy(
            observed = observed,
            observedAtMs = nowMs,
            userLabel = if (observed == HudLabObserved.NAMED_INDICATOR) {
                normalizeUserLabel(userLabel)
            } else {
                null
            },
        )
    }

    fun completedExplorerScenarioIds(context: Context): Set<String> = records(context)
        .filter(::isCompletedExplorerRecord)
        .mapNotNull(HudLabRecord::scenarioId)
        .toSet()

    @Synchronized
    fun records(context: Context): List<HudLabRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun renderDiagnosticSection(context: Context): String = buildString {
        val records = records(context)
        appendLine("--- windshield SOME/IP HUD Lab ---")
        appendLine("schema: ${HudLabRecord.CURRENT_SCHEMA_VERSION}")
        appendLine("runtime_output_may_be_owned: ${HudLabRuntimeState.outputMayBeOwned(context)}")
        appendLine("saved_tests: ${records.size}")
        appendExplorerDictionary(records)
        records.forEachIndexed { index, record ->
            appendLine("  #${index + 1} ${recordLine(record)}")
            record.events.forEach { event -> appendLine("    ${eventLine(event)}") }
        }
    }

    fun export(context: Context, nowMs: Long = System.currentTimeMillis()): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val saveDir = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            context.getExternalFilesDir(null),
        ).firstOrNull { dir ->
            dir != null && (dir.exists() || dir.mkdirs()) && dir.canWrite()
        } ?: error("no_writable_export_directory")
        val target = File(saveDir, "bydmate_hud_lab_$timestamp.txt")
        val report = buildString {
            appendLine("=== BYDMate HUD Lab export ===")
            appendLine(
                "exported_at: " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs)),
            )
            appendLine("app: ${context.packageName} v${BuildConfig.VERSION_NAME} (code=${BuildConfig.VERSION_CODE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("fingerprint: ${Build.FINGERPRINT}")
            appendLine("vehicle: ${VehicleProfile.CURRENT.model} ${VehicleProfile.CURRENT.trim}")
            append(renderDiagnosticSection(context))
            append(ClusterLabLogStore.renderDiagnosticSection(context))
            appendLine("==============================")
        }
        target.writeText(report, Charsets.UTF_8)
        return target
    }

    @Synchronized
    fun clearRecords(context: Context) {
        check(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECORDS).commit(),
        ) { "hud_lab_log_clear_failed" }
    }

    @Synchronized
    internal fun clearForTest(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Synchronized
    private fun update(
        context: Context,
        id: String,
        transform: (HudLabRecord) -> HudLabRecord,
    ): HudLabRecord? {
        var changed: HudLabRecord? = null
        val updated = records(context).map { record ->
            if (record.id == id) transform(record).also { changed = it } else record
        }
        if (changed != null) persist(context, updated)
        return changed
    }

    private fun persist(context: Context, records: List<HudLabRecord>) {
        val array = JSONArray()
        records.forEach { array.put(encode(it)) }
        check(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECORDS, array.toString()).commit(),
        ) { "hud_lab_log_persist_failed" }
    }

    private fun encode(record: HudLabRecord): JSONObject = JSONObject().apply {
        put("schemaVersion", record.schemaVersion)
        put("id", record.id)
        put("sessionId", record.sessionId)
        put("requestedAtMs", record.requestedAtMs)
        putNullable("command", record.command?.name)
        putNullable("rawF28", record.rawF28)
        put("frameVariant", record.frameVariant.name)
        put("includePng", record.includePng)
        putNullable("iconGaodeCode", record.iconGaodeCode)
        putNullable("pngBytes", record.pngBytes)
        put("payloadBytes", record.payloadBytes)
        putNullable("sendRc", record.sendRc)
        putNullable("sendFailure", record.sendFailure)
        putNullable("gear", record.gear)
        putNullable("speedKmh", record.speedKmh)
        putNullable("clearedAtMs", record.clearedAtMs)
        putNullable("clearRc", record.clearRc)
        put("autoCleared", record.autoCleared)
        putNullable("observed", record.observed?.name)
        putNullable("observedAtMs", record.observedAtMs)
        putNullable("userLabel", record.userLabel)
        putNullable("scenarioId", record.scenarioId)
        putNullable("scenarioTitle", record.scenarioTitle)
        putNullable("scenarioSummary", record.scenarioSummary)
        put("expected", record.expected.name)
        put("events", JSONArray().apply { record.events.forEach { put(encodeEvent(it)) } })
        putNullable("deliveryCompletedAtMs", record.deliveryCompletedAtMs)
        putNullable("abortedFailure", record.abortedFailure)
        put("appVersion", record.appVersion)
        put("appVersionCode", record.appVersionCode)
        put("buildFingerprint", record.buildFingerprint)
    }

    private fun encodeEvent(event: HudLabEvent): JSONObject = JSONObject().apply {
        put("type", event.type.name)
        put("stepIndex", event.stepIndex)
        put("label", event.label)
        put("pushIndex", event.pushIndex)
        put("atMs", event.atMs)
        put("elapsedMs", event.elapsedMs)
        put("payloadBytes", event.payloadBytes)
        putNullable("payloadSha256", event.payloadSha256)
        putNullable("fieldManifest", event.fieldManifest)
        putNullable("rc", event.rc)
        putNullable("failure", event.failure)
        putNullable("gear", event.gear)
        putNullable("speedKmh", event.speedKmh)
        put("phase", event.phase.name)
        putNullable("attemptId", event.attemptId)
        putNullable("outputMayBeOwned", event.outputMayBeOwned)
    }

    private fun decode(json: JSONObject): HudLabRecord? = runCatching {
        val command = json.optNullableString("command")?.let(HudLabCommand::valueOf)
        val includePng = json.optBoolean("includePng", false)
        val frameVariant = json.optNullableString("frameVariant")
            ?.let(HudLabFrameVariant::valueOf)
            ?: if (includePng) HudLabFrameVariant.LIVE_PNG_F8
            else HudLabFrameVariant.RAW_F28_ONLY
        val events = json.optJSONArray("events")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    decodeEvent(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.orEmpty()
        HudLabRecord(
            id = json.getString("id"),
            sessionId = json.optString("sessionId", json.getString("id")),
            requestedAtMs = json.getLong("requestedAtMs"),
            command = command,
            rawF28 = json.optNullableInt("rawF28") ?: command?.rawF28,
            frameVariant = frameVariant,
            includePng = includePng,
            iconGaodeCode = json.optNullableInt("iconGaodeCode"),
            pngBytes = json.optNullableInt("pngBytes"),
            payloadBytes = json.optInt("payloadBytes", 0),
            sendRc = json.optNullableInt("sendRc"),
            sendFailure = json.optNullableString("sendFailure"),
            gear = json.optNullableInt("gear"),
            speedKmh = json.optNullableInt("speedKmh"),
            clearedAtMs = json.optNullableLong("clearedAtMs"),
            clearRc = json.optNullableInt("clearRc"),
            autoCleared = json.optBoolean("autoCleared", false),
            observed = json.optNullableString("observed")?.let(HudLabObserved::valueOf),
            observedAtMs = json.optNullableLong("observedAtMs"),
            userLabel = json.optNullableString("userLabel"),
            schemaVersion = json.optInt("schemaVersion", 1),
            scenarioId = json.optNullableString("scenarioId"),
            scenarioTitle = json.optNullableString("scenarioTitle"),
            scenarioSummary = json.optNullableString("scenarioSummary"),
            expected = json.optNullableString("expected")?.let(HudLabObserved::valueOf)
                ?: command?.expected ?: HudLabObserved.NOTHING,
            events = events,
            deliveryCompletedAtMs = json.optNullableLong("deliveryCompletedAtMs"),
            abortedFailure = json.optNullableString("abortedFailure"),
            appVersion = json.optString("appVersion", "?"),
            appVersionCode = json.optInt("appVersionCode", 0),
            buildFingerprint = json.optString("buildFingerprint", "?"),
        )
    }.getOrNull()

    private fun decodeEvent(json: JSONObject): HudLabEvent? = runCatching {
        HudLabEvent(
            type = HudLabEventType.valueOf(json.getString("type")),
            stepIndex = json.optInt("stepIndex", 0),
            label = json.optString("label", "?"),
            pushIndex = json.optInt("pushIndex", 0),
            atMs = json.optLong("atMs", 0L),
            elapsedMs = json.optLong("elapsedMs", 0L),
            payloadBytes = json.optInt("payloadBytes", 0),
            payloadSha256 = json.optNullableString("payloadSha256"),
            fieldManifest = json.optNullableString("fieldManifest"),
            rc = json.optNullableInt("rc"),
            failure = json.optNullableString("failure"),
            gear = json.optNullableInt("gear"),
            speedKmh = json.optNullableInt("speedKmh"),
            phase = json.optNullableString("phase")?.let(HudLabEventPhase::valueOf)
                ?: HudLabEventPhase.RESULT,
            attemptId = json.optNullableString("attemptId"),
            outputMayBeOwned = json.optNullableBoolean("outputMayBeOwned"),
        )
    }.getOrNull()

    private fun recordLine(record: HudLabRecord): String {
        val observed = record.observed?.name ?: "PENDING"
        val intents = record.events.filter { it.phase == HudLabEventPhase.INTENT }
        val results = record.events.filter { it.phase == HudLabEventPhase.RESULT }
        val sends = results.filter { it.type == HudLabEventType.SEND }
        val clears = results.filter { it.type == HudLabEventType.CLEAR }
        val intentAttemptIds = intents.mapNotNull { it.attemptId }.toSet()
        val completedAttemptIds = results.mapNotNull { it.attemptId }.toSet()
        val interruptedAttempts = intents.count { it.attemptId !in completedAttemptIds }
        val orphanResults = results.count {
            it.attemptId != null && it.attemptId !in intentAttemptIds
        }
        val latestClearStep = clears.maxOfOrNull { it.stepIndex }
        val latestClears = clears.filter { it.stepIndex == latestClearStep }
        val latestClearUnconfirmed = latestClears.isNotEmpty() && latestClears.none { it.rc == 0 }
        val modernScenarioInterrupted = record.schemaVersion >= 2 &&
            record.scenarioId != null && record.deliveryCompletedAtMs == null
        val rcHistogram = (sends + clears).groupingBy { it.rc?.toString() ?: "none" }
            .eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        val verdict = when {
            orphanResults > 0 -> "JOURNAL_GAP"
            modernScenarioInterrupted || interruptedAttempts > 0 -> "INTERRUPTED"
            record.abortedFailure != null || record.sendFailure != null ||
                (record.sendRc != null && record.sendRc < 0) ||
                sends.any { it.remoteResult == HudLabRemoteResult.LOCAL_ERROR } ->
                "DELIVERY_ABORTED"
            latestClearUnconfirmed -> "CLEAR_UNCONFIRMED"
            record.observed == null -> "PENDING"
            record.observed == HudLabObserved.NOT_REPORTED -> "NOT_REPORTED"
            record.observed == record.expected -> "MATCH"
            record.observed == HudLabObserved.NOTHING -> "NO_OUTPUT"
            else -> "MISMATCH"
        }
        return "id=${record.id} session=${record.sessionId} schema=${record.schemaVersion} " +
            "requestedAtMs=${record.requestedAtMs} scenario=${record.scenarioId ?: "LEGACY"} " +
            "title=${quoted(record.scenarioTitle)} command=${record.command} rawF28=${record.rawF28} " +
            "expected=${record.expected} frameVariant=${record.frameVariant} includePng=${record.includePng} " +
            "iconGaodeCode=${record.iconGaodeCode} pngBytes=${record.pngBytes} " +
            "payloadMaxBytes=${record.payloadBytes} intents=${intents.size} sends=${sends.size} " +
            "clears=${clears.size} interruptedAttempts=$interruptedAttempts " +
            "orphanResults=$orphanResults " +
            "rcHistogram=[$rcHistogram] sendRc=${record.sendRc} sendFailure=${record.sendFailure} " +
            "gear=${record.gear} speedKmh=${record.speedKmh} clearedAtMs=${record.clearedAtMs} " +
            "clearRc=${record.clearRc} autoCleared=${record.autoCleared} observed=$observed " +
            "userLabel=${jsonQuoted(record.userLabel)} observedAtMs=${record.observedAtMs} " +
            "completedAtMs=${record.deliveryCompletedAtMs} " +
            "abortedFailure=${record.abortedFailure} verdict=$verdict summary=${quoted(record.scenarioSummary)} " +
            "app=${record.appVersion}(${record.appVersionCode}) fingerprint=${quoted(record.buildFingerprint)}"
    }

    private fun eventLine(event: HudLabEvent): String =
        "event=${event.type} phase=${event.phase} attempt=${event.attemptId} " +
            "step=${event.stepIndex} label=${event.label} push=${event.pushIndex} " +
            "atMs=${event.atMs} elapsedMs=${event.elapsedMs} payloadBytes=${event.payloadBytes} " +
            "sha256=${event.payloadSha256} fields=${quoted(event.fieldManifest)} rc=${event.rc} " +
            "remoteResult=${event.remoteResult} failure=${event.failure} gear=${event.gear} " +
            "speedKmh=${event.speedKmh} outputMayBeOwned=${event.outputMayBeOwned}"

    private fun quoted(value: String?): String = value?.replace(' ', '_') ?: "null"

    private fun StringBuilder.appendExplorerDictionary(records: List<HudLabRecord>) {
        val explorer = records.filter { HudLabScenarioCatalog.isExplorerScenario(it.scenarioId) }
        val labeled = explorer.count {
            it.observed == HudLabObserved.NAMED_INDICATOR && !it.userLabel.isNullOrBlank()
        }
        val noOutput = explorer.count { it.observed == HudLabObserved.NOTHING }
        val flashed = explorer.count { it.observed == HudLabObserved.FLASHED }
        val visibleUndescribed = explorer.count {
            it.observed == HudLabObserved.VISIBLE_UNDESCRIBED
        }
        val notReported = explorer.count { it.observed == HudLabObserved.NOT_REPORTED }
        val failed = explorer.count { it.abortedFailure != null || it.sendFailure != null }
        val uniqueCompleted = explorer.filter(::isCompletedExplorerRecord)
            .mapNotNull(HudLabRecord::scenarioId).toSet().size
        appendLine("--- HUD f28 Indicator Explorer dictionary ---")
        appendLine(
            "candidates_total=${HudF28ExplorerCatalog.candidates.size} " +
                "unique_completed=$uniqueCompleted records=${explorer.size} labeled=$labeled " +
                "no_output=$noOutput flashed=$flashed visible_undescribed=$visibleUndescribed " +
                "repeat_requested=$notReported failed=$failed",
        )
        explorer.forEach { record ->
            val raw = record.rawF28
            val hex = raw?.toString(16)?.uppercase()?.padStart(2, '0') ?: "??"
            appendLine(
                "  scenario=${record.scenarioId} f28=${raw ?: "?"}/0x$hex " +
                    "rc=${record.sendRc} observed=${record.observed ?: "PENDING"} " +
                    "label=${jsonQuoted(record.userLabel)} aborted=${record.abortedFailure}",
            )
        }
    }

    private fun normalizeUserLabel(value: String?): String? = value
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()
        ?.take(MAX_USER_LABEL_CHARS)
        ?.takeIf(String::isNotEmpty)

    private fun jsonQuoted(value: String?): String = value?.let(JSONObject::quote) ?: "null"

    private fun isCompletedExplorerRecord(record: HudLabRecord): Boolean =
        HudLabScenarioCatalog.isExplorerScenario(record.scenarioId) &&
            ((record.observed != null && record.observed != HudLabObserved.NOT_REPORTED) ||
                record.abortedFailure != null || record.sendFailure != null)

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
