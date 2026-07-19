package com.bydmate.app.data.diagnostics

import com.bydmate.app.hud.HudController
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudIncidentRecorderTest {

    @Test fun `outage confirmation window is five seconds`() {
        assertEquals(5_000L, HudIncidentClassifier.OUTAGE_CONFIRM_MS)
    }

    @Test fun `recent service start is classified before downstream symptoms`() {
        val sample = sample(
            serviceUptimeMs = 6_000L,
            hudStatus = HudController.Status.SEND_FAILED,
            resultCode = -1,
        )

        assertEquals(HudIncidentCause.SERVICE_RESTART, HudIncidentClassifier.cause(sample))
    }

    @Test fun `rejected SOME IP frame is classified as transport failure`() {
        val sample = sample(
            hudStatus = HudController.Status.SEND_FAILED,
            resultCode = -7,
            failure = "frame_rejected",
        )

        assertEquals(HudIncidentCause.SOME_IP, HudIncidentClassifier.cause(sample))
    }

    @Test fun `lost accessibility connection explains an A11Y route outage`() {
        val sample = sample(accessibilityConnected = false)

        assertEquals(HudIncidentCause.ACCESSIBILITY, HudIncidentClassifier.cause(sample))
    }

    @Test fun `unreachable Waze window is distinguished from stale route data`() {
        val sample = sample(wazeWindowReachable = false)

        assertEquals(HudIncidentCause.WAZE_WINDOW, HudIncidentClassifier.cause(sample))
    }

    @Test fun `stale active route is classified as Waze data loss`() {
        val staleAge = NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1L
        val sample = sample(routeAgeMs = staleAge, wazeGuidanceAgeMs = staleAge)

        assertEquals(HudIncidentCause.WAZE_DATA, HudIncidentClassifier.cause(sample))
    }

    @Test fun `fresh notification route does not require accessibility guidance`() {
        val sample = sample(
            routeSource = NavGuidanceHub.Source.NOTIFICATION.name,
            accessibilityConnected = false,
            feedEnabled = false,
            wazeWindowReachable = false,
            wazeGuidanceAgeMs = null,
        )

        assertEquals(HudIncidentCause.HUD_PIPELINE, HudIncidentClassifier.cause(sample))
    }

    @Test fun `healthy inputs with missing frame are conservatively classified as HUD pipeline`() {
        assertEquals(HudIncidentCause.HUD_PIPELINE, HudIncidentClassifier.cause(sample()))
    }

    @Test fun `accepted frames remain healthy between event driven Waze updates`() {
        val stale = sample(
            routeAgeMs = 8_000L,
            wazeGuidanceAgeMs = 8_000L,
        ).copy(lastFrameSuccessAgeMs = 100L)

        assertEquals(true, HudIncidentClassifier.deliveryHealthy(stale))
        assertEquals(HudIncidentCause.HUD_PIPELINE, HudIncidentClassifier.cause(stale))
    }

    @Test fun `lost accessibility marks A11Y delivery unhealthy while frames repeat`() {
        val disconnected = sample(accessibilityConnected = false)
            .copy(lastFrameSuccessAgeMs = 100L)

        assertEquals(false, HudIncidentClassifier.deliveryHealthy(disconnected))
        assertEquals(HudIncidentCause.ACCESSIBILITY, HudIncidentClassifier.cause(disconnected))
    }

    @Test fun `unreachable A11Y window marks delivery unhealthy without event age heuristics`() {
        val unreachable = sample(
            wazeWindowReachable = false,
            routeAgeMs = 8_000L,
            wazeGuidanceAgeMs = 8_000L,
        ).copy(lastFrameSuccessAgeMs = 100L)

        assertEquals(false, HudIncidentClassifier.deliveryHealthy(unreachable))
        assertEquals(HudIncidentCause.WAZE_WINDOW, HudIncidentClassifier.cause(unreachable))
    }

    @Test fun `normal route-end grace stays healthy while final frame is delivered`() {
        val ending = sample(
            routeAgeMs = 8_000L,
            wazeGuidanceAgeMs = 8_000L,
            wazeNoGuidanceAgeMs = 500L,
        ).copy(lastFrameSuccessAgeMs = 100L)

        assertEquals(true, HudIncidentClassifier.deliveryHealthy(ending))
    }

    @Test fun `fresh guidance and recent SOME IP frame are healthy`() {
        assertEquals(
            true,
            HudIncidentClassifier.deliveryHealthy(sample().copy(lastFrameSuccessAgeMs = 100L)),
        )
    }

    @Test fun `explicit readable no guidance does not blame stale Waze data`() {
        val ending = sample(
            routeAgeMs = 12_000L,
            wazeEventAgeMs = 10_000L,
            wazeGuidanceAgeMs = 12_000L,
            wazeNoGuidanceAgeMs = 10_000L,
        )

        assertEquals(HudIncidentCause.HUD_PIPELINE, HudIncidentClassifier.cause(ending))
        assertNull(
            HudIncidentClassifier.routeLossCause(
                ending.copy(routeActive = false, routeSource = null),
                NavGuidanceHub.Source.A11Y.name,
                12_000L,
            ),
        )
    }

    @Test fun `normal route end with recent readable Waze event is not an incident`() {
        val ended = sample(
            routeActive = false,
            routeSource = null,
            wazeWindowReachable = true,
            wazeEventAgeMs = 500L,
        )

        assertNull(
            HudIncidentClassifier.routeLossCause(
                ended,
                NavGuidanceHub.Source.A11Y.name,
                500L,
            ),
        )
    }

    @Test fun `route loss uses the previous source before blaming accessibility`() {
        val ended = sample(
            routeActive = false,
            routeSource = null,
            accessibilityConnected = false,
            feedEnabled = false,
            wazeEventAgeMs = 500L,
        )

        assertEquals(
            HudIncidentCause.ACCESSIBILITY,
            HudIncidentClassifier.routeLossCause(
                ended,
                NavGuidanceHub.Source.A11Y.name,
                500L,
            ),
        )
        assertNull(
            HudIncidentClassifier.routeLossCause(
                ended.copy(wazeWindowReachable = true),
                NavGuidanceHub.Source.NOTIFICATION.name,
                500L,
            ),
        )
    }

    @Test fun `stale notification route loss is Waze data but fresh notification removal is normal`() {
        val ended = sample(
            routeActive = false,
            routeSource = null,
            wazeEventAgeMs = null,
            wazeGuidanceAgeMs = null,
        )

        assertEquals(
            HudIncidentCause.WAZE_DATA,
            HudIncidentClassifier.routeLossCause(
                ended,
                NavGuidanceHub.Source.NOTIFICATION.name,
                NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1L,
            ),
        )
        assertNull(
            HudIncidentClassifier.routeLossCause(
                ended,
                NavGuidanceHub.Source.NOTIFICATION.name,
                500L,
            ),
        )
    }

    @Test fun `incident JSON preserves evidence and nullable values`() {
        val original = incident(
            cause = HudIncidentCause.WAZE_WINDOW,
            recoveredAtMs = 20_000L,
            resultCode = null,
            failure = "window_unreachable",
        )

        assertEquals(listOf(original), HudIncidentJson.decode(HudIncidentJson.encode(listOf(original))))
    }

    @Test fun `incident history keeps only newest thirty entries`() {
        val all = (1L..35L).fold(emptyList<HudIncident>()) { current, timestamp ->
            HudIncidentRecorder.appendBounded(current, incident(detectedAtMs = timestamp))
        }

        assertEquals(HudIncidentRecorder.MAX_INCIDENTS, all.size)
        assertEquals(6L, all.first().detectedAtMs)
        assertEquals(35L, all.last().detectedAtMs)
    }

    private fun sample(
        serviceUptimeMs: Long = 60_000L,
        hudStatus: HudController.Status = HudController.Status.ON,
        resultCode: Int? = 0,
        failure: String? = null,
        routeActive: Boolean = true,
        routeSource: String? = NavGuidanceHub.Source.A11Y.name,
        routeAgeMs: Long? = 500L,
        accessibilityConnected: Boolean = true,
        feedEnabled: Boolean = true,
        wazeWindowReachable: Boolean? = true,
        wazeEventAgeMs: Long? = 500L,
        wazeGuidanceAgeMs: Long? = 500L,
        wazeNoGuidanceAgeMs: Long? = null,
    ) = HudHealthSample(
        nowMs = 100_000L,
        serviceUptimeMs = serviceUptimeMs,
        hudEnabled = true,
        hudStatus = hudStatus,
        lastFrameSuccessAgeMs = 6_000L,
        resultCode = resultCode,
        failure = failure,
        routeActive = routeActive,
        routeSource = routeSource,
        routeAgeMs = routeAgeMs,
        accessibilityConnected = accessibilityConnected,
        feedEnabled = feedEnabled,
        wazeWindowReachable = wazeWindowReachable,
        wazeEventAgeMs = wazeEventAgeMs,
        wazeGuidanceAgeMs = wazeGuidanceAgeMs,
        wazeNoGuidanceAgeMs = wazeNoGuidanceAgeMs,
    )

    private fun incident(
        detectedAtMs: Long = 10_000L,
        cause: HudIncidentCause = HudIncidentCause.SOME_IP,
        recoveredAtMs: Long? = null,
        resultCode: Int? = -1,
        failure: String? = null,
    ) = HudIncident(
        detectedAtMs = detectedAtMs,
        cause = cause,
        outageBeforeDetectionMs = 5_250L,
        recoveredAtMs = recoveredAtMs,
        hudStatus = HudController.Status.SEND_FAILED.name,
        resultCode = resultCode,
        failure = failure,
        routeSource = NavGuidanceHub.Source.A11Y.name,
        routeAgeMs = 700L,
        accessibilityConnected = true,
        feedEnabled = true,
        wazeWindowReachable = false,
        wazeEventAgeMs = 600L,
        wazeGuidanceAgeMs = 700L,
        wazeNoGuidanceAgeMs = null,
    )
}
