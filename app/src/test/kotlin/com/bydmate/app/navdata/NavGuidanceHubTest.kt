package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavGuidanceHubTest {

    @Before fun reset() = NavGuidanceHub.reset()

    private fun data(
        gaode: Int = 0, dist: Int = 0, road: String = "",
        eta: Int = 0, arrival: String = "", total: Int = 0, limit: Int = 0,
    ) = NavGuidance(
        maneuverGaode = gaode,
        distanceMeters = dist,
        road = road,
        etaSeconds = eta,
        arrivalTime = arrival,
        totalDistMeters = total,
        speedLimit = limit,
    )

    @Test fun `update activates and fills fields`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250, road = "ул. Ленина", eta = 300, total = 5000, limit = 60),
            NavGuidanceHub.Source.A11Y, nowMs = 1000)
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertTrue(s.active)
        assertEquals(2, s.maneuverGaode)
        assertEquals(250, s.distanceMeters)
        assertEquals("ул. Ленина", s.road)
        assertEquals(300, s.etaSeconds)
        assertEquals(5000, s.totalDistMeters)
        assertEquals(60, s.speedLimit)
        assertEquals(1000, s.lastUpdateMs)
    }

    @Test fun `empty update is ignored and cannot keep stale route artificially fresh`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250, road = "ул. Ленина"), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.update(data(), NavGuidanceHub.Source.A11Y, nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(250, s.distanceMeters)
        assertEquals("ул. Ленина", s.road)
        assertEquals(1000, s.lastUpdateMs)
    }

    @Test fun `sources merge into one snapshot`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 300), NavGuidanceHub.Source.NOTIFICATION, nowMs = 1000)
        NavGuidanceHub.update(data(limit = 60), NavGuidanceHub.Source.A11Y, nowMs = 2000)
        val s = NavGuidanceHub.snapshot(nowMs = 2000)
        assertEquals(2, s.maneuverGaode)
        assertEquals(300, s.distanceMeters)
        assertEquals(60, s.speedLimit)
        assertEquals(NavGuidanceHub.Source.A11Y, s.source)
    }

    @Test fun `notification supplies right turn while fresh a11y maneuver is unknown`() {
        NavGuidanceHub.update(
            data(dist = 520, road = "Live road"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(
            data(gaode = 2, dist = 500),
            nowMs = 1_100,
        )

        val snapshot = NavGuidanceHub.snapshot(nowMs = 1_100)
        assertEquals(NavGuidanceHub.Source.A11Y, snapshot.source)
        assertEquals(2, snapshot.maneuverGaode)
        assertEquals(520, snapshot.distanceMeters)
        assertEquals("Live road", snapshot.road)
    }

    @Test fun `HUD refresh generation is monotonic and resettable`() {
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        assertEquals(0L, NavGuidanceHub.snapshot(nowMs = 1_000).hudRefreshGeneration)
        NavGuidanceHub.requestHudRefresh()
        NavGuidanceHub.requestHudRefresh()
        assertEquals(2L, NavGuidanceHub.snapshot(nowMs = 1_000).hudRefreshGeneration)

        NavGuidanceHub.reset()
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 2_000)
        assertEquals(0L, NavGuidanceHub.snapshot(nowMs = 2_000).hudRefreshGeneration)
    }

    @Test fun `partial same-source update retains route fields inside bounded hold`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Старая улица", eta = 300),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.update(
            data(gaode = 1, dist = 900),
            NavGuidanceHub.Source.A11Y,
            nowMs = 2_000,
        )

        val rerouted = NavGuidanceHub.snapshot(nowMs = 2_000)
        assertEquals(1, rerouted.maneuverGaode)
        assertEquals(900, rerouted.distanceMeters)
        assertEquals("Старая улица", rerouted.road)
        assertEquals(300, rerouted.etaSeconds)
        assertEquals(1_000, rerouted.etaUpdatedAtMs)

        val expiredFields = NavGuidanceHub.snapshot(
            nowMs = 1_001 + NavGuidanceHub.ROUTE_TEXT_HOLD_MS,
        )
        assertTrue(expiredFields.active)
        assertEquals("", expiredFields.road)
        assertEquals(0, expiredFields.etaSeconds)
    }

    @Test fun `distance progress renews a recognized maneuver without repeating its text`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 500, road = "Main Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.update(
            data(dist = 350, road = "Main Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 25_000,
        )
        NavGuidanceHub.update(
            data(dist = 200, road = "Main Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 49_000,
        )

        assertEquals(2, NavGuidanceHub.snapshot(nowMs = 70_000).maneuverGaode)
    }

    @Test fun `distance jump clears retained maneuver until a new direction is known`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 50, road = "First Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.update(
            data(dist = 600, road = "Second Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 2_000,
        )

        val afterTurn = NavGuidanceHub.snapshot(nowMs = 2_000)
        assertTrue(afterTurn.active)
        assertEquals(0, afterTurn.maneuverGaode)
        assertEquals(600, afterTurn.distanceMeters)
        assertEquals("Second Street", afterTurn.road)
    }

    @Test fun `small distance increase is treated as GPS jitter and keeps maneuver`() {
        NavGuidanceHub.update(
            data(gaode = 1, dist = 300, road = "Main Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.update(
            data(dist = 350, road = "Main Street"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 20_000,
        )

        assertEquals(1, NavGuidanceHub.snapshot(nowMs = 45_000).maneuverGaode)
    }

    @Test fun `distance hold spans two refresh periods but still expires`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )

        assertEquals(
            250,
            NavGuidanceHub.snapshot(1_000 + NavGuidanceHub.DISTANCE_HOLD_MS).distanceMeters,
        )
        NavGuidanceHub.markRouteObserved(
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_001 + NavGuidanceHub.DISTANCE_HOLD_MS,
        )
        val expired = NavGuidanceHub.snapshot(
            nowMs = 1_001 + NavGuidanceHub.DISTANCE_HOLD_MS,
        )
        assertTrue(expired.active)
        assertEquals(0, expired.distanceMeters)
    }

    @Test fun `fresh a11y wins while notification only fills missing values`() {
        NavGuidanceHub.updateFromNotification(
            data(gaode = 1, dist = 800, road = "Notification road", eta = 600),
            nowMs = 1_000,
        )
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Live road"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 2_000,
        )

        // A later notification must still not overwrite the live-window fields.
        NavGuidanceHub.updateFromNotification(
            data(gaode = 3, dist = 700, road = "Newer notification", eta = 500),
            nowMs = 3_000,
        )
        val snapshot = NavGuidanceHub.snapshot(nowMs = 3_000)
        assertEquals(NavGuidanceHub.Source.A11Y, snapshot.source)
        assertEquals(2, snapshot.maneuverGaode)
        assertEquals(250, snapshot.distanceMeters)
        assertEquals("Live road", snapshot.road)
        assertEquals(500, snapshot.etaSeconds)
    }

    @Test fun `newer notification remains primary while its maneuver fields are current`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(
            data(gaode = 1, dist = 900),
            nowMs = 50_000,
        )

        val snapshot = NavGuidanceHub.snapshot(nowMs = 60_000)
        assertTrue(snapshot.active)
        assertEquals(NavGuidanceHub.Source.NOTIFICATION, snapshot.source)
        assertEquals(1, snapshot.maneuverGaode)
        assertEquals(900, snapshot.distanceMeters)
    }

    @Test fun `much newer notification replaces stale but not expired a11y`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Old A11Y road", eta = 800),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(
            data(gaode = 1, dist = 900, road = "Current notification", eta = 300),
            nowMs = 20_000,
        )

        val snapshot = NavGuidanceHub.snapshot(nowMs = 20_000)
        assertEquals(NavGuidanceHub.Source.NOTIFICATION, snapshot.source)
        assertEquals(1, snapshot.maneuverGaode)
        assertEquals(900, snapshot.distanceMeters)
        assertEquals("Current notification", snapshot.road)
        assertEquals(300, snapshot.etaSeconds)
        assertEquals(20_000, snapshot.lastUpdateMs)
    }

    @Test fun `newer notification takes over once a11y priority grace elapses`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Old A11Y road", eta = 800),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(data(gaode = 1, dist = 900), nowMs = 4_000)

        val snapshot = NavGuidanceHub.snapshot(
            nowMs = 1_001 + NavGuidanceHub.A11Y_PRIORITY_GRACE_MS,
        )
        assertEquals(NavGuidanceHub.Source.NOTIFICATION, snapshot.source)
        assertEquals(1, snapshot.maneuverGaode)
        assertEquals(900, snapshot.distanceMeters)
        assertEquals("", snapshot.road)
        assertEquals(0, snapshot.etaSeconds)
    }

    @Test fun `stale secondary source cannot fill missing fields`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Old A11Y road", eta = 800),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(data(gaode = 1, dist = 900), nowMs = 20_000)

        val snapshot = NavGuidanceHub.snapshot(nowMs = 20_000)
        assertEquals("", snapshot.road)
        assertEquals(0, snapshot.etaSeconds)
    }

    @Test fun `route lease expires after one minute without any route evidence`() {
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 1000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 1001 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        assertEquals(
            NavGuidanceHub.RouteEndReason.LEASE_EXPIRED,
            NavGuidanceHub.diagnostics().lastRouteEndReason,
        )
    }

    @Test fun `delayed route presence refresh keeps route active without stale arrow forever`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 5_000), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markRouteObserved(
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000 + NavGuidanceHub.ROUTE_LEASE_TIMEOUT_MS + 1,
        )

        val stillActive = NavGuidanceHub.snapshot(
            nowMs = 1_000 + NavGuidanceHub.ROUTE_LEASE_TIMEOUT_MS + 2,
        )
        assertTrue(stillActive.active)
        assertEquals(0, stillActive.maneuverGaode)
        assertEquals(1_000, stillActive.lastUpdateMs)
        assertEquals(
            1_000 + NavGuidanceHub.ROUTE_LEASE_TIMEOUT_MS + 1,
            stillActive.lastRouteObservedMs,
        )
    }

    @Test fun `periodic unchanged guidance refresh keeps long straight maneuver alive`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 5_000), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.update(data(gaode = 2, dist = 5_000), NavGuidanceHub.Source.A11Y, nowMs = 45_000)

        val stillActive = NavGuidanceHub.snapshot(nowMs = 61_000)
        assertTrue(stillActive.active)
        assertEquals(2, stillActive.maneuverGaode)
        assertEquals(45_000, stillActive.lastUpdateMs)
    }

    @Test fun `relative ETA keeps its own source anchor`() {
        NavGuidanceHub.updateFromNotification(data(eta = 900), nowMs = 10_000)
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 11_000)

        val snapshot = NavGuidanceHub.snapshot(nowMs = 11_000)
        assertEquals(900, snapshot.etaSeconds)
        assertEquals(10_000, snapshot.etaUpdatedAtMs)
    }

    @Test fun `direct arrival clock is retained independently from relative ETA`() {
        NavGuidanceHub.update(
            data(gaode = 2, eta = 900, arrival = "21:45"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 10_000,
        )
        NavGuidanceHub.update(data(dist = 400), NavGuidanceHub.Source.A11Y, nowMs = 11_000)

        val snapshot = NavGuidanceHub.snapshot(nowMs = 11_000)
        assertEquals("21:45", snapshot.arrivalTime)
        assertEquals(900, snapshot.etaSeconds)
        assertEquals(10_000, snapshot.etaUpdatedAtMs)
    }

    @Test fun `unchanged rounded ETA keeps its original arrival anchor`() {
        NavGuidanceHub.update(
            data(gaode = 2, eta = 18 * 60),
            NavGuidanceHub.Source.A11Y,
            nowMs = 10_000,
        )
        NavGuidanceHub.update(
            data(gaode = 2, eta = 18 * 60),
            NavGuidanceHub.Source.A11Y,
            nowMs = 25_000,
        )

        assertEquals(10_000, NavGuidanceHub.snapshot(nowMs = 25_000).etaUpdatedAtMs)
    }

    @Test fun `changed rounded ETA advances its arrival anchor`() {
        NavGuidanceHub.update(
            data(gaode = 2, eta = 18 * 60),
            NavGuidanceHub.Source.A11Y,
            nowMs = 10_000,
        )
        NavGuidanceHub.update(
            data(gaode = 2, eta = 17 * 60),
            NavGuidanceHub.Source.A11Y,
            nowMs = 25_000,
        )

        assertEquals(25_000, NavGuidanceHub.snapshot(nowMs = 25_000).etaUpdatedAtMs)
    }

    @Test fun `speed limit clears after its own timeout`() {
        NavGuidanceHub.update(data(gaode = 2, limit = 60), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.update(data(dist = 200), NavGuidanceHub.Source.A11Y,
            nowMs = 1000 + NavGuidanceHub.SPEED_LIMIT_TIMEOUT_MS + 1)
        val s = NavGuidanceHub.snapshot(nowMs = 1000 + NavGuidanceHub.SPEED_LIMIT_TIMEOUT_MS + 1)
        assertTrue(s.active)
        assertEquals(0, s.speedLimit)
    }

    @Test fun `partial source update keeps speed limit only inside speed TTL`() {
        NavGuidanceHub.update(data(gaode = 2, limit = 60), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.update(data(dist = 200), NavGuidanceHub.Source.A11Y, nowMs = 2_000)
        assertEquals(60, NavGuidanceHub.snapshot(nowMs = 2_000).speedLimit)
        assertEquals(
            0,
            NavGuidanceHub.snapshot(nowMs = 1_001 + NavGuidanceHub.SPEED_LIMIT_TIMEOUT_MS).speedLimit,
        )
    }

    @Test fun `no-guidance streak deactivates after hysteresis`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.markNoGuidance(nowMs = 2000)   // streak starts
        assertTrue(NavGuidanceHub.snapshot(nowMs = 2000).active)
        NavGuidanceHub.markNoGuidance(nowMs = 2000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 2000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS).active)
        assertEquals(
            NavGuidanceHub.RouteEndReason.EXPLICIT_NO_ROUTE,
            NavGuidanceHub.diagnostics().lastRouteEndReason,
        )
    }

    @Test fun `guidance update resets no-guidance streak`() {
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.markNoGuidance(nowMs = 2000)
        NavGuidanceHub.update(data(dist = 100), NavGuidanceHub.Source.A11Y, nowMs = 3000)
        NavGuidanceHub.markNoGuidance(nowMs = 4000)   // new streak, not a continuation
        assertTrue(NavGuidanceHub.snapshot(nowMs = 4000 + 5000).active)
    }

    @Test fun `notification update accepts parsed Waze guidance`() {
        NavGuidanceHub.updateFromNotification(
            data(gaode = 2, dist = 300, road = "улица Ленина"),
            nowMs = 1000,
        )
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertTrue(s.active)
        assertEquals(2, s.maneuverGaode)
        assertEquals(300, s.distanceMeters)
        assertEquals("улица Ленина", s.road)
    }

    @Test fun `blank notification update is ignored`() {
        NavGuidanceHub.updateFromNotification(data(), nowMs = 1000)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 1000).active)
    }

    @Test fun `reset clears everything`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 250), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.reset()
        val s = NavGuidanceHub.snapshot(nowMs = 1000)
        assertFalse(s.active)
        assertEquals(0, s.maneuverGaode)
    }

    @Test fun `single no-guidance signal never deactivates route by itself`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 5_000).active)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 12_001).active)
        assertEquals(1, NavGuidanceHub.diagnostics().explicitNoRouteObservationCount)
    }

    @Test fun `indeterminate window cancels pending no-route confirmation`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        NavGuidanceHub.markRouteIndeterminate()
        NavGuidanceHub.markNoGuidance(nowMs = 7_000)

        assertTrue(NavGuidanceHub.snapshot(nowMs = 12_000).active)
        assertEquals(1, NavGuidanceHub.diagnostics().explicitNoRouteObservationCount)
    }

    @Test fun `guidance update cancels pending no-guidance deadline`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        NavGuidanceHub.update(data(dist = 400), NavGuidanceHub.Source.A11Y, nowMs = 3_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 20_000).active)
    }

    @Test fun `fresh notification also cancels pending no-guidance deadline`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        NavGuidanceHub.updateFromNotification(data(gaode = 1, dist = 300), nowMs = 3_000)

        val snapshot = NavGuidanceHub.snapshot(nowMs = 12_001)
        assertTrue(snapshot.active)
        assertEquals(1, snapshot.maneuverGaode)
        assertEquals(300, snapshot.distanceMeters)
    }

    @Test fun `confirmed empty Waze window keeps independent live notification route`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 500, road = "A11Y road"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(
            data(gaode = 2, dist = 480, road = "Notification road"),
            nowMs = 2_000,
        )

        assertEquals(
            NavGuidanceHub.NoGuidanceResult.PENDING,
            NavGuidanceHub.markNoGuidance(nowMs = 3_000),
        )
        assertEquals(
            NavGuidanceHub.NoGuidanceResult.A11Y_CLEARED_NOTIFICATION_RETAINED,
            NavGuidanceHub.markNoGuidance(
                nowMs = 3_000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS,
            ),
        )

        val retained = NavGuidanceHub.snapshot(
            nowMs = 3_000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS,
        )
        assertTrue(retained.active)
        assertEquals(NavGuidanceHub.Source.NOTIFICATION, retained.source)
        assertEquals(2, retained.maneuverGaode)
        assertEquals("Notification road", retained.road)
        assertEquals(null, NavGuidanceHub.diagnostics().lastRouteEndReason)
    }

    @Test fun `notification end uses grace before deactivating notification-only guidance`() {
        NavGuidanceHub.updateFromNotification(
            data(gaode = 1, dist = 500, road = "ул. Ленина"),
            nowMs = 1_000,
        )
        assertTrue(NavGuidanceHub.snapshot(nowMs = 1_500).active)
        NavGuidanceHub.markNotificationEnded(nowMs = 2_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 2_100).active)
        assertFalse(
            NavGuidanceHub.snapshot(
                nowMs = 2_001 + NavGuidanceHub.NOTIFICATION_END_GRACE_MS,
            ).active,
        )
        assertEquals(
            NavGuidanceHub.RouteEndReason.NOTIFICATION_REMOVED,
            NavGuidanceHub.diagnostics().lastRouteEndReason,
        )
    }

    @Test fun `notification end ignored while a11y guidance is fresh`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 300), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNotificationEnded(nowMs = 2_000)
        assertTrue(
            NavGuidanceHub.snapshot(
                nowMs = 2_001 + NavGuidanceHub.NOTIFICATION_END_GRACE_MS,
            ).active,
        )
    }

    @Test fun `new route after expiry does not inherit stale fields`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Старая улица", limit = 60),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        assertFalse(NavGuidanceHub.snapshot(nowMs = 1_001 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        NavGuidanceHub.update(
            data(dist = 900),
            NavGuidanceHub.Source.NOTIFICATION,
            nowMs = 100_000,
        )
        val fresh = NavGuidanceHub.snapshot(nowMs = 100_000)
        assertTrue(fresh.active)
        assertEquals(0, fresh.maneuverGaode)
        assertEquals("", fresh.road)
        assertEquals(0, fresh.speedLimit)
        assertEquals(900, fresh.distanceMeters)
    }

    @Test fun `new route after confirmed no-guidance does not inherit old fields`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Старая улица"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        NavGuidanceHub.markNoGuidance(
            nowMs = 2_000 + NavGuidanceHub.NO_GUIDANCE_DEACTIVATE_MS,
        )

        NavGuidanceHub.update(
            data(dist = 900),
            NavGuidanceHub.Source.A11Y,
            nowMs = 7_000,
        )

        val fresh = NavGuidanceHub.snapshot(nowMs = 7_000)
        assertTrue(fresh.active)
        assertEquals(0, fresh.maneuverGaode)
        assertEquals("", fresh.road)
        assertEquals(900, fresh.distanceMeters)
        assertEquals(null, NavGuidanceHub.diagnostics().lastRouteEndReason)
    }
}
