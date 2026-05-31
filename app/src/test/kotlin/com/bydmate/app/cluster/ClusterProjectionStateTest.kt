package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterProjectionStateTest {

    @Test fun `cycle order is OFF MINI FULLSCREEN OFF`() {
        assertEquals(ClusterMode.MINI, ClusterMode.OFF.next())
        assertEquals(ClusterMode.FULLSCREEN, ClusterMode.MINI.next())
        assertEquals(ClusterMode.OFF, ClusterMode.FULLSCREEN.next())
    }

    @Test fun `fullscreen geometry fills the whole cluster`() {
        assertEquals(
            ClusterGeometry(width = 1280, height = 480, xOffset = 0, yOffset = 0),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480),
        )
    }

    @Test fun `mini geometry is a centered card of the starting width`() {
        assertEquals(
            ClusterGeometry(width = MINI_WIDTH, height = 480, xOffset = (1280 - MINI_WIDTH) / 2, yOffset = 0),
            geometryFor(ClusterMode.MINI, 1280, 480),
        )
    }

    @Test fun `mini width is clamped to a narrow cluster`() {
        assertEquals(
            ClusterGeometry(width = 480, height = 480, xOffset = 0, yOffset = 0),
            geometryFor(ClusterMode.MINI, 480, 480),
        )
    }

    @Test fun `OFF has no geometry`() {
        assertNull(geometryFor(ClusterMode.OFF, 1280, 480))
    }

    @Test fun `cycle trigger is right-star short press only`() {
        assertTrue(isClusterCycleTrigger(RIGHT_STAR_KEYCODE, action = 0, isLongPress = false, repeatCount = 0))
        assertFalse(isClusterCycleTrigger(RIGHT_STAR_KEYCODE, action = 0, isLongPress = true, repeatCount = 0))  // long
        assertFalse(isClusterCycleTrigger(RIGHT_STAR_KEYCODE, action = 0, isLongPress = false, repeatCount = 1)) // repeat
        assertFalse(isClusterCycleTrigger(RIGHT_STAR_KEYCODE, action = 1, isLongPress = false, repeatCount = 0)) // ACTION_UP
        assertFalse(isClusterCycleTrigger(305, action = 0, isLongPress = false, repeatCount = 0))                // other key
    }

    @Test fun `isMappedButton matches only the right star`() {
        assertTrue(isMappedButton(RIGHT_STAR_KEYCODE))
        assertFalse(isMappedButton(305))
    }
}
