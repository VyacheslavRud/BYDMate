package com.bydmate.app.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperDaemonWazeDeepLinkTest {

    @Test fun `accepts only official Waze route search show and favorite links`() {
        assertTrue(isAllowedWazeDeepLink(
            "https://waze.com/ul?utm_source=com.bydmate.app.dev&ll=50.0%2C14.0&navigate=yes"))
        assertTrue(isAllowedWazeDeepLink(
            "https://waze.com/ul?utm_source=com.bydmate.app.dev&q=Prague%20Castle&navigate=yes"))
        assertTrue(isAllowedWazeDeepLink(
            "https://waze.com/ul?utm_source=com.bydmate.app.dev&favorite=home&navigate=yes"))
        assertTrue(isAllowedWazeDeepLink(
            "https://waze.com/ul?utm_source=com.bydmate.app.dev&ll=50.0%2C14.0&z=8"))
    }

    @Test fun `rejects non Waze and privilege-expanding URI shapes`() {
        assertFalse(isAllowedWazeDeepLink("http://waze.com/ul?ll=50,14"))
        assertFalse(isAllowedWazeDeepLink("https://waze.com.evil.test/ul?ll=50,14"))
        assertFalse(isAllowedWazeDeepLink("https://waze.com/other?ll=50,14"))
        assertFalse(isAllowedWazeDeepLink("https://waze.com/ul?url=https://evil.test"))
        assertFalse(isAllowedWazeDeepLink("https://user@waze.com/ul?ll=50,14"))
        assertFalse(isAllowedWazeDeepLink("https://waze.com/ul?utm_source=com.bydmate.app"))
    }
}
