package com.bydmate.app.data.diagnostics

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.ClusterProjectionPhase
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.trips.TripSource
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.demo.DemoMode
import com.bydmate.app.hud.HudController
import com.bydmate.app.hud.HudSomeIpBridge
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavA11yExtractor
import com.bydmate.app.navdata.WazeAccessibilityReader
import com.bydmate.app.navigation.WazeNavigation
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Passive aggregator for the diagnostics screen. collect() never calls ensureRunning(), starts an
 * app, enables a permission, creates a display, binds HUD, or writes to the car. */
@Singleton
class VehicleDiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val energyDataReader: EnergyDataReader,
    private val energyDataDeadDetector: EnergyDataDeadDetector,
    private val helperBootstrap: HelperBootstrap,
    private val hudController: HudController,
    private val tripDao: TripDao,
    private val chargeDao: ChargeDao,
    private val writeLogDao: VehicleWriteLogDao,
) {

    suspend fun collect(): VehicleDiagnosticsSnapshot = withContext(Dispatchers.IO) {
        // A denied/stale energydata mount is one of the failures this screen exists to explain. It
        // must produce an UNKNOWN/attention row, not abort collection of every other subsystem.
        val liveness = probeOrNull { energyDataDeadDetector.snapshot() }
        val fingerprint = liveness?.currentFingerprint
            ?: probeOrNull { energyDataReader.sourceFingerprint() }
        val energyDataAvailable = probeOrDefault(false) { energyDataReader.isAvailable() }
        val helperAlive = probeOrDefault(false) { helperBootstrap.isHealthy() }
        val energyTripCount = probeOrDefault(0) {
            tripDao.getCountBySource(TripSource.ENERGYDATA)
        }
        // Existing DAO contract is ascending, so lastOrNull is the latest autoservice session.
        // Keeping this on the existing method avoids expanding a widely faked DAO interface solely
        // for diagnostics while still fixing the old "only inspect the latest charge of any kind"
        // false negative.
        val lastAutoserviceCharge = probeOrDefault<List<ChargeEntity>>(emptyList()) {
            chargeDao.getAllAutoserviceCharges()
        }.lastOrNull()
        val successfulWrite = probeOrNull { writeLogDao.getLastSuccessful() }

        val wazeInfo = DiagnosticEvidenceStore.packageIdentity(
            context,
            WazeNavigation.PACKAGE_NAME,
        )
        val a11yEnabled = isAccessibilityEnabled()
        val a11yDiagnostics = NavA11yFeed.diagnostics()
        val (wazeWindow, displays) = withContext(Dispatchers.Main.immediate) {
            probeWazeWindow() to runCatching {
                ClusterProjectionManager.inspectDisplays(context)
            }.getOrDefault(emptyList())
        }

        val cluster = ClusterProjectionManager.diagnosticState.value
        val clusterSuccess = newest(
            cluster.lastSuccessAtMs,
            DiagnosticEvidenceStore.timestamp(
                context,
                DiagnosticEvidenceStore.Evidence.CLUSTER_PROJECTION,
            ),
        )
        val hudDelivery = hudController.deliveryDiagnostics.value
        val hudSuccess = newest(
            hudDelivery.lastFrameSuccessAtMs,
            DiagnosticEvidenceStore.timestamp(
                context,
                DiagnosticEvidenceStore.Evidence.FACTORY_HUD_FRAME,
            ),
        )
        val wazeGuidance = wazeInfo?.evidenceScope?.let { scope ->
            newest(
                a11yDiagnostics.lastGuidanceAtMs.takeIf {
                    a11yDiagnostics.lastGuidanceEvidenceScope == scope
                },
                DiagnosticEvidenceStore.timestamp(
                    context,
                    DiagnosticEvidenceStore.Evidence.WAZE_GUIDANCE,
                    scope,
                ),
            )
        }

        // Read the companion flows together and late in collection. A helper Binder ping can take
        // up to two seconds; reading liveData before it and lastDataUpdatedAt after it could combine
        // two different polling ticks into one impossible snapshot.
        val serviceRunning = TrackingService.isRunning.value
        val serviceStartedAt = TrackingService.serviceStartedAt.value
        val vehicleDataConnected = TrackingService.vehicleDataConnected.value
        val liveData = TrackingService.lastData.value
        val lastVehicleDataAt = TrackingService.lastDataUpdatedAt.value
        val now = System.currentTimeMillis()

        VehicleDiagnosticsSnapshot(
            capturedAtMs = now,
            serviceRunning = serviceRunning,
            serviceStartedAtMs = serviceStartedAt,
            vehicleDataConnected = vehicleDataConnected,
            vehicleSnapshotPresent = liveData != null,
            lastVehicleDataAtMs = lastVehicleDataAt,
            energyDataAvailable = energyDataAvailable,
            energyDataDead = liveness?.dead ?: false,
            energyDataDebug = liveness?.let {
                buildString {
                    append("dead=${it.dead} streak=${it.frozenDrivingStreak}")
                    append(" pending=${it.pendingDriving}")
                    append(" stored=${it.storedFingerprint}")
                    append(" current=${it.currentFingerprint}")
                }
            } ?: "liveness_probe=unavailable",
            energyDataFingerprintMtimeMs = fingerprint?.first,
            energyDataFingerprintSizeBytes = fingerprint?.second,
            energyDataTripCount = energyTripCount,
            helperAlive = helperAlive,
            wazeInstalled = wazeInfo != null,
            wazeVersion = wazeInfo?.versionLabel,
            accessibilityEnabled = a11yEnabled,
            accessibilityConnected = SteeringWheelKeyService.isConnected,
            wazeWindowState = wazeWindow,
            wazeFeedEnabled = a11yDiagnostics.enabled,
            wazeLastEventAtMs = a11yDiagnostics.lastWazeEventAtMs,
            wazeLastReadableAtMs = a11yDiagnostics.lastReadableAtMs,
            wazeLastGuidanceAtMs = wazeGuidance,
            hudEnabled = hudController.isEnabled(),
            hudGatewayPresent = HudSomeIpBridge.isServicePresent(context.packageManager),
            hudState = hudController.status.value.toRuntimeState(),
            hudLastAttemptAtMs = newest(
                hudDelivery.lastFrameAttemptAtMs,
                hudDelivery.bindStartedAtMs,
            ),
            hudLastSuccessAtMs = hudSuccess,
            hudLastResultCode = hudDelivery.lastResultCode,
            hudFailure = hudDelivery.lastFailure,
            hudReconnectAttempt = hudDelivery.reconnectAttempt,
            hudNextReconnectAtMs = hudDelivery.nextReconnectAtMs,
            hudLastRecoveredAtMs = hudDelivery.lastRecoveredAtMs,
            clusterEnabled = context.getSharedPreferences(
                ClusterProjectionManager.PREFS_NAME,
                Context.MODE_PRIVATE,
            ).getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false),
            clusterMode = ClusterProjectionManager.currentMode.name,
            clusterPhase = cluster.phase.toRuntimePhase(),
            clusterLastAttemptAtMs = cluster.attemptStartedAtMs,
            clusterLastSuccessAtMs = clusterSuccess,
            clusterDisplaySearchElapsedMs = cluster.displaySearchElapsedMs,
            clusterSelectedDisplayId = cluster.selectedDisplay?.id,
            clusterFailure = cluster.lastFailure ?: ClusterProjectionManager.lastFailure,
            displays = displays.map {
                DisplaySnapshot(
                    id = it.id,
                    name = it.name,
                    widthPx = it.widthPx,
                    heightPx = it.heightPx,
                    densityDpi = it.densityDpi,
                    state = it.state,
                    isClusterCandidate = it.isClusterCandidate,
                )
            },
            lastSuccessfulCommandAtMs = successfulWrite?.ts,
            lastSuccessfulCommandName = successfulWrite?.actionName,
            lastAutoserviceChargeAtMs = lastAutoserviceCharge?.endTs
                ?: lastAutoserviceCharge?.startTs,
            // Count independent traction-battery signal groups. The 12 V accessory battery is
            // intentionally excluded: seeing only voltage12v must never confirm a capability that
            // the UI labels as traction-battery metrics.
            batterySignalCount = listOf(
                liveData?.soc != null,
                liveData?.avgBatTemp != null || liveData?.maxBatTemp != null ||
                    liveData?.minBatTemp != null,
                liveData?.maxCellVoltage != null && liveData.minCellVoltage != null,
                liveData?.totalElecConsumption != null,
            ).count { it },
            energyDataLivenessKnown = liveness != null,
            demoModeEnabled = DemoMode.enabled.value,
            userConfirmedAtMs = CapabilityId.entries.mapNotNull { id ->
                DiagnosticEvidenceStore.userConfirmationAt(context, id)?.let { id to it }
            }.toMap(),
        )
    }

    fun setUserConfirmed(capability: CapabilityId, confirmed: Boolean) {
        DiagnosticEvidenceStore.setUserConfirmed(context, capability, confirmed)
    }

    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val globallyEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!globallyEnabled) return@runCatching false
        val expected = ComponentName(context, SteeringWheelKeyService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }.getOrDefault(false)

    private fun probeWazeWindow(): WazeWindowState {
        val service = SteeringWheelKeyService.instance ?: return WazeWindowState.NOT_CHECKED
        val root: AccessibilityNodeInfo = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: return WazeWindowState.NO_WINDOW
        return try {
            when (runCatching { NavA11yExtractor.read(root) }.getOrNull()) {
                is NavA11yExtractor.ReadResult.Guidance -> WazeWindowState.GUIDANCE_VISIBLE
                else -> {
                    // findNavigatorRoot deliberately prefers a route window. If its known route
                    // anchor is visible but the extractor cannot produce guidance, the route is
                    // active and our parser is blind (often after a Waze layout update). Do not
                    // misreport that as "no active route".
                    if (runCatching { WazeAccessibilityReader.hasRouteAnchor(root) }
                            .getOrDefault(false)
                    ) {
                        WazeWindowState.ROUTE_UNREADABLE
                    } else {
                        WazeWindowState.WINDOW_VISIBLE
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    private fun newest(first: Long?, second: Long?): Long? =
        listOfNotNull(first, second).maxOrNull()

    /** Failure-isolated passive probe that preserves structured coroutine cancellation. Kotlin's
     * runCatching also catches CancellationException, which kept a diagnostics refresh alive after
     * its ViewModel was cleared. */
    private suspend fun <T> probeOrDefault(default: T, probe: suspend () -> T): T = try {
        probe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        default
    }

    private suspend fun <T> probeOrNull(probe: suspend () -> T?): T? = try {
        probe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun HudController.Status.toRuntimeState(): HudRuntimeState = when (this) {
        HudController.Status.OFF -> HudRuntimeState.OFF
        HudController.Status.UNSUPPORTED -> HudRuntimeState.UNSUPPORTED
        HudController.Status.CONNECTING -> HudRuntimeState.CONNECTING
        HudController.Status.ON -> HudRuntimeState.ON
        HudController.Status.BIND_FAILED -> HudRuntimeState.BIND_FAILED
        HudController.Status.SEND_FAILED -> HudRuntimeState.SEND_FAILED
    }

    private fun ClusterProjectionPhase.toRuntimePhase(): ClusterRuntimePhase = when (this) {
        ClusterProjectionPhase.OFF -> ClusterRuntimePhase.OFF
        ClusterProjectionPhase.STARTING -> ClusterRuntimePhase.STARTING
        ClusterProjectionPhase.WAITING_FOR_DISPLAY -> ClusterRuntimePhase.WAITING_FOR_DISPLAY
        ClusterProjectionPhase.ACTIVE -> ClusterRuntimePhase.ACTIVE
        ClusterProjectionPhase.FAILED -> ClusterRuntimePhase.FAILED
    }
}
