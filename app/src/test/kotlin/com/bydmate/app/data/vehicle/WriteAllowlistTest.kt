package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class WriteAllowlistTest {

    private val bannedDevs = setOf(1004, 1006, 1007, 1009, 1011, 1012, 1013, 1014, 1016, 1023, 1032)

    @Test fun `EMPTY has no entries`() {
        assertEquals(0, WriteAllowlist.EMPTY.size)
        assertNull(WriteAllowlist.EMPTY.find("anything"))
    }

    @Test fun `no production entry targets a banned dev namespace`() {
        for (entry in WriteAllowlist.PRODUCTION.allEntries()) {
            if (entry.dev in bannedDevs) {
                fail("WriteAllowlist contains banned dev=${entry.dev} for action=${entry.actionName}")
            }
        }
    }
}
