package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavA11yFeedRootLossTest {

    @After fun tearDown() {
        NavA11yFeed.disable()
        NavGuidanceHub.reset()
    }

    @Test fun `navigator event without reachable window keeps route indeterminate and active`() {
        NavGuidanceHub.reset()
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y, nowMs = t0)
        NavA11yFeed.enable()
        NavA11yFeed.lastProcessMs = 0L
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } returns null
        }
        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "com.waze"
        }
        NavA11yFeed.onEvent(service, event)
        assertTrue(NavGuidanceHub.snapshot(t0 + 1_000).active)
        assertTrue(
            NavGuidanceHub.snapshot(
                t0 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS + 5_000,
            ).active,
        )
        assertEquals(0, NavGuidanceHub.diagnostics().explicitNoRouteObservationCount)
        assertEquals(
            NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE,
            NavA11yFeed.diagnostics().lastProbeResult,
        )
    }

    @Test fun `two explicit route-free reads spanning grace confirm route end`() {
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y,
            nowMs = t0,
        )
        NavA11yFeed.enable()
        val roots = listOf(blankWazeRoot(), blankWazeRoot()).iterator()
        val service = mockk<SteeringWheelKeyService> {
            every { findNavigatorRoot() } answers { roots.next() }
        }

        assertEquals(
            NavA11yFeed.ProbeResult.EXPLICIT_NO_ROUTE,
            NavA11yFeed.processWindow(service, t0 + 1_000, 1_000),
        )
        assertTrue(NavGuidanceHub.snapshot(t0 + 4_000).active)
        assertEquals(
            NavA11yFeed.ProbeResult.EXPLICIT_NO_ROUTE,
            NavA11yFeed.processWindow(
                service,
                t0 + 1_000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS,
                5_000,
            ),
        )
        assertFalse(NavGuidanceHub.snapshot(t0 + 6_000).active)
        assertEquals(
            NavGuidanceHub.RouteEndReason.EXPLICIT_NO_ROUTE,
            NavGuidanceHub.diagnostics().lastRouteEndReason,
        )
    }

    @Test fun `window loss between empty reads cancels false route-end sequence`() {
        val t0 = System.currentTimeMillis()
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 500),
            NavGuidanceHub.Source.A11Y,
            nowMs = t0,
        )
        NavA11yFeed.enable()
        val service = mockk<SteeringWheelKeyService>()
        every { service.findNavigatorRoot() } returns blankWazeRoot() andThen null andThen blankWazeRoot()

        NavA11yFeed.processWindow(service, t0 + 1_000, 1_000)
        NavA11yFeed.processWindow(service, t0 + 2_000, 2_000)
        NavA11yFeed.processWindow(
            service,
            t0 + 1_000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS,
            5_000,
        )

        assertTrue(NavGuidanceHub.snapshot(t0 + 6_000).active)
        assertEquals(1, NavGuidanceHub.diagnostics().explicitNoRouteObservationCount)
    }

    private fun blankWazeRoot(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.waze"
        className = "android.view.View"
        isVisibleToUser = true
    }
}
