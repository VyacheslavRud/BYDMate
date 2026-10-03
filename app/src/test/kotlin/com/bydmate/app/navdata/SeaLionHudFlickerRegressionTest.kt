package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
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
    private fun startRoute(nowMs: Long, distanceMeters: Int = 400) {
        NavGuidanceHub.update(
            NavGuidance(distanceMeters = distanceMeters, road = "Nádražní"),
            NavGuidanceHub.Source.A11Y,
            nowMs,
        )
    }

    private fun generation(nowMs: Long): Long = NavGuidanceHub.snapshot(nowMs).hudRefreshGeneration

    @Test fun `repeated roundabout readings survive distance updates without extra redraws`() {
        startRoute(nowMs = 1_000)
        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER, nowMs = 1_100)
        val afterFirst = generation(1_100)
        listOf(200, 101, 100, 50).forEachIndexed { index, distance ->
            val now = 2_000L + index * 1_000L
            NavGuidanceHub.update(NavGuidance(distanceMeters = distance, road = "Nádražní"),
                NavGuidanceHub.Source.A11Y, now)
            NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER, now + 100)
            assertEquals(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER, NavGuidanceHub.snapshot(now + 100).maneuverGaode)
            assertEquals(afterFirst, generation(now + 100))
        }
    }

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

    @Test fun `the first arrow inside the approach reaches the frame without a CLEAR`() {
        startRoute(nowMs = 1_000, distanceMeters = 80)
        val before = generation(1_000)

        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)

        assertEquals(before, generation(1_100))
        assertEquals(NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.snapshot(1_100).maneuverGaode)
    }

    @Test fun `the first arrow far from the turn does not blink the straight card`() {
        // Beyond 100 m the unknown maneuver is already drawn as straight, like a known one.
        startRoute(nowMs = 1_000)
        val before = generation(1_000)

        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)

        assertEquals(before, generation(1_100))
        assertEquals(NavManeuverCodes.GAODE_LEFT, NavGuidanceHub.snapshot(1_100).maneuverGaode)
    }

    @Test fun `a real LEFT to RIGHT change is applied without blinking the card`() {
        // Parked HUD Lab T01 (2026-10-03): the glass swaps left for right on the next frame, so
        // the change needs no CLEAR. f28 goes from 1 to 2 inside the 100 m approach.
        startRoute(nowMs = 1_000, distanceMeters = 80)
        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, nowMs = 1_100)
        repeat(5) { NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_LEFT, 2_100L + it * 1_000) }
        val beforeTurnChange = generation(7_100)

        NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_RIGHT, nowMs = 8_100)
        val afterTurnChange = generation(8_100)

        assertEquals(beforeTurnChange, afterTurnChange)
        assertEquals(NavManeuverCodes.GAODE_RIGHT, NavGuidanceHub.snapshot(8_100).maneuverGaode)

        // The new arrow then settles: further identical reads must go quiet again.
        repeat(5) { NavA11yFeed.applyVisualManeuver(NavManeuverCodes.GAODE_RIGHT, 9_100L + it * 1_000) }
        assertEquals(afterTurnChange, generation(14_100))
    }

    /**
     * Since the 100 m approach gate every known maneuver beyond 100 m is sent as f28=11, so a
     * LEFT/RIGHT reading change far from the turn leaves the windshield frame byte-identical.
     * A refresh there is a CLEAR with nothing new to draw: the card blinks for no reason.
     */
    @Test fun `a maneuver change that leaves the windshield frame identical does not blink`() {
        data class Case(val distance: Int, val first: Int, val second: Int)
        listOf(
            Case(400, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_RIGHT),
            Case(400, NavManeuverCodes.GAODE_STRAIGHT, NavManeuverCodes.GAODE_LEFT),
            Case(400, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_ROUNDABOUT_ENTER),
            Case(50, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_HARD_LEFT),
            Case(50, NavManeuverCodes.GAODE_ROUNDABOUT_ENTER, NavManeuverCodes.GAODE_ARRIVE),
        ).forEach { case ->
            NavGuidanceHub.reset()
            startRoute(nowMs = 1_000, distanceMeters = case.distance)
            NavA11yFeed.applyVisualManeuver(case.first, nowMs = 1_100)
            val settled = generation(1_100)

            NavA11yFeed.applyVisualManeuver(case.second, nowMs = 2_100)

            assertEquals("$case", settled, generation(2_100))
            // The route state still follows the newest reading for the voice agent and logs.
            assertEquals("$case", case.second, NavGuidanceHub.snapshot(2_100).maneuverGaode)
        }
    }

    @Test fun `a maneuver change that changes the windshield frame needs no CLEAR`() {
        data class Case(val distance: Int, val first: Int, val second: Int)
        listOf(
            Case(50, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_RIGHT),
            Case(50, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_STRAIGHT),
            Case(50, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_SLIGHT_LEFT),
            Case(50, NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_ROUNDABOUT_ENTER),
        ).forEach { case ->
            NavGuidanceHub.reset()
            startRoute(nowMs = 1_000, distanceMeters = case.distance)
            NavA11yFeed.applyVisualManeuver(case.first, nowMs = 1_100)
            val settled = generation(1_100)

            NavA11yFeed.applyVisualManeuver(case.second, nowMs = 2_100)

            assertEquals("$case", settled, generation(2_100))
            assertEquals("$case", case.second, NavGuidanceHub.snapshot(2_100).maneuverGaode)
        }
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
