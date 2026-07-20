package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32])
class HudLabLogStoreTest {
    private lateinit var context: Context
    private var exported: File? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HudLabLogStore.clearForTest(context)
    }

    @After fun tearDown() {
        exported?.delete()
        HudLabLogStore.clearForTest(context)
    }

    @Test fun `attempt clear and visual mismatch survive persistent reload`() {
        val attempt = HudLabLogStore.createAttempt(
            context = context,
            command = HudLabCommand.RIGHT,
            payloadBytes = 42,
            sendRc = 0,
            sendFailure = null,
            gear = 1,
            speedKmh = 0,
            nowMs = 1_000L,
        )
        HudLabLogStore.recordClear(
            context,
            attempt.id,
            clearRc = 1,
            autoCleared = true,
            nowMs = 2_000L,
        )
        HudLabLogStore.recordObservation(
            context,
            attempt.id,
            HudLabObserved.LEFT,
            nowMs = 3_000L,
        )

        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabCommand.RIGHT, saved.command)
        assertEquals(2, saved.rawF28)
        assertFalse(saved.includePng)
        assertEquals(HudLabObserved.LEFT, saved.observed)
        assertEquals(1, saved.clearRc)
        assertTrue(saved.autoCleared)

        val section = HudLabLogStore.renderDiagnosticSection(context)
        assertTrue(section.contains("rawF28=2"))
        assertTrue(section.contains("observed=LEFT"))
        assertTrue(section.contains("verdict=MISMATCH"))
    }

    @Test fun `export creates self-contained shareable calibration report`() {
        HudLabLogStore.createAttempt(
            context = context,
            command = HudLabCommand.LEFT,
            payloadBytes = 40,
            sendRc = -2,
            sendFailure = HudLabSendFailure.HUD_NOT_READY.name,
            gear = null,
            speedKmh = null,
            nowMs = 1_000L,
        )

        exported = HudLabLogStore.export(context, nowMs = 2_000L)

        assertTrue(exported!!.exists())
        val report = exported!!.readText()
        assertTrue(report.contains("=== BYDMate HUD Lab export ==="))
        assertTrue(report.contains("command=LEFT rawF28=3"))
        assertTrue(report.contains("sendFailure=HUD_NOT_READY"))
        assertTrue(report.contains("vehicle:"))
    }
}
