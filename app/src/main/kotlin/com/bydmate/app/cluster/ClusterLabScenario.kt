package com.bydmate.app.cluster

/**
 * Parked, development-only checks for the instrument-cluster projection path.
 *
 * The lab deliberately has no scenario that powers the BYD compositor through auto_container.
 * Those commands are not validated on the Sea Lion 07. C04/C05 exercise only the existing
 * projection pipeline after the driver has manually opened the native Navi/projection mode.
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
    )

    fun byId(id: String): ClusterLabScenario? = all.firstOrNull { it.id == id }

    fun visible(clusterDisplayAvailable: Boolean): List<ClusterLabScenario> = all.filter {
        it.mutation == ClusterLabMutation.NONE || clusterDisplayAvailable
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
