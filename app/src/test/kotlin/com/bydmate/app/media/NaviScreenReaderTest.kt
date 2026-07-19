package com.bydmate.app.media

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaviScreenReaderTest {

    private fun node(
        text: String?,
        visible: Boolean = true,
        description: String? = null,
    ): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { this@mockk.text } returns text
        every { isVisibleToUser } returns visible
        every { contentDescription } returns description
    }

    @Test fun `null root returns null`() {
        assertNull(NaviScreenReader.read(null))
    }

    @Test fun `foreign package returns null`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.bydmate.app"
        assertNull(NaviScreenReader.read(root))
    }

    @Test fun `reads route fields from Waze window`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/speedLimitWarn") } returns
            listOf(node("60"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirectionText") } returns
            listOf(node("Take the 2nd exit"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(node("250 m"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/minimizedEtaBarDistanceToDestination") } returns
            listOf(node("28 км"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/minimizedEtaBarTimeToDestination") } returns
            listOf(node("27 мин"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/minimizedEtaBarArrivalTime") } returns
            listOf(node("10:10"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine") } returns
            listOf(node("ул. Качаны"))

        val info = NaviScreenReader.read(root)!!
        assertEquals("Take the 2nd exit", info.maneuver)
        assertEquals("60", info.speedLimit)
        assertEquals("2", info.exitNumber)
        assertEquals("250 m", info.maneuverDistance)
        assertEquals("28 км", info.remainingDistance)
        assertEquals("27 мин", info.remainingTime)
        assertEquals("10:10", info.arrivalTime)
        assertEquals("ул. Качаны", info.street)
    }

    @Test fun `maneuver distance is read as one Waze field`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(node("250 m"))

        val info = NaviScreenReader.read(root)!!
        assertEquals("250 m", info.maneuverDistance)
    }

    @Test fun `reads full ETA panel ids used by active Waze route`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine") } returns
            listOf(node("Proceed to highlighted route"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/lblDistanceToDestination") } returns
            listOf(node("3.1 mi"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/lblTimeToDestination") } returns
            listOf(node("15 min"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/lblArrivalTime") } returns
            listOf(node("3:40 PM"))

        val info = NaviScreenReader.read(root)!!
        assertEquals("3.1 mi", info.remainingDistance)
        assertEquals("15 min", info.remainingTime)
        assertEquals("3:40 PM", info.arrivalTime)
    }

    @Test fun `Waze window without route fields returns null`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        assertNull(NaviScreenReader.read(root))
    }

    @Test fun `hidden stale node is skipped in favour of visible value`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirectionText") } returns
            listOf(node("LEFT", visible = false), node("RIGHT", visible = true))

        assertEquals("RIGHT", NaviScreenReader.read(root)?.maneuver)
    }

    @Test fun `uppercase spoken descriptions survive while compose tags are ignored`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirectionText") } returns
            listOf(
                node(null, description = "NAV_BAR_DIRECTION"),
                node(null, description = "LEFT"),
            )

        assertEquals("LEFT", NaviScreenReader.read(root)?.maneuver)
    }
}
