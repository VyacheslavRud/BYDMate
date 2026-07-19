package com.bydmate.app.data.diagnostics

/** Severity used by the diagnostics UI. It deliberately distinguishes a disabled optional
 * feature from a broken one, so an unused HUD does not make the whole car look unhealthy. */
enum class DiagnosticHealth { HEALTHY, ATTENTION, ERROR, CHECKING, DISABLED, UNKNOWN }

enum class DiagnosticSection {
    SERVICE,
    ENERGYDATA,
    BYD_SERVICES,
    NAVIGATION,
    CLUSTER,
    HUD,
}

enum class DiagnosticReason {
    SERVICE_RUNNING_LIVE,
    SERVICE_RUNNING_WAITING_DATA,
    SERVICE_DEMO_MODE,
    SERVICE_STOPPED,

    ENERGYDATA_AVAILABLE,
    ENERGYDATA_DEAD,
    ENERGYDATA_UNAVAILABLE,

    BYD_CONNECTED,
    BYD_HELPER_ONLY,
    BYD_DATA_WITHOUT_HELPER,
    BYD_DISCONNECTED,

    WAZE_GUIDANCE_VISIBLE,
    WAZE_GUIDANCE_FALLBACK,
    WAZE_READY_ROUTE_INACTIVE,
    WAZE_ROUTE_UNREADABLE,
    WAZE_WINDOW_UNREACHABLE,
    WAZE_ACCESSIBILITY_DISCONNECTED,
    WAZE_ACCESSIBILITY_DISABLED,
    WAZE_NOT_INSTALLED,

    CLUSTER_ACTIVE,
    CLUSTER_WAITING_FOR_DISPLAY,
    CLUSTER_DISPLAY_READY,
    CLUSTER_FAILED_DAEMON,
    CLUSTER_FAILED_DISPLAY,
    CLUSTER_FAILED_OTHER,
    CLUSTER_NO_DISPLAY,
    CLUSTER_DISABLED,

    HUD_FRAME_ACCEPTED,
    HUD_CHANNEL_READY,
    HUD_CONNECTING,
    HUD_BIND_FAILED,
    HUD_SEND_FAILED,
    HUD_UNSUPPORTED,
    HUD_ENABLED_BUT_OFF,
    HUD_DISABLED,
}

enum class CapabilityId {
    LIVE_TELEMETRY,
    ENERGYDATA_TRIPS,
    BATTERY_METRICS,
    VEHICLE_COMMANDS,
    AUTOMATIC_CHARGING,
    WAZE_GUIDANCE,
    CLUSTER_PROJECTION,
    FACTORY_HUD,
}

enum class CapabilityState { CONFIRMED, EXPERIMENTAL, NOT_TESTED, UNAVAILABLE }

enum class WazeWindowState {
    NOT_CHECKED,
    NO_WINDOW,
    WINDOW_VISIBLE,
    ROUTE_UNREADABLE,
    GUIDANCE_VISIBLE,
}

enum class HudRuntimeState { OFF, UNSUPPORTED, CONNECTING, ON, BIND_FAILED, SEND_FAILED }

enum class ClusterRuntimePhase { OFF, STARTING, WAITING_FOR_DISPLAY, ACTIVE, FAILED }

data class DisplaySnapshot(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val state: Int,
    val isClusterCandidate: Boolean,
)

