package com.bydmate.app.data.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProbeGenerationGateTest {

    @Test
    fun `stale probe can neither publish nor clear newer pending state`() {
        assertFalse(
            NetworkProbeGenerationGate.shouldPublish(
                probeGeneration = 4,
                currentGeneration = 5,
                probeNetworkId = 10,
                validatedNetworkId = 10,
                reached = true,
            )
        )
        assertFalse(NetworkProbeGenerationGate.ownsState(4, 5))
    }

    @Test
    fun `current successful probe publishes only for its tracked network`() {
        assertTrue(
            NetworkProbeGenerationGate.shouldPublish(
                probeGeneration = 5,
                currentGeneration = 5,
                probeNetworkId = 10,
                validatedNetworkId = 10,
                reached = true,
            )
        )
        assertFalse(
            NetworkProbeGenerationGate.shouldPublish(
                probeGeneration = 5,
                currentGeneration = 5,
                probeNetworkId = 10,
                validatedNetworkId = 11,
                reached = true,
            )
        )
        assertFalse(
            NetworkProbeGenerationGate.shouldPublish(
                probeGeneration = 5,
                currentGeneration = 5,
                probeNetworkId = 10,
                validatedNetworkId = 10,
                reached = false,
            )
        )
    }
}
