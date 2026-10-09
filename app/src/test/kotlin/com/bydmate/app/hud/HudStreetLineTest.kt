package com.bydmate.app.hud

import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavGuidanceParser
import com.bydmate.app.navdata.NavManeuverCodes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class HudStreetLineTest {
    private val ru = HudStreetLine.Labels("мин", "ч", "км", "м", ',', "Кольцо", "%1\$d-й съезд")
    private val en = HudStreetLine.Labels("min", "h", "km", "m", '.', "Roundabout", "exit %1\$d")

    private fun snapshot(
        distance: Int = 1_200,
        road: String = "Nádražní",
        etaSeconds: Int = 25 * 60,
        arrival: String = "18:45",
        total: Int = 8_449,
        etaAtMs: Long = 1_000L,
        maneuver: Int = NavManeuverCodes.GAODE_RIGHT,
        exit: Int = 0,
    ) = NavGuidanceHub.Snapshot(
        active = true,
        maneuverGaode = maneuver,
        roundaboutExit = exit,
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
            HudStreetLine.compose(snapshot(), 0L, routeInfoEnabled = true, labels = ru),
        )
    }

    @Test fun `near the turn and when switched off only the street is shown`() {
        listOf(1, 150, HudStreetLine.STREET_ONLY_WITHIN_METERS).forEach { distance ->
            assertEquals("Nádražní", HudStreetLine.compose(snapshot(distance = distance), 0L, true, ru))
        }
        assertEquals("Nádražní", HudStreetLine.compose(snapshot(), 0L, routeInfoEnabled = false, labels = ru))
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

    @Test fun `near a roundabout the exit goes before the street`() {
        val ring = { distance: Int, exit: Int, road: String, info: Boolean ->
            HudStreetLine.compose(
                snapshot(distance = distance, road = road, maneuver = NavManeuverCodes.GAODE_ROUNDABOUT_EXIT, exit = exit),
                0L,
                info,
                ru,
            )
        }
        assertEquals("Кольцо · 2-й съезд | Nádražní", ring(250, 2, "Nádražní", true))
        assertEquals("Кольцо · 10-й съезд | Nádražní", ring(1, 10, "Nádražní", true))
        // It is guidance, not route info: the route-info switch does not hide it.
        assertEquals("Кольцо · 2-й съезд | Nádražní", ring(80, 2, "Nádražní", false))
        assertEquals("Кольцо · 3-й съезд", ring(80, 3, " ", true))
    }

    @Test fun `a roundabout without a readable exit number is still named`() {
        listOf(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER, NavManeuverCodes.GAODE_ROUNDABOUT_EXIT).forEach { code ->
            assertEquals(
                "Кольцо | Nádražní",
                HudStreetLine.compose(snapshot(distance = 120, maneuver = code), 0L, true, ru),
            )
        }
        assertEquals(
            "Roundabout · exit 2 | Main St",
            HudStreetLine.compose(
                snapshot(distance = 120, road = "Main St", maneuver = NavManeuverCodes.GAODE_ROUNDABOUT_ENTER, exit = 2),
                0L,
                true,
                en,
            ),
        )
    }

    @Test fun `a far roundabout and an ordinary turn keep the usual line`() {
        assertEquals(
            "18:45 · 25 мин · 8,4 км | Nádražní",
            HudStreetLine.compose(
                snapshot(distance = 1_200, maneuver = NavManeuverCodes.GAODE_ROUNDABOUT_EXIT, exit = 2),
                0L,
                true,
                ru,
            ),
        )
        assertEquals(
            "Nádražní",
            HudStreetLine.compose(snapshot(distance = 120, maneuver = NavManeuverCodes.GAODE_LEFT, exit = 2), 0L, true, ru),
        )
    }

    @Test fun `exit number read by the parser reaches f10`() {
        val guidance = NavGuidanceParser.parse(
            NavGuidanceParser.RawFields(
                maneuverDesc = "At the roundabout, take the 2nd exit",
                exitNumber = null,
                distance = "150 m",
                nextStreet = "Vinohradská",
                etaTime = null,
                arrivalTime = null,
                etaDistance = null,
                speedLimit = null,
            ),
        )!!
        NavGuidanceHub.update(guidance, NavGuidanceHub.Source.A11Y, nowMs = 1_000L)
        val payloads = mutableListOf<ByteArray>()
        val sink = object : HudEventSink {
            override fun fireEvent(topic: Long, payload: ByteArray): Int { payloads += payload; return 0 }
        }
        HudPushLoop(
            sink,
            nowMsProvider = { 1_000L },
            streetLine = { s, now -> HudStreetLine.compose(s, now, true, ru) },
        ).tick(false)
        assertEquals(
            true,
            String(payloads.single(), Charsets.UTF_8).contains("Кольцо · 2-й съезд | Vinohradská"),
        )
    }
}
