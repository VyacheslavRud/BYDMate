package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavA11yFeedTest {

    private val contentChanged = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    private val stateChanged = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    private val windowsChanged = AccessibilityEvent.TYPE_WINDOWS_CHANGED
    private val textChanged = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    private val clicked = AccessibilityEvent.TYPE_VIEW_CLICKED

    @Test fun `Waze content change passes`() {
        assertTrue(NavA11yFeed.shouldProcess("com.waze", contentChanged, nowMs = 1000, lastMs = 0))
        assertTrue(NavA11yFeed.shouldProcess("com.waze", stateChanged, nowMs = 1000, lastMs = 0))
        assertTrue(NavA11yFeed.shouldProcess("com.waze", textChanged, nowMs = 1000, lastMs = 0))
    }

    @Test fun `foreign package filtered`() {
        assertFalse(NavA11yFeed.shouldProcess("com.android.systemui", contentChanged, nowMs = 1000, lastMs = 0))
        assertFalse(NavA11yFeed.shouldProcess(null, contentChanged, nowMs = 1000, lastMs = 0))
    }

    @Test fun `irrelevant event types filtered`() {
        assertFalse(NavA11yFeed.shouldProcess("com.waze", clicked, nowMs = 1000, lastMs = 0))
    }

    @Test fun `window topology change probes only while route is already active`() {
        assertFalse(
            NavA11yFeed.shouldProcess(
                null,
                windowsChanged,
                nowMs = 1_000,
                lastMs = 0,
                allowWindowProbe = false,
            ),
        )
        assertTrue(
            NavA11yFeed.shouldProcess(
                null,
                windowsChanged,
                nowMs = 1_000,
                lastMs = 0,
                allowWindowProbe = true,
            ),
        )
    }

    @Test fun `debounce blocks rapid events`() {
        assertFalse(NavA11yFeed.shouldProcess("com.waze", contentChanged, nowMs = 1400, lastMs = 1000))
        assertTrue(NavA11yFeed.shouldProcess("com.waze", contentChanged, nowMs = 1500, lastMs = 1000))
    }
}
