package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.autoservice.BatteryReading
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

    // Writes — stub-throw until Group C.
    override suspend fun dispatch(commandString: String): Boolean =
        throw NotImplementedError("populated in Group C task C.3")
    override suspend fun writeAcOn(): Boolean =
        throw NotImplementedError("populated in Group C task C.2")
    override suspend fun writeAcOff(): Boolean =
        throw NotImplementedError("populated in Group C task C.2")
    override suspend fun writeSetDriverTemp(celsius: Int): Boolean =
        throw NotImplementedError("populated in Group C task C.2")
    override suspend fun writeWindowDriver(percent: Int): Boolean =
        throw NotImplementedError("populated in Group C task C.2")
}
