package com.bydmate.app.hud

import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class HudStreetLineTest {
    private val ru = HudStreetLine.Units("мин", "ч", "км", "м", ',')

    private fun snapshot(
        distance: Int = 1_200,
        road: String = "Nádražní",
        etaSeconds: Int = 25 * 60,
        arrival: String = "18:45",
        total: Int = 8_449,
        etaAtMs: Long = 1_000L,
    ) = NavGuidanceHub.Snapshot(
        active = true,
        distanceMeters = distance,
        road = road,
        etaSeconds = etaSeconds,
        arrivalTime = arrival,
        etaUpdatedAtMs = etaAtMs,
        totalDistMeters = total,
    )

    @Before fun reset() = NavGuidanceHub.reset()

    @Test fun `far from the turn the route goes before the street`() {
        assertEquals(
            "18:45 · 25 мин · 8,4 км | Nádražní",
            HudStreetLine.compose(snapshot(), 0L, routeInfoEnabled = true, units = ru),
        )
    }

    @Test fun `near the turn and when switched off only the street is shown`() {
        listOf(1, 150, HudStreetLine.STREET_ONLY_WITHIN_METERS).forEach { distance ->
            assertEquals("Nádražní", HudStreetLine.compose(snapshot(distance = distance), 0L, true, ru))
        }
        assertEquals("Nádražní", HudStreetLine.compose(snapshot(), 0L, routeInfoEnabled = false, units = ru))
    }

    @Test fun `unknown maneuver distance still shows the route`() {
        assertEquals(
            "18:45 · 25 мин · 8,4 км | Nádražní",
            HudStreetLine.compose(snapshot(distance = 0), 0L, true, ru),
        )
    }

    @Test fun `missing parts are left out and a missing street leaves only the route`() {
        assertEquals("Nádražní", HudStreetLine.compose(snapshot(etaSeconds = 0, arrival = "", total = 0), 0L, true, ru))
        assertEquals("18:45 · 25 мин · 8,4 км", HudStreetLine.compose(snapshot(road = " "), 0L, true, ru))
        assertEquals("850 м | Nádražní", HudStreetLine.compose(snapshot(etaSeconds = 0, arrival = "", total = 850), 0L, true, ru))
    }

    @Test fun `arrival is computed from the anchored remaining time when Waze shows none`() {
        val anchor = 1_700_000_000_000L
        val expected = Calendar.getInstance().apply { timeInMillis = anchor + 25 * 60_000L }
            .let { String.format(Locale.US, "%02d:%02d", it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
        assertEquals(
            "$expected · 25 мин · 8,4 км | Nádražní",
            HudStreetLine.compose(snapshot(arrival = "", etaAtMs = anchor), anchor + 90_000L, true, ru),
        )
    }

    @Test fun `long times and distances are compact`() {
        val line = { eta: Int, total: Int ->
            HudStreetLine.compose(snapshot(etaSeconds = eta, arrival = "", total = total, etaAtMs = 0L, road = ""), 0L, true, ru)
        }
        assertEquals("1 ч 5 мин · 999 м", line(65 * 60, 999))
        assertEquals("1 ч · 1,0 км", line(60 * 60, 1_000))
        assertEquals("2 ч 30 мин · 150 км", line(150 * 60, 150_000))
    }

    @Test fun `push loop sends the composed line as f10`() {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 800, road = "A", etaSeconds = 600, arrivalTime = "09:10", totalDistMeters = 5_000),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000L,
        )
        val payloads = mutableListOf<ByteArray>()
        val sink = object : HudEventSink {
            override fun fireEvent(topic: Long, payload: ByteArray): Int { payloads += payload; return 0 }
        }
        val loop = HudPushLoop(
            sink,
            nowMsProvider = { 1_000L },
            streetLine = { s, now -> HudStreetLine.compose(s, now, true, ru) },
        )
        loop.tick(false)
        val text = "09:10 · 10 мин · 5,0 км | A"
        assertEquals(true, String(payloads.single(), Charsets.UTF_8).contains(text))
    }
}
