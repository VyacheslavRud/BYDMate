package com.bydmate.app.hud

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

/** Raw native-arrow commands offered by the dev-only HUD calibration screen. */
enum class HudLabCommand(val rawF28: Int, val expected: HudLabObserved) {
    STRAIGHT(1, HudLabObserved.STRAIGHT),
    RIGHT(2, HudLabObserved.RIGHT),
    LEFT(3, HudLabObserved.LEFT),
    UTURN(9, HudLabObserved.UTURN),
}

/** What the driver actually saw in the factory windshield HUD. */
enum class HudLabObserved { LEFT, RIGHT, STRAIGHT, UTURN, NOTHING, OTHER, NOT_REPORTED }

data class HudLabRecord(
    val id: String,
    val requestedAtMs: Long,
    val command: HudLabCommand,
    val rawF28: Int,
    val includePng: Boolean,
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
    val appVersion: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val buildFingerprint: String = Build.FINGERPRINT,
)

/**
 * Small privacy-safe persistent journal for HUD Lab. It stores only calibration codes, transport
 * results and the user's visual answer: no Waze text, route, street or location is retained.
 */
object HudLabLogStore {
    private const val PREFS_NAME = "hud_lab_log"
    private const val KEY_RECORDS = "records_json"
    private const val MAX_RECORDS = 100

    @Synchronized
    fun createAttempt(
        context: Context,
        command: HudLabCommand,
        payloadBytes: Int,
        sendRc: Int?,
        sendFailure: String?,
        gear: Int?,
        speedKmh: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord {
        val record = HudLabRecord(
            id = UUID.randomUUID().toString(),
            requestedAtMs = nowMs,
            command = command,
            rawF28 = command.rawF28,
            includePng = false,
            payloadBytes = payloadBytes,
            sendRc = sendRc,
            sendFailure = sendFailure,
            gear = gear,
            speedKmh = speedKmh,
        )
        val updated = (records(context) + record).takeLast(MAX_RECORDS)
        persist(context, updated)
        return record
    }

    @Synchronized
    fun recordClear(
        context: Context,
        id: String,
        clearRc: Int,
        autoCleared: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord? = update(context, id) {
        it.copy(clearedAtMs = nowMs, clearRc = clearRc, autoCleared = autoCleared)
    }

    @Synchronized
    fun recordObservation(
        context: Context,
        id: String,
        observed: HudLabObserved,
        nowMs: Long = System.currentTimeMillis(),
    ): HudLabRecord? = update(context, id) {
        it.copy(observed = observed, observedAtMs = nowMs)
    }

    @Synchronized
    fun records(context: Context): List<HudLabRecord> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null)
            ?: return emptyList()
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
        appendLine("--- HUD Lab calibration ---")
        appendLine("saved_tests: ${records.size}")
        records.forEachIndexed { index, record ->
            appendLine("  #${index + 1} ${recordLine(record)}")
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
            appendLine("===============================")
        }
        target.writeText(report, Charsets.UTF_8)
        return target
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
                .edit()
                .putString(KEY_RECORDS, array.toString())
                .commit(),
        ) { "hud_lab_log_persist_failed" }
    }

    private fun encode(record: HudLabRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("requestedAtMs", record.requestedAtMs)
        put("command", record.command.name)
        put("rawF28", record.rawF28)
        put("includePng", record.includePng)
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
        put("appVersion", record.appVersion)
        put("appVersionCode", record.appVersionCode)
        put("buildFingerprint", record.buildFingerprint)
    }

    private fun decode(json: JSONObject): HudLabRecord? = runCatching {
        val command = HudLabCommand.valueOf(json.getString("command"))
        HudLabRecord(
            id = json.getString("id"),
            requestedAtMs = json.getLong("requestedAtMs"),
            command = command,
            rawF28 = json.optInt("rawF28", command.rawF28),
            includePng = json.optBoolean("includePng", false),
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
            appVersion = json.optString("appVersion", "?"),
            appVersionCode = json.optInt("appVersionCode", 0),
            buildFingerprint = json.optString("buildFingerprint", "?"),
        )
    }.getOrNull()

    private fun recordLine(record: HudLabRecord): String {
        val expected = record.command.expected.name
        val observed = record.observed?.name ?: "PENDING"
        val verdict = when {
            record.sendFailure != null || (record.sendRc ?: -1) < 0 -> "SEND_FAILED"
            record.observed == null -> "PENDING"
            record.observed == HudLabObserved.NOT_REPORTED -> "NOT_REPORTED"
            record.observed == record.command.expected -> "MATCH"
            record.observed == HudLabObserved.NOTHING -> "NO_OUTPUT"
            else -> "MISMATCH"
        }
        return "id=${record.id} requestedAtMs=${record.requestedAtMs} " +
            "command=${record.command} rawF28=${record.rawF28} expected=$expected " +
            "includePng=${record.includePng} payloadBytes=${record.payloadBytes} " +
            "sendRc=${record.sendRc} sendFailure=${record.sendFailure} " +
            "gear=${record.gear} speedKmh=${record.speedKmh} " +
            "clearedAtMs=${record.clearedAtMs} clearRc=${record.clearRc} " +
            "autoCleared=${record.autoCleared} observed=$observed " +
            "observedAtMs=${record.observedAtMs} verdict=$verdict " +
            "app=${record.appVersion}(${record.appVersionCode}) fingerprint=${record.buildFingerprint}"
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        takeUnless { isNull(key) }?.optString(key)?.takeIf(String::isNotEmpty)

    private fun JSONObject.optNullableInt(key: String): Int? =
        takeUnless { isNull(key) }?.optInt(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        takeUnless { isNull(key) }?.optLong(key)
}
