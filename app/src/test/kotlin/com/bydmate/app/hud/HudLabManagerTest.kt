package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudLabManagerTest {
    private lateinit var context: Context
    private lateinit var controller: HudController

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HudLabLogStore.clearForTest(context)
        controller = mockk(relaxed = true)
    }

    @After fun tearDown() {
        HudLabLogStore.clearForTest(context)
    }

    @Test fun `accepted test auto clears and later stores visual answer`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrame() } returns 0
        val manager = HudLabManager(context, controller).apply {
            autoClearDelayMs = 0L
            scenarioDelay = {}
        }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { manager.state.value.pending?.autoCleared == true }
        verify(exactly = 10) { controller.sendHudLabFrame(any(), true) }
        verify(exactly = 3) { controller.clearHudLabFrameSafely(true) }
        verify(exactly = 3) { controller.clearHudLabFrame() }
        val pending = manager.state.value.pending!!
        assertEquals(9, pending.record.rawF28)
        assertEquals(HudLabFrameVariant.SCENARIO_MATRIX, pending.record.frameVariant)
        assertTrue(!pending.record.includePng)
        assertEquals(null, pending.record.iconGaodeCode)
        assertTrue(pending.record.events.any { it.fieldManifest?.contains("pngBytes=") == true })
        assertEquals(10, pending.record.events.count {
            it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.RESULT
        })
        assertEquals(10, pending.record.events.count {
            it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.INTENT
        })
        assertEquals(
            pending.record.events.filter {
                it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.INTENT
            }.mapNotNull { it.attemptId }.toSet(),
            pending.record.events.filter {
                it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.RESULT
            }.mapNotNull { it.attemptId }.toSet(),
        )
        assertTrue(pending.record.events.filter {
            it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.RESULT
        }
            .mapNotNull { it.payloadSha256 }.distinct().size == 1)

        manager.recordObservation(HudLabObserved.LEFT)

        awaitTrue { manager.state.value.pending == null }
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabObserved.LEFT, saved.observed)
        assertTrue(saved.autoCleared)
        assertEquals(0, saved.clearRc)
    }

    @Test fun `post-delivery watchdog clear does not rewrite a completed test as aborted`() {
        var safetyChecks = 0
        every { controller.checkHudLabSafety(true) } answers {
            safetyChecks += 1
            if (safetyChecks <= 3) {
                HudLabTransportResult(rc = 0, gear = 1, speedKmh = 0)
            } else {
                HudLabTransportResult(
                    failure = HudLabSendFailure.VEHICLE_DATA_UNAVAILABLE,
                    gear = 1,
                    speedKmh = 0,
                )
            }
        }
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrame() } returns 0
        val manager = HudLabManager(context, controller).apply {
            autoClearDelayMs = 1L
            scenarioDelay = {}
        }

        manager.sendScenario("X01", parkConfirmedByUser = true)

        awaitTrue { manager.state.value.pending?.autoCleared == true }
        val afterWatchdog = HudLabLogStore.records(context).single()
        assertNull(afterWatchdog.abortedFailure)
        assertTrue(afterWatchdog.deliveryCompletedAtMs != null)
        assertEquals(3, afterWatchdog.events.count {
            it.label == "safety_watchdog_clear" && it.phase == HudLabEventPhase.RESULT
        })

        manager.recordObservation(HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE)

        awaitTrue { manager.state.value.pending == null }
        assertTrue(HudLabLogStore.renderDiagnosticSection(context).contains("verdict=MATCH"))
    }

    @Test fun `transport rejection is durably exported as failed attempt`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            failure = HudLabSendFailure.ROUTE_ACTIVE,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        val manager = HudLabManager(context, controller).apply { scenarioDelay = {} }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { !manager.state.value.busy }
        verify(exactly = 1) { controller.sendHudLabFrame(any(), true) }
        assertEquals(HudLabOutcomeType.SEND_REJECTED, manager.state.value.lastOutcome?.type)
        assertEquals(HudLabSendFailure.ROUTE_ACTIVE, manager.state.value.lastOutcome?.failure)
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabSendFailure.ROUTE_ACTIVE.name, saved.sendFailure)
        assertEquals(HudLabCommand.UTURN, saved.command)
        assertEquals(HudLabFrameVariant.SCENARIO_MATRIX, saved.frameVariant)
        assertTrue(!saved.includePng)
    }

    @Test fun `positive remote code remains unconfirmed but does not truncate burst`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            rc = 1,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrame() } returns 0
        val manager = HudLabManager(context, controller).apply {
            autoClearDelayMs = 0L
            scenarioDelay = {}
        }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { manager.state.value.pending?.autoCleared == true }
        verify(exactly = 10) { controller.sendHudLabFrame(any(), true) }
        val sends = HudLabLogStore.records(context).single().events
            .filter { it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.RESULT }
        assertEquals(10, sends.size)
        assertTrue(sends.all { it.remoteResult == HudLabRemoteResult.REMOTE_NONZERO_UNCONFIRMED })
    }

    @Test fun `clear-only scenario rejects three negative transport results`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = -2,
            gear = 1,
            speedKmh = 0,
        )
        val manager = HudLabManager(context, controller).apply { scenarioDelay = {} }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { !manager.state.value.busy }
        assertEquals(HudLabOutcomeType.SEND_REJECTED, manager.state.value.lastOutcome?.type)
        assertEquals(HudLabSendFailure.HUD_CLEAR_FAILED, manager.state.value.lastOutcome?.failure)
        assertEquals(null, manager.state.value.pending)
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabSendFailure.HUD_CLEAR_FAILED.name, saved.abortedFailure)
        assertTrue(saved.events.filter { it.phase == HudLabEventPhase.RESULT }
            .all { it.remoteResult == HudLabRemoteResult.LOCAL_ERROR })
    }

    @Test fun `failed timeout clear remains pending and keeps monotonic elapsed time`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrame() } returns -2
        val manager = HudLabManager(context, controller).apply {
            autoClearDelayMs = 0L
            scenarioDelay = {}
        }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue {
            manager.state.value.pending != null &&
                manager.state.value.lastOutcome?.failure == HudLabSendFailure.HUD_CLEAR_FAILED
        }
        val pending = manager.state.value.pending!!
        assertTrue(!pending.autoCleared)
        val sends = pending.record.events.filter {
            it.type == HudLabEventType.SEND && it.phase == HudLabEventPhase.RESULT
        }
        val timeoutClears = pending.record.events.filter {
            it.label == "timeout_clear" && it.phase == HudLabEventPhase.RESULT
        }
        assertEquals(3, timeoutClears.size)
        assertTrue(timeoutClears.first().elapsedMs >= sends.last().elapsedMs)
    }

    @Test fun `positive clear code is unconfirmed and schedules channel recovery`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 1,
            gear = 1,
            speedKmh = 0,
        )
        val manager = HudLabManager(context, controller).apply { scenarioDelay = {} }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { !manager.state.value.busy }
        assertEquals(HudLabSendFailure.HUD_CLEAR_FAILED, manager.state.value.lastOutcome?.failure)
        verify(exactly = 1) { controller.recoverHudLabAfterFailedClear("scenario_clear") }
        val results = HudLabLogStore.records(context).single().events.filter {
            it.type == HudLabEventType.CLEAR && it.phase == HudLabEventPhase.RESULT
        }
        assertTrue(results.all {
            it.remoteResult == HudLabRemoteResult.REMOTE_NONZERO_UNCONFIRMED
        })
    }

    @Test fun `negative post-dispatch result still receives confirmed cleanup`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrameSafely(true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            rc = -2,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrame() } returns 0
        val manager = HudLabManager(context, controller).apply { scenarioDelay = {} }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { !manager.state.value.busy }
        verify(exactly = 3) { controller.clearHudLabFrame() }
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabSendFailure.HUD_SEND_FAILED.name, saved.abortedFailure)
        assertTrue(saved.clearedAtMs != null)
    }

    @Test fun `persisted ownership on preclear rejection still forces emergency cleanup`() {
        every { controller.checkHudLabSafety(true) } returns HudLabTransportResult(
            failure = HudLabSendFailure.VEHICLE_DATA_UNAVAILABLE,
            outputMayBeOwned = true,
        )
        every { controller.clearHudLabFrame() } returns 0
        val manager = HudLabManager(context, controller).apply { scenarioDelay = {} }

        manager.sendScenario("U01", parkConfirmedByUser = true)

        awaitTrue { !manager.state.value.busy }
        verify(exactly = 3) { controller.clearHudLabFrame() }
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabSendFailure.VEHICLE_DATA_UNAVAILABLE.name, saved.abortedFailure)
        assertTrue(saved.clearedAtMs != null)
    }

    @Test fun `manual clear without pending scenario is still exported`() {
        every { controller.clearHudLabFrame() } returns 0
        val manager = HudLabManager(context, controller).apply { scenarioDelay = {} }

        manager.clear()

        awaitTrue { !manager.state.value.busy }
        val saved = HudLabLogStore.records(context).single()
        assertEquals("MANUAL_CLEAR", saved.scenarioId)
        assertEquals(HudLabObserved.NOT_REPORTED, saved.observed)
        assertEquals(3, saved.events.count {
            it.type == HudLabEventType.CLEAR && it.phase == HudLabEventPhase.RESULT
        })
        assertEquals(HudLabOutcomeType.CLEARED, manager.state.value.lastOutcome?.type)
    }

    @Test fun `delete records clears only idle HUD journal and updates count`() {
        HudLabLogStore.createAttempt(
            context = context,
            command = HudLabCommand.UTURN,
            payloadBytes = 20,
            sendRc = 0,
            sendFailure = null,
            gear = 1,
            speedKmh = 0,
        )
        val manager = HudLabManager(context, controller)
        assertEquals(1, manager.state.value.recordsCount)

        manager.deleteRecords()

        awaitTrue { manager.state.value.lastOutcome?.type == HudLabOutcomeType.RECORDS_DELETED }
        assertEquals(0, manager.state.value.recordsCount)
        assertTrue(HudLabLogStore.records(context).isEmpty())
    }

    private fun awaitTrue(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue(condition())
    }
}
