package com.bydmate.app.helper

import com.bydmate.app.navigation.WazeNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HelperDaemonTaskProjectionStateTest {

    @Test fun `daemon allows only exact Waze package`() {
        var lookedUpPackage: String? = null
        val expected = DaemonTaskProjectionState(taskId = 57, displayId = 4, windowingMode = 5)
        val found = DaemonTaskProjectionQueryResult.Found(expected)

        assertEquals(found, getTaskProjectionStateCore(WazeNavigation.PACKAGE_NAME) {
            lookedUpPackage = it
            found
        })
        assertEquals(WazeNavigation.PACKAGE_NAME, lookedUpPackage)
    }

    @Test fun `daemon rejects every non Waze spelling without invoking task lookup`() {
        var lookupCalled = false
        val lookup: (String) -> DaemonTaskProjectionQueryResult = {
            lookupCalled = true
            DaemonTaskProjectionQueryResult.Found(DaemonTaskProjectionState(1, 2, 5))
        }

        listOf(
            "",
            "com.waze ",
            "COM.WAZE",
            "com.waze.beta",
            "com.google.android.apps.maps",
            "com.bydmate.app.dev",
        ).forEach {
            assertEquals(
                DaemonTaskProjectionQueryResult.Unavailable,
                getTaskProjectionStateCore(it, lookup),
            )
        }
        assertFalse(lookupCalled)
    }

    @Test fun `daemon preserves not-running and unavailable as distinct results`() {
        val pkg = WazeNavigation.PACKAGE_NAME
        assertEquals(
            DaemonTaskProjectionQueryResult.NotRunning,
            getTaskProjectionStateCore(pkg) { DaemonTaskProjectionQueryResult.NotRunning },
        )
        assertEquals(
            DaemonTaskProjectionQueryResult.Unavailable,
            getTaskProjectionStateCore(pkg) { DaemonTaskProjectionQueryResult.Unavailable },
        )
    }

    @Test fun `daemon maps structurally invalid found states to unavailable`() {
        val pkg = WazeNavigation.PACKAGE_NAME
        listOf(
            DaemonTaskProjectionState(0, 4, 5),
            DaemonTaskProjectionState(57, -1, 5),
            DaemonTaskProjectionState(57, 4, 0),
        ).forEach { state ->
            assertEquals(
                DaemonTaskProjectionQueryResult.Unavailable,
                getTaskProjectionStateCore(pkg) {
                    DaemonTaskProjectionQueryResult.Found(state)
                },
            )
        }
    }
}
