package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.TaskProjectionQueryResult

/** Pure verdict used by the on-car lab and by local fake/replay tests. */
internal data class ClusterProjectionVerification(
    val failure: ClusterLabFailure? = null,
    val detail: String,
) {
    val passed: Boolean get() = failure == null
}

/** Converts the production pipeline's stable raw reason into an exportable lab result. */
internal fun clusterProjectionFailureFor(
    rawFailure: String?,
    transition: ClusterLabProjectionTransitionResult? = null,
): ClusterLabFailure = when (rawFailure) {
    "daemon" -> ClusterLabFailure.HELPER_UNAVAILABLE
    "overlay_permission" -> ClusterLabFailure.OVERLAY_PERMISSION_UNAVAILABLE
    "compositor_marker" -> ClusterLabFailure.COMPOSITOR_MARKER_WRITE_FAILED
    "container_on_rejected" -> ClusterLabFailure.CONTAINER_ON_REJECTED
    "display_not_found" -> when {
        transition?.autoContainerRequested == true &&
            transition.autoContainerMarkerWritten == false ->
            ClusterLabFailure.COMPOSITOR_MARKER_WRITE_FAILED
        transition?.autoContainerRequested == true &&
            transition.autoContainerCommandAccepted == false ->
            ClusterLabFailure.CONTAINER_ON_REJECTED
        transition?.autoContainerRequested == true ->
            ClusterLabFailure.CLUSTER_DISPLAY_DID_NOT_APPEAR
        else -> ClusterLabFailure.CLUSTER_DISPLAY_NOT_FOUND
    }
    "geometry" -> ClusterLabFailure.INVALID_GEOMETRY
    "surface_timeout" -> ClusterLabFailure.OVERLAY_SURFACE_TIMEOUT
    "stale_display_release" -> ClusterLabFailure.STALE_DISPLAY_RELEASE_FAILED
    "factory_reboot_required" -> ClusterLabFailure.FACTORY_REBOOT_REQUIRED
    "public_virtual_display_unavailable" -> ClusterLabFailure.PUBLIC_VIRTUAL_DISPLAY_REQUIRED
    "virtual_display" -> ClusterLabFailure.VIRTUAL_DISPLAY_CREATE_FAILED
    "virtual_display_marker" -> ClusterLabFailure.VIRTUAL_DISPLAY_MARKER_WRITE_FAILED
    "task_launch" -> ClusterLabFailure.WAZE_TASK_LAUNCH_FAILED
    "exception" -> ClusterLabFailure.PROJECTION_EXCEPTION
    "display_removed" -> ClusterLabFailure.CLUSTER_DISPLAY_REMOVED
    "surface_destroyed" -> ClusterLabFailure.PROJECTION_SURFACE_DESTROYED
    "lab_lease_not_owned" -> ClusterLabFailure.LAB_LEASE_LOST
    else -> ClusterLabFailure.PROJECTION_FAILED
}

/** A successful transition is not enough: Waze must really be on the display the path created. */
internal fun verifyClusterProjectionStart(
    transition: ClusterLabProjectionTransitionResult,
    task: TaskProjectionQueryResult,
): ClusterProjectionVerification {
    if (!transition.success) {
        val failure = clusterProjectionFailureFor(transition.failure, transition)
        return ClusterProjectionVerification(
            failure,
            clusterProjectionVerdictDetail("activation", transition, task, failure),
        )
    }
    if (transition.renderPath == null || transition.projectedTaskDisplayId == null) {
        val failure = ClusterLabFailure.RENDER_PATH_UNKNOWN
        return ClusterProjectionVerification(
            failure,
            clusterProjectionVerdictDetail("activation", transition, task, failure),
        )
    }
    if (!transition.runtimeResourcesActive) {
        val failure = ClusterLabFailure.PROJECTION_RESOURCES_MISSING
        return ClusterProjectionVerification(
            failure,
            clusterProjectionVerdictDetail("activation", transition, task, failure),
        )
    }
    val failure = when (task) {
        TaskProjectionQueryResult.Unavailable -> ClusterLabFailure.TASK_STATE_UNAVAILABLE
        TaskProjectionQueryResult.NotRunning -> ClusterLabFailure.WAZE_TASK_NOT_RUNNING
        is TaskProjectionQueryResult.Found -> when {
            task.state.displayId == 0 -> ClusterLabFailure.TASK_STAYED_ON_MAIN_DISPLAY
            task.state.displayId != transition.projectedTaskDisplayId ->
                ClusterLabFailure.TASK_ON_WRONG_DISPLAY
            else -> null
        }
    }
    return ClusterProjectionVerification(
        failure,
        clusterProjectionVerdictDetail("activation", transition, task, failure),
    )
}

