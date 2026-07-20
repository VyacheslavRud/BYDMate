package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.TaskProjectionQueryResult
import com.bydmate.app.data.vehicle.TaskProjectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleVirtualDisplayRecoveryTest {

    @Test fun `startup policy requires markers and no live projection ownership`() {
        assertTrue(
            shouldRecoverStaleVirtualDisplays(
                ownedDisplayIds = setOf(4),
                mode = ClusterMode.OFF,
                liveDisplayId = -1,
                clusterLabLeaseActive = false,
            ),
        )
        assertFalse(
            shouldRecoverStaleVirtualDisplays(emptySet(), ClusterMode.OFF, -1, false),
        )
        assertFalse(
            shouldRecoverStaleVirtualDisplays(setOf(4), ClusterMode.FULLSCREEN, -1, false),
        )
        assertFalse(
            shouldRecoverStaleVirtualDisplays(setOf(4), ClusterMode.OFF, 4, false),
        )
        assertFalse(
            shouldRecoverStaleVirtualDisplays(setOf(4), ClusterMode.OFF, -1, true),
        )
    }

    @Test fun `confirmed not-running releases every marker independently`() = runTest {
        val releasedAttempts = mutableListOf<Int>()
        val markerClearAttempts = mutableListOf<Int>()

        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(9, 4, 7),
            queryWazeTask = { TaskProjectionQueryResult.NotRunning },
            setTaskFullscreen = { error("task mutation must not run") },
            moveTaskToMain = { error("task mutation must not run") },
            releaseVirtualDisplay = { id ->
                releasedAttempts += id
                id != 7
            },
            clearReleasedMarker = { id ->
                markerClearAttempts += id
                true
            },
        )

        assertTrue(result.taskRecoveryConfirmed)
        assertEquals(listOf(4, 7, 9), releasedAttempts)
        assertEquals(listOf(4, 9), markerClearAttempts)
        assertEquals(setOf(4, 9), result.releaseConfirmedIds)
        assertEquals(setOf(4, 9), result.markerClearedIds)
    }

    @Test fun `unavailable task state retains every marker without release`() = runTest {
        var releaseCalled = false

        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4, 7),
            queryWazeTask = { TaskProjectionQueryResult.Unavailable },
            setTaskFullscreen = { error("must not mutate unavailable task") },
            moveTaskToMain = { error("must not mutate unavailable task") },
            releaseVirtualDisplay = { releaseCalled = true; true },
            clearReleasedMarker = { error("must not clear marker") },
        )

        assertFalse(result.taskRecoveryConfirmed)
        assertFalse(releaseCalled)
        assertTrue(result.releaseConfirmedIds.isEmpty())
        assertTrue(result.markerClearedIds.isEmpty())
    }

    @Test fun `nonzero fullscreen result retains every marker`() = runTest {
        var releaseCalled = false
        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4),
            queryWazeTask = {
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 5))
            },
            setTaskFullscreen = { false },
            moveTaskToMain = { error("move must not follow failed fullscreen") },
            releaseVirtualDisplay = { releaseCalled = true; true },
            clearReleasedMarker = { error("must not clear marker") },
        )

        assertFalse(result.taskRecoveryConfirmed)
        assertFalse(releaseCalled)
    }

    @Test fun `nonzero move result retains every marker`() = runTest {
        val taskStates = ArrayDeque(
            listOf(
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 5)),
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 1)),
            ),
        )
        var releaseCalled = false

        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4),
            queryWazeTask = { taskStates.removeFirst() },
            setTaskFullscreen = { true },
            moveTaskToMain = { false },
            releaseVirtualDisplay = { releaseCalled = true; true },
            clearReleasedMarker = { error("must not clear marker") },
        )

        assertFalse(result.taskRecoveryConfirmed)
        assertFalse(releaseCalled)
    }

    @Test fun `final unavailable verification retains markers after successful mutations`() = runTest {
        val taskStates = ArrayDeque(
            listOf(
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 5)),
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 1)),
                TaskProjectionQueryResult.Unavailable,
            ),
        )
        var releaseCalled = false

        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4),
            queryWazeTask = { taskStates.removeFirst() },
            setTaskFullscreen = { true },
            moveTaskToMain = { true },
            releaseVirtualDisplay = { releaseCalled = true; true },
            clearReleasedMarker = { error("must not clear marker") },
        )

        assertFalse(result.taskRecoveryConfirmed)
        assertFalse(releaseCalled)
    }

    @Test fun `reclaim follows relaunched Waze task id and verifies main fullscreen`() = runTest {
        val taskStates = ArrayDeque(
            listOf(
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 5)),
                TaskProjectionQueryResult.Found(TaskProjectionState(91, 4, 1)),
                TaskProjectionQueryResult.Found(TaskProjectionState(91, 0, 1)),
            ),
        )
        val fullscreenIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Int>()

        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4),
            queryWazeTask = { taskStates.removeFirst() },
            setTaskFullscreen = { id -> fullscreenIds += id; true },
            moveTaskToMain = { id -> movedIds += id; true },
            releaseVirtualDisplay = { true },
            clearReleasedMarker = { true },
        )

        assertTrue(result.taskRecoveryConfirmed)
        assertEquals(listOf(57), fullscreenIds)
        assertEquals(listOf(91), movedIds)
        assertEquals(setOf(4), result.markerClearedIds)
    }

    @Test fun `confirmed release with failed marker commit remains retryable`() = runTest {
        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4),
            queryWazeTask = {
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 0, 1))
            },
            setTaskFullscreen = { error("already fullscreen") },
            moveTaskToMain = { error("already on main") },
            releaseVirtualDisplay = { true },
            clearReleasedMarker = { false },
        )

        assertTrue(result.taskRecoveryConfirmed)
        assertEquals(setOf(4), result.releaseConfirmedIds)
        assertTrue(result.markerClearedIds.isEmpty())
    }

    @Test fun `one release exception does not skip remaining owned displays`() = runTest {
        val attempts = mutableListOf<Int>()
        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4, 7),
            queryWazeTask = { TaskProjectionQueryResult.NotRunning },
            setTaskFullscreen = { error("task is absent") },
            moveTaskToMain = { error("task is absent") },
            releaseVirtualDisplay = { id ->
                attempts += id
                if (id == 4) throw IllegalStateException("daemon rejected id")
                true
            },
            clearReleasedMarker = { true },
        )

        assertEquals(listOf(4, 7), attempts)
        assertEquals(setOf(7), result.releaseConfirmedIds)
        assertEquals(setOf(7), result.markerClearedIds)
    }

    @Test fun `callback exception is treated as unavailable and retains markers`() = runTest {
        val result = recoverPersistedVirtualDisplays(
            ownedDisplayIds = setOf(4),
            queryWazeTask = { throw IllegalStateException("binder died") },
            setTaskFullscreen = { true },
            moveTaskToMain = { true },
            releaseVirtualDisplay = { true },
            clearReleasedMarker = { true },
        )

        assertFalse(result.taskRecoveryConfirmed)
        assertTrue(result.releaseConfirmedIds.isEmpty())
    }
}
