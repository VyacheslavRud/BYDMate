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
        eta: Int = 0, total: Int = 0, limit: Int = 0,
    ) = NavGuidance(gaode, dist, road, eta, total, limit)

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

    @Test fun `a11y replacement clears fields missing after reroute`() {
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
        assertEquals("", rerouted.road)
        assertEquals(0, rerouted.etaSeconds)
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

    @Test fun `notification becomes primary after a11y source expires`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.updateFromNotification(
            data(gaode = 1, dist = 900),
            nowMs = 50_000,
        )

        val snapshot = NavGuidanceHub.snapshot(nowMs = 91_001)
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

    @Test fun `snapshot expires after timeout`() {
        NavGuidanceHub.update(data(gaode = 2), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 1000 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 1001 + NavGuidanceHub.ACTIVE_TIMEOUT_MS).active)
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

    @Test fun `single no-guidance signal deactivates via snapshot after deadline`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 500), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 5_000).active)      // deadline not reached yet
        assertFalse(NavGuidanceHub.snapshot(nowMs = 12_001).active)    // >=10 s, NO second event
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

    @Test fun `notification end deactivates notification-only guidance immediately`() {
        NavGuidanceHub.updateFromNotification(
            data(gaode = 1, dist = 500, road = "ул. Ленина"),
            nowMs = 1_000,
        )
        assertTrue(NavGuidanceHub.snapshot(nowMs = 1_500).active)
        NavGuidanceHub.markNotificationEnded(nowMs = 2_000)
        assertFalse(NavGuidanceHub.snapshot(nowMs = 2_100).active)
    }

    @Test fun `notification end ignored while a11y guidance is fresh`() {
        NavGuidanceHub.update(data(gaode = 2, dist = 300), NavGuidanceHub.Source.A11Y, nowMs = 1_000)
        NavGuidanceHub.markNotificationEnded(nowMs = 2_000)
        assertTrue(NavGuidanceHub.snapshot(nowMs = 2_100).active)
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

    @Test fun `new route evaluates no-guidance deadline before merging`() {
        NavGuidanceHub.update(
            data(gaode = 2, dist = 250, road = "Старая улица"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000,
        )
        NavGuidanceHub.markNoGuidance(nowMs = 2_000)

        // No snapshot() call in between: update itself must notice that the old route expired.
        NavGuidanceHub.update(
            data(dist = 900),
            NavGuidanceHub.Source.A11Y,
            nowMs = 12_001,
        )

        val fresh = NavGuidanceHub.snapshot(nowMs = 12_001)
        assertTrue(fresh.active)
        assertEquals(0, fresh.maneuverGaode)
        assertEquals("", fresh.road)
        assertEquals(900, fresh.distanceMeters)
    }
}
