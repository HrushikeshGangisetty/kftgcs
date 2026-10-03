package com.example.kftgcs.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HobbywingEscTest {
    private fun bytes(vararg b: Int) = b.map { it.toUByte() }

    @Test
    fun framesMatchDroneCanEncoding() {
        assertEquals(HwCanFrame(0x984E2D7Fu, bytes(0, 0xC3)), hwGetEscIdFrame(3))
        // SetID request to node 5: new node 6, throttle 2
        assertEquals(HwCanFrame(0x98D285FFu, bytes(6, 2, 0xC1)), hwServiceFrame(HW_SET_ID, 5, listOf(6, 2), 33))
        // SetReportingFrequency: write, MSG_ID 20051 (0x4E53 little-endian), 10 Hz
        assertEquals(
            HwCanFrame(0x98D685FFu, bytes(1, 0x53, 0x4E, 7, 0xC0)),
            hwServiceFrame(HW_SET_REPORTING_FREQUENCY, 5, listOf(1, 0x53, 0x4E, 7), 0)
        )
    }

    @Test
    fun parsesReplies() {
        // GetEscID answer from node 5, throttle 3
        assertEquals(HwEscReply(5, HW_GET_ESC_ID, listOf(5, 3)), hwParseReply(0x984E2D05u, bytes(5, 3, 0xC0), 3))
        // SetID response from node 5 to us (127)
        assertEquals(HwEscReply(5, HW_SET_ID, listOf(6, 2)), hwParseReply(0x98D27F85u, bytes(6, 2, 0xC1), 3))
        // SetBaud response: empty payload, tail byte only
        assertEquals(HwEscReply(5, HW_SET_BAUD, emptyList()), hwParseReply(0x98D37F85u, bytes(0xC0), 1))
        // A GetEscID request, another data type, a reply to another node, a multi-frame start
        assertNull(hwParseReply(0x984E2D0Au, bytes(0, 0xC0), 2))
        assertNull(hwParseReply(0x984E5205u, bytes(5, 3, 0xC0), 3))
        assertNull(hwParseReply(0x98D20A85u, bytes(6, 2, 0xC1), 3))
        assertNull(hwParseReply(0x98F27F85u, bytes(1, 2, 3, 4, 5, 6, 7, 0x80), 8))
    }
}
