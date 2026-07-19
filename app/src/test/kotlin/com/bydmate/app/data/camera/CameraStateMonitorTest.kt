package com.bydmate.app.data.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStateMonitorTest {
    @Test
    fun `first query uses lookback and subsequent query starts after cursor`() {
        val now = 1_000_000L

        assertEquals(now - 5L * 60_000L, CameraStateMonitor.queryBeginTimestamp(0L, now))
        assertEquals(900_001L, CameraStateMonitor.queryBeginTimestamp(900_000L, now))
    }
}
