package com.bydmate.app.hud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
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
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            rc = 0,
            gear = 1,
            speedKmh = 0,
        )
        every { controller.clearHudLabFrame() } returns 1
        val manager = HudLabManager(context, controller).apply { autoClearDelayMs = 0L }

        manager.send(HudLabCommand.RIGHT, parkConfirmedByUser = true)

        awaitTrue { manager.state.value.pending?.autoCleared == true }
        verify(exactly = 1) { controller.clearHudLabFrame() }
        val pending = manager.state.value.pending!!
        assertEquals(2, pending.record.rawF28)

        manager.recordObservation(HudLabObserved.LEFT)

        awaitTrue { manager.state.value.pending == null }
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabObserved.LEFT, saved.observed)
        assertTrue(saved.autoCleared)
        assertEquals(1, saved.clearRc)
    }

    @Test fun `transport rejection is durably exported as failed attempt`() {
        every { controller.sendHudLabFrame(any(), true) } returns HudLabTransportResult(
            failure = HudLabSendFailure.ROUTE_ACTIVE,
            gear = 1,
            speedKmh = 0,
        )
        val manager = HudLabManager(context, controller)

        manager.send(HudLabCommand.LEFT, parkConfirmedByUser = true)

        awaitTrue { !manager.state.value.busy }
        assertEquals(HudLabOutcomeType.SEND_REJECTED, manager.state.value.lastOutcome?.type)
        assertEquals(HudLabSendFailure.ROUTE_ACTIVE, manager.state.value.lastOutcome?.failure)
        val saved = HudLabLogStore.records(context).single()
        assertEquals(HudLabSendFailure.ROUTE_ACTIVE.name, saved.sendFailure)
        assertEquals(HudLabCommand.LEFT, saved.command)
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
