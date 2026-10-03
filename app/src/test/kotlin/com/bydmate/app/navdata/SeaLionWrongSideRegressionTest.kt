package com.bydmate.app.navdata

import com.bydmate.app.hud.HudProtobufBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression cover for "Waze says turn left, the windshield shows right" on Sea Lion 07.
 *
 * On this car the arrow exists only as Waze's icon image, so the direction comes from pixel
 * readings while distance and street come from accessibility text. Three paths could put the
 * wrong side on the glass: a passed turn's arrow carried onto a close next turn, a single
 * misread screenshot, and an old arrow surviving while the next icon is unrecognizable.
 */
class SeaLionWrongSideRegressionTest {

    @Before fun setUp() {
        NavGuidanceHub.reset()
        NavA11yFeed.disable()
    }

    private fun text(distanceMeters: Int, nowMs: Long, road: String = "") {
        NavGuidanceHub.update(
            NavGuidance(distanceMeters = distanceMeters, road = road),
            NavGuidanceHub.Source.A11Y,
            nowMs,
        )
    }

    private fun glass(nowMs: Long): Int? {
        val s = NavGuidanceHub.snapshot(nowMs)
        return HudProtobufBuilder.seaLionF28ForGuidance(s.maneuverGaode, s.distanceMeters)
    }

    private fun confirmedLeftAt(distanceMeters: Int, nowMs: Long) {
        text(distanceMeters, nowMs)
        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_LEFT, nowMs + 10)
        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_LEFT, nowMs + 1_100)
        assertEquals(HudProtobufBuilder.SEA_LION_F28_LEFT, glass(nowMs + 1_100))
    }

    @Test fun `a passed left turn is not carried onto a right turn 60 m later`() {
        confirmedLeftAt(distanceMeters = 30, nowMs = 1_000)

        // Unnamed streets: only the distance tells that the left turn was passed.
        text(distanceMeters = 85, nowMs = 3_000)

        assertEquals(0, NavGuidanceHub.snapshot(3_000).maneuverGaode)
        assertNull(glass(3_000))
    }

    @Test fun `small countdown jitter inside the approach keeps the arrow`() {
        confirmedLeftAt(distanceMeters = 60, nowMs = 1_000)

        text(distanceMeters = 80, nowMs = 3_000)

        assertEquals(HudProtobufBuilder.SEA_LION_F28_LEFT, glass(3_000))
    }

    @Test fun `one misread screenshot never flips the arrow`() {
        confirmedLeftAt(distanceMeters = 70, nowMs = 1_000)
        val generation = NavGuidanceHub.snapshot(2_200).hudRefreshGeneration

        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_RIGHT, nowMs = 2_200)
        assertEquals(HudProtobufBuilder.SEA_LION_F28_LEFT, glass(2_200))
        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_LEFT, nowMs = 3_300)

        assertEquals(HudProtobufBuilder.SEA_LION_F28_LEFT, glass(3_300))
        assertEquals(generation, NavGuidanceHub.snapshot(3_300).hudRefreshGeneration)
    }

    @Test fun `a real change of direction is applied after two agreeing readings`() {
        confirmedLeftAt(distanceMeters = 70, nowMs = 1_000)

        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_RIGHT, nowMs = 2_200)
        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_RIGHT, nowMs = 3_300)

        assertEquals(HudProtobufBuilder.SEA_LION_F28_RIGHT, glass(3_300))
    }

    @Test fun `an arrow Waze no longer shows is dropped instead of kept`() {
        confirmedLeftAt(distanceMeters = 70, nowMs = 1_000)

        // The icon is captured but cannot be classified (e.g. a fork glyph).
        NavA11yFeed.applyVisualReading(0, nowMs = 2_200)
        assertEquals(HudProtobufBuilder.SEA_LION_F28_LEFT, glass(2_200))
        NavA11yFeed.applyVisualReading(0, nowMs = 3_300)

        assertEquals(0, NavGuidanceHub.snapshot(3_300).maneuverGaode)
        assertNull(glass(3_300))
    }

    @Test fun `distance updates cannot revive an arrow the reader dropped`() {
        confirmedLeftAt(distanceMeters = 90, nowMs = 1_000)
        NavA11yFeed.applyVisualReading(0, nowMs = 2_200)
        NavA11yFeed.applyVisualReading(0, nowMs = 3_300)

        text(distanceMeters = 80, nowMs = 4_000)
        text(distanceMeters = 70, nowMs = 5_000)

        assertNull(glass(5_000))
    }

    @Test fun `readings far apart do not confirm each other`() {
        text(distanceMeters = 60, nowMs = 1_000)

        NavA11yFeed.applyVisualReading(NavManeuverCodes.GAODE_RIGHT, nowMs = 1_100)
        NavA11yFeed.applyVisualReading(
            NavManeuverCodes.GAODE_RIGHT,
            nowMs = 1_100 + VisualManeuverFilter.MAX_READING_GAP_MS + 1,
        )

        assertNull(glass(1_100 + VisualManeuverFilter.MAX_READING_GAP_MS + 1))
    }
}
