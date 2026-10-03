package com.bydmate.app.navdata

import com.bydmate.app.navdata.VisualManeuverFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualManeuverFilterTest {
    private val left = NavManeuverCodes.GAODE_LEFT
    private val right = NavManeuverCodes.GAODE_RIGHT

    @Test fun `reading equal to the shown maneuver is a confirmation`() {
        val filter = VisualManeuverFilter()
        assertEquals(Decision.Confirmed, filter.onReading(left, current = left, nowMs = 1_000))
    }

    @Test fun `new direction needs two consecutive agreeing readings`() {
        val filter = VisualManeuverFilter()
        assertEquals(Decision.Pending(right), filter.onReading(right, current = left, nowMs = 1_000))
        assertEquals(Decision.Apply(right), filter.onReading(right, current = left, nowMs = 2_000))
    }

    @Test fun `first arrow of a segment also waits for agreement`() {
        val filter = VisualManeuverFilter()
        assertEquals(Decision.Pending(left), filter.onReading(left, current = 0, nowMs = 1_000))
        assertEquals(Decision.Apply(left), filter.onReading(left, current = 0, nowMs = 2_000))
    }

    @Test fun `an interleaved confirmation of the shown arrow cancels the candidate`() {
        val filter = VisualManeuverFilter()
        filter.onReading(right, current = left, nowMs = 1_000)
        assertEquals(Decision.Confirmed, filter.onReading(left, current = left, nowMs = 2_000))
        assertEquals(Decision.Pending(right), filter.onReading(right, current = left, nowMs = 3_000))
    }

    @Test fun `alternating misreads never apply`() {
        val filter = VisualManeuverFilter()
        val straight = NavManeuverCodes.GAODE_STRAIGHT
        listOf(right, straight, right, straight).forEachIndexed { index, reading ->
            val decision = filter.onReading(reading, current = left, nowMs = 1_000L + index * 1_000L)
            assertEquals(Decision.Pending(reading), decision)
        }
    }

    @Test fun `two unrecognized readings drop the shown arrow`() {
        val filter = VisualManeuverFilter()
        assertEquals(Decision.Pending(0), filter.onReading(0, current = left, nowMs = 1_000))
        assertEquals(Decision.Clear, filter.onReading(0, current = left, nowMs = 2_000))
    }

    @Test fun `an unrecognized reading between recognized ones does not drop the arrow`() {
        val filter = VisualManeuverFilter()
        filter.onReading(0, current = left, nowMs = 1_000)
        assertEquals(Decision.Confirmed, filter.onReading(left, current = left, nowMs = 2_000))
        assertEquals(Decision.Pending(0), filter.onReading(0, current = left, nowMs = 3_000))
    }

    @Test fun `unrecognized readings without a shown arrow change nothing`() {
        val filter = VisualManeuverFilter()
        assertEquals(Decision.Confirmed, filter.onReading(0, current = 0, nowMs = 1_000))
        assertEquals(Decision.Confirmed, filter.onReading(0, current = 0, nowMs = 2_000))
    }

    @Test fun `stale readings are not consecutive`() {
        val filter = VisualManeuverFilter()
        filter.onReading(right, current = left, nowMs = 1_000)
        val late = 1_000 + VisualManeuverFilter.MAX_READING_GAP_MS + 1
        assertEquals(Decision.Pending(right), filter.onReading(right, current = left, nowMs = late))
    }
}
