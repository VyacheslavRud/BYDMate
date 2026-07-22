package com.bydmate.app.cluster

/**
 * Parked, development-only checks for the instrument-cluster projection path.
 *
 * C06 and C07 are the only scenarios allowed to power the BYD compositor. They are explicit parked
 * calibration probes for the existing, daemon-whitelisted 16/18/0 sequence; they must not be
 * treated as proof that donor commands are generally compatible with the Sea Lion 07.
 */
enum class ClusterLabMutation {
    NONE,
    APP_OVERLAY,
    PROJECTION_PIPELINE,
}

data class ClusterLabScenario(
    val id: String,
    val title: String,
    val summary: String,
    val mutation: ClusterLabMutation,
    val durationMs: Long,
)

object ClusterLabScenarioCatalog {
    val all: List<ClusterLabScenario> = listOf(
        ClusterLabScenario(
            id = "C01",
            title = "Display inventory watch",
            summary = "Watches app-visible displays for five seconds and records when a cluster candidate appears.",
            mutation = ClusterLabMutation.NONE,
            durationMs = 5_000L,
        ),
        ClusterLabScenario(
            id = "C02",
            title = "Projection state snapshot",
            summary = "Captures display, projection, helper, geometry and ownership state without changing it.",
            mutation = ClusterLabMutation.NONE,
            durationMs = 0L,
        ),
        ClusterLabScenario(
            id = "C03",
            title = "Calibration pattern",
            summary = "Shows a bounded grid on an already-visible cluster display, then always removes it.",
            mutation = ClusterLabMutation.APP_OVERLAY,
            durationMs = 8_000L,
        ),
        ClusterLabScenario(
            id = "C04",
            title = "Current Waze projection once",
            summary = "Starts the current production projection, samples its phases, then restores OFF.",
            mutation = ClusterLabMutation.PROJECTION_PIPELINE,
            durationMs = 8_000L,
        ),
        ClusterLabScenario(
            id = "C05",
            title = "Projection start-stop-start",
            summary = "Runs two bounded production projection cycles and records every lifecycle transition.",
            mutation = ClusterLabMutation.PROJECTION_PIPELINE,
            durationMs = 14_000L,
        ),
        ClusterLabScenario(
            id = "C06",
            title = "Sea Lion Waze map end-to-end",
            summary = "Opens the factory projection container, verifies Waze task placement, then restores the center display and OFF.",
            mutation = ClusterLabMutation.PROJECTION_PIPELINE,
            durationMs = 10_000L,
        ),
        ClusterLabScenario(
            id = "C07",
            title = "Sea Lion container transport",
            summary = "Powers only the known container sequence, records Binder/display/SurfaceFlinger evidence, then always restores OFF.",
            mutation = ClusterLabMutation.PROJECTION_PIPELINE,
            durationMs = 12_000L,
        ),
        ClusterLabScenario(
            id = "C08",
            title = "Manual factory Navi watch",
            summary = "Watches system and app display inventories while you switch the instrument panel to its factory Navi mode.",
            mutation = ClusterLabMutation.NONE,
            durationMs = 20_000L,
        ),
        ClusterLabScenario(
            id = "C09",
            title = "Fission projection contract snapshot",
            summary = "Reads the vendor-native and Java projection parcel plus all Fission surfaces without changing cluster state.",
            mutation = ClusterLabMutation.NONE,
            durationMs = 0L,
        ),
    )

    fun byId(id: String): ClusterLabScenario? = all.firstOrNull { it.id == id }

    fun visible(clusterDisplayAvailable: Boolean): List<ClusterLabScenario> = all.filter {
        it.mutation == ClusterLabMutation.NONE || it.id == "C07" || clusterDisplayAvailable
    }

    fun primary(): ClusterLabScenario = checkNotNull(byId("C07"))

    fun support(): List<ClusterLabScenario> = all.filter {
        it.id == "C01" || it.id == "C02" || it.id == "C09"
    }

    fun manualTransport(): List<ClusterLabScenario> = all.filter { it.id == "C08" }

    fun advanced(clusterDisplayAvailable: Boolean): List<ClusterLabScenario> = all.filter {
        it.id in setOf("C03", "C04", "C05", "C06") && clusterDisplayAvailable
    }
}

