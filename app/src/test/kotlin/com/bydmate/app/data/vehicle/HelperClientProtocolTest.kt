package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperClientProtocolTest {

    @Test fun `read request roundtrip`() {
        val req = HelperRequest(op = "read", tx = 5, dev = 1014, fid = 1246777400)
        assertEquals(req, HelperRequest.fromJsonLine(req.toJsonLine().trim()))
    }

    @Test fun `write request roundtrip`() {
        val req = HelperRequest(op = "write", tx = 6, dev = 1000, fid = 501219368, value = 23)
        assertEquals(req, HelperRequest.fromJsonLine(req.toJsonLine().trim()))
    }

    @Test fun `response roundtrip with error`() {
        val rsp = HelperResponse(status = -1, value = 0, ms = 7, error = "SecurityException")
        assertEquals(rsp, HelperResponse.fromJsonLine(rsp.toJsonLine().trim()))
    }

    @Test fun `response roundtrip without error field`() {
        val rsp = HelperResponse(status = 0, value = 42, ms = 3)
        assertEquals(rsp, HelperResponse.fromJsonLine(rsp.toJsonLine().trim()))
    }

    @Test fun `request line ends with newline`() {
        val req = HelperRequest(op = "ping")
        assertEquals('\n', req.toJsonLine().last())
    }
}
