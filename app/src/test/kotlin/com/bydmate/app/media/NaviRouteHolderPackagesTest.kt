package com.bydmate.app.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NaviRouteHolderPackagesTest {

    @After fun cleanup() {
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
    }

    @Test fun `Waze package accepted`() {
        NaviRouteHolder.update("com.waze", "5 км", "направо", null, 1000L)
        assertNotNull(NaviRouteHolder.latest)
        assertEquals("5 км", NaviRouteHolder.latest?.title)
    }

    @Test fun `Waze package clears holder`() {
        NaviRouteHolder.update("com.waze", "5 км", null, null, 1000L)
        NaviRouteHolder.clear("com.waze")
        assertNull(NaviRouteHolder.latest)
    }

    @Test fun `legacy Yandex package is rejected`() {
        NaviRouteHolder.update("ru.yandex.yandexnavi", "5 км", null, null, 1000L)
        assertNull(NaviRouteHolder.latest)
    }

    @Test fun `foreign package still rejected`() {
        NaviRouteHolder.update("com.spotify.music", "x", "y", null, 1000L)
        assertNull(NaviRouteHolder.latest)
    }
}
