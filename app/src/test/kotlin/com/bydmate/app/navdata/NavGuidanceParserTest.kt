package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavGuidanceParserTest {

    private fun raw(
        maneuverDesc: String? = null, exitNumber: String? = null,
        distance: String? = null, nextStreet: String? = null,
        etaTime: String? = null, etaDistance: String? = null, speedLimit: String? = null,
    ) = NavGuidanceParser.RawFields(
        maneuverDesc, exitNumber, distance, nextStreet, etaTime, etaDistance, speedLimit,
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
    }

    @Test fun `distance text combined parsed`() {
        assertEquals(300, NavGuidanceParser.parseDistanceText("300 м"))
        assertEquals(1200, NavGuidanceParser.parseDistanceText("1,2 км"))
        assertEquals(4988, NavGuidanceParser.parseDistanceText("3.1 mi"))
        assertEquals(152, NavGuidanceParser.parseDistanceText("500 ft"))
        assertEquals(0, NavGuidanceParser.parseDistanceText("скоро"))
        assertEquals(0, NavGuidanceParser.parseDistanceText(null))
    }

    @Test fun `distance before instruction punctuation is parsed`() {
        assertEquals(500, NavGuidanceParser.parseDistanceText("In 500 m, turn right"))
        assertEquals(500, NavGuidanceParser.parseDistanceText("Через 500 м, поверните направо"))
        assertEquals(1200, NavGuidanceParser.parseDistanceText("In 1.2 km — keep left"))
    }
}
