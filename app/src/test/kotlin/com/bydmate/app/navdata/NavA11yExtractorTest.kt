package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavA11yExtractorTest {

    private fun valueNode(value: String): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { isVisibleToUser } returns true
        every { text } returns value
    }

    @Test fun `null root is not navigator`() {
        assertEquals(NavA11yExtractor.ReadResult.NotNavigator, NavA11yExtractor.read(null))
    }

    @Test fun `foreign package is not navigator`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.android.launcher"
        assertEquals(NavA11yExtractor.ReadResult.NotNavigator, NavA11yExtractor.read(node))
    }

    @Test fun `Waze package without guidance widgets is no-guidance`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.waze"
        assertEquals(NavA11yExtractor.ReadResult.NoGuidance, NavA11yExtractor.read(node))
    }

    @Test fun `Waze accessibility fields include stable arrival and complete route summary`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarInstructionText") } returns
            listOf(valueNode("Turn right, then turn left"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("350 m"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine") } returns
            listOf(valueNode("Main Street"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/etaBarDistanceToDestination") } returns
            listOf(valueNode("12 km"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/etaBarTimeToDestination") } returns
            listOf(valueNode("18 min"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/etaBarArrivalTime") } returns
            listOf(valueNode("3:40 PM"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/speedLimitWarn") } returns
            listOf(valueNode("60"))

        val result = NavA11yExtractor.read(root) as NavA11yExtractor.ReadResult.Guidance
        assertEquals(2, result.data.maneuverGaode)
        assertEquals(350, result.data.distanceMeters)
        assertEquals("Main Street", result.data.road)
        assertEquals(18 * 60, result.data.etaSeconds)
        assertEquals("15:40", result.data.arrivalTime)
        assertEquals(12_000, result.data.totalDistMeters)
        assertEquals(60, result.data.speedLimit)
    }

    @Test fun `unknown Waze view id falls back to bounded visible tree direction`() {
        val direction = valueNode("TURN_RIGHT")
        every { direction.childCount } returns 0
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.isVisibleToUser } returns true
        every { root.text } returns null
        every { root.contentDescription } returns null
        every { root.childCount } returns 1
        every { root.getChild(0) } returns direction
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("500 m"))

        val result = NavA11yExtractor.read(root) as NavA11yExtractor.ReadResult.Guidance

        assertEquals(2, result.data.maneuverGaode)
        assertEquals(500, result.data.distanceMeters)
    }

    @Test fun `unrecognized known direction node does not hide semantic child direction`() {
        val direction = valueNode("TURN_RIGHT")
        every { direction.childCount } returns 0
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.isVisibleToUser } returns true
        every { root.text } returns null
        every { root.contentDescription } returns null
        every { root.childCount } returns 1
        every { root.getChild(0) } returns direction
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirectionText") } returns
            listOf(valueNode("Maneuver"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("500 m"))

        val result = NavA11yExtractor.read(root) as NavA11yExtractor.ReadResult.Guidance

        assertEquals(2, result.data.maneuverGaode)
    }

    @Test fun `Compose maneuver resource id is read when icon has no text`() {
        val direction = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { text } returns null
            every { contentDescription } returns null
            every { viewIdResourceName } returns "com.waze:id/navigation_turn_right_icon"
            every { childCount } returns 0
        }
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.isVisibleToUser } returns true
        every { root.text } returns null
        every { root.contentDescription } returns null
        every { root.childCount } returns 1
        every { root.getChild(0) } returns direction
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("500 m"))

        val result = NavA11yExtractor.read(root) as NavA11yExtractor.ReadResult.Guidance

        assertEquals(2, result.data.maneuverGaode)
    }

    @Test fun `unrelated left resource id is not treated as maneuver`() {
        val unrelated = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { text } returns null
            every { contentDescription } returns null
            every { viewIdResourceName } returns "com.waze:id/left_panel"
            every { childCount } returns 0
        }
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.isVisibleToUser } returns true
        every { root.text } returns null
        every { root.contentDescription } returns null
        every { root.childCount } returns 1
        every { root.getChild(0) } returns unrelated
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("500 m"))

        val result = NavA11yExtractor.read(root) as NavA11yExtractor.ReadResult.Guidance

        assertEquals(0, result.data.maneuverGaode)
    }
}
