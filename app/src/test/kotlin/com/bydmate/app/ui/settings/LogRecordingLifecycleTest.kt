package com.bydmate.app.ui.settings

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRecordingLifecycleTest {

    @Test
    fun `second start is rejected until active session finishes stopping`() {
        val lifecycle = LogRecordingLifecycle<String>()
        val token = assertNotNullValue(lifecycle.beginStart())
        val startJob = Job()
        val autoStopJob = Job()

        assertTrue(lifecycle.attachStartJob(token, startJob))
        assertNull(lifecycle.beginStart())
        assertTrue(lifecycle.activate(token, "session"))
        assertTrue(lifecycle.attachAutoStopJob(token, autoStopJob))
        assertNull(lifecycle.beginStart())

        val resources = assertNotNullValue(lifecycle.requestStop())
        assertSame(startJob, resources.startJob)
        assertSame(autoStopJob, resources.autoStopJob)
        assertTrue(resources.session == "session")
        assertNull(lifecycle.beginStart())

        assertTrue(lifecycle.finishStop(token))
        assertNotNull(lifecycle.beginStart())
    }

    @Test
    fun `stale token cannot control a newer start`() {
        val lifecycle = LogRecordingLifecycle<String>()
        val oldToken = assertNotNullValue(lifecycle.beginStart())
        assertNotNull(lifecycle.requestStop(oldToken))
        assertTrue(lifecycle.finishStop(oldToken))

        val currentToken = assertNotNullValue(lifecycle.beginStart())

        assertFalse(lifecycle.activate(oldToken, "old"))
        assertNull(lifecycle.requestStop(oldToken))
        assertTrue(lifecycle.isStarting(currentToken))
        assertTrue(lifecycle.activate(currentToken, "current"))
    }

    @Test
    fun `state callbacks only run for matching active and stopping tokens`() {
        val lifecycle = LogRecordingLifecycle<String>()
        val token = assertNotNullValue(lifecycle.beginStart())
        var activeUpdates = 0
        var stopUpdates = 0

        assertTrue(lifecycle.activate(token, "session"))
        assertTrue(lifecycle.runIfActive(token) { activeUpdates++ })
        assertNotNull(lifecycle.requestStop(token))
        assertFalse(lifecycle.runIfActive(token) { activeUpdates++ })
        assertTrue(lifecycle.finishStop(token) { stopUpdates++ })
        assertFalse(lifecycle.finishStop(token) { stopUpdates++ })

        assertTrue(activeUpdates == 1)
        assertTrue(stopUpdates == 1)
    }

    @Test
    fun `clear returns resources and permanently rejects new work`() {
        val lifecycle = LogRecordingLifecycle<String>()
        val token = assertNotNullValue(lifecycle.beginStart())
        val startJob = Job()
        val autoStopJob = Job()
        lifecycle.attachStartJob(token, startJob)
        lifecycle.activate(token, "session")
        lifecycle.attachAutoStopJob(token, autoStopJob)

        val resources = assertNotNullValue(lifecycle.clear())

        assertSame(startJob, resources.startJob)
        assertSame(autoStopJob, resources.autoStopJob)
        assertTrue(resources.session == "session")
        assertNull(lifecycle.beginStart())
        assertFalse(lifecycle.runIfActive(token) {})
        assertNull(lifecycle.clear())
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
