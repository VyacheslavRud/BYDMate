package com.bydmate.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WazeNavigationTest {

    @Test fun `coordinate route follows official Waze deep-link contract`() {
        val uri = WazeNavigation.routeTo(50.0755, 14.4378, "com.bydmate.app")
        assertEquals("https", uri.scheme)
        assertEquals("waze.com", uri.host)
        assertEquals("/ul", uri.path)
        assertEquals("50.0755,14.4378", uri.getQueryParameter("ll"))
        assertEquals("yes", uri.getQueryParameter("navigate"))
        assertEquals("com.bydmate.app", uri.getQueryParameter("utm_source"))
    }

    @Test fun `search can either navigate or only show results`() {
        val route = WazeNavigation.search("Prague Castle", true, "com.bydmate.app")
        val results = WazeNavigation.search("Prague Castle", false, "com.bydmate.app")
        assertEquals("Prague Castle", route.getQueryParameter("q"))
        assertEquals("yes", route.getQueryParameter("navigate"))
        assertNull(results.getQueryParameter("navigate"))
    }

    @Test fun `favorites use Waze home and work names`() {
        val uri = WazeNavigation.favorite("home", "com.bydmate.app")
        assertEquals("home", uri.getQueryParameter("favorite"))
        assertEquals("yes", uri.getQueryParameter("navigate"))
    }

    @Test fun `show point does not start navigation`() {
        val uri = WazeNavigation.showPoint(50.0, 14.0, "com.bydmate.app")
        assertEquals("50.0,14.0", uri.getQueryParameter("ll"))
        assertEquals("8", uri.getQueryParameter("z"))
        assertNull(uri.getQueryParameter("navigate"))
    }

    @Test fun `every generated link passes the privileged contract`() {
        val links = listOf(
            WazeNavigation.routeTo(50.0, 14.0, "com.bydmate.app"),
            WazeNavigation.showPoint(50.0, 14.0, "com.bydmate.app"),
            WazeNavigation.search("Dlouhá 1 & Prague", true, "com.bydmate.app"),
            WazeNavigation.favorite("work", "com.bydmate.app"),
        )
        assertTrue(links.all { WazeNavigation.isAllowedDeepLink(it.toString()) })
    }

    @Test fun `builder rejects invalid coordinates blank queries and unknown favorites`() {
        assertThrows(IllegalArgumentException::class.java) {
            WazeNavigation.routeTo(91.0, 14.0, "com.bydmate.app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WazeNavigation.search("   ", true, "com.bydmate.app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WazeNavigation.favorite("school", "com.bydmate.app")
        }
    }

    @Test fun `privileged contract rejects malformed duplicate and mixed targets`() {
        val invalid = listOf(
            "https://waze.com/ul?ll=50,14&utm_source=com.bydmate.app&ll=51,15",
            "https://waze.com/ul?ll=50,14&q=Prague&utm_source=com.bydmate.app",
            "https://waze.com/ul?ll=999,14&utm_source=com.bydmate.app",
            "https://waze.com/ul?q=&utm_source=com.bydmate.app",
            "https://waze.com/ul?favorite=school&navigate=yes&utm_source=com.bydmate.app",
            "https://waze.com/ul?favorite=home&utm_source=com.bydmate.app",
            "https://waze.com/ul?q=Prague&navigate=no&utm_source=com.bydmate.app",
            "https://waze.com/ul?q=Prague&utm_source=",
            "https://waze.com/ul?q=Prague&utm_source=com.bydmate.app&extra=true",
            "https://waze.com/ul?q=Prague&utm_source=com.bydmate.app#fragment",
        )
        assertFalse(invalid.any(WazeNavigation::isAllowedDeepLink))
    }
}
