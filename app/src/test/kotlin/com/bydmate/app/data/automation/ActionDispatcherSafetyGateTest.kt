package com.bydmate.app.data.automation

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the composite safety gate [ActionDispatcher.safetyBlockReason] which
 * combines blocked-pattern, frunk parked-only, door-unlock, and window/sunroof
 * speed checks into a single pure function used by the "Выполнить сейчас" button.
 */
class ActionDispatcherSafetyGateTest {

    private fun gate(cmd: String, speed: Int?) =
        ActionDispatcher.safetyBlockReason(cmd, diParsData(speed = speed))

    @Test fun `blocked pattern is rejected regardless of speed`() {
        assertNotNull(ActionDispatcher.safetyBlockReason("发送CAN123", null))
    }

    @Test fun `window open blocked above 120`() {
        assertNotNull(gate("车窗全开", 121))
        assertNull(gate("车窗全开", 120))
    }

    @Test fun `sunroof open blocked above 80`() {
        assertNotNull(gate("天窗全开", 81))
        assertNull(gate("天窗全开", 80))
    }

    @Test fun `frunk open only when parked and fails closed`() {
        assertNotNull(gate("前备箱打开", 1))
        assertNull(gate("前备箱打开", 0))
        assertNotNull(ActionDispatcher.safetyBlockReason("前备箱打开", null))
    }

    @Test fun `rear trunk open only when parked and fails closed`() {
        assertNotNull(gate("开后备箱", 1))
        assertNull(gate("开后备箱", 0))
        assertNotNull(ActionDispatcher.safetyBlockReason("开后备箱", null))
        assertNull(gate("关后备箱", 120))
    }

    @Test fun `unlock blocked above 30 and fails closed`() {
        assertNotNull(gate("车门解锁", 31))
        assertNull(gate("车门解锁", 30))
        assertNotNull(ActionDispatcher.safetyBlockReason("车门解锁", null))
    }

    @Test fun `windows and sunroof fail closed without telemetry`() {
        assertNotNull(ActionDispatcher.safetyBlockReason("车窗全开", null))
        assertNotNull(ActionDispatcher.safetyBlockReason("天窗全开", null))
        assertNull(ActionDispatcher.safetyBlockReason("车窗关闭", null))
    }

    @Test fun `harmless command passes`() {
        assertNull(gate("车窗关闭", 200))
    }

    @Test fun `new per-window commands are speed gated`() {
        assertNotNull(gate("后左打开100", 121))   // rear-left open blocked >120
        assertNull(gate("后左打开0", 200))         // close never gated
        assertNotNull(gate("主驾通风", 121))       // driver vent = open, blocked >120
        assertNotNull(gate("后右打开100", 121))   // rear-right open blocked >120
        assertNull(gate("后右打开0", 200))         // close never gated
        assertNotNull(gate("副驾通风", 121))       // passenger vent blocked >120
        assertNotNull(gate("后左通风", 121))       // rear-left vent blocked >120
        assertNotNull(gate("后右通风", 121))       // rear-right vent blocked >120
    }

    @Test fun `new sunroof commands gated correctly`() {
        assertNotNull(gate("天窗通风", 81))        // tilt vent = open, blocked >80
        assertNotNull(gate("天窗舒适打开", 81))    // comfort open blocked >80
        assertNull(gate("天窗停止", 200))          // stop is always allowed
    }
}