enum class ClusterLabFailure {
    DEV_BUILD_REQUIRED,
    PARK_CONFIRMATION_REQUIRED,
    VEHICLE_DATA_UNAVAILABLE,
    VEHICLE_MOVING,
    PARK_GEAR_REQUIRED,
    ROUTE_ACTIVE,
    AUTO_CONTAINER_ENABLED,
    COMPOSITOR_OWNERSHIP_PENDING,
    PROJECTION_ALREADY_ACTIVE,
    HELPER_UNAVAILABLE,
    OVERLAY_ALREADY_ACTIVE,
    OVERLAY_PERMISSION_UNAVAILABLE,
    CLUSTER_DISPLAY_NOT_FOUND,
    COMPOSITOR_MARKER_WRITE_FAILED,
    CONTAINER_ON_REJECTED,
    CLUSTER_DISPLAY_DID_NOT_APPEAR,
    INVALID_GEOMETRY,
    OVERLAY_SURFACE_TIMEOUT,
    STALE_DISPLAY_RELEASE_FAILED,
    VIRTUAL_DISPLAY_CREATE_FAILED,
    VIRTUAL_DISPLAY_MARKER_WRITE_FAILED,
    WAZE_TASK_LAUNCH_FAILED,
    PROJECTION_EXCEPTION,
    CLUSTER_DISPLAY_REMOVED,
    PROJECTION_SURFACE_DESTROYED,
    LAB_LEASE_LOST,
    RENDER_PATH_UNKNOWN,
    PROJECTION_RESOURCES_MISSING,
    TASK_STATE_UNAVAILABLE,
    WAZE_TASK_NOT_RUNNING,
    TASK_STAYED_ON_MAIN_DISPLAY,
    TASK_ON_WRONG_DISPLAY,
    CLEANUP_TRANSITION_FAILED,
    CLEANUP_TASK_STATE_UNAVAILABLE,
    WAZE_NOT_RETURNED_TO_MAIN_DISPLAY,
    OWNERSHIP_NOT_CLEARED,
    RUNTIME_RESOURCES_NOT_RELEASED,
    PROJECTION_FAILED,
    PROJECTION_TIMEOUT,
    CLEANUP_TIMEOUT,
    JOURNAL_WRITE_FAILED,
    CANCELLED,
    INTERNAL_ERROR,
}

data class ClusterLabSafetyInput(
    val isDebugBuild: Boolean,
    val parkConfirmedByUser: Boolean,
    val routeActive: Boolean,
    val gear: Int?,
    val speedKmh: Int?,
)

data class ClusterLabSafetyResult(
    val failure: ClusterLabFailure? = null,
    val gear: Int? = null,
    val speedKmh: Int? = null,
) {
    val safe: Boolean get() = failure == null
}

/** Pure policy used both before a scenario and continuously while it owns visual output. */
fun evaluateClusterLabSafety(input: ClusterLabSafetyInput): ClusterLabSafetyResult {
    val failure = when {
        !input.isDebugBuild -> ClusterLabFailure.DEV_BUILD_REQUIRED
        !input.parkConfirmedByUser -> ClusterLabFailure.PARK_CONFIRMATION_REQUIRED
        input.routeActive -> ClusterLabFailure.ROUTE_ACTIVE
        input.gear == null || input.speedKmh == null ->
            ClusterLabFailure.VEHICLE_DATA_UNAVAILABLE
        input.speedKmh > 0 -> ClusterLabFailure.VEHICLE_MOVING
        input.gear != 1 -> ClusterLabFailure.PARK_GEAR_REQUIRED
        else -> null
    }
    return ClusterLabSafetyResult(failure, input.gear, input.speedKmh)
}

enum class ClusterLabObservation {
    VISIBLE,
    NOTHING,
    BLACK_SCREEN,
    FLICKERED,
    WRONG_GEOMETRY,
    MAIN_DISPLAY_ONLY,
    OTHER,
    NOT_REPORTED,
}

/** A stable, privacy-safe display snapshot stored with every relevant timeline event. */
data class ClusterLabDisplaySnapshot(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val state: Int,
    val clusterCandidate: Boolean,
)

internal fun ClusterDisplayDiagnostic.toLabSnapshot() = ClusterLabDisplaySnapshot(
    id = id,
    name = name,
    widthPx = widthPx,
    heightPx = heightPx,
    densityDpi = densityDpi,
    state = state,
    clusterCandidate = isClusterCandidate,
)
