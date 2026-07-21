package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.hud.HudProtobufBuilder
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression cover for the blank windshield arrow on Sea Lion 07 / Waze 4.105.
 *
 * Reproduced from three diagnostic exports taken during one drive on 2026-07-21. Every export
 * reported `maneuverValues=0` (the maneuver ids expose no text at all — the arrow is an image),
 * `fallbackRecognized=true`, and `route: maneuver=48` mapping to `f28=OMITTED`. The logcat shows
 * the pattern that made it visible to the driver exactly once:
 *
 * ```
 * 19:14:53.417 maneuver transition: previous=ARRIVE maneuver=RIGHT gaode=2   (visual classifier)
 * 19:14:53.749 Waze maneuver=RIGHT(2) -> SeaLion f28=2 -> rc=0               (arrow appears)
 * 19:14:53.926 maneuver transition: previous=RIGHT maneuver=ARRIVE gaode=48  (text scan wins back)
 * 19:14:54.058 Waze maneuver=ARRIVE(48) -> SeaLion f28=OMITTED -> rc=0       (arrow gone)
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SeaLionWazeArrowRegressionTest {

    @Before fun setUp() {
        WazeAccessibilityReader.resetCensus()
        NavGuidanceHub.reset()
    }

    /** See [WazeAccessibilityCensusTest] — relaxed CharSequence getters answer with usable mocks. */
    private fun AccessibilityNodeInfo.stubUnusedSemanticChannels() {
        every { hintText } returns null
        every { paneTitle } returns null
        every { tooltipText } returns null
        every { stateDescription } returns null
        every { actionList } returns emptyList()
        every { viewIdResourceName } returns null
    }

    private fun textNode(value: String?): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { isVisibleToUser } returns true
        every { text } returns value
        every { contentDescription } returns null
        every { childCount } returns 0
        stubUnusedSemanticChannels()
    }

    /**
     * The shape all three exports reported: an image-only direction node that is present and
     * visible but semantically empty, real distance and street, and one further text panel that
     * the bounded fallback scan reaches while walking the window.
     */
    private fun seaLionRoot(panelText: String?): AccessibilityNodeInfo {
        val panel = textNode(panelText)
        return mockk<AccessibilityNodeInfo>(relaxed = true).also { root ->
            every { root.packageName } returns "com.waze"
            every { root.isVisibleToUser } returns true
            every { root.text } returns null
            every { root.contentDescription } returns null
            every { root.childCount } returns 1
            every { root.getChild(0) } returns panel
            every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
            every {
                root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirection")
            } returns listOf(textNode(null))
            every {
                root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDistance")
            } returns listOf(textNode("350 m"))
            every {
                root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarStreetLine")
            } returns listOf(textNode("Nádražní"))
            root.stubUnusedSemanticChannels()
        }
    }

    @Test fun `arrival panel text cannot become the route maneuver of a running route`() {
        val fields = WazeAccessibilityReader.read(seaLionRoot("Прибытие 19:12"), recordCensus = true)
        val census = WazeAccessibilityReader.census()

        // Before the fix this returned the panel text, which parsed to ARRIVE(48).
        assertNull(fields?.maneuver)
        assertEquals(0, NavManeuverCodes.fromInstructionText(fields?.maneuver))
        assertFalse(census.maneuverRecognized)
        assertFalse(census.fallbackRecognized)
        assertTrue(census.fallbackNonDirectional >= 1)
        // Distance and street must survive untouched: they are what still rendered on the HUD.
        assertEquals("350 m", fields?.maneuverDistance)
        assertEquals("Nádražní", fields?.street)
    }

    @Test fun `english destination panel is rejected the same way`() {
        val fields = WazeAccessibilityReader.read(
            seaLionRoot("You have arrived at your destination"),
            recordCensus = true,
        )

        assertNull(fields?.maneuver)
        assertTrue(WazeAccessibilityReader.census().fallbackNonDirectional >= 1)
    }

    @Test fun `a genuine direction on an unlisted node is still recovered by the fallback`() {
        val fields = WazeAccessibilityReader.read(seaLionRoot("TURN_RIGHT"), recordCensus = true)
        val census = WazeAccessibilityReader.census()

        assertEquals(
            NavManeuverCodes.GAODE_RIGHT,
            NavManeuverCodes.fromInstructionText(fields?.maneuver),
        )
        assertTrue(census.fallbackRecognized)
        assertEquals(0, census.fallbackNonDirectional)
    }

    @Test fun `roundabout guidance stays available to the fallback`() {
        val fields = WazeAccessibilityReader.read(seaLionRoot("Съезжайте с кольца"), recordCensus = true)

        assertEquals(
            NavManeuverCodes.GAODE_ROUNDABOUT_EXIT,
            NavManeuverCodes.fromInstructionText(fields?.maneuver),
        )
    }

    /**
     * The 19:14:53 sequence, replayed with the fixed reader output. The text read that follows the
     * visual classification 332 ms later now carries maneuver=0 instead of ARRIVE(48), so the
     * distance-only continuity rule keeps the arrow instead of replacing it.
     */
    @Test fun `visual arrow survives the text read that used to overwrite it`() {
        NavGuidanceHub.update(
            NavGuidance(distanceMeters = 400, road = "Nádražní"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 10_000,
        )
        assertTrue(
            NavGuidanceHub.updateManeuverHint(
                NavManeuverCodes.GAODE_RIGHT,
                NavGuidanceHub.Source.A11Y,
                nowMs = 10_100,
            ),
        )
        assertEquals(NavManeuverCodes.GAODE_RIGHT, NavGuidanceHub.snapshot(10_100).maneuverGaode)

        NavGuidanceHub.update(
            NavGuidance(distanceMeters = 380, road = "Nádražní"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 10_432,
        )

        val snapshot = NavGuidanceHub.snapshot(10_432)
        assertEquals(NavManeuverCodes.GAODE_RIGHT, snapshot.maneuverGaode)
        assertEquals(380, snapshot.distanceMeters)
        assertEquals("Nádražní", snapshot.road)
        assertEquals(
            HudProtobufBuilder.SEA_LION_F28_RIGHT,
            HudProtobufBuilder.seaLionF28ForGaode(snapshot.maneuverGaode),
        )
    }

    @Test fun `passing the turn still clears the arrow instead of holding it stale`() {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = NavManeuverCodes.GAODE_LEFT, distanceMeters = 120, road = "Nádražní"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 20_000,
        )
        // Turn taken: distance jumps back up and the next street replaces the old one.
        NavGuidanceHub.update(
            NavGuidance(distanceMeters = 900, road = "Husova"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 21_000,
        )

        val snapshot = NavGuidanceHub.snapshot(21_000)
        assertEquals(0, snapshot.maneuverGaode)
        assertEquals(900, snapshot.distanceMeters)
        assertEquals("Husova", snapshot.road)
        assertNull(HudProtobufBuilder.seaLionF28ForGaode(snapshot.maneuverGaode))
    }

    /**
     * The confirmed Sea Lion contract is deliberately re-asserted here: the defect was the input to
     * this mapping, never the mapping itself, so a future change to it must break this test.
     */
    @Test fun `confirmed Sea Lion f28 mapping is unchanged`() {
        assertEquals(HudProtobufBuilder.SEA_LION_F28_LEFT, HudProtobufBuilder.seaLionF28ForGaode(1))
        assertEquals(HudProtobufBuilder.SEA_LION_F28_RIGHT, HudProtobufBuilder.seaLionF28ForGaode(2))
        assertEquals(
            HudProtobufBuilder.SEA_LION_F28_STRAIGHT,
            HudProtobufBuilder.seaLionF28ForGaode(NavManeuverCodes.GAODE_STRAIGHT),
        )
        assertNull(HudProtobufBuilder.seaLionF28ForGaode(NavManeuverCodes.GAODE_ARRIVE))
        assertNull(HudProtobufBuilder.seaLionF28ForGaode(0))
    }

    @Test fun `route lifecycle codes are not steering directions`() {
        listOf(
            NavManeuverCodes.GAODE_ARRIVE,
            NavManeuverCodes.GAODE_WAYPOINT,
            NavManeuverCodes.GAODE_FERRY,
            NavManeuverCodes.GAODE_TUNNEL,
            0,
        ).forEach { assertFalse(NavManeuverCodes.isDirectionalManeuver(it)) }

        listOf(
            NavManeuverCodes.GAODE_LEFT,
            NavManeuverCodes.GAODE_RIGHT,
            NavManeuverCodes.GAODE_SLIGHT_LEFT,
            NavManeuverCodes.GAODE_SLIGHT_RIGHT,
            NavManeuverCodes.GAODE_HARD_LEFT,
            NavManeuverCodes.GAODE_HARD_RIGHT,
            NavManeuverCodes.GAODE_UTURN,
            NavManeuverCodes.GAODE_UTURN_RIGHT,
            NavManeuverCodes.GAODE_STRAIGHT,
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER,
            NavManeuverCodes.GAODE_ROUNDABOUT_EXIT,
        ).forEach { assertTrue(NavManeuverCodes.isDirectionalManeuver(it)) }
    }
}
