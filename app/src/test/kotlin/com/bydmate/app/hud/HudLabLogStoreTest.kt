package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterLabLogStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
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
        ClusterLabLogStore.clearForTest(context)
    }

    @After fun tearDown() {
        exported?.delete()
        HudLabLogStore.clearForTest(context)
        ClusterLabLogStore.clearForTest(context)
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
        assertFalse(saved.autoCleared)
        assertEquals(null, saved.clearedAtMs)

        val section = HudLabLogStore.renderDiagnosticSection(context)
        assertTrue(section.contains("rawF28=2"))
        assertTrue(section.contains("observed=LEFT"))
        assertTrue(section.contains("verdict=MISMATCH"))
    }

    @Test fun `export creates self-contained shareable calibration report`() {
        HudLabLogStore.createAttempt(
            context = context,
            command = HudLabCommand.LEFT,
            frameVariant = HudLabFrameVariant.LIVE_PNG_F8,
            iconGaodeCode = 1,
            pngBytes = 1_271,
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
        assertTrue(report.contains("frameVariant=LIVE_PNG_F8 includePng=true"))
        assertTrue(report.contains("iconGaodeCode=1 pngBytes=1271"))
        assertTrue(report.contains("sendFailure=HUD_NOT_READY"))
        assertTrue(report.contains("--- Instrument Cluster Lab ---"))
        assertTrue(report.contains("vehicle:"))
    }

    @Test fun `records created by raw-only 3_6_8 remain readable`() {
        val legacy = JSONObject().apply {
            put("id", "legacy")
            put("requestedAtMs", 1_000L)
            put("command", "RIGHT")
            put("rawF28", 2)
            put("includePng", false)
            put("payloadBytes", 39)
            put("sendRc", 0)
            put("sendFailure", JSONObject.NULL)
            put("gear", 1)
            put("speedKmh", 0)
        }
        context.getSharedPreferences("hud_lab_log", Context.MODE_PRIVATE)
            .edit()
            .putString("records_json", JSONArray().put(legacy).toString())
            .commit()

        val restored = HudLabLogStore.records(context).single()

        assertEquals("legacy", restored.id)
        assertEquals(HudLabFrameVariant.RAW_F28_ONLY, restored.frameVariant)
        assertFalse(restored.includePng)
        assertEquals(null, restored.iconGaodeCode)
        assertEquals(null, restored.pngBytes)
        assertFalse(HudLabLogStore.renderDiagnosticSection(context).contains("verdict=INTERRUPTED"))
    }

    @Test fun `scenario journal preserves every event and treats positive rc as unconfirmed`() {
        val scenario = requireNotNull(HudLabScenarioCatalog.byId("W14"))
        val record = HudLabLogStore.beginScenario(context, scenario, nowMs = 1_000L)
        HudLabLogStore.appendEvent(
            context,
            record.id,
            HudLabEvent(
                type = HudLabEventType.SEND,
                stepIndex = 1,
                label = "matched_right",
                pushIndex = 0,
                atMs = 1_100L,
                elapsedMs = 100L,
                payloadBytes = 1_288,
                payloadSha256 = "abc123",
                fieldManifest = "f2=2,f8=0x2.png,f28=2",
                rc = 1,
                gear = 1,
                speedKmh = 0,
                outputMayBeOwned = true,
            ),
        )
        HudLabLogStore.completeDelivery(context, record.id, nowMs = 1_200L)

        val restored = HudLabLogStore.records(context).single()
        assertEquals("W14", restored.scenarioId)
        assertEquals(HudLabRemoteResult.REMOTE_NONZERO_UNCONFIRMED, restored.events.single().remoteResult)
        assertEquals("abc123", restored.events.single().payloadSha256)
        assertEquals(true, restored.events.single().outputMayBeOwned)

        val report = HudLabLogStore.renderDiagnosticSection(context)
        assertTrue(report.contains("scenario=W14"))
        assertTrue(report.contains("remoteResult=REMOTE_NONZERO_UNCONFIRMED"))
        assertTrue(report.contains("sha256=abc123"))
    }

    @Test fun `guard rejection before transport is classified as not sent`() {
        val event = HudLabEvent(
            type = HudLabEventType.SEND,
            stepIndex = 0,
            label = "blocked",
            pushIndex = 0,
            atMs = 1_000L,
            elapsedMs = 0L,
            failure = HudLabSendFailure.ROUTE_ACTIVE.name,
        )

        assertEquals(HudLabRemoteResult.NOT_SENT, event.remoteResult)
    }

    @Test fun `durable intent survives reload and exposes interrupted native attempt`() {
        val scenario = requireNotNull(HudLabScenarioCatalog.byId("W14"))
        val record = HudLabLogStore.beginScenario(context, scenario, nowMs = 1_000L)
        HudLabLogStore.appendEvent(
            context,
            record.id,
            HudLabEvent(
                type = HudLabEventType.SEND,
                stepIndex = 1,
                label = "matched_right",
                pushIndex = 0,
                atMs = 1_100L,
                elapsedMs = 100L,
                phase = HudLabEventPhase.INTENT,
                attemptId = "attempt-1",
            ),
        )

        val restored = HudLabLogStore.records(context).single().events.single()
        assertEquals(HudLabEventPhase.INTENT, restored.phase)
        assertEquals("attempt-1", restored.attemptId)
        assertTrue(HudLabLogStore.renderDiagnosticSection(context).contains("verdict=INTERRUPTED"))
    }

    @Test fun `clear summary requires an explicitly confirmed zero result`() {
        val attempt = HudLabLogStore.createAttempt(
            context = context,
            command = HudLabCommand.RIGHT,
            payloadBytes = 42,
            sendRc = 0,
            sendFailure = null,
            gear = 1,
            speedKmh = 0,
        )

        HudLabLogStore.recordClear(
            context,
            attempt.id,
            clearRc = 1,
            autoCleared = true,
            clearConfirmed = true,
            nowMs = 2_000L,
        )

        val restored = HudLabLogStore.records(context).single()
        assertTrue(restored.autoCleared)
        assertEquals(2_000L, restored.clearedAtMs)
        assertEquals(1, restored.clearRc)
    }
}
