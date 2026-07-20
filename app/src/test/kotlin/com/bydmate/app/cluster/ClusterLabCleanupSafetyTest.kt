package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.TaskProjectionQueryResult
import com.bydmate.app.data.vehicle.TaskProjectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLabCleanupSafetyTest {

    @Test fun `C02 Waze snapshot export distinguishes found not-running and unavailable`() {
        assertEquals(
            "snapshot target=com.waze status=FOUND taskId=57 displayId=4 windowingMode=5",
            wazeTaskProjectionDetail(
                "snapshot",
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 5)),
            ),
        )
        assertEquals(
            "snapshot target=com.waze status=NOT_RUNNING",
            wazeTaskProjectionDetail("snapshot", TaskProjectionQueryResult.NotRunning),
        )
        assertEquals(
            "snapshot target=com.waze status=UNAVAILABLE",
            wazeTaskProjectionDetail("snapshot", TaskProjectionQueryResult.Unavailable),
        )
    }

    @Test fun `cleanup accepts only confirmed absent or main fullscreen Waze task`() {
        assertTrue(isProjectionTaskCleanupConfirmed(TaskProjectionQueryResult.NotRunning))
        assertTrue(
            isProjectionTaskCleanupConfirmed(
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 0, 1)),
            ),
        )

        assertFalse(isProjectionTaskCleanupConfirmed(TaskProjectionQueryResult.Unavailable))
        assertFalse(
            isProjectionTaskCleanupConfirmed(
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 4, 1)),
            ),
        )
        assertFalse(
            isProjectionTaskCleanupConfirmed(
                TaskProjectionQueryResult.Found(TaskProjectionState(57, 0, 5)),
            ),
        )
    }

    @Test fun `journal failure is retained without blocking subsequent cleanup work`() {
        val firstFailure = IllegalStateException("disk full")
        val journal = ClusterLabBestEffortJournal()
        var offAndVerificationRan = false
        var completionLogAttempted = false

        journal.record { throw firstFailure }
        offAndVerificationRan = true
        journal.record { completionLogAttempted = true }

        assertTrue(offAndVerificationRan)
        assertTrue(completionLogAttempted)
        assertSame(firstFailure, journal.failure)
    }

    @Test fun `overlay slot never overwrites an existing owner`() {
        val slot = ExclusiveOverlaySlot<Any>()
        val existing = Any()
        val incoming = Any()

        assertTrue(slot.claim(existing))
        assertFalse(slot.claim(incoming))
        assertSame(existing, slot.current())
        assertFalse(slot.replaceIfOwned(incoming, Any()))
        assertSame(existing, slot.current())
        assertFalse(slot.releaseIfOwned(incoming))
        assertTrue(slot.releaseIfOwned(existing))
        assertNull(slot.current())
    }

    @Test fun `overlay reservation can only be replaced by its current owner`() {
        val slot = ExclusiveOverlaySlot<Any>()
        val reservation = Any()
        val handle = Any()

        assertTrue(slot.claim(reservation))
        assertTrue(slot.replaceIfOwned(reservation, handle))
        assertSame(handle, slot.current())
        assertFalse(slot.replaceIfOwned(reservation, Any()))
        assertSame(handle, slot.current())
    }
}
