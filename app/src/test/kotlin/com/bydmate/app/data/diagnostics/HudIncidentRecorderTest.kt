package com.bydmate.app.data.diagnostics

import com.bydmate.app.hud.HudController
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class HudIncidentRecorderTest {

    @Test fun `outage confirmation window is five seconds`() {
        assertEquals(5_000L, HudIncidentClassifier.OUTAGE_CONFIRM_MS)
    }

    @Test fun `recent service start does not mask concrete transport failure`() {
        val sample = sample(
            serviceUptimeMs = 6_000L,
            hudStatus = HudController.Status.SEND_FAILED,
            resultCode = -1,
        )

        assertEquals(HudIncidentCause.SOME_IP, HudIncidentClassifier.cause(sample))
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
        val sample = sample(
            wazeWindowReachable = false,
            wazeWindowUnreachableAgeMs = 500L,
            wazeProbeResult = NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE,
        )

        assertEquals(HudIncidentCause.WAZE_WINDOW, HudIncidentClassifier.cause(sample))
    }

    @Test fun `stale active route is classified as Waze data loss`() {
        val staleAge = NavGuidanceHub.ACTIVE_TIMEOUT_MS + 1L
        val sample = sample(routeAgeMs = staleAge, wazeGuidanceAgeMs = staleAge)

        assertEquals(HudIncidentCause.WAZE_DATA, HudIncidentClassifier.cause(sample))
    }

    @Test fun `parser blindness is Waze data rather than window loss`() {
        val sample = sample(
            wazeWindowReachable = true,
            wazeUnreadableAgeMs = 500L,
            wazeProbeResult = NavA11yFeed.ProbeResult.ROUTE_UNREADABLE,
        )

        assertEquals(HudIncidentCause.WAZE_DATA, HudIncidentClassifier.cause(sample))
    }

    @Test fun `active route without renderable maneuver is Waze data outage`() {
        val sample = sample(routeManeuverGaode = 0, routeRenderable = false)

        assertEquals(false, HudIncidentClassifier.deliveryHealthy(sample))
        assertEquals(HudIncidentCause.WAZE_DATA, HudIncidentClassifier.cause(sample))
    }

    @Test fun `lease expiry after permanent window loss keeps Waze window cause`() {
        val ended = sample(
            routeActive = false,
            routeSource = null,
            routeEndReason = NavGuidanceHub.RouteEndReason.LEASE_EXPIRED,
            routeEndAgeMs = 500L,
            wazeWindowReachable = false,
            wazeWindowUnreachableAgeMs = 500L,
            wazeProbeResult = NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE,
        )

        assertEquals(
            HudIncidentCause.WAZE_WINDOW,
            HudIncidentClassifier.routeLossCause(
                ended,
                NavGuidanceHub.Source.A11Y.name,
                NavGuidanceHub.ROUTE_LEASE_TIMEOUT_MS + 1,
            ),
        )
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

    @Test fun `lost accessibility does not fabricate outage while guidance frames still succeed`() {
        val disconnected = sample(accessibilityConnected = false)
            .copy(lastFrameSuccessAgeMs = 100L)

        assertEquals(true, HudIncidentClassifier.deliveryHealthy(disconnected))
        assertEquals(HudIncidentCause.ACCESSIBILITY, HudIncidentClassifier.cause(disconnected))
    }

    @Test fun `unreachable A11Y window is evidence but not an outage while frames repeat`() {
        val unreachable = sample(
            wazeWindowReachable = false,
            routeAgeMs = 8_000L,
            wazeGuidanceAgeMs = 8_000L,
            wazeWindowUnreachableAgeMs = 500L,
            wazeProbeResult = NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE,
        ).copy(lastFrameSuccessAgeMs = 100L)

        assertEquals(true, HudIncidentClassifier.deliveryHealthy(unreachable))
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
            routeEndReason = NavGuidanceHub.RouteEndReason.EXPLICIT_NO_ROUTE,
            routeEndAgeMs = 10_000L,
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
            routeEndReason = NavGuidanceHub.RouteEndReason.EXPLICIT_NO_ROUTE,
            routeEndAgeMs = 500L,
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
                ended.copy(
                    routeEndReason = NavGuidanceHub.RouteEndReason.NOTIFICATION_REMOVED,
                    routeEndAgeMs = 500L,
                ),
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

    @Test fun `intentional stop cannot be reported as service restart`() {
        assertNull(
            HudIncidentRecorder.restartReferenceAt(
                nowMs = 100_000L,
                previousRuntimeActive = false,
                previousRouteActive = true,
                previousHudEnabled = true,
                previousReferenceAtMs = 99_000L,
            ),
        )
    }

    @Test fun `live restart preserves recent active HUD reference`() {
        assertEquals(
            99_000L,
            HudIncidentRecorder.restartReferenceAt(
                nowMs = 100_000L,
                previousRuntimeActive = true,
                previousRouteActive = true,
                previousHudEnabled = true,
                previousReferenceAtMs = 99_000L,
            ),
        )
        assertNull(
            HudIncidentRecorder.restartReferenceAt(
                nowMs = 300_000L,
                previousRuntimeActive = true,
                previousRouteActive = true,
                previousHudEnabled = true,
                previousReferenceAtMs = 99_000L,
            ),
        )
    }

    @Test fun `stale monitor generation cannot own recorder state`() {
        assertEquals(true, HudIncidentRecorder.ownsGeneration(expected = 4L, current = 4L))
        assertEquals(false, HudIncidentRecorder.ownsGeneration(expected = 3L, current = 4L))
    }

    @Test fun `malformed JSON entry does not erase valid incidents around it`() {
        val first = incident(detectedAtMs = 10L)
        val second = incident(detectedAtMs = 20L, cause = HudIncidentCause.WAZE_DATA)
        val encodedFirst = JSONArray(HudIncidentJson.encode(listOf(first))).getJSONObject(0)
        val encodedSecond = JSONArray(HudIncidentJson.encode(listOf(second))).getJSONObject(0)
        val raw = JSONArray()
            .put(encodedFirst)
            .put(JSONObject().put("cause", "SOME_IP"))
            .put("not-an-object")
            .put(encodedSecond)
            .toString()

        assertEquals(listOf(first, second), HudIncidentJson.decode(raw))
    }

    private fun sample(
        serviceUptimeMs: Long = 60_000L,
        hudStatus: HudController.Status = HudController.Status.ON,
        resultCode: Int? = 0,
        failure: String? = null,
        routeActive: Boolean = true,
        routeSource: String? = NavGuidanceHub.Source.A11Y.name,
        routeAgeMs: Long? = 500L,
        routeObservedAgeMs: Long? = 500L,
        routeManeuverGaode: Int = 2,
        routeRenderable: Boolean = true,
        routeEndReason: NavGuidanceHub.RouteEndReason? = null,
        routeEndAgeMs: Long? = null,
        accessibilityConnected: Boolean = true,
        feedEnabled: Boolean = true,
        wazeWindowReachable: Boolean? = true,
        wazeEventAgeMs: Long? = 500L,
        wazeGuidanceAgeMs: Long? = 500L,
        wazeNoGuidanceAgeMs: Long? = null,
        wazeWindowUnreachableAgeMs: Long? = null,
        wazeUnreadableAgeMs: Long? = null,
        wazeProbeResult: NavA11yFeed.ProbeResult? = NavA11yFeed.ProbeResult.GUIDANCE,
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
        routeObservedAgeMs = routeObservedAgeMs,
        routeManeuverGaode = routeManeuverGaode,
        routeRenderable = routeRenderable,
        routeEndReason = routeEndReason,
        routeEndAgeMs = routeEndAgeMs,
        accessibilityConnected = accessibilityConnected,
        feedEnabled = feedEnabled,
        wazeWindowReachable = wazeWindowReachable,
        wazeEventAgeMs = wazeEventAgeMs,
        wazeGuidanceAgeMs = wazeGuidanceAgeMs,
        wazeNoGuidanceAgeMs = wazeNoGuidanceAgeMs,
        wazeWindowUnreachableAgeMs = wazeWindowUnreachableAgeMs,
        wazeUnreadableAgeMs = wazeUnreadableAgeMs,
        wazeProbeResult = wazeProbeResult,
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
        routeObservedAgeMs = 500L,
        routeManeuverGaode = 2,
        routeRenderable = true,
        routeEndReason = null,
        accessibilityConnected = true,
        feedEnabled = true,
        wazeWindowReachable = false,
        wazeEventAgeMs = 600L,
        wazeGuidanceAgeMs = 700L,
        wazeNoGuidanceAgeMs = null,
        wazeWindowUnreachableAgeMs = 600L,
        wazeUnreadableAgeMs = null,
        wazeProbeResult = NavA11yFeed.ProbeResult.WINDOW_UNREACHABLE.name,
    )
}
