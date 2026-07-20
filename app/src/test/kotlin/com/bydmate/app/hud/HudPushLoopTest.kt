package com.bydmate.app.hud

import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HudPushLoopTest {

    private class FakeSink(
        private val result: Int = 0,
        results: List<Int> = emptyList(),
    ) : HudEventSink {
        val events = mutableListOf<Pair<Long, ByteArray>>()
        private val queuedResults = ArrayDeque(results)
        override fun fireEvent(topic: Long, payload: ByteArray): Int {
            events.add(topic to payload)
            return queuedResults.removeFirstOrNull() ?: result
        }
    }

    @Before fun reset() = NavGuidanceHub.reset()

    private fun activeHub(nowMs: Long) {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 250, road = "A", speedLimit = 0),
            NavGuidanceHub.Source.A11Y, nowMs = nowMs,
        )
    }

    @Test fun `inactive hub sends nothing`() {
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertFalse(loop.tick(wasActive = false))
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `active hub sends frame on navi topic`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))
        assertEquals(HudSomeIpBridge.TOPIC_NAVI, sink.events.single().first)
        val expected = HudProtobufBuilder.buildSeaLionGuidanceFrame(
            maneuverGaode = 2, distanceMeters = 250, road = "A",
            etaString = null,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `active route with distance but temporarily missing maneuver keeps HUD alive`() {
        NavGuidanceHub.update(
            NavGuidance(road = "A", distanceMeters = 250),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1000L,
        )
        val snapshot = NavGuidanceHub.snapshot(1000L)
        assertTrue(snapshot.active)
        assertTrue(hasRenderableHudGuidance(snapshot))
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })

        assertTrue(loop.tick(wasActive = false))

        val expected = HudProtobufBuilder.buildSeaLionGuidanceFrame(
            maneuverGaode = 0,
            distanceMeters = 250,
            road = "A",
            etaString = null,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `unparsed active maneuver does not clear previous HUD frame`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))
        NavGuidanceHub.reset()
        NavGuidanceHub.update(
            // The maneuver description was present in Waze but could not be mapped; another
            // route field is what makes this a positive active-route update in NavGuidanceHub.
            NavGuidance(arrivalTime = "10:30"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1000L,
        )

        assertTrue(loop.tick(wasActive = true))

        assertEquals(2, sink.events.size)
        assertArrayEquals(
            HudProtobufBuilder.buildSeaLionGuidanceFrame(
                maneuverGaode = 0,
                distanceMeters = 0,
                road = "",
                etaString = "10:30",
            ),
            sink.events.last().second,
        )
    }

    @Test fun `guidance end sends single clear frame`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))     // guidance frame
        NavGuidanceHub.reset()
        assertFalse(loop.tick(wasActive = true))     // clear frame, counter 0
        assertFalse(loop.tick(wasActive = false))    // silence
        assertEquals(2, sink.events.size)
        assertArrayEquals(HudProtobufBuilder.buildClearFrame(0), sink.events[1].second)
    }

    @Test fun `window recovery clears overlay then redraws guidance on next tick`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))

        NavGuidanceHub.requestHudRefresh()
        assertTrue(loop.tick(wasActive = true))
        assertArrayEquals(HudProtobufBuilder.buildClearFrame(0), sink.events.last().second)

        assertTrue(loop.tick(wasActive = true))
        assertEquals(3, sink.events.size)
        assertArrayEquals(sink.events.first().second, sink.events.last().second)
    }

    @Test fun `HUD Lab suspension does not send or mutate active loop state`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink()
        var suspended = true
        val loop = HudPushLoop(
            sink,
            nowMsProvider = { 1000L },
            outputSuspended = { suspended },
        )

        assertTrue(loop.tick(wasActive = true))
        assertTrue(sink.events.isEmpty())

        suspended = false
        assertTrue(loop.tick(wasActive = true))
        assertEquals(1, sink.events.size)
    }

    @Test fun `atomic HUD Lab gate does not report delivery or consume a clear retry`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink(
            results = listOf(0, HudPushLoop.RESULT_OUTPUT_SUSPENDED, 0),
        )
        val results = mutableListOf<Int>()
        val attempts = mutableListOf<Pair<HudFrameKind, Int>>()
        val loop = HudPushLoop(
            sink,
            nowMsProvider = { 1000L },
            onDeliveryResult = results::add,
            onDeliveryAttempt = { kind, rc -> attempts += kind to rc },
        )

        assertTrue(loop.tick(wasActive = false))
        NavGuidanceHub.reset()
        assertFalse(loop.tick(wasActive = true)) // suppressed by the late atomic gate
        assertFalse(loop.tick(wasActive = false)) // same pending clear is accepted

        assertEquals(listOf(0, 0), results)
        assertEquals(
            listOf(HudFrameKind.GUIDANCE to 0, HudFrameKind.CLEAR to 0),
            attempts,
        )
        assertEquals(3, sink.events.size)
    }

    @Test fun `rejected clear frame retries until accepted`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink(results = listOf(0, -7, -7, 0))
        val results = mutableListOf<Int>()
        val loop = HudPushLoop(
            sink,
            nowMsProvider = { 1000L },
            onDeliveryResult = results::add,
        )
        assertTrue(loop.tick(wasActive = false))
        NavGuidanceHub.reset()
        assertFalse(loop.tick(wasActive = true))
        assertFalse(loop.tick(wasActive = false))
        assertFalse(loop.tick(wasActive = false))
        assertFalse(loop.tick(wasActive = false))
        assertEquals(listOf(0, -7, -7, 0), results)
        assertEquals(4, sink.events.size) // guidance + three clear attempts; then silence
    }

    @Test fun `rejected clear frame gives up after bounded attempts`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink(result = -7)
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        assertTrue(loop.tick(wasActive = false))
        NavGuidanceHub.reset()
        assertFalse(loop.tick(wasActive = true))
        repeat(HudPushLoop.CLEAR_MAX_ATTEMPTS + 2) {
            assertFalse(loop.tick(wasActive = false))
        }
        assertEquals(1 + HudPushLoop.CLEAR_MAX_ATTEMPTS, sink.events.size)
    }

    @Test fun `positive remote clear code is unconfirmed and exhausts bounded retries`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink(results = listOf(0, 1, 1, 1))
        val exhausted = mutableListOf<Int>()
        val loop = HudPushLoop(
            sink,
            nowMsProvider = { 1000L },
            onClearExhausted = exhausted::add,
        )

        assertTrue(loop.tick(wasActive = false))
        NavGuidanceHub.reset()
        repeat(HudPushLoop.CLEAR_MAX_ATTEMPTS) { loop.tick(wasActive = it == 0) }

        assertEquals(listOf(1), exhausted)
        assertEquals(1 + HudPushLoop.CLEAR_MAX_ATTEMPTS, sink.events.size)
    }

    @Test fun `throwing clear sink still gives up after bounded attempts`() {
        activeHub(nowMs = 1000L)
        var calls = 0
        val results = mutableListOf<Int>()
        val loop = HudPushLoop(
            sink = object : HudEventSink {
                override fun fireEvent(topic: Long, payload: ByteArray): Int {
                    calls++
                    if (calls == 1) return 0 // initial guidance frame
                    error("synthetic clear failure")
                }
            },
            nowMsProvider = { 1000L },
            onDeliveryResult = results::add,
        )
        assertTrue(loop.tick(wasActive = false))
        NavGuidanceHub.reset()
        assertFalse(loop.tick(wasActive = true))
        repeat(HudPushLoop.CLEAR_MAX_ATTEMPTS + 2) {
            assertFalse(loop.tick(wasActive = false))
        }

        assertEquals(1 + HudPushLoop.CLEAR_MAX_ATTEMPTS, calls)
        assertEquals(
            listOf(0) + List(HudPushLoop.CLEAR_MAX_ATTEMPTS) {
                HudSomeIpBridge.RESULT_LOCAL_ERROR
            },
            results,
        )
    }

    @Test fun `deferred clear retries immediately after a channel reconnect`() {
        val sink = FakeSink(result = 0)
        val loop = HudPushLoop(
            sink = sink,
            nowMsProvider = { 1000L },
            initialClearPending = true,
        )

        assertFalse(loop.tick(wasActive = false))
        assertFalse(loop.tick(wasActive = false))

        assertEquals(1, sink.events.size)
        assertArrayEquals(HudProtobufBuilder.buildClearFrame(0), sink.events.single().second)
    }

    @Test fun `recovery clear precedes active Waze guidance after process death`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink(result = 0)
        val attempts = mutableListOf<Pair<HudFrameKind, Int>>()
        val loop = HudPushLoop(
            sink = sink,
            nowMsProvider = { 1000L },
            initialClearPending = true,
            onDeliveryAttempt = { kind, rc -> attempts += kind to rc },
        )

        assertFalse(loop.tick(wasActive = false))
        assertArrayEquals(HudProtobufBuilder.buildClearFrame(0), sink.events.single().second)
        assertEquals(listOf(HudFrameKind.CLEAR to 0), attempts)

        assertTrue(loop.tick(wasActive = false))
        assertEquals(2, sink.events.size)
        assertEquals(
            listOf(HudFrameKind.CLEAR to 0, HudFrameKind.GUIDANCE to 0),
            attempts,
        )
    }

    @Test fun `rejected recovery clear is bounded and never bypassed by active guidance`() {
        activeHub(nowMs = 1000L)
        val sink = FakeSink(result = 1)
        val attempts = mutableListOf<Pair<HudFrameKind, Int>>()
        val exhausted = mutableListOf<Int>()
        val loop = HudPushLoop(
            sink = sink,
            nowMsProvider = { 1000L },
            initialClearPending = true,
            onDeliveryAttempt = { kind, rc -> attempts += kind to rc },
            onClearExhausted = exhausted::add,
        )

        repeat(HudPushLoop.CLEAR_MAX_ATTEMPTS) {
            assertFalse(loop.tick(wasActive = false))
        }

        assertEquals(
            List(HudPushLoop.CLEAR_MAX_ATTEMPTS) { HudFrameKind.CLEAR to 1 },
            attempts,
        )
        assertEquals(listOf(1), exhausted)
        assertEquals(HudPushLoop.CLEAR_MAX_ATTEMPTS, sink.events.size)
        sink.events.forEachIndexed { index, event ->
            assertArrayEquals(HudProtobufBuilder.buildClearFrame(index), event.second)
        }
    }

    @Test fun `Sea Lion live frame omits unconfirmed speed and PNG fields`() {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 250, road = "A", speedLimit = 60),
            NavGuidanceHub.Source.A11Y, nowMs = 1000L,
        )
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })
        loop.tick(wasActive = false)
        val expected = HudProtobufBuilder.buildSeaLionGuidanceFrame(
            maneuverGaode = 2, distanceMeters = 250, road = "A",
            etaString = null,
        )
        assertArrayEquals(expected, sink.events.single().second)
    }

    @Test fun `Sea Lion live straight guidance stays active without false direction`() {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 11, distanceMeters = 500, road = "A"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1000L,
        )
        val sink = FakeSink()
        val loop = HudPushLoop(sink, nowMsProvider = { 1000L })

        assertTrue(loop.tick(wasActive = false))
        assertArrayEquals(
            HudProtobufBuilder.buildSeaLionGuidanceFrame(
                maneuverGaode = 11,
                distanceMeters = 500,
                road = "A",
                etaString = null,
            ),
            sink.events.single().second,
        )
    }

    @Test fun `eta string format`() {
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { 1_000_000_000_000L })
        assertEquals(null, loop.etaString(0))
        assertTrue(loop.etaString(1620)!!.matches(Regex("""\d{2}:\d{2}""")))
    }

    @Test fun `eta fallback stays anchored to guidance update`() {
        var now = 1_000_000_000_000L
        val updatedAt = now
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { now })
        val first = loop.etaString(1620, updatedAt)
        now += 60_000L
        assertEquals(first, loop.etaString(1620, updatedAt))
    }

    @Test fun `delivery result reports gateway rejection`() {
        activeHub(nowMs = 1000L)
        val results = mutableListOf<Int>()
        val loop = HudPushLoop(
            FakeSink(result = -7),
            nowMsProvider = { 1000L },
            onDeliveryResult = results::add,
        )
        assertTrue(loop.tick(wasActive = false))
        assertEquals(listOf(-7), results)
    }

    @Test fun `push failure is reported without killing loop state`() {
        activeHub(nowMs = 1000L)
        val failures = mutableListOf<Throwable>()
        val loop = HudPushLoop(
            sink = object : HudEventSink {
                override fun fireEvent(topic: Long, payload: ByteArray): Int =
                    error("synthetic sink failure")
            },
            nowMsProvider = { 1000L },
            onLoopFailure = failures::add,
        )

        assertTrue(loop.tickSafely(wasActive = true))
        assertEquals(1, failures.size)
        assertEquals("synthetic sink failure", failures.single().message)
    }
}
