package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.TaskProjectionQueryResult
import kotlinx.coroutines.CancellationException

internal data class StaleVirtualDisplayRecoveryResult(
    val taskRecoveryConfirmed: Boolean,
    val releaseConfirmedIds: Set<Int> = emptySet(),
    val markerClearedIds: Set<Int> = emptySet(),
)

/** Startup recovery must never touch a live projection or a parked Cluster Lab session. */
internal fun shouldRecoverStaleVirtualDisplays(
    ownedDisplayIds: Set<Int>,
    mode: ClusterMode,
    liveDisplayId: Int,
    clusterLabLeaseActive: Boolean,
): Boolean = ownedDisplayIds.isNotEmpty() &&
    mode == ClusterMode.OFF &&
    liveDisplayId == -1 &&
    !clusterLabLeaseActive

/**
 * Crash recovery for daemon-owned VirtualDisplays.
 *
 * Reclaiming the exact Waze task is a hard prerequisite for releasing any display. A boolean
 * mutation failure or an unavailable verification retains every marker for the next startup.
 * Once task recovery is proven, every persisted display is attempted independently: one failed
 * release cannot prevent cleanup of the remaining owned displays.
 */
internal suspend fun recoverPersistedVirtualDisplays(
    ownedDisplayIds: Set<Int>,
    queryWazeTask: suspend () -> TaskProjectionQueryResult,
    setTaskFullscreen: suspend (taskId: Int) -> Boolean,
    moveTaskToMain: suspend (taskId: Int) -> Boolean,
    releaseVirtualDisplay: suspend (displayId: Int) -> Boolean,
    clearReleasedMarker: suspend (displayId: Int) -> Boolean,
): StaleVirtualDisplayRecoveryResult {
    val taskRecovered = reclaimWazeTask(
        queryWazeTask = queryWazeTask,
        setTaskFullscreen = setTaskFullscreen,
        moveTaskToMain = moveTaskToMain,
    )
    if (!taskRecovered) return StaleVirtualDisplayRecoveryResult(taskRecoveryConfirmed = false)

    val released = linkedSetOf<Int>()
    val cleared = linkedSetOf<Int>()
    ownedDisplayIds.filter { it >= 0 }.sorted().forEach { displayId ->
        if (safeBooleanCall { releaseVirtualDisplay(displayId) }) {
            released += displayId
            if (safeBooleanCall { clearReleasedMarker(displayId) }) cleared += displayId
        }
    }
    return StaleVirtualDisplayRecoveryResult(
        taskRecoveryConfirmed = true,
        releaseConfirmedIds = released,
        markerClearedIds = cleared,
    )
}

private suspend fun reclaimWazeTask(
    queryWazeTask: suspend () -> TaskProjectionQueryResult,
    setTaskFullscreen: suspend (taskId: Int) -> Boolean,
    moveTaskToMain: suspend (taskId: Int) -> Boolean,
): Boolean {
    var task = safeTaskQuery(queryWazeTask)
    if (isWazeTaskReclaimed(task)) return true
    var found = task as? TaskProjectionQueryResult.Found ?: return false

    if (found.state.windowingMode != WINDOWING_MODE_FULLSCREEN) {
        if (!safeBooleanCall { setTaskFullscreen(found.state.taskId) }) return false
        task = safeTaskQuery(queryWazeTask)
        if (isWazeTaskReclaimed(task)) return true
        found = task as? TaskProjectionQueryResult.Found ?: return false
    }

    if (found.state.displayId != MAIN_DISPLAY_ID) {
        if (!safeBooleanCall { moveTaskToMain(found.state.taskId) }) return false
        task = safeTaskQuery(queryWazeTask)
    }
    return isWazeTaskReclaimed(task)
}

private fun isWazeTaskReclaimed(result: TaskProjectionQueryResult): Boolean = when (result) {
    is TaskProjectionQueryResult.Found ->
        result.state.displayId == MAIN_DISPLAY_ID &&
            result.state.windowingMode == WINDOWING_MODE_FULLSCREEN
    TaskProjectionQueryResult.NotRunning -> true
    TaskProjectionQueryResult.Unavailable -> false
}

private suspend fun safeTaskQuery(
    query: suspend () -> TaskProjectionQueryResult,
): TaskProjectionQueryResult = try {
    query()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    TaskProjectionQueryResult.Unavailable
}

private suspend fun safeBooleanCall(call: suspend () -> Boolean): Boolean = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

private const val MAIN_DISPLAY_ID = 0
private const val WINDOWING_MODE_FULLSCREEN = 1
