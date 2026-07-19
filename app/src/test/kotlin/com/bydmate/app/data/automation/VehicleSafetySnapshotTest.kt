package com.bydmate.app.data.automation

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSafetySnapshotTest {
    private val data = diParsData(speed = 0)

    @Test fun `snapshot at freshness boundary is accepted`() {
        assertNotNull(VehicleSafetySnapshot.freshOrNull(data, updatedAtMs = 1_000L, nowMs = 6_000L))
    }

    @Test fun `snapshot older than freshness boundary is rejected`() {
        assertNull(VehicleSafetySnapshot.freshOrNull(data, updatedAtMs = 1_000L, nowMs = 6_001L))
    }

    @Test fun `missing timestamp and future timestamp are rejected`() {
        assertNull(VehicleSafetySnapshot.freshOrNull(data, updatedAtMs = null, nowMs = 6_000L))
        assertNull(VehicleSafetySnapshot.freshOrNull(data, updatedAtMs = 6_001L, nowMs = 6_000L))
    }

    @Test fun `missing data is rejected`() {
        assertNull(VehicleSafetySnapshot.freshOrNull(null, updatedAtMs = 1_000L, nowMs = 1_000L))
    }
}
