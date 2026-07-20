package com.bydmate.app.hud

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavA11yFeed
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HudControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val helperBootstrap = mockk<HelperBootstrap>(relaxed = true)

    private fun controller(bridge: HudSomeIpBridge? = null): HudController =
        HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            reconnectDelayMs = { 0L }
            if (bridge != null) bridgeFactory = { _, _ -> bridge }
        }

    private fun installSomeIp() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
    }

    private fun connectedBridge(): HudSomeIpBridge {
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } returns true
        every { bridge.startService(any()) } returns 0
        every { bridge.isConnected() } returns true
        return bridge
    }

    @Before fun reset() {
        NavGuidanceHub.reset()
        NavA11yFeed.disable()
        context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `unsupported head unit makes zero helper calls and stays inert`() {
        val c = controller()
        c.setEnabled(true)
        assertEquals(HudController.Status.UNSUPPORTED, c.status.value)
        assertFalse(NavA11yFeed.isEnabled)
        assertFalse(c.requiresA11y())
        assertFalse(context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(HudController.KEY_SUPPORTED, true))
        verify { helperClient wasNot Called }
        verify { helperBootstrap wasNot Called }
    }

    @Test fun `supported path binds starts service and enables feed`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        assertTrue(NavA11yFeed.isEnabled)
        assertTrue(c.requiresA11y())
        coVerify { helperClient.enableAccessibilityService() }
        verify { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        c.setEnabled(false)   // stop the push loop so it does not leak into other tests
    }

    @Test fun `HUD Lab requires parked confirmation before native send`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)

        val result = c.sendHudLabFrame(
            HudProtobufBuilder.buildHudLabFrame(2),
            parkConfirmedByUser = false,
        )

        assertEquals(HudLabSendFailure.PARK_CONFIRMATION_REQUIRED, result.failure)
        assertFalse(result.accepted)
        verify(exactly = 0) { bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any()) }
        c.setEnabled(false)
    }

    @Test fun `HUD Lab sends raw frame then clear through ready native channel`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val payloads = mutableListOf<ByteArray>()
        val bridge = connectedBridge()
        every { bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, capture(payloads)) } returns 0
        val c = controller(bridge)
        c.hudLabVehicleSnapshot = { diParsData(speed = 0, gear = 1) }
        c.setEnabled(true)
        val labFrame = HudProtobufBuilder.buildHudLabFrame(2)

        val sent = c.sendHudLabFrame(labFrame, parkConfirmedByUser = true)
        val clearRc = c.clearHudLabFrame()

        assertTrue(sent.accepted)
        assertEquals(0, clearRc)
        assertEquals(2, payloads.size)
        assertTrue(labFrame.contentEquals(payloads[0]))
        assertTrue(HudProtobufBuilder.buildClearFrame(0).contentEquals(payloads[1]))
        c.setEnabled(false)
    }

    @Test fun `HUD Lab cannot overwrite an active Waze route`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        NavGuidanceHub.update(
            com.bydmate.app.navdata.NavGuidance(maneuverGaode = 2, distanceMeters = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = System.currentTimeMillis(),
        )

        val result = c.sendHudLabFrame(
            HudProtobufBuilder.buildHudLabFrame(3),
            parkConfirmedByUser = true,
        )

        assertEquals(HudLabSendFailure.ROUTE_ACTIVE, result.failure)
        assertFalse(result.accepted)
        c.setEnabled(false)
    }

    @Test fun `HUD Lab rejects moving or unavailable vehicle safety data`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)

        c.hudLabVehicleSnapshot = { null }
        assertEquals(
            HudLabSendFailure.VEHICLE_DATA_UNAVAILABLE,
            c.sendHudLabFrame(
                HudProtobufBuilder.buildHudLabFrame(1),
                parkConfirmedByUser = true,
            ).failure,
        )

        c.hudLabVehicleSnapshot = { diParsData(speed = 5, gear = 4) }
        assertEquals(
            HudLabSendFailure.VEHICLE_MOVING,
            c.sendHudLabFrame(
                HudProtobufBuilder.buildHudLabFrame(1),
                parkConfirmedByUser = true,
            ).failure,
        )

        c.hudLabVehicleSnapshot = { diParsData(speed = 0, gear = 4) }
        assertEquals(
            HudLabSendFailure.PARK_GEAR_REQUIRED,
            c.sendHudLabFrame(
                HudProtobufBuilder.buildHudLabFrame(1),
                parkConfirmedByUser = true,
            ).failure,
        )
        c.setEnabled(false)
    }

    @Test fun `rejected HUD frame is visible as send failure`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        every { bridge.fireEvent(any(), any()) } returns -7
        NavGuidanceHub.update(
            com.bydmate.app.navdata.NavGuidance(maneuverGaode = 2, distanceMeters = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = System.currentTimeMillis(),
        )
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.SEND_FAILED, c.status.value)
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `unexpected local push exception is visible in delivery diagnostics`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        every { bridge.fireEvent(any(), any()) } throws IllegalStateException("synthetic")
        NavGuidanceHub.update(
            com.bydmate.app.navdata.NavGuidance(maneuverGaode = 2, distanceMeters = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = System.currentTimeMillis(),
        )
        val c = controller(bridge)

        c.setEnabled(true)

        assertEquals(HudController.Status.SEND_FAILED, c.status.value)
        assertEquals(
            HudSomeIpBridge.RESULT_LOCAL_ERROR,
            c.deliveryDiagnostics.value.lastResultCode,
        )
        assertEquals("push_loop:IllegalStateException", c.deliveryDiagnostics.value.lastFailure)
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `clear delivery does not replace last guidance success evidence`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        every { bridge.fireEvent(any(), any()) } returns 0
        NavGuidanceHub.update(
            com.bydmate.app.navdata.NavGuidance(maneuverGaode = 2, distanceMeters = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = System.currentTimeMillis(),
        )
        val c = controller(bridge)
        c.setEnabled(true)
        val guidanceAt = c.deliveryDiagnostics.value.lastGuidanceFrameSuccessAtMs
        assertTrue(guidanceAt != null)
        assertEquals(HudFrameKind.GUIDANCE, c.deliveryDiagnostics.value.lastDeliveryKind)

        c.setEnabled(false)

        awaitTrue { c.status.value == HudController.Status.OFF }
        assertEquals(guidanceAt, c.deliveryDiagnostics.value.lastGuidanceFrameSuccessAtMs)
        assertEquals(HudFrameKind.CLEAR, c.deliveryDiagnostics.value.lastDeliveryKind)
        assertTrue(c.deliveryDiagnostics.value.lastClearSuccessAtMs != null)
    }

    @Test fun `successful frame edge records recovery timestamp`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        every { bridge.fireEvent(any(), any()) } returnsMany listOf(-7, 0)
        NavGuidanceHub.update(
            com.bydmate.app.navdata.NavGuidance(maneuverGaode = 2, distanceMeters = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = System.currentTimeMillis(),
        )
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.SEND_FAILED, c.status.value)

        awaitTrue { c.status.value == HudController.Status.ON }

        assertTrue(c.deliveryDiagnostics.value.lastRecoveredAtMs != null)
        c.setEnabled(false)
    }

    @Test fun `stop sends clear frame before stopService and unbind`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        c.setEnabled(false)
        verifyOrder {
            bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
            bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI)
            bridge.unbind()
        }
        assertFalse(NavA11yFeed.isEnabled)
        assertEquals(HudController.Status.OFF, c.status.value)
    }

    @Test fun `toggle off during slow bind cancels start and unbinds`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } coAnswers { delay(30_000); true }
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            bridgeFactory = { _, _ -> bridge }
        }
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.CONNECTING }
        c.setEnabled(false)
        awaitTrue { runCatching { verify { bridge.unbind() } }.isSuccess }
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    @Test fun `speed sign pref default on and persists`() {
        val c = controller()
        assertTrue(c.isSpeedSignEnabled())
        c.setSpeedSignEnabled(false)
        assertFalse(c.isSpeedSignEnabled())
    }

    @Test fun `requiresA11y false when disabled`() {
        assertFalse(controller().requiresA11y())
    }

    @Test fun `connection lost automatically rebuilds the HUD channel`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        var onLost: (String) -> Unit = {}
        val c = HudController(context, helperClient, helperBootstrap).apply {
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            reconnectDelayMs = { 0L }
            bridgeFactory = { _, lost -> onLost = lost; bridge }
        }
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        onLost("binding_died")   // gateway binding died
        awaitTrue {
            runCatching {
                verify(exactly = 2) { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
            }.isSuccess
        }
        assertEquals(HudController.Status.ON, c.status.value)
        assertTrue(NavA11yFeed.isEnabled)
        c.setEnabled(false)
    }

    @Test fun `watchdog rebuilds false ON state when bridge binder is unavailable`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = controller(bridge)
        c.setEnabled(true)
        assertEquals(HudController.Status.ON, c.status.value)
        every { bridge.isConnected() } returns false

        c.ensureRunning("periodic")

        awaitTrue {
            runCatching {
                verify(exactly = 2) { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
            }.isSuccess
        }
        assertEquals(HudController.Status.ON, c.status.value)
        c.setEnabled(false)
    }

    @Test fun `stop retries rejected clear frame before transport teardown`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        every { bridge.fireEvent(any(), any()) } returnsMany listOf(-7, -7, 0)
        val c = controller(bridge)
        c.setEnabled(true)

        c.setEnabled(false)

        awaitTrue {
            runCatching {
                verify(exactly = HudPushLoop.CLEAR_MAX_ATTEMPTS) {
                    bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
                }
            }.isSuccess
        }
        verifyOrder {
            bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
            bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI)
            bridge.unbind()
        }
    }

    @Test fun `failed teardown clear remains visible after controller reaches OFF`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        every { bridge.fireEvent(any(), any()) } returns -7
        val c = controller(bridge)
        c.setEnabled(true)

        c.setEnabled(false)

        awaitTrue { c.status.value == HudController.Status.OFF }
        assertEquals("teardown_clear_rejected", c.deliveryDiagnostics.value.lastFailure)
        assertEquals(-7, c.deliveryDiagnostics.value.lastResultCode)
        verify(exactly = HudPushLoop.CLEAR_MAX_ATTEMPTS) {
            bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, any())
        }
    }

    @Test fun `initial bind timeout retries without restarting TrackingService`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = mockk<HudSomeIpBridge>(relaxed = true)
        coEvery { bridge.bind() } returnsMany listOf(false, true)
        every { bridge.startService(any()) } returns 0
        val c = controller(bridge)

        c.setEnabled(true)

        awaitTrue { c.status.value == HudController.Status.ON }
        coVerify(exactly = 2) { bridge.bind() }
        verify(exactly = 1) { bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI) }
        assertTrue(c.deliveryDiagnostics.value.lastRecoveredAtMs != null)
        c.setEnabled(false)
    }

    @Test fun `reconnect policy is bounded and waits for sustained send failure`() {
        assertEquals(5_000L, HudController.reconnectDelayForAttempt(0))
        assertEquals(15_000L, HudController.reconnectDelayForAttempt(1))
        assertEquals(30_000L, HudController.reconnectDelayForAttempt(2))
        assertEquals(60_000L, HudController.reconnectDelayForAttempt(20))
        assertFalse(
            HudController.shouldReconnectAfterSendFailures(
                HudController.SEND_FAILURES_BEFORE_RECONNECT - 1,
            ),
        )
        assertTrue(
            HudController.shouldReconnectAfterSendFailures(
                HudController.SEND_FAILURES_BEFORE_RECONNECT,
            ),
        )
    }

    @Test fun `service restart stop then start lands ON with ordered default scope`() {
        installSomeIp()
        coEvery { helperBootstrap.ensureRunning() } returns true
        val bridge = connectedBridge()
        val c = HudController(context, helperClient, helperBootstrap).apply {
            bridgeFactory = { _, _ -> bridge }   // scope left at its ordered default
        }
        c.setEnabled(true)
        awaitTrue { c.status.value == HudController.Status.ON }
        c.stop()             // TrackingService.onDestroy
        c.startIfEnabled()   // new service instance onCreate
        awaitTrue { c.status.value == HudController.Status.ON }
        c.setEnabled(false)
        awaitTrue { c.status.value == HudController.Status.OFF }
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond())
    }
}
