package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavGuidanceParserTest {

    private fun raw(
        maneuverDesc: String? = null, exitNumber: String? = null,
        distance: String? = null, nextStreet: String? = null,
        etaTime: String? = null, arrivalTime: String? = null,
        etaDistance: String? = null, speedLimit: String? = null,
    ) = NavGuidanceParser.RawFields(
        maneuverDesc, exitNumber, distance, nextStreet, etaTime, arrivalTime, etaDistance, speedLimit,
    )

    @Test fun `no guidance widgets means null`() {
        assertNull(NavGuidanceParser.parse(raw(etaTime = "27 мин", speedLimit = "60")))
    }

    @Test fun `meters distance parsed`() {
        val d = NavGuidanceParser.parse(raw(maneuverDesc = "Поверните направо", distance = "250 м"))!!
        assertEquals(2, d.maneuverGaode)
        assertEquals(250, d.distanceMeters)
    }

    @Test fun `fractional km distance parsed`() {
        val d = NavGuidanceParser.parse(raw(maneuverDesc = ">>>", distance = "1,2 км"))!!
        assertEquals(1200, d.distanceMeters)
    }

    @Test fun `exit number forces roundabout exit`() {
        val d = NavGuidanceParser.parse(raw(maneuverDesc = "Поверните направо", exitNumber = "2", distance = "300 м"))!!
        assertEquals(24, d.maneuverGaode)
    }

    @Test fun `eta minutes and hours parsed`() {
        assertEquals(27 * 60, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaTime = "27 мин"))!!.etaSeconds)
        assertEquals(3900, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaTime = "1 ч 5 мин"))!!.etaSeconds)
        assertEquals(3900, NavGuidanceParser.parseDurationSeconds("1 hr 5 mins"))
        assertEquals(0, NavGuidanceParser.parseDurationSeconds("15:40"))
    }

    @Test fun `absolute arrival time supports 24 and 12 hour Waze labels`() {
        assertEquals("10:10", NavGuidanceParser.parse(
            raw(maneuverDesc = ">>>", etaTime = "27 мин", arrivalTime = "10:10"),
        )!!.arrivalTime)
        assertEquals("15:40", NavGuidanceParser.parse(
            raw(maneuverDesc = ">>>", arrivalTime = "ETA 3:40 PM"),
        )!!.arrivalTime)
        assertEquals("00:05", NavGuidanceParser.normalizeArrivalTime("12:05 a.m."))
        assertEquals("12:05", NavGuidanceParser.normalizeArrivalTime("12:05 PM"))
        assertNull(NavGuidanceParser.normalizeArrivalTime("25:70"))
    }

    @Test fun `generic Waze time node can carry absolute arrival clock`() {
        val parsed = NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaTime = "3:40 PM"))!!
        assertEquals(0, parsed.etaSeconds)
        assertEquals("15:40", parsed.arrivalTime)
    }

    @Test fun `total distance km parsed`() {
        assertEquals(28000, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaDistance = "28 км"))!!.totalDistMeters)
        assertEquals(800, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaDistance = "800 м"))!!.totalDistMeters)
    }

    @Test fun `road comes from Waze next street field`() {
        assertEquals("ул. Ленина", NavGuidanceParser.parse(
            raw(maneuverDesc = ">>>", nextStreet = "ул. Ленина"),
        )!!.road)
    }

    @Test fun `speed limit parsed`() {
        assertEquals(60, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", speedLimit = "60"))!!.speedLimit)
        assertEquals(130, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", speedLimit = "130 km/h"))!!.speedLimit)
        assertEquals(0, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", speedLimit = "9999"))!!.speedLimit)
        assertEquals(0, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", speedLimit = "-1"))!!.speedLimit)
    }

    @Test fun `distance text combined parsed`() {
        assertEquals(300, NavGuidanceParser.parseDistanceText("300 м"))
        assertEquals(1200, NavGuidanceParser.parseDistanceText("1,2 км"))
        assertEquals(4988, NavGuidanceParser.parseDistanceText("3.1 mi"))
        assertEquals(152, NavGuidanceParser.parseDistanceText("500 ft"))
        assertEquals(0, NavGuidanceParser.parseDistanceText("скоро"))
        assertEquals(0, NavGuidanceParser.parseDistanceText(null))
        assertEquals(0, NavGuidanceParser.parseDistanceText("-500 m"))
        assertEquals(0, NavGuidanceParser.parseDistanceText("0.5 m"))
    }

    @Test fun `distance before instruction punctuation is parsed`() {
        assertEquals(500, NavGuidanceParser.parseDistanceText("In 500 m, turn right"))
        assertEquals(500, NavGuidanceParser.parseDistanceText("Через 500 м, поверните направо"))
        assertEquals(1200, NavGuidanceParser.parseDistanceText("In 1.2 km — keep left"))
        assertEquals(500, NavGuidanceParser.parseDistanceText("In 500 m, 12 km remaining"))
    }

    @Test fun `implausible numeric values fail soft instead of overflowing`() {
        assertEquals(0, NavGuidanceParser.parseDistanceText("999999999999999999999 km"))
        assertEquals(0, NavGuidanceParser.parseDurationSeconds("999999999999999999999 h"))
        assertEquals(0, NavGuidanceParser.parseDurationSeconds("100000 h 100000 min"))
        assertEquals(0, NavGuidanceParser.parseDurationSeconds("-15 min"))
        val parsed = NavGuidanceParser.parse(raw(
            maneuverDesc = "Turn right",
            distance = "999999999999999999999",
            etaTime = "999999999999999999999 min",
        ))!!
        assertEquals(0, parsed.distanceMeters)
        assertEquals(0, parsed.etaSeconds)
    }
}
