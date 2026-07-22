package com.bydmate.app.cluster

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLabScenarioTest {
    @Test fun `catalog has nine stable bounded scenarios`() {
        assertEquals(
            listOf("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09"),
            ClusterLabScenarioCatalog.all.map { it.id },
        )
        assertEquals(
            listOf(
                ClusterLabMutation.NONE,
                ClusterLabMutation.NONE,
                ClusterLabMutation.APP_OVERLAY,
                ClusterLabMutation.PROJECTION_PIPELINE,
                ClusterLabMutation.PROJECTION_PIPELINE,
                ClusterLabMutation.PROJECTION_PIPELINE,
                ClusterLabMutation.PROJECTION_PIPELINE,
                ClusterLabMutation.NONE,
                ClusterLabMutation.NONE,
            ),
            ClusterLabScenarioCatalog.all.map { it.mutation },
        )
        assertTrue(ClusterLabScenarioCatalog.all.all { it.durationMs in 0L..20_000L })
        assertNull(ClusterLabScenarioCatalog.byId("C99"))
        assertEquals("C07", ClusterLabScenarioCatalog.primary().id)
        assertEquals(listOf("C08"), ClusterLabScenarioCatalog.manualTransport().map { it.id })
        assertEquals(listOf("C01", "C02", "C09"), ClusterLabScenarioCatalog.support().map { it.id })
    }

    @Test fun `visual scenarios stay hidden until a cluster display was detected`() {
        assertEquals(
            listOf("C01", "C02", "C07", "C08", "C09"),
            ClusterLabScenarioCatalog.visible(clusterDisplayAvailable = false).map { it.id },
        )
        assertEquals(
            listOf("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09"),
            ClusterLabScenarioCatalog.visible(clusterDisplayAvailable = true).map { it.id },
        )
        assertEquals(
            listOf("C03", "C04", "C05", "C06"),
            ClusterLabScenarioCatalog.advanced(clusterDisplayAvailable = true).map { it.id },
        )
    }

    @Test fun `parked debug route-free input is accepted`() {
        val result = evaluateClusterLabSafety(
            ClusterLabSafetyInput(
                isDebugBuild = true,
                parkConfirmedByUser = true,
                routeActive = false,
                gear = 1,
                speedKmh = 0,
            ),
        )

        assertTrue(result.safe)
        assertNull(result.failure)
        assertEquals(1, result.gear)
        assertEquals(0, result.speedKmh)
    }

    @Test fun `release build and missing confirmation are rejected before vehicle state`() {
        assertEquals(
            ClusterLabFailure.DEV_BUILD_REQUIRED,
            evaluateClusterLabSafety(
                ClusterLabSafetyInput(false, false, false, null, null),
            ).failure,
        )
        assertEquals(
            ClusterLabFailure.PARK_CONFIRMATION_REQUIRED,
            evaluateClusterLabSafety(
                ClusterLabSafetyInput(true, false, false, 1, 0),
            ).failure,
        )
    }

    @Test fun `active route movement and non-P gear are independently rejected`() {
        val cases = listOf(
            ClusterLabSafetyInput(true, true, true, 1, 0) to ClusterLabFailure.ROUTE_ACTIVE,
            ClusterLabSafetyInput(true, true, false, null, 0) to
                ClusterLabFailure.VEHICLE_DATA_UNAVAILABLE,
            ClusterLabSafetyInput(true, true, false, 1, 1) to ClusterLabFailure.VEHICLE_MOVING,
            ClusterLabSafetyInput(true, true, false, 4, 0) to ClusterLabFailure.PARK_GEAR_REQUIRED,
        )

        cases.forEach { (input, expected) ->
            val result = evaluateClusterLabSafety(input)
            assertFalse(result.safe)
            assertEquals(expected, result.failure)
        }
    }

    @Test fun `cluster display selection exactly matches production priority`() {
        val displays = listOf(
            2 to "fission_bg_XDJAScreenProjection",
            7 to "XDJAScreenProjection_0",
            9 to "XDJAScreenProjection_1",
        )

        assertEquals(9, preferredClusterDisplayId(displays))
        assertEquals(
            7,
            preferredClusterDisplayId(displays.filterNot { it.first == 9 }),
        )
        assertNull(preferredClusterDisplayId(displays.filterNot { it.second.contains("XDJ") }))
        assertNull(preferredClusterDisplayId(listOf(4 to "Center display")))
    }

    @Test fun `Sea Lion fission display is a center floating window not a cluster candidate`() {
        assertFalse(isClusterProjectionDisplay(2, "fission_bg_XDJAScreenProjection"))
        assertNull(preferredClusterDisplayId(listOf(2 to "fission_bg_XDJAScreenProjection")))
        assertFalse(isClusterProjectionDisplay(2, "Fallback panel"))
        assertTrue(isClusterProjectionDisplay(9, "XDJAScreenProjection_1"))
    }

    @Test fun `direct projection is opt in and never accepts the Sea Lion floating compositor`() {
        assertFalse(shouldAttemptDirectProjection(false, 9, "XDJAScreenProjection_1"))
        assertTrue(shouldAttemptDirectProjection(true, 9, "XDJAScreenProjection_1"))
        assertFalse(shouldAttemptDirectProjection(true, 2, "fission_bg_XDJAScreenProjection"))
        assertFalse(shouldAttemptDirectProjection(true, 0, "XDJAScreenProjection_1"))
    }

    @Test fun `factory setting stays blocked until a different boot session`() {
        assertEquals(
            FactoryProjectionSettingDecision(true, true),
            decideFactoryProjectionSetting(false, null, "count:10"),
        )
        assertEquals(
            FactoryProjectionSettingDecision(false, true),
            decideFactoryProjectionSetting(false, "count:10", "count:10"),
        )
        assertEquals(
            FactoryProjectionSettingDecision(false, false),
            decideFactoryProjectionSetting(false, "count:10", "count:11"),
        )
        assertEquals(
            FactoryProjectionSettingDecision(false, false),
            decideFactoryProjectionSetting(true, null, "count:10"),
        )
    }

    @Test fun `HUD safe virtual display policy makes one public attempt and no private fallback`() =
        runTest {
            val attemptedFlags = mutableListOf<Int>()
            val result = createPublicOnlyClusterVirtualDisplay(
                baseFlags = 322,
                publicFlag = 1,
            ) { flags ->
                attemptedFlags += flags
                null
            }

            assertNull(result)
            assertEquals(listOf(323), attemptedFlags)
        }

    @Test fun `lab auto container remains disabled unless allowed and preferred or forced`() {
        assertFalse(shouldUseAutoContainer(false, true))
        assertFalse(shouldUseAutoContainer(false, false))
        assertTrue(shouldUseAutoContainer(true, true))
        assertFalse(shouldUseAutoContainer(true, false))
        assertTrue(shouldUseAutoContainer(true, false, forceForParkedLab = true))
        assertFalse(shouldUseAutoContainer(false, true, forceForParkedLab = true))
    }
}
