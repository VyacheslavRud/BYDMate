package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression cover for the 1 Hz windshield card blink on Sea Lion 07 / Waze 4.105.
 *
 * Once the arrow started being read from pixels, the classifier re-read the same unchanged arrow
 * about once a second, and every read asked for a HUD refresh. A refresh sends a CLEAR and redraws
 * guidance one 300 ms tick later, so the card switched off and on continuously. The export taken
 * at 21:15:04 counted 136 completed classifications against 136 `HUD overlay recovery clear
 * accepted` lines while `maneuver transition` was logged only twice:
 *
 * ```
 * 21:15:27.841 Waze visual maneuver=LEFT gaode=1
 * 21:15:27.967 HUD overlay recovery clear accepted; guidance redraw pending
 * 21:15:28.803 Waze visual maneuver=LEFT gaode=1
 * 21:15:28.885 HUD overlay recovery clear accepted; guidance redraw pending
 * ```
 */
class SeaLionHudFlickerRegressionTest {

    @Before fun setUp() {
        NavGuidanceHub.reset()
    }

    /** Distance and street arrive from the text path first, exactly as on the car. */
    private fun startRoute(nowMs: Long) {
        NavGuidanceHub.update(
            NavGuidance(distanceMeters = 400, road = "Nádražní"),
            NavGuidanceHub.Source.A11Y,
            nowMs,
        )
    }

    private fun generation(nowMs: Long): Long = NavGuidanceHub.snapshot(nowMs).hudRefreshGeneration

    @Test fun `re-reading the same arrow every second never asks for a redraw`() {
        startRoute(nowMs = 1_000)
        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)
        val afterFirst = generation(1_100)

        // The logged cadence: one classification per second for half a minute, same arrow.
        var nowMs = 2_100L
        repeat(30) {
            NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs)
            nowMs += 1_000
        }

        assertEquals(afterFirst, generation(nowMs))
        assertEquals(NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.snapshot(nowMs).maneuverGaode)
    }

    @Test fun `the first arrow of a route is still a change worth drawing`() {
        startRoute(nowMs = 1_000)
        val before = generation(1_000)

        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)

        assertNotEquals(before, generation(1_100))
    }

    @Test fun `a real LEFT to RIGHT change requests exactly one redraw`() {
        startRoute(nowMs = 1_000)
        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)
        repeat(5) { NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, 2_100L + it * 1_000) }
        val beforeTurnChange = generation(7_100)

        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_RIGHT, nowMs = 8_100)
        val afterTurnChange = generation(8_100)

        assertEquals(beforeTurnChange + 1, afterTurnChange)
        assertEquals(NavManeuverCodes.GAODE_RIGHT, NavGuidanceHub.snapshot(8_100).maneuverGaode)

        // The new arrow then settles: further identical reads must go quiet again.
        repeat(5) { NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_RIGHT, 9_100L + it * 1_000) }
        assertEquals(afterTurnChange, generation(14_100))
    }

    @Test fun `hint result separates re-confirmation from a real change`() {
        startRoute(nowMs = 1_000)

        assertEquals(
            NavGuidanceHub.ManeuverHintResult.CHANGED,
            NavGuidanceHub.updateManeuverHint(
                NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.Source.A11Y, nowMs = 1_100,
            ),
        )
        assertEquals(
            NavGuidanceHub.ManeuverHintResult.UNCHANGED,
            NavGuidanceHub.updateManeuverHint(
                NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.Source.A11Y, nowMs = 2_100,
            ),
        )
        assertEquals(
            NavGuidanceHub.ManeuverHintResult.CHANGED,
            NavGuidanceHub.updateManeuverHint(
                NavManeuverCodes.GAODE_RIGHT, NavGuidanceHub.Source.A11Y, nowMs = 3_100,
            ),
        )
    }

    @Test fun `an unchanged arrow still renews the route lease`() {
        startRoute(nowMs = 1_000)
        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)

        // Well past ROUTE_LEASE_TIMEOUT_MS from the last text update; only silent re-confirmations
        // kept the route alive, which is what makes suppressing the refresh safe.
        var nowMs = 2_100L
        repeat(90) {
            NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs)
            nowMs += 1_000
        }

        val snapshot = NavGuidanceHub.snapshot(nowMs)
        assertTrue(nowMs - 1_000 > NavGuidanceHub.ROUTE_LEASE_TIMEOUT_MS)
        assertTrue(snapshot.active)
        assertEquals(NavManeuverCodes.GAODE_LEFT, snapshot.maneuverGaode)
    }

    /**
     * The two genuine recovery sources — a system notification overlay erasing the card, and the
     * Waze window becoming readable again — call [NavGuidanceHub.requestHudRefresh] directly. A
     * stable maneuver must not suppress them.
     */
    @Test fun `overlay and window recovery still force a redraw on a stable maneuver`() {
        startRoute(nowMs = 1_000)
        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)
        repeat(5) { NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, 2_100L + it * 1_000) }
        val settled = generation(7_100)

        NavGuidanceHub.requestHudRefresh()

        assertEquals(settled + 1, generation(7_100))
        assertEquals(NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.snapshot(7_100).maneuverGaode)
    }

    @Test fun `a hint without an active route stays ignored and draws nothing`() {
        val before = generation(1_000)

        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_000)

        assertEquals(before, generation(1_000))
        assertEquals(
            NavGuidanceHub.ManeuverHintResult.IGNORED,
            NavGuidanceHub.updateManeuverHint(
                NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.Source.A11Y, nowMs = 1_000,
            ),
        )
    }
}
