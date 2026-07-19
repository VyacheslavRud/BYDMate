package com.bydmate.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLifecycleOwnerTest {
    @Test fun `partial attach rollback attempts removal and lifecycle destruction`() {
        var removed = 0
        var destroyed = 0

        rollbackOverlayAttach(
            removeView = { removed++ },
            destroyLifecycle = { destroyed++ },
        )

        assertEquals(1, removed)
        assertEquals(1, destroyed)
    }

    @Test fun `lifecycle is destroyed even when partial view removal throws`() {
        var destroyed = 0

        rollbackOverlayAttach(
            removeView = { error("vendor addView left no removable token") },
            destroyLifecycle = { destroyed++ },
        )

        assertEquals(1, destroyed)
    }
}