/** One passive, point-in-time view of the car and Android integration. */
data class VehicleDiagnosticsSnapshot(
    val capturedAtMs: Long,
    val serviceRunning: Boolean,
    val serviceStartedAtMs: Long?,
    val vehicleDataConnected: Boolean,
    val vehicleSnapshotPresent: Boolean,
    val lastVehicleDataAtMs: Long?,
    val energyDataAvailable: Boolean,
    val energyDataDead: Boolean,
    val energyDataDebug: String,
    val energyDataFingerprintMtimeMs: Long?,
    val energyDataFingerprintSizeBytes: Long?,
    val energyDataTripCount: Int,
    val helperAlive: Boolean,
    val wazeInstalled: Boolean,
    val wazeVersion: String?,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val wazeWindowState: WazeWindowState,
    val wazeFeedEnabled: Boolean,
    val wazeLastEventAtMs: Long?,
    val wazeLastReadableAtMs: Long?,
    val wazeLastGuidanceAtMs: Long?,
    val hudEnabled: Boolean,
    val hudGatewayPresent: Boolean,
    val hudState: HudRuntimeState,
    val hudLastAttemptAtMs: Long?,
    val hudLastSuccessAtMs: Long?,
    val hudLastResultCode: Int?,
    val hudFailure: String?,
    val hudReconnectAttempt: Int,
    val hudNextReconnectAtMs: Long?,
    val hudLastRecoveredAtMs: Long?,
    val hudIncidentCount: Int,
    val hudLastIncident: HudIncident?,
    val clusterEnabled: Boolean,
    val clusterMode: String,
    val clusterPhase: ClusterRuntimePhase,
    val clusterLastAttemptAtMs: Long?,
    val clusterLastSuccessAtMs: Long?,
    val clusterDisplaySearchElapsedMs: Long?,
    val clusterSelectedDisplayId: Int?,
    val clusterFailure: String?,
    val displays: List<DisplaySnapshot>,
    val lastSuccessfulCommandAtMs: Long?,
    val lastSuccessfulCommandName: String?,
    val lastAutoserviceChargeAtMs: Long?,
    val batterySignalCount: Int,
    val energyDataLivenessKnown: Boolean = true,
    val demoModeEnabled: Boolean = false,
    val userConfirmedAtMs: Map<CapabilityId, Long> = emptyMap(),
    val routeActive: Boolean = false,
    val routeSource: String? = null,
    val routeManeuverGaode: Int = 0,
    val routeRenderable: Boolean = false,
    val routeLastUpdateAtMs: Long? = null,
    val routeLastObservedAtMs: Long? = null,
    val routeEndReason: String? = null,
    val routeEndedAtMs: Long? = null,
    val wazeLastNoGuidanceAtMs: Long? = null,
    val wazeLastWindowUnreachableAtMs: Long? = null,
    val wazeLastUnreadableAtMs: Long? = null,
    val wazeLastProbeResult: String? = null,
    val hudLastDeliveryKind: String? = null,
    val hudLastGuidanceSuccessAtMs: Long? = null,
    val hudLastClearAttemptAtMs: Long? = null,
    val hudLastClearSuccessAtMs: Long? = null,
    val clusterMonitoredDisplayId: Int? = null,
    val clusterLastDisplayEvent: String? = null,
    val clusterLastDisplayEventAtMs: Long? = null,
)

data class DiagnosticSectionResult(
    val section: DiagnosticSection,
    val health: DiagnosticHealth,
    val reason: DiagnosticReason,
)

data class CapabilityAssessment(
    val id: CapabilityId,
    val state: CapabilityState,
    val evidenceAtMs: Long? = null,
    val evidenceLabel: String? = null,
)

data class DiagnosticEvaluation(
    val sections: List<DiagnosticSectionResult>,
    val capabilities: List<CapabilityAssessment>,
) {
    fun section(id: DiagnosticSection): DiagnosticSectionResult =
        sections.first { it.section == id }
}

/** Pure policy layer. Android probing lives in VehicleDiagnosticsCollector; keeping the judgement
 * here makes temporary/unsupported/disabled states deterministic and unit-testable. */
object VehicleDiagnosticsEvaluator {
    // Native polling intentionally slows to 30 s while idle and can back off to 60 s after a
    // failed read. A 15 s window made a healthy parked car alternate between green and warning.
    // A real read failure is still reported immediately through vehicleDataConnected=false.
    const val VEHICLE_DATA_FRESH_MS = 75_000L
    private const val SERVICE_START_GRACE_MS = 30_000L
    private const val WAZE_EVENT_RECENT_MS = 15_000L

