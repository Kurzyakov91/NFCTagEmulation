package com.kurzyakov.nfctagemulation.tag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandApduTest {

    @Test
    fun `case 1 has no data and no Le`() {
        val apdu = CommandApdu.parse("00 A4 04 00".hexToBytes())!!
        assertEquals(0xA4, apdu.ins)
        assertEquals(0, apdu.data.size)
        assertNull(apdu.ne)
    }

    @Test
    fun `case 2S zero Le means 256`() {
        assertEquals(256, CommandApdu.parse("00 B0 00 00 00".hexToBytes())!!.ne)
        assertEquals(15, CommandApdu.parse("00 B0 00 00 0F".hexToBytes())!!.ne)
    }

    @Test
    fun `case 3S and 4S`() {
        val select = CommandApdu.parse("00 A4 00 0C 02 E1 03".hexToBytes())!!
        assertArrayEquals("E103".hexToBytes(), select.data)
        assertNull(select.ne)

        val selectWithLe = CommandApdu.parse("00 A4 04 00 07 D2 76 00 00 85 01 01 00".hexToBytes())!!
        assertArrayEquals(Type4Tag.NDEF_AID_V2, selectWithLe.data)
        assertEquals(256, selectWithLe.ne)
    }

    @Test
    fun `extended length forms`() {
        assertEquals(65536, CommandApdu.parse("00 B0 00 00 00 00 00".hexToBytes())!!.ne)
        assertEquals(0x0123, CommandApdu.parse("00 B0 00 00 00 01 23".hexToBytes())!!.ne)

        val case3 = CommandApdu.parse("00 D6 00 00 00 00 02 AA BB".hexToBytes())!!
        assertArrayEquals("AABB".hexToBytes(), case3.data)
        assertNull(case3.ne)

        val case4 = CommandApdu.parse("00 D6 00 00 00 00 02 AA BB 00 10".hexToBytes())!!
        assertArrayEquals("AABB".hexToBytes(), case4.data)
        assertEquals(16, case4.ne)
    }

    @Test
    fun `length mismatch is rejected`() {
        assertNull(CommandApdu.parse("00 A4".hexToBytes()))
        assertNull(CommandApdu.parse("00 D6 00 00 05 01 02".hexToBytes()))
        assertNull(CommandApdu.parse("00 D6 00 00 02 01 02 03 04".hexToBytes()))
        assertNull(CommandApdu.parse("00 D6 00 00 00 00 05 01".hexToBytes()))
    }
}
