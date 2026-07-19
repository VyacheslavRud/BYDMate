package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavManeuverCodesTest {

    private fun gaode(text: String?) = NavManeuverCodes.fromInstructionText(text)

    @Test fun `packages set contains only Waze`() {
        assertTrue("com.waze" in NavPackages.WAZE)
        assertEquals(false, NavPackages.isNavigationPackage("ru.yandex.yandexnavi"))
    }

    @Test fun `null and blank map to unknown`() {
        assertEquals(0, gaode(null))
        assertEquals(0, gaode(""))
        assertEquals(0, gaode("   "))
    }

    @Test fun `straight marker maps to straight`() {
        assertEquals(11, gaode(">>>"))
        assertEquals(11, gaode("Продолжайте движение прямо"))
    }

    @Test fun `turns map to gaode codes`() {
        assertEquals(2, gaode("Поверните направо"))
        assertEquals(1, gaode("Поворот налево"))
        assertEquals(3, gaode("Держитесь левее"))
        assertEquals(4, gaode("Плавно направо"))
        assertEquals(7, gaode("Резкий поворот налево"))
        assertEquals(8, gaode("Резко направо"))
    }

    @Test fun `uturn direction resolves left and right`() {
        assertEquals(9, gaode("Развернитесь"))
        assertEquals(10, gaode("Развернитесь направо"))
    }

    @Test fun `roundabout enter exit and numbered exit`() {
        assertEquals(13, gaode("Кольцевое движение"))
        assertEquals(13, gaode("Въезжайте на кольцо"))
        assertEquals(24, gaode("Выезд с кольца"))
        assertEquals(24, gaode("2-й съезд"))
    }

    @Test fun `arrive ferry tunnel waypoint`() {
        assertEquals(48, gaode("Прибытие"))
        assertEquals(46, gaode("Въезд на паром"))
        assertEquals(49, gaode("Тоннель"))
        assertEquals(45, gaode("Промежуточная точка"))
    }

    @Test fun `Waze English instructions map to gaode`() {
        assertEquals(2, gaode("Turn right onto Main Street"))
        assertEquals(1, gaode("Turn left"))
        assertEquals(3, gaode("Keep left"))
        assertEquals(4, gaode("Slight right"))
        assertEquals(11, gaode("Continue straight"))
        assertEquals(13, gaode("Enter the roundabout"))
        assertEquals(24, gaode("Take the 2nd exit"))
        assertEquals(48, gaode("You have arrived at your destination"))
    }

    @Test fun `short uppercase Waze arrow descriptions map to turns`() {
        assertEquals(1, gaode("LEFT"))
        assertEquals(2, gaode("RIGHT"))
    }

    @Test fun `gaode phrase for agent`() {
        assertEquals("направо", NavManeuverCodes.gaodePhrase(2))
        assertEquals("разворот", NavManeuverCodes.gaodePhrase(9))
        assertEquals("съезд с кольца", NavManeuverCodes.gaodePhrase(24))
        assertNull(NavManeuverCodes.gaodePhrase(0))
    }

    @Test fun `nbsp inside phrase is normalized`() {
        // Discriminating inputs: without normalization these hit a DIFFERENT branch
        // (broken code gives 1 / 0 / 0), so the test actually pins the fix.
        assertEquals(7, gaode("Резкий\u00A0поворот\u00A0налево"))
        assertEquals(24, gaode("Съезд\u00A0с\u00A0кольца"))
        assertEquals(45, gaode("Промежуточная\u00A0точка"))
    }
}
