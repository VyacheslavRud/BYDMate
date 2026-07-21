package com.bydmate.app.media

import com.bydmate.app.navdata.NavManeuverCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazeRemoteViewsManeuverReaderTest {
    @Test fun `Waze image resource maps explicit right maneuver`() {
        val selected = WazeRemoteViewsManeuverReader.selectManeuver(
            listOf(
                WazeRemoteViewsManeuverReader.ResourceCandidate(
                    viewName = "next_maneuver_icon",
                    resourceName = "ic_turn_right_24",
                ),
            ),
        )

        assertEquals(NavManeuverCodes.GAODE_RIGHT, selected?.first)
        assertEquals("ic_turn_right_24", selected?.second)
    }

    @Test fun `generic chevron is not accepted as driving direction`() {
        assertEquals(
            0,
            WazeRemoteViewsManeuverReader.semanticResourceManeuver("ic_chevron_right"),
        )
    }

    @Test fun `conflicting image resources are rejected instead of guessed`() {
        val selected = WazeRemoteViewsManeuverReader.selectManeuver(
            listOf(
                WazeRemoteViewsManeuverReader.ResourceCandidate("maneuver", "turn_left"),
                WazeRemoteViewsManeuverReader.ResourceCandidate("maneuver", "turn_right"),
            ),
        )

        assertNull(selected)
    }

    @Test fun `resource-side maneuver wins over generic view-side evidence`() {
        val selected = WazeRemoteViewsManeuverReader.selectManeuver(
            listOf(
                WazeRemoteViewsManeuverReader.ResourceCandidate(
                    viewName = "turn_left",
                    resourceName = "ic_turn_right",
                ),
            ),
        )

        assertEquals(NavManeuverCodes.GAODE_RIGHT, selected?.first)
    }
}
