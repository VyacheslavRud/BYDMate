package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRouteSamplingTest {

    @Test
    fun `short route is returned unchanged`() {
        val points = points(12)

        assertSame(points, sampleRoutePoints(points))
    }

    @Test
    fun `long route preserves endpoints and respects render cap`() {
        val points = points(2_001)
        val sampled = sampleRoutePoints(points)

        assertEquals(MAX_ROUTE_RENDER_POINTS, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
    }

    @Test
    fun `sampled route remains strictly ordered`() {
        val sampled = sampleRoutePoints(points(10_000))

        assertTrue(sampled.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp })
    }

    @Test
    fun `sampling is deterministic`() {
        val points = points(1_777)

        assertEquals(sampleRoutePoints(points), sampleRoutePoints(points))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `render cap below two is rejected`() {
        sampleRoutePoints(points(3), maxPoints = 1)
    }

    private fun points(count: Int): List<TripPointEntity> = List(count) { index ->
        TripPointEntity(
            id = index.toLong(),
            tripId = 1L,
            timestamp = index.toLong(),
            lat = 50.0 + index / 100_000.0,
            lon = 14.0 + index / 100_000.0,
            speedKmh = (index % 130).toDouble(),
        )
    }
}
