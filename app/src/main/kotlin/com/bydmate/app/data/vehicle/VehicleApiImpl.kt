package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleApiImpl @Inject constructor(
    private val parsReader: ParsReader,
    private val autoservice: AutoserviceClient,
    private val helper: HelperClient,
    private val allowlist: WriteAllowlist,
    private val writeLogDao: VehicleWriteLogDao,
) : VehicleApi {

    // Liveness + snapshots — passthroughs.
    override suspend fun isAvailable(): Boolean = autoservice.isAvailable()
    override suspend fun readBatterySnapshot(): BatteryReading? = autoservice.readBatterySnapshot()
    override suspend fun readSnapshot(): DiParsData? = parsReader.fetch()

    // Individual readers — direct autoservice fid hits.
    override suspend fun readSoc(): Float? = autoservice.getFloat(1014, 1246777400)
    override suspend fun readSpeed(): Float? = autoservice.getFloat(1013, -1807745016)
    override suspend fun readMileageKm(): Float? =
        autoservice.getInt(1014, 1246765072)?.let { it / 10f }
    override suspend fun readPowerKw(): Int? = autoservice.getInt(1012, 339738656)
    override suspend fun readAcStatus(): Int? = autoservice.getInt(1000, 1077936144)
    override suspend fun readAcTemp(): Int? = autoservice.getInt(1000, 1077936168)
    override suspend fun readInsideTemp(): Int? = autoservice.getInt(1000, 1031798832)
    override suspend fun readExteriorTemp(): Int? = autoservice.getInt(1000, 1077936184)
    override suspend fun readFanLevel(): Int? = autoservice.getInt(1000, 1077936156)
    override suspend fun readWindowDriver(): Int? = autoservice.getInt(1001, 947912728)
    override suspend fun readWindowPassenger(): Int? = autoservice.getInt(1001, 1267728400)
    override suspend fun readWindowRearLeft(): Int? = autoservice.getInt(1001, 947912736)
    override suspend fun readWindowRearRight(): Int? = autoservice.getInt(1001, 947912752)

    // ─── Writes ────────────────────────────────────────────────────────────────

    override suspend fun dispatch(commandString: String): Boolean {
        val resolved = CommandTranslator.resolve(commandString) ?: run {
            Log.w(TAG, "dispatch: unknown command '$commandString'")
            return false
        }
        return doWrite(resolved.actionName, resolved.value)
    }

    // Climate
    override suspend fun writeAcOn(): Boolean = doWrite("ac_on", 2)
    override suspend fun writeAcOff(): Boolean = doWrite("ac_off", 1)
    override suspend fun writeSetDriverTemp(celsius: Int): Boolean =
        doWrite("ac_temp_main", celsius)

    // Windows
    override suspend fun writeWindowDriver(percent: Int): Boolean =
        doWrite("window_driver_pos", percent)
    override suspend fun writeWindowPassenger(percent: Int): Boolean =
        doWrite("window_passenger_pos", percent)
    override suspend fun writeWindowRearLeft(percent: Int): Boolean =
        doWrite("window_rear_left_pos", percent)
    override suspend fun writeWindowRearRight(percent: Int): Boolean =
        doWrite("window_rear_right_pos", percent)

    // Locks
    override suspend fun writeLockDoors(): Boolean = doWrite("doors_lock", 2)
    override suspend fun writeUnlockDoors(): Boolean = doWrite("doors_unlock", 1)

    // Sunroof — one allowlist entry per mode (sunroof_open, sunroof_close, etc.)
    override suspend fun writeSunroof(mode: SunroofMode): Boolean =
        doWrite("sunroof_${mode.name.lowercase()}", mode.value)

    // Sunshade
    override suspend fun writeSunshade(open: Boolean): Boolean =
        doWrite(if (open) "sunshade_open" else "sunshade_close", if (open) 1 else 2)

    // ─── Core write helper ─────────────────────────────────────────────────────

    /**
     * Fail-soft write via WriteAllowlist + HelperClient. Never throws — returns
     * false on any failure (allowlist miss, range violation, daemon failure,
     * readback sentinel/mismatch). Audit entry always written via [logWrite].
     *
     * Sentinel -10011 in readback = "no data / permission denied" (transient).
     * Readback null = entry has no readbackFid → trust the write result.
     */
    private suspend fun doWrite(actionName: String, value: Int): Boolean {
        val entry = allowlist.find(actionName) ?: run {
            Log.w(TAG, "doWrite: action=$actionName not in allowlist")
            logWrite(actionName, -1, -1, value, null, false, "allowlist_miss", validated = false)
            return false
        }
        if (value < entry.valueMin || value > entry.valueMax) {
            Log.w(TAG, "doWrite: action=$actionName value=$value out of range [${entry.valueMin}..${entry.valueMax}]")
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, "range", entry.validated)
            return false
        }
        val wrote = helper.write(entry.dev, entry.writeFid, value)
        if (!wrote) {
            val errTag = if (entry.validated) "helper_fail" else "helper_fail_nonvalidated"
            logWrite(actionName, entry.dev, entry.writeFid, value, null, false, errTag, entry.validated)
            return false
        }
        val readback: Long? = entry.readbackFid?.let { helper.read(entry.dev, it) }
        val readbackSentinel = readback == -10011L
        val ok = readback == null || (!readbackSentinel && readback.toInt() == value)
        logWrite(
            action = actionName,
            dev = entry.dev,
            fid = entry.writeFid,
            requested = value,
            readback = readback?.toInt(),
            ok = ok,
            error = when {
                ok               -> null
                readbackSentinel -> "readback_sentinel"
                else             -> "readback_mismatch"
            },
            validated = entry.validated,
        )
        return ok
    }

    // ─── Audit logger ──────────────────────────────────────────────────────────

    private suspend fun logWrite(
        action: String, dev: Int, fid: Int, requested: Int, readback: Int?,
        ok: Boolean, error: String?, validated: Boolean,
    ) {
        writeLogDao.insert(
            VehicleWriteLogEntity(
                ts = System.currentTimeMillis(),
                actionName = action,
                dev = dev,
                fid = fid,
                requested = requested,
                readback = readback,
                status = if (ok) 0 else -1,
                error = error,
                validated = validated,
            )
        )
    }

    companion object {
        private const val TAG = "VehicleApiImpl"
    }
}
