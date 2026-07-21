package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.TaskProjectionQueryResult
import com.bydmate.app.data.vehicle.TaskProjectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterProjectionContractTest {
    @Test fun `direct path passes only when Waze is on the selected physical display`() {
        val transition = transition(
            renderPath = ClusterProjectionRenderPath.DIRECT,
            projectedTaskDisplayId = 4,
        )

        val result = verifyClusterProjectionStart(transition, found(displayId = 4, windowingMode = 5))

        assertTrue(result.passed)
        assertNull(result.failure)
        assertTrue(result.detail.contains("renderPath=DIRECT"))
        assertTrue(result.detail.contains("actualTaskDisplay=4"))
    }

    @Test fun `virtual display path passes when Waze is on the daemon display`() {
        val transition = transition(
            renderPath = ClusterProjectionRenderPath.VIRTUAL_DISPLAY,
            projectedTaskDisplayId = 11,
        )

        assertTrue(verifyClusterProjectionStart(transition, found(displayId = 11)).passed)
    }

    @Test fun `successful transition without a recorded path is rejected`() {
        assertEquals(
            ClusterLabFailure.RENDER_PATH_UNKNOWN,
            verifyClusterProjectionStart(transition(), found(displayId = 4)).failure,
        )
    }

    @Test fun `successful transition without live projection resources is rejected`() {
        val transition = transition(
            renderPath = ClusterProjectionRenderPath.DIRECT,
            projectedTaskDisplayId = 4,
            runtimeResourcesActive = false,
        )

        assertEquals(
            ClusterLabFailure.PROJECTION_RESOURCES_MISSING,
            verifyClusterProjectionStart(transition, found(displayId = 4)).failure,
        )
    }

    @Test fun `task query states produce distinct failures`() {
        val transition = transition(
            renderPath = ClusterProjectionRenderPath.DIRECT,
            projectedTaskDisplayId = 4,
        )
        assertEquals(
            ClusterLabFailure.TASK_STATE_UNAVAILABLE,
            verifyClusterProjectionStart(transition, TaskProjectionQueryResult.Unavailable).failure,
        )
        assertEquals(
            ClusterLabFailure.WAZE_TASK_NOT_RUNNING,
            verifyClusterProjectionStart(transition, TaskProjectionQueryResult.NotRunning).failure,
        )
        assertEquals(
            ClusterLabFailure.TASK_STAYED_ON_MAIN_DISPLAY,
            verifyClusterProjectionStart(transition, found(displayId = 0)).failure,
        )
        assertEquals(
            ClusterLabFailure.TASK_ON_WRONG_DISPLAY,
            verifyClusterProjectionStart(transition, found(displayId = 8)).failure,
        )
    }

    @Test fun `forced container failure distinguishes command rejection from missing display`() {
        val rejected = transition(
            success = false,
            rawFailure = "display_not_found",
            autoContainerRequested = true,
            autoContainerMarkerWritten = true,
            autoContainerCommandAccepted = false,
        )
        val acceptedButMissing = rejected.copy(autoContainerCommandAccepted = true)

        assertEquals(
            ClusterLabFailure.CONTAINER_ON_REJECTED,
            verifyClusterProjectionStart(rejected, TaskProjectionQueryResult.NotRunning).failure,
        )
        assertEquals(
            ClusterLabFailure.CLUSTER_DISPLAY_DID_NOT_APPEAR,
            verifyClusterProjectionStart(acceptedButMissing, TaskProjectionQueryResult.NotRunning).failure,
        )
    }

    @Test fun `all stable production raw failures have specific lab codes`() {
        val expected = mapOf(
            "daemon" to ClusterLabFailure.HELPER_UNAVAILABLE,
            "overlay_permission" to ClusterLabFailure.OVERLAY_PERMISSION_UNAVAILABLE,
            "compositor_marker" to ClusterLabFailure.COMPOSITOR_MARKER_WRITE_FAILED,
            "container_on_rejected" to ClusterLabFailure.CONTAINER_ON_REJECTED,
            "geometry" to ClusterLabFailure.INVALID_GEOMETRY,
            "surface_timeout" to ClusterLabFailure.OVERLAY_SURFACE_TIMEOUT,
            "stale_display_release" to ClusterLabFailure.STALE_DISPLAY_RELEASE_FAILED,
            "virtual_display" to ClusterLabFailure.VIRTUAL_DISPLAY_CREATE_FAILED,
            "virtual_display_marker" to ClusterLabFailure.VIRTUAL_DISPLAY_MARKER_WRITE_FAILED,
            "task_launch" to ClusterLabFailure.WAZE_TASK_LAUNCH_FAILED,
            "system_display_launch" to ClusterLabFailure.WAZE_TASK_LAUNCH_FAILED,
            "exception" to ClusterLabFailure.PROJECTION_EXCEPTION,
            "display_removed" to ClusterLabFailure.CLUSTER_DISPLAY_REMOVED,
            "surface_destroyed" to ClusterLabFailure.PROJECTION_SURFACE_DESTROYED,
            "lab_lease_not_owned" to ClusterLabFailure.LAB_LEASE_LOST,
        )

        expected.forEach { (raw, failure) ->
            assertEquals(raw, failure, clusterProjectionFailureFor(raw))
        }
    }

    @Test fun `cleanup passes for Waze restored to main fullscreen with clean ownership`() {
        val result = verifyClusterProjectionCleanup(
            transition = transition(requestedMode = ClusterMode.OFF),
            task = found(displayId = 0, windowingMode = TASK_WINDOWING_MODE_FULLSCREEN),
            ownershipMarkersClear = true,
        )

        assertTrue(result.passed)
    }

    @Test fun `cleanup also passes when Waze is confirmed not running`() {
        assertTrue(
            verifyClusterProjectionCleanup(
                transition(requestedMode = ClusterMode.OFF),
                TaskProjectionQueryResult.NotRunning,
                ownershipMarkersClear = true,
            ).passed,
        )
    }

    @Test fun `cleanup reports transition task and ownership failures separately`() {
        assertEquals(
            ClusterLabFailure.CLEANUP_TRANSITION_FAILED,
            verifyClusterProjectionCleanup(
                transition(success = false, requestedMode = ClusterMode.OFF),
                TaskProjectionQueryResult.NotRunning,
                true,
            ).failure,
        )
        assertEquals(
            ClusterLabFailure.CLEANUP_TASK_STATE_UNAVAILABLE,
            verifyClusterProjectionCleanup(
                transition(requestedMode = ClusterMode.OFF),
                TaskProjectionQueryResult.Unavailable,
                true,
            ).failure,
        )
        assertEquals(
            ClusterLabFailure.WAZE_NOT_RETURNED_TO_MAIN_DISPLAY,
            verifyClusterProjectionCleanup(
                transition(requestedMode = ClusterMode.OFF),
                found(displayId = 4, windowingMode = 5),
                true,
            ).failure,
        )
        assertEquals(
            ClusterLabFailure.OWNERSHIP_NOT_CLEARED,
            verifyClusterProjectionCleanup(
                transition(requestedMode = ClusterMode.OFF),
                found(displayId = 0),
                false,
            ).failure,
        )
        assertEquals(
            ClusterLabFailure.RUNTIME_RESOURCES_NOT_RELEASED,
            verifyClusterProjectionCleanup(
                transition(
                    requestedMode = ClusterMode.OFF,
                    runtimeResourcesActive = true,
                ),
                found(displayId = 0),
                true,
            ).failure,
        )
    }

    private fun transition(
        success: Boolean = true,
        rawFailure: String? = null,
        requestedMode: ClusterMode = ClusterMode.FULLSCREEN,
        renderPath: ClusterProjectionRenderPath? = null,
        projectedTaskDisplayId: Int? = null,
        autoContainerRequested: Boolean = false,
        autoContainerMarkerWritten: Boolean? = null,
        autoContainerCommandAccepted: Boolean? = null,
        runtimeResourcesActive: Boolean = success && requestedMode == ClusterMode.FULLSCREEN,
    ) = ClusterLabProjectionTransitionResult(
        requestedMode = requestedMode,
        resultingMode = if (success) requestedMode else ClusterMode.OFF,
        phase = if (requestedMode == ClusterMode.OFF) {
            ClusterProjectionPhase.OFF
        } else if (success) {
            ClusterProjectionPhase.ACTIVE
        } else {
            ClusterProjectionPhase.FAILED
        },
        success = success,
        failure = rawFailure,
        selectedDisplay = null,
        renderPath = renderPath,
        projectedTaskDisplayId = projectedTaskDisplayId,
        autoContainerRequested = autoContainerRequested,
        autoContainerMarkerWritten = autoContainerMarkerWritten,
        autoContainerCommandAccepted = autoContainerCommandAccepted,
        runtimeResourcesActive = runtimeResourcesActive,
        attemptStartedAtMs = 1L,
        attemptFinishedAtMs = 2L,
    )

    private fun found(
        displayId: Int,
        windowingMode: Int = TASK_WINDOWING_MODE_FULLSCREEN,
    ) = TaskProjectionQueryResult.Found(
        TaskProjectionState(taskId = 42, displayId = displayId, windowingMode = windowingMode),
    )
}
