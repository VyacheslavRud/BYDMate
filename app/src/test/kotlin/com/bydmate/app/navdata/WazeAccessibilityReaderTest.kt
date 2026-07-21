package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeAccessibilityReaderTest {

    @Test fun `Waze accessibility event exposes a maneuver missed by the later window read`() {
        val event = mockk<AccessibilityEvent>(relaxed = true) {
            every { packageName } returns "com.waze"
            every { text } returns listOf("TURN_RIGHT")
            every { contentDescription } returns null
            every { source } returns null
        }

        assertEquals(
            NavManeuverCodes.GAODE_RIGHT,
            WazeAccessibilityReader.maneuverFromEvent(event),
        )
    }

    @Test fun `foreign accessibility event cannot provide a maneuver`() {
        val event = mockk<AccessibilityEvent>(relaxed = true) {
            every { packageName } returns "com.android.systemui"
            every { text } returns listOf("TURN_LEFT")
        }

        assertEquals(0, WazeAccessibilityReader.maneuverFromEvent(event))
    }

    @Test fun `Waze accessibility event can expose maneuver only as content description`() {
        val event = mockk<AccessibilityEvent>(relaxed = true) {
            every { packageName } returns "com.waze"
            every { text } returns emptyList()
            every { contentDescription } returns "TURN_LEFT"
            every { source } returns null
        }

        assertEquals(
            NavManeuverCodes.GAODE_LEFT,
            WazeAccessibilityReader.maneuverFromEvent(event),
        )
    }

    private fun fields(
        maneuver: String? = null,
        distance: String? = null,
        street: String? = null,
        remainingDistance: String? = null,
        remainingTime: String? = null,
        arrivalTime: String? = null,
    ) = WazeAccessibilityReader.Fields(
        maneuver = maneuver,
        maneuverDistance = distance,
        street = street,
        remainingDistance = remainingDistance,
        remainingTime = remainingTime,
        arrivalTime = arrivalTime,
        speedLimit = null,
        exitNumber = null,
    )

    @Test fun `guidance score prefers complete route window over stale short arrow`() {
        val shortArrow = WazeAccessibilityReader.guidanceScore(
            fields(maneuver = "LEFT"),
            hasAnchor = true,
        )
        val completeRoute = WazeAccessibilityReader.guidanceScore(
            fields(
                maneuver = "Turn right",
                distance = "350 m",
                street = "Main Street",
                remainingDistance = "12 km",
                remainingTime = "18 min",
                arrivalTime = "15:40",
            ),
            hasAnchor = true,
        )

        assertTrue(completeRoute > shortArrow)
    }

    @Test fun `anchor-only surface remains a low priority candidate`() {
        assertEquals(0, WazeAccessibilityReader.guidanceScore(null, hasAnchor = false))
        assertEquals(1, WazeAccessibilityReader.guidanceScore(null, hasAnchor = true))
        assertTrue(WazeAccessibilityReader.guidanceScore(
            fields(maneuver = "Turn right"),
            hasAnchor = true,
        ) > 1)
    }
}
