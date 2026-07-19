package com.bydmate.app.data.remote

import com.bydmate.app.data.automation.ActionDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlicePollingPolicyTest {
    @Test fun `duplicate response ids are selected once`() {
        val selected = AlicePollingManager.selectUniqueCommands(sequenceOf(
            AlicePollingManager.RemoteCommand("1", "自动空调"),
            AlicePollingManager.RemoteCommand("1", "关闭空调"),
            AlicePollingManager.RemoteCommand("2", "车门上锁"),
        ))

        assertEquals(listOf("1", "2"), selected.map { it.id })
        assertEquals("自动空调", selected.first().command)
    }

    @Test fun `selection enforces per-poll command cap`() {
        val raw = (1..100).asSequence().map {
            AlicePollingManager.RemoteCommand(it.toString(), "cmd-$it")
        }
        val selected = AlicePollingManager.selectUniqueCommands(raw)
        assertEquals(AlicePollingManager.MAX_COMMANDS_PER_POLL, selected.size)
    }

    @Test fun `blank ids and commands are ignored`() {
        val selected = AlicePollingManager.selectUniqueCommands(sequenceOf(
            AlicePollingManager.RemoteCommand("", "自动空调"),
            AlicePollingManager.RemoteCommand("1", "  "),
            AlicePollingManager.RemoteCommand("2", "车门上锁"),
        ))
        assertEquals(listOf("2"), selected.map { it.id })
    }

    @Test fun `Alice full safety policy blocks aperture and frunk commands`() {
        assertNotNull(ActionDispatcher.safetyBlockReason("前备箱打开", diParsData(speed = 1)))
        assertNotNull(ActionDispatcher.safetyBlockReason("开后备箱", diParsData(speed = 1)))
        assertNotNull(ActionDispatcher.safetyBlockReason("开后备箱", null))
        assertNotNull(ActionDispatcher.safetyBlockReason("天窗打开100", diParsData(speed = 81)))
        assertNotNull(ActionDispatcher.safetyBlockReason("车窗全开", diParsData(speed = 121)))
        assertNull(ActionDispatcher.safetyBlockReason("关闭空调", diParsData(speed = 130)))
    }

    @Test fun `Alice fail-closed policy blocks windows and sunroof before telemetry arrives`() {
        listOf("车窗全开", "天窗全开").forEach { command ->
            assertNotNull(ActionDispatcher.safetyBlockReason(command, data = null))
        }
    }

    @Test fun `late data from stopped generation cannot enter restarted lifecycle`() {
        val state = LatestDataGeneration<String>(maxAgeMs = 5_000L)
        val first = state.begin()
        state.publish(first, "first", nowMs = 1_000L)
        assertEquals("first", state.current(nowMs = 1_000L))

        state.clear()
        val second = state.begin()
        state.publish(first, "late-first", nowMs = 2_000L)
        assertNull(state.current(nowMs = 2_000L))

        state.publish(second, "second", nowMs = 2_000L)
        assertEquals("second", state.current(nowMs = 2_000L))
    }

    @Test fun `Alice telemetry expires instead of authorizing from stale speed`() {
        val state = LatestDataGeneration<String>(maxAgeMs = 5_000L)
        val generation = state.begin()
        state.publish(generation, "parked", nowMs = 1_000L)

        assertEquals("parked", state.current(nowMs = 6_000L))
        assertNull(state.current(nowMs = 6_001L))
    }
}
