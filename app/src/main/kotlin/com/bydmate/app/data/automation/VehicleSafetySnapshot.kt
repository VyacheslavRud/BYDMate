package com.bydmate.app.data.automation

import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.service.TrackingService

/**
 * Provides vehicle data only while it belongs to the current live service instance and is fresh
 * enough for a safety decision. [TrackingService.lastData] intentionally survives a normal service
 * shutdown, so control paths must never use it without the lifecycle timestamp checks here.
 */
internal object VehicleSafetySnapshot {
    internal const val MAX_AGE_MS = 5_000L

    internal fun freshOrNull(
        data: DiParsData?,
        updatedAtMs: Long?,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): DiParsData? {
        if (data == null || updatedAtMs == null || maxAgeMs < 0L) return null
        val ageMs = nowMs - updatedAtMs
        return data.takeIf { ageMs in 0L..maxAgeMs }
    }

    /**
     * Reads the timestamp before the data because TrackingService publishes data first and then its
     * timestamp. That ordering can only produce a conservative false-negative, never make old data
     * look newer. Lifecycle flags are checked again after the read to close a concurrent stop race.
     */
    internal fun current(nowMs: Long = System.currentTimeMillis()): DiParsData? {
        if (!TrackingService.isRunning.value || !TrackingService.vehicleDataConnected.value) return null
        val updatedAtMs = TrackingService.lastDataUpdatedAt.value
        val data = TrackingService.lastData.value
        val fresh = freshOrNull(data, updatedAtMs, nowMs) ?: return null
        return fresh.takeIf {
            TrackingService.isRunning.value && TrackingService.vehicleDataConnected.value
        }
    }
}