/** OFF is complete only after the task and every durable ownership marker are restored. */
internal fun verifyClusterProjectionCleanup(
    transition: ClusterLabProjectionTransitionResult?,
    task: TaskProjectionQueryResult,
    ownershipMarkersClear: Boolean,
): ClusterProjectionVerification {
    val failure = when {
        transition?.success != true -> ClusterLabFailure.CLEANUP_TRANSITION_FAILED
        task == TaskProjectionQueryResult.Unavailable ->
            ClusterLabFailure.CLEANUP_TASK_STATE_UNAVAILABLE
        task is TaskProjectionQueryResult.Found &&
            (task.state.displayId != 0 || task.state.windowingMode != TASK_WINDOWING_MODE_FULLSCREEN) ->
            ClusterLabFailure.WAZE_NOT_RETURNED_TO_MAIN_DISPLAY
        !ownershipMarkersClear -> ClusterLabFailure.OWNERSHIP_NOT_CLEARED
        transition.runtimeResourcesActive ->
            ClusterLabFailure.RUNTIME_RESOURCES_NOT_RELEASED
        else -> null
    }
    return ClusterProjectionVerification(
        failure,
        clusterProjectionVerdictDetail(
            stage = "cleanup",
            transition = transition,
            task = task,
            failure = failure,
            ownershipMarkersClear = ownershipMarkersClear,
        ),
    )
}

internal fun clusterProjectionVerdictDetail(
    stage: String,
    transition: ClusterLabProjectionTransitionResult?,
    task: TaskProjectionQueryResult,
    failure: ClusterLabFailure?,
    ownershipMarkersClear: Boolean? = null,
): String = buildString {
    append("stage=$stage pass=${failure == null} failure=${failure ?: "NONE"} ")
    append("transitionSuccess=${transition?.success} rawFailure=${transition?.failure} ")
    append("phase=${transition?.phase} selectedDisplay=${transition?.selectedDisplay?.id} ")
    append("renderPath=${transition?.renderPath} expectedTaskDisplay=")
    append(transition?.projectedTaskDisplayId)
    append(" autoContainerRequested=${transition?.autoContainerRequested} ")
    append("containerMarkerWritten=${transition?.autoContainerMarkerWritten} ")
    append("containerCommandAccepted=${transition?.autoContainerCommandAccepted} ")
    append("runtimeResourcesActive=${transition?.runtimeResourcesActive} ")
    when (task) {
        is TaskProjectionQueryResult.Found -> {
            append("taskStatus=FOUND taskId=${task.state.taskId} ")
            append("actualTaskDisplay=${task.state.displayId} ")
            append("windowingMode=${task.state.windowingMode}")
        }
        TaskProjectionQueryResult.NotRunning -> append("taskStatus=NOT_RUNNING")
        TaskProjectionQueryResult.Unavailable -> append("taskStatus=UNAVAILABLE")
    }
    ownershipMarkersClear?.let { append(" ownershipMarkersClear=$it") }
}

internal const val TASK_WINDOWING_MODE_FULLSCREEN = 1
