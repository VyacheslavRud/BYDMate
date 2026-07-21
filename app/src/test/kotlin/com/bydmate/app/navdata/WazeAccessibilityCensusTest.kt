package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The census exists to separate three indistinguishable live outcomes on the Sea Lion 07: the
 * maneuver id is missing, the node exists but carries no semantic value, or the bounded fallback
 * scan ran out of nodes. All three currently render as a blank windshield arrow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WazeAccessibilityCensusTest {

    @Before fun setUp() {
        WazeAccessibilityReader.resetCensus()
    }

    /**
     * `relaxed = true` is required for the node API this reader never touches, but a relaxed
     * CharSequence getter answers with a non-null mock whose `toString()` is a perfectly valid
     * semantic value. Every channel `maneuverSemanticValues` reads must therefore be stubbed
     * explicitly, or the census counts mock identities as maneuver evidence. Pinning sdk 32 keeps
     * `stateDescription` — read only on R+ — part of that contract instead of silently skipped.
     */
    private fun AccessibilityNodeInfo.stubUnusedSemanticChannels() {
        every { hintText } returns null
        every { paneTitle } returns null
        every { tooltipText } returns null
        every { stateDescription } returns null
        every { actionList } returns emptyList()
        every { viewIdResourceName } returns null
    }

    private fun valueNode(value: String?): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { isVisibleToUser } returns true
        every { text } returns value
        every { contentDescription } returns null
        every { childCount } returns 0
        stubUnusedSemanticChannels()
    }

    private fun routeRoot(): AccessibilityNodeInfo = mockk<AccessibilityNodeInfo>(relaxed = true)
        .also { root ->
            every { root.packageName } returns "com.waze"
            every { root.isVisibleToUser } returns true
            every { root.text } returns null
            every { root.contentDescription } returns null
            every { root.childCount } returns 0
            every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
            root.stubUnusedSemanticChannels()
        }

    @Test fun `census is not recorded while scoring candidate windows`() {
        val root = routeRoot()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("350 m"))

        WazeAccessibilityReader.read(root)

        assertEquals(0L, WazeAccessibilityReader.census().readAtMs)
    }

    @Test fun `distance and street without any maneuver node is the observed live shape`() {
        val root = routeRoot()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("1100 m"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine") } returns
            listOf(valueNode("Main Street"))

        val fields = WazeAccessibilityReader.read(root, recordCensus = true)
        val census = WazeAccessibilityReader.census()

        assertEquals(null, fields?.maneuver)
        assertTrue(census.readAtMs > 0L)
        assertEquals(0, census.maneuverNodes)
        assertEquals(0, census.maneuverValues)
        assertFalse(census.maneuverRecognized)
        assertTrue(census.distancePresent)
        assertTrue(census.streetPresent)
        assertTrue(census.fallbackScanned)
        assertFalse(census.fallbackRecognized)
    }

    @Test fun `a present but semantically empty maneuver node is counted separately`() {
        val root = routeRoot()
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirection") } returns
            listOf(valueNode(null))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("350 m"))

        WazeAccessibilityReader.read(root, recordCensus = true)
        val census = WazeAccessibilityReader.census()

        assertEquals(1, census.maneuverNodes)
        assertEquals(1, census.maneuverVisibleNodes)
        assertEquals(0, census.maneuverValues)
        assertFalse(census.maneuverRecognized)
    }

    @Test fun `a recognized maneuver marks the census recognized and skips the fallback scan`() {
        val root = routeRoot()
        every {
            root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarInstructionText")
        } returns listOf(valueNode("Turn right"))
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("350 m"))

        WazeAccessibilityReader.read(root, recordCensus = true)
        val census = WazeAccessibilityReader.census()

        assertTrue(census.maneuverRecognized)
        assertEquals(1, census.maneuverValues)
        assertFalse(census.fallbackScanned)
    }

    @Test fun `fallback scan reports the nodes it visited before finding a direction`() {
        val direction = valueNode("TURN_RIGHT")
        val root = routeRoot()
        every { root.childCount } returns 1
        every { root.getChild(0) } returns direction
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance") } returns
            listOf(valueNode("500 m"))

        WazeAccessibilityReader.read(root, recordCensus = true)
        val census = WazeAccessibilityReader.census()

        assertTrue(census.fallbackScanned)
        assertTrue(census.fallbackRecognized)
        assertFalse(census.fallbackCapped)
        assertTrue(census.fallbackNodesVisited >= 2)
    }
}
