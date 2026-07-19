package com.bydmate.app.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleDiagnosticsEvaluatorTest {
    private val nowMs = 1_000_000L

    @Test fun `fresh connected service is healthy and confirms live telemetry`() {
        val lastDataAt = nowMs - 1_000L

        val evaluation = evaluate(snapshot(lastVehicleDataAtMs = lastDataAt))

        assertSection(
            evaluation,
            DiagnosticSection.SERVICE,
            DiagnosticHealth.HEALTHY,
            DiagnosticReason.SERVICE_RUNNING_LIVE,
        )
        assertCapability(
            evaluation,
            CapabilityId.LIVE_TELEMETRY,
            CapabilityState.CONFIRMED,
            evidenceAtMs = lastDataAt,
        )
    }

    @Test fun `stale service data waits and does not confirm live telemetry`() {
        val staleAt = nowMs - VehicleDiagnosticsEvaluator.VEHICLE_DATA_FRESH_MS - 1L

        val evaluation = evaluate(snapshot(lastVehicleDataAtMs = staleAt))

        assertSection(
            evaluation,
            DiagnosticSection.SERVICE,
            DiagnosticHealth.ATTENTION,
            DiagnosticReason.SERVICE_RUNNING_WAITING_DATA,
        )
        assertCapability(
            evaluation,
            CapabilityId.LIVE_TELEMETRY,
            CapabilityState.NOT_TESTED,
        )
    }

    @Test fun `stopped service is an error even when its last sample was fresh`() {
        val evaluation = evaluate(snapshot(serviceRunning = false))

        assertSection(
            evaluation,
            DiagnosticSection.SERVICE,
            DiagnosticHealth.ERROR,
            DiagnosticReason.SERVICE_STOPPED,
        )
        assertCapability(
            evaluation,
            CapabilityId.LIVE_TELEMETRY,
            CapabilityState.NOT_TESTED,
        )
    }

    @Test fun `demo data is visible but never confirms live car capabilities`() {
        val evaluation = evaluate(
            snapshot(
                demoModeEnabled = true,
                batterySignalCount = 4,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.SERVICE,
            DiagnosticHealth.ATTENTION,
            DiagnosticReason.SERVICE_DEMO_MODE,
        )
        assertCapability(
            evaluation,
            CapabilityId.LIVE_TELEMETRY,
            CapabilityState.NOT_TESTED,
        )
        assertCapability(
            evaluation,
            CapabilityId.BATTERY_METRICS,
            CapabilityState.NOT_TESTED,
        )
    }

    @Test fun `battery signals require a fresh live vehicle connection`() {
        val disconnected = evaluate(
            snapshot(
                vehicleDataConnected = false,
                batterySignalCount = 4,
            ),
        )
        val connected = evaluate(snapshot(batterySignalCount = 4))

        assertCapability(
            disconnected,
            CapabilityId.BATTERY_METRICS,
            CapabilityState.NOT_TESTED,
        )
        assertCapability(
            connected,
            CapabilityId.BATTERY_METRICS,
            CapabilityState.CONFIRMED,
            evidenceAtMs = nowMs - 1_000L,
            evidenceLabel = "4",
        )
    }

    @Test fun `dead energydata wins over availability and has no inferred capability`() {
        val evaluation = evaluate(
            snapshot(
                energyDataAvailable = true,
                energyDataDead = true,
                energyDataTripCount = 0,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.ENERGYDATA,
            DiagnosticHealth.ERROR,
            DiagnosticReason.ENERGYDATA_DEAD,
        )
        assertCapability(
            evaluation,
            CapabilityId.ENERGYDATA_TRIPS,
            CapabilityState.NOT_TESTED,
        )
    }

    @Test fun `BYD service assessment distinguishes every helper and data combination`() {
        data class Case(
            val helperAlive: Boolean,
            val dataConnected: Boolean,
            val expectedHealth: DiagnosticHealth,
            val expectedReason: DiagnosticReason,
        )
        val cases = listOf(
            Case(true, true, DiagnosticHealth.HEALTHY, DiagnosticReason.BYD_CONNECTED),
            Case(true, false, DiagnosticHealth.ATTENTION, DiagnosticReason.BYD_HELPER_ONLY),
            Case(false, true, DiagnosticHealth.ATTENTION, DiagnosticReason.BYD_DATA_WITHOUT_HELPER),
            Case(false, false, DiagnosticHealth.ERROR, DiagnosticReason.BYD_DISCONNECTED),
        )

        cases.forEach { case ->
            val evaluation = evaluate(
                snapshot(
                    helperAlive = case.helperAlive,
                    vehicleDataConnected = case.dataConnected,
                ),
            )
            assertSection(
                evaluation,
                DiagnosticSection.BYD_SERVICES,
                case.expectedHealth,
                case.expectedReason,
            )
        }
    }

    @Test fun `recent Waze event with unreachable window is an error`() {
        val evaluation = evaluate(
            snapshot(
                wazeWindowState = WazeWindowState.NO_WINDOW,
                wazeLastEventAtMs = nowMs - 500L,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.NAVIGATION,
            DiagnosticHealth.ERROR,
            DiagnosticReason.WAZE_WINDOW_UNREACHABLE,
        )
    }

    @Test fun `stale Waze event does not falsely report an unreachable window`() {
        val evaluation = evaluate(
            snapshot(
                wazeWindowState = WazeWindowState.NO_WINDOW,
                wazeLastEventAtMs = nowMs - 15_001L,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.NAVIGATION,
            DiagnosticHealth.HEALTHY,
            DiagnosticReason.WAZE_READY_ROUTE_INACTIVE,
        )
    }

    @Test fun `visible Waze route with unreadable guidance is an error`() {
        val evaluation = evaluate(
            snapshot(wazeWindowState = WazeWindowState.ROUTE_UNREADABLE),
        )

        assertSection(
            evaluation,
            DiagnosticSection.NAVIGATION,
            DiagnosticHealth.ERROR,
            DiagnosticReason.WAZE_ROUTE_UNREADABLE,
        )
    }

    @Test fun `active cluster is healthy while automatic evidence remains experimental`() {
        val successAt = nowMs - 2_000L
        val evaluation = evaluate(
            snapshot(
                clusterEnabled = true,
                clusterPhase = ClusterRuntimePhase.ACTIVE,
                clusterLastSuccessAtMs = successAt,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.CLUSTER,
            DiagnosticHealth.HEALTHY,
            DiagnosticReason.CLUSTER_ACTIVE,
        )
        assertCapability(
            evaluation,
            CapabilityId.CLUSTER_PROJECTION,
            CapabilityState.EXPERIMENTAL,
            evidenceAtMs = successAt,
        )
    }

    @Test fun `cluster failures retain their actionable reason`() {
        val cases = listOf(
            "daemon" to DiagnosticReason.CLUSTER_FAILED_DAEMON,
            "display_not_found" to DiagnosticReason.CLUSTER_FAILED_DISPLAY,
            "permission denied" to DiagnosticReason.CLUSTER_FAILED_OTHER,
        )

        cases.forEach { (failure, reason) ->
            val evaluation = evaluate(
                snapshot(
                    clusterEnabled = true,
                    clusterPhase = ClusterRuntimePhase.FAILED,
                    clusterFailure = failure,
                ),
            )
            assertSection(
                evaluation,
                DiagnosticSection.CLUSTER,
                DiagnosticHealth.ERROR,
                reason,
            )
        }
    }

    @Test fun `disabled cluster stays disabled even when a candidate display exists`() {
        val evaluation = evaluate(
            snapshot(
                clusterEnabled = false,
                displays = listOf(clusterDisplay()),
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.CLUSTER,
            DiagnosticHealth.DISABLED,
            DiagnosticReason.CLUSTER_DISABLED,
        )
        assertCapability(
            evaluation,
            CapabilityId.CLUSTER_PROJECTION,
            CapabilityState.NOT_TESTED,
        )
    }

    @Test fun `disabled cluster ignores a stale failed runtime phase`() {
        val evaluation = evaluate(
            snapshot(
                clusterEnabled = false,
                clusterPhase = ClusterRuntimePhase.FAILED,
                clusterFailure = "daemon",
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.CLUSTER,
            DiagnosticHealth.DISABLED,
            DiagnosticReason.CLUSTER_DISABLED,
        )
    }

    @Test fun `disabled HUD ignores gateway and stale runtime errors`() {
        val evaluation = evaluate(
            snapshot(
                hudEnabled = false,
                hudGatewayPresent = false,
                hudState = HudRuntimeState.BIND_FAILED,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.HUD,
            DiagnosticHealth.DISABLED,
            DiagnosticReason.HUD_DISABLED,
        )
        assertCapability(
            evaluation,
            CapabilityId.FACTORY_HUD,
            CapabilityState.UNAVAILABLE,
        )
    }

    @Test fun `missing HUD gateway is unsupported and capability unavailable`() {
        val evaluation = evaluate(
            snapshot(
                hudEnabled = true,
                hudGatewayPresent = false,
                hudState = HudRuntimeState.ON,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.HUD,
            DiagnosticHealth.ERROR,
            DiagnosticReason.HUD_UNSUPPORTED,
        )
        assertCapability(
            evaluation,
            CapabilityId.FACTORY_HUD,
            CapabilityState.UNAVAILABLE,
        )
    }

    @Test fun `HUD bind failure with scheduled reconnect is still checking`() {
        val evaluation = evaluate(
            snapshot(
                hudEnabled = true,
                hudGatewayPresent = true,
                hudState = HudRuntimeState.BIND_FAILED,
                hudReconnectAttempt = 2,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.HUD,
            DiagnosticHealth.CHECKING,
            DiagnosticReason.HUD_CONNECTING,
        )
    }

    @Test fun `accepted HUD frame is healthy but only experimental without visual confirmation`() {
        val frameAt = nowMs - 3_000L
        val evaluation = evaluate(
            snapshot(
                hudEnabled = true,
                hudGatewayPresent = true,
                hudState = HudRuntimeState.ON,
                hudLastSuccessAtMs = frameAt,
                hudLastResultCode = 0,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.HUD,
            DiagnosticHealth.HEALTHY,
            DiagnosticReason.HUD_FRAME_ACCEPTED,
        )
        assertCapability(
            evaluation,
            CapabilityId.FACTORY_HUD,
            CapabilityState.EXPERIMENTAL,
            evidenceAtMs = frameAt,
        )
    }

    @Test fun `connected HUD gateway without accepted frame reports channel ready`() {
        val evaluation = evaluate(
            snapshot(
                hudEnabled = true,
                hudGatewayPresent = true,
                hudState = HudRuntimeState.ON,
                hudLastSuccessAtMs = null,
            ),
        )

        assertSection(
            evaluation,
            DiagnosticSection.HUD,
            DiagnosticHealth.HEALTHY,
            DiagnosticReason.HUD_CHANNEL_READY,
        )
        assertCapability(
            evaluation,
            CapabilityId.FACTORY_HUD,
            CapabilityState.EXPERIMENTAL,
        )
    }

    @Test fun `manual HUD confirmation promotes accepted frame and overrides evidence time`() {
        val frameAt = nowMs - 3_000L
        val confirmedAt = nowMs - 500L
        val evaluation = evaluate(
            snapshot(
                hudEnabled = true,
                hudGatewayPresent = true,
                hudState = HudRuntimeState.ON,
                hudLastSuccessAtMs = frameAt,
                userConfirmedAtMs = mapOf(CapabilityId.FACTORY_HUD to confirmedAt),
            ),
        )

        assertCapability(
            evaluation,
            CapabilityId.FACTORY_HUD,
            CapabilityState.CONFIRMED,
            evidenceAtMs = confirmedAt,
        )
    }

    @Test fun `successful command is experimental until manually confirmed`() {
        val automaticAt = nowMs - 4_000L
        val confirmedAt = nowMs - 250L
        val automatic = evaluate(
            snapshot(
                lastSuccessfulCommandAtMs = automaticAt,
                lastSuccessfulCommandName = "climate_on",
            ),
        )
        val confirmed = evaluate(
            snapshot(
                lastSuccessfulCommandAtMs = automaticAt,
                lastSuccessfulCommandName = "climate_on",
                userConfirmedAtMs = mapOf(CapabilityId.VEHICLE_COMMANDS to confirmedAt),
            ),
        )

        assertCapability(
            automatic,
            CapabilityId.VEHICLE_COMMANDS,
            CapabilityState.EXPERIMENTAL,
            evidenceAtMs = automaticAt,
            evidenceLabel = "climate_on",
        )
        assertCapability(
            confirmed,
            CapabilityId.VEHICLE_COMMANDS,
            CapabilityState.CONFIRMED,
            evidenceAtMs = confirmedAt,
            evidenceLabel = "climate_on",
        )
    }

    private fun evaluate(snapshot: VehicleDiagnosticsSnapshot): DiagnosticEvaluation =
        VehicleDiagnosticsEvaluator.evaluate(snapshot, nowMs)

    private fun assertSection(
        evaluation: DiagnosticEvaluation,
        section: DiagnosticSection,
        health: DiagnosticHealth,
        reason: DiagnosticReason,
    ) {
        assertEquals(
            DiagnosticSectionResult(section, health, reason),
            evaluation.section(section),
        )
    }

    private fun assertCapability(
        evaluation: DiagnosticEvaluation,
        id: CapabilityId,
        state: CapabilityState,
        evidenceAtMs: Long? = null,
        evidenceLabel: String? = null,
    ) {
        val capability = evaluation.capabilities.first { it.id == id }
        assertEquals(state, capability.state)
        if (evidenceAtMs == null) assertNull(capability.evidenceAtMs)
        else assertEquals(evidenceAtMs, capability.evidenceAtMs)
        if (evidenceLabel == null) assertNull(capability.evidenceLabel)
        else assertEquals(evidenceLabel, capability.evidenceLabel)
    }

    private fun clusterDisplay() = DisplaySnapshot(
        id = 2,
        name = "cluster",
        widthPx = 1_920,
        heightPx = 720,
        densityDpi = 320,
        state = 2,
        isClusterCandidate = true,
    )

    private fun snapshot(
        serviceRunning: Boolean = true,
        serviceStartedAtMs: Long? = nowMs - 60_000L,
        vehicleDataConnected: Boolean = true,
        vehicleSnapshotPresent: Boolean = true,
        lastVehicleDataAtMs: Long? = nowMs - 1_000L,
        energyDataAvailable: Boolean = true,
        energyDataDead: Boolean = false,
        energyDataTripCount: Int = 1,
        helperAlive: Boolean = true,
        wazeInstalled: Boolean = true,
        accessibilityEnabled: Boolean = true,
        accessibilityConnected: Boolean = true,
        wazeWindowState: WazeWindowState = WazeWindowState.WINDOW_VISIBLE,
        wazeLastEventAtMs: Long? = null,
        wazeLastGuidanceAtMs: Long? = null,
        hudEnabled: Boolean = false,
        hudGatewayPresent: Boolean = false,
        hudState: HudRuntimeState = HudRuntimeState.OFF,
        hudLastSuccessAtMs: Long? = null,
        hudLastResultCode: Int? = null,
        hudReconnectAttempt: Int = 0,
        clusterEnabled: Boolean = false,
        clusterPhase: ClusterRuntimePhase = ClusterRuntimePhase.OFF,
        clusterLastSuccessAtMs: Long? = null,
        clusterFailure: String? = null,
        displays: List<DisplaySnapshot> = emptyList(),
        lastSuccessfulCommandAtMs: Long? = null,
        lastSuccessfulCommandName: String? = null,
        batterySignalCount: Int = 0,
        demoModeEnabled: Boolean = false,
        userConfirmedAtMs: Map<CapabilityId, Long> = emptyMap(),
    ) = VehicleDiagnosticsSnapshot(
        capturedAtMs = nowMs,
        serviceRunning = serviceRunning,
        serviceStartedAtMs = serviceStartedAtMs,
        vehicleDataConnected = vehicleDataConnected,
        vehicleSnapshotPresent = vehicleSnapshotPresent,
        lastVehicleDataAtMs = lastVehicleDataAtMs,
        energyDataAvailable = energyDataAvailable,
        energyDataDead = energyDataDead,
        energyDataDebug = "test",
        energyDataFingerprintMtimeMs = null,
        energyDataFingerprintSizeBytes = null,
        energyDataTripCount = energyDataTripCount,
        helperAlive = helperAlive,
        wazeInstalled = wazeInstalled,
        wazeVersion = "5.0-test",
        accessibilityEnabled = accessibilityEnabled,
        accessibilityConnected = accessibilityConnected,
        wazeWindowState = wazeWindowState,
        wazeFeedEnabled = true,
        wazeLastEventAtMs = wazeLastEventAtMs,
        wazeLastReadableAtMs = null,
        wazeLastGuidanceAtMs = wazeLastGuidanceAtMs,
        hudEnabled = hudEnabled,
        hudGatewayPresent = hudGatewayPresent,
        hudState = hudState,
        hudLastAttemptAtMs = null,
        hudLastSuccessAtMs = hudLastSuccessAtMs,
        hudLastResultCode = hudLastResultCode,
        hudFailure = null,
        hudReconnectAttempt = hudReconnectAttempt,
        hudNextReconnectAtMs = null,
        hudLastRecoveredAtMs = null,
        clusterEnabled = clusterEnabled,
        clusterMode = "OFF",
        clusterPhase = clusterPhase,
        clusterLastAttemptAtMs = null,
        clusterLastSuccessAtMs = clusterLastSuccessAtMs,
        clusterDisplaySearchElapsedMs = null,
        clusterSelectedDisplayId = null,
        clusterFailure = clusterFailure,
        displays = displays,
        lastSuccessfulCommandAtMs = lastSuccessfulCommandAtMs,
        lastSuccessfulCommandName = lastSuccessfulCommandName,
        lastAutoserviceChargeAtMs = null,
        batterySignalCount = batterySignalCount,
        demoModeEnabled = demoModeEnabled,
        userConfirmedAtMs = userConfirmedAtMs,
    )
}
