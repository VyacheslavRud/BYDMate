package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.remote.DiParsData

/**
 * Single in-process facade for vehicle reads + writes.
 *
 * Reads delegate to NativeParsReader / AutoserviceClient (Phase 1b stack).
 * Writes (Group C, populated from Phase 2c probe) go through HelperClient
 * via WriteAllowlist.
 *
 * Returns null on any read failure (sentinel, daemon down, parse failure).
 * Returns false on any write failure (allowlist miss, daemon down, readback mismatch).
 */
interface VehicleApi {
    // Liveness / snapshots — passthroughs to AutoserviceClient / NativeParsReader.
    suspend fun isAvailable(): Boolean
    suspend fun readBatterySnapshot(): BatteryReading?
    suspend fun readSnapshot(): DiParsData?

    // Individual read accessors (grow organically as consumers migrate).
    suspend fun readSoc(): Float?
    suspend fun readSpeed(): Float?
    suspend fun readMileageKm(): Float?
    suspend fun readPowerKw(): Int?
    suspend fun readAcStatus(): Int?
    suspend fun readAcTemp(): Int?
    suspend fun readInsideTemp(): Int?
    suspend fun readExteriorTemp(): Int?
    suspend fun readFanLevel(): Int?
    suspend fun readWindowDriver(): Int?
    suspend fun readWindowPassenger(): Int?
    suspend fun readWindowRearLeft(): Int?
    suspend fun readWindowRearRight(): Int?

    // Write entrypoints — backwards-compat dispatch + structured methods.
    // All `write*` methods are stubs in Group A; real allowlist + helper wiring lands in Group C.
    suspend fun dispatch(commandString: String): Boolean
    suspend fun writeAcOn(): Boolean
    suspend fun writeAcOff(): Boolean
    suspend fun writeSetDriverTemp(celsius: Int): Boolean
    suspend fun writeWindowDriver(percent: Int): Boolean
}