    fun evaluate(
        snapshot: VehicleDiagnosticsSnapshot,
        nowMs: Long = snapshot.capturedAtMs,
    ): DiagnosticEvaluation {
        val rawDataFresh = snapshot.serviceRunning && snapshot.vehicleSnapshotPresent &&
            snapshot.lastVehicleDataAtMs?.let { nowMs - it in 0..VEHICLE_DATA_FRESH_MS } == true
        // Demo snapshots deliberately look realistic. They prove the UI path, not a connection to
        // this Sea Lion, and therefore must never promote live/traction-battery capabilities.
        val dataFresh = rawDataFresh && !snapshot.demoModeEnabled
        val liveDataHealthy = dataFresh && snapshot.vehicleDataConnected
        val serviceStarting = !snapshot.serviceRunning && snapshot.serviceStartedAtMs?.let {
            nowMs - it in 0..SERVICE_START_GRACE_MS
        } == true

        val service = when {
            snapshot.serviceRunning && snapshot.demoModeEnabled -> result(
                DiagnosticSection.SERVICE, DiagnosticHealth.ATTENTION,
                DiagnosticReason.SERVICE_DEMO_MODE)
            serviceStarting -> result(
                DiagnosticSection.SERVICE, DiagnosticHealth.CHECKING,
                DiagnosticReason.SERVICE_RUNNING_WAITING_DATA)
            !snapshot.serviceRunning -> result(
                DiagnosticSection.SERVICE, DiagnosticHealth.ERROR, DiagnosticReason.SERVICE_STOPPED)
            liveDataHealthy -> result(
                DiagnosticSection.SERVICE, DiagnosticHealth.HEALTHY, DiagnosticReason.SERVICE_RUNNING_LIVE)
            else -> result(
                DiagnosticSection.SERVICE, DiagnosticHealth.ATTENTION,
                DiagnosticReason.SERVICE_RUNNING_WAITING_DATA)
        }

        val energyData = when {
            !snapshot.energyDataLivenessKnown -> result(
                DiagnosticSection.ENERGYDATA, DiagnosticHealth.ATTENTION,
                DiagnosticReason.ENERGYDATA_UNAVAILABLE)
            snapshot.energyDataDead -> result(
                DiagnosticSection.ENERGYDATA, DiagnosticHealth.ERROR, DiagnosticReason.ENERGYDATA_DEAD)
            snapshot.energyDataAvailable -> result(
                DiagnosticSection.ENERGYDATA, DiagnosticHealth.HEALTHY,
                DiagnosticReason.ENERGYDATA_AVAILABLE)
            else -> result(
                DiagnosticSection.ENERGYDATA, DiagnosticHealth.ATTENTION,
                DiagnosticReason.ENERGYDATA_UNAVAILABLE)
        }

        val bydServices = when {
            snapshot.helperAlive && liveDataHealthy -> result(
                DiagnosticSection.BYD_SERVICES, DiagnosticHealth.HEALTHY,
                DiagnosticReason.BYD_CONNECTED)
            snapshot.helperAlive -> result(
                DiagnosticSection.BYD_SERVICES, DiagnosticHealth.ATTENTION,
                DiagnosticReason.BYD_HELPER_ONLY)
            liveDataHealthy -> result(
                DiagnosticSection.BYD_SERVICES, DiagnosticHealth.ATTENTION,
                DiagnosticReason.BYD_DATA_WITHOUT_HELPER)
            else -> result(
                DiagnosticSection.BYD_SERVICES, DiagnosticHealth.ERROR,
                DiagnosticReason.BYD_DISCONNECTED)
        }

        val recentWazeEvent = snapshot.wazeLastEventAtMs?.let {
            nowMs - it in 0..WAZE_EVENT_RECENT_MS
        } == true
        val recentWindowLoss = snapshot.wazeLastWindowUnreachableAtMs?.let {
            nowMs - it in 0..WAZE_EVENT_RECENT_MS
        } == true
        val recentUnreadable = snapshot.wazeLastUnreadableAtMs?.let {
            nowMs - it in 0..WAZE_EVENT_RECENT_MS
        } == true
        val navigation = when {
            !snapshot.wazeInstalled -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ERROR,
                DiagnosticReason.WAZE_NOT_INSTALLED)
            !snapshot.accessibilityEnabled -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ERROR,
                DiagnosticReason.WAZE_ACCESSIBILITY_DISABLED)
            !snapshot.accessibilityConnected -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ATTENTION,
                DiagnosticReason.WAZE_ACCESSIBILITY_DISCONNECTED)
            snapshot.routeActive && !snapshot.routeRenderable -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ERROR,
                DiagnosticReason.WAZE_ROUTE_UNREADABLE)
            snapshot.wazeWindowState == WazeWindowState.GUIDANCE_VISIBLE -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.HEALTHY,
                DiagnosticReason.WAZE_GUIDANCE_VISIBLE)
            snapshot.wazeWindowState == WazeWindowState.ROUTE_UNREADABLE -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ERROR,
                DiagnosticReason.WAZE_ROUTE_UNREADABLE)
            snapshot.routeActive && snapshot.wazeWindowState == WazeWindowState.NO_WINDOW -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ATTENTION,
                DiagnosticReason.WAZE_WINDOW_UNREACHABLE)
            snapshot.routeActive && recentUnreadable -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ERROR,
                DiagnosticReason.WAZE_ROUTE_UNREADABLE)
            snapshot.routeActive && recentWindowLoss -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ATTENTION,
                DiagnosticReason.WAZE_WINDOW_UNREACHABLE)
            snapshot.routeActive -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ATTENTION,
                DiagnosticReason.WAZE_GUIDANCE_FALLBACK)
            recentWazeEvent && snapshot.wazeWindowState == WazeWindowState.NO_WINDOW -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.ERROR,
                DiagnosticReason.WAZE_WINDOW_UNREACHABLE)
            else -> result(
                DiagnosticSection.NAVIGATION, DiagnosticHealth.HEALTHY,
                DiagnosticReason.WAZE_READY_ROUTE_INACTIVE)
        }

        val hasClusterDisplay = snapshot.displays.any { it.isClusterCandidate }
        val cluster = when {
            // A retained failure describes the previous attempt. Once the owner switches the
            // feature off it remains useful in technical details, but must not make an optional
            // disabled integration look currently broken.
            !snapshot.clusterEnabled -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.DISABLED,
                DiagnosticReason.CLUSTER_DISABLED)
            snapshot.clusterPhase == ClusterRuntimePhase.ACTIVE -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.HEALTHY,
                DiagnosticReason.CLUSTER_ACTIVE)
            snapshot.clusterPhase == ClusterRuntimePhase.WAITING_FOR_DISPLAY ||
                snapshot.clusterPhase == ClusterRuntimePhase.STARTING -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.CHECKING,
                DiagnosticReason.CLUSTER_WAITING_FOR_DISPLAY)
            snapshot.clusterPhase == ClusterRuntimePhase.FAILED &&
                snapshot.clusterFailure == "daemon" -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.ERROR,
                DiagnosticReason.CLUSTER_FAILED_DAEMON)
            snapshot.clusterPhase == ClusterRuntimePhase.FAILED &&
                snapshot.clusterFailure == "display_not_found" -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.ERROR,
                DiagnosticReason.CLUSTER_FAILED_DISPLAY)
            snapshot.clusterPhase == ClusterRuntimePhase.FAILED -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.ERROR,
                DiagnosticReason.CLUSTER_FAILED_OTHER)
            hasClusterDisplay -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.HEALTHY,
                DiagnosticReason.CLUSTER_DISPLAY_READY)
            else -> result(
                DiagnosticSection.CLUSTER, DiagnosticHealth.ATTENTION,
                DiagnosticReason.CLUSTER_NO_DISPLAY)
        }

        val hud = when {
            !snapshot.hudEnabled -> result(
                DiagnosticSection.HUD, DiagnosticHealth.DISABLED, DiagnosticReason.HUD_DISABLED)
            !snapshot.hudGatewayPresent || snapshot.hudState == HudRuntimeState.UNSUPPORTED -> result(
                DiagnosticSection.HUD, DiagnosticHealth.ERROR, DiagnosticReason.HUD_UNSUPPORTED)
            snapshot.hudReconnectAttempt > 0 -> result(
                DiagnosticSection.HUD, DiagnosticHealth.CHECKING, DiagnosticReason.HUD_CONNECTING)
            snapshot.hudState == HudRuntimeState.CONNECTING -> result(
                DiagnosticSection.HUD, DiagnosticHealth.CHECKING, DiagnosticReason.HUD_CONNECTING)
            snapshot.hudState == HudRuntimeState.BIND_FAILED -> result(
                DiagnosticSection.HUD, DiagnosticHealth.ERROR, DiagnosticReason.HUD_BIND_FAILED)
            snapshot.hudState == HudRuntimeState.SEND_FAILED -> result(
                DiagnosticSection.HUD, DiagnosticHealth.ERROR, DiagnosticReason.HUD_SEND_FAILED)
            snapshot.hudState == HudRuntimeState.ON && snapshot.hudLastSuccessAtMs != null -> result(
                DiagnosticSection.HUD, DiagnosticHealth.HEALTHY,
                DiagnosticReason.HUD_FRAME_ACCEPTED)
            snapshot.hudState == HudRuntimeState.ON -> result(
                DiagnosticSection.HUD, DiagnosticHealth.HEALTHY,
                DiagnosticReason.HUD_CHANNEL_READY)
            else -> result(
                DiagnosticSection.HUD, DiagnosticHealth.ATTENTION,
                DiagnosticReason.HUD_ENABLED_BUT_OFF)
        }

        val capabilities = listOf(
            capability(
                CapabilityId.LIVE_TELEMETRY,
                if (liveDataHealthy) CapabilityState.CONFIRMED
                else CapabilityState.NOT_TESTED,
                snapshot.lastVehicleDataAtMs.takeIf { liveDataHealthy },
            ),
            capability(
                CapabilityId.ENERGYDATA_TRIPS,
                when {
                    snapshot.energyDataTripCount > 0 -> CapabilityState.CONFIRMED
                    snapshot.energyDataAvailable && !snapshot.energyDataDead -> CapabilityState.EXPERIMENTAL
                    else -> CapabilityState.NOT_TESTED
                },
                evidenceLabel = snapshot.energyDataTripCount.takeIf { it > 0 }?.toString(),
            ),
            capability(
                CapabilityId.BATTERY_METRICS,
                if (liveDataHealthy && snapshot.batterySignalCount > 0) CapabilityState.CONFIRMED
                else CapabilityState.NOT_TESTED,
                snapshot.lastVehicleDataAtMs.takeIf {
                    liveDataHealthy && snapshot.batterySignalCount > 0
                },
                snapshot.batterySignalCount.takeIf { liveDataHealthy && it > 0 }?.toString(),
            ),
            capability(
                CapabilityId.VEHICLE_COMMANDS,
                manualState(
                    snapshot,
                    CapabilityId.VEHICLE_COMMANDS,
                    if (snapshot.lastSuccessfulCommandAtMs != null) CapabilityState.EXPERIMENTAL
                    else CapabilityState.NOT_TESTED,
                ),
                snapshot.userConfirmedAtMs[CapabilityId.VEHICLE_COMMANDS]
                    ?: snapshot.lastSuccessfulCommandAtMs,
                snapshot.lastSuccessfulCommandName,
            ),
            capability(
                CapabilityId.AUTOMATIC_CHARGING,
                manualState(
                    snapshot,
                    CapabilityId.AUTOMATIC_CHARGING,
                    if (snapshot.lastAutoserviceChargeAtMs != null) CapabilityState.EXPERIMENTAL
                    else CapabilityState.NOT_TESTED,
                ),
                snapshot.userConfirmedAtMs[CapabilityId.AUTOMATIC_CHARGING]
                    ?: snapshot.lastAutoserviceChargeAtMs,
            ),
            capability(
                CapabilityId.WAZE_GUIDANCE,
                when {
                    !snapshot.wazeInstalled -> CapabilityState.UNAVAILABLE
                    snapshot.wazeLastGuidanceAtMs != null -> CapabilityState.CONFIRMED
                    snapshot.accessibilityConnected -> CapabilityState.EXPERIMENTAL
                    else -> CapabilityState.NOT_TESTED
                },
                snapshot.wazeLastGuidanceAtMs,
            ),
            capability(
                CapabilityId.CLUSTER_PROJECTION,
                manualState(
                    snapshot,
                    CapabilityId.CLUSTER_PROJECTION,
                    when {
                        snapshot.clusterLastSuccessAtMs != null -> CapabilityState.EXPERIMENTAL
                        snapshot.clusterEnabled && hasClusterDisplay -> CapabilityState.EXPERIMENTAL
                        else -> CapabilityState.NOT_TESTED
                    },
                ),
                snapshot.userConfirmedAtMs[CapabilityId.CLUSTER_PROJECTION]
                    ?: snapshot.clusterLastSuccessAtMs,
            ),
            capability(
                CapabilityId.FACTORY_HUD,
                if (!snapshot.hudGatewayPresent) {
                    CapabilityState.UNAVAILABLE
                } else {
                    manualState(
                        snapshot,
                        CapabilityId.FACTORY_HUD,
                        when {
                            snapshot.hudLastSuccessAtMs != null -> CapabilityState.EXPERIMENTAL
                            snapshot.hudState == HudRuntimeState.ON -> CapabilityState.EXPERIMENTAL
                            else -> CapabilityState.NOT_TESTED
                        },
                    )
                },
                snapshot.userConfirmedAtMs[CapabilityId.FACTORY_HUD]
                    ?: snapshot.hudLastSuccessAtMs,
            ),
        )

        return DiagnosticEvaluation(
            sections = listOf(service, energyData, bydServices, navigation, cluster, hud),
            capabilities = capabilities,
        )
    }

    private fun result(
        section: DiagnosticSection,
        health: DiagnosticHealth,
        reason: DiagnosticReason,
    ) = DiagnosticSectionResult(section, health, reason)

    private fun capability(
        id: CapabilityId,
        state: CapabilityState,
        evidenceAtMs: Long? = null,
        evidenceLabel: String? = null,
    ) = CapabilityAssessment(id, state, evidenceAtMs, evidenceLabel)

    private fun manualState(
        snapshot: VehicleDiagnosticsSnapshot,
        id: CapabilityId,
        automaticState: CapabilityState,
    ): CapabilityState = if (snapshot.userConfirmedAtMs[id] != null) {
        CapabilityState.CONFIRMED
    } else {
        automaticState
    }
}
