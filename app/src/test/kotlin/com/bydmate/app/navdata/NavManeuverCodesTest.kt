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
        assertEquals(0, gaode("Take the 11th exit"))
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

    @Test fun `symbolic Waze arrow tags are parsed without accepting road prose`() {
        assertEquals(2, gaode("TURN_RIGHT"))
        assertEquals(1, gaode("NAVIGATION_TURN_LEFT"))
        assertEquals(4, gaode("KEEP_RIGHT"))
        assertEquals(2, gaode("ic_turn_right_24"))
        assertEquals(2, gaode("TURN_RIGHT_THEN_LEFT"))
        assertEquals(0, gaode("Left Bank Road"))
    }

    @Test fun `Chinese Waze direction descriptions map to HUD codes`() {
        assertEquals(1, gaode("向左转"))
        assertEquals(2, gaode("向右转"))
        assertEquals(3, gaode("靠左"))
        assertEquals(4, gaode("靠右"))
        assertEquals(11, gaode("直行"))
        assertEquals(9, gaode("掉头"))
    }

    @Test fun `Czech Waze direction descriptions map to HUD codes`() {
        assertEquals(1, gaode("Odbočte vlevo"))
        assertEquals(2, gaode("Odbočte vpravo"))
        assertEquals(3, gaode("Držte se vlevo"))
        assertEquals(4, gaode("Mírně vpravo"))
        assertEquals(7, gaode("Ostře vlevo"))
        assertEquals(8, gaode("Ostře vpravo"))
        assertEquals(9, gaode("Otočte se"))
        assertEquals(11, gaode("Pokračujte rovně"))
    }

    @Test fun `compound instruction selects first maneuver by textual position`() {
        assertEquals(2, gaode("Turn right, then turn left"))
        assertEquals(1, gaode("Turn left, then turn right"))
        assertEquals(2, gaode("Поверните направо, затем налево"))
        assertEquals(1, gaode("Поверните налево, затем направо"))
    }

    @Test fun `specific first maneuver wins overlapping generic direction`() {
        assertEquals(4, gaode("Slight right, then turn left"))
        assertEquals(10, gaode("Make a U-turn to the right, then keep left"))
        assertEquals(24, gaode("At the roundabout, take the 2nd exit, then turn left"))
        assertEquals(2, gaode("Turn right, then take the 2nd exit"))
    }

    @Test fun `diagnostic result contains only recognized maneuver sequence`() {
        val result = NavManeuverCodes.parseInstructionText("Turn right onto Secret Road, then turn left")
        assertEquals(listOf(2, 1), result.recognizedCodes)
        assertEquals("recognized=RIGHT>LEFT selected=RIGHT gaode=2", result.diagnosticSummary())
        assertEquals(false, "Secret Road" in result.diagnosticSummary())
    }

    @Test fun `road names and engagement copy are not maneuvers`() {
        assertEquals(0, gaode("Left Bank Road"))
        assertEquals(0, gaode("Правый берег"))
        assertEquals(0, gaode("Your destination is waiting"))
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
