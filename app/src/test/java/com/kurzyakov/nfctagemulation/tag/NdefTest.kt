package com.kurzyakov.nfctagemulation.tag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NdefTest {

    @Test
    fun `text record matches the NFC Forum encoding`() {
        val bytes = NdefMessageCodec.encode(listOf(NdefRecord.text("hello", "en")))
        assertArrayEquals("D1 01 08 54 02 65 6E 68 65 6C 6C 6F".hexToBytes(), bytes)
    }

    @Test
    fun `text round trip keeps language and unicode`() {
        val record = NdefMessageCodec.decode(NdefMessageCodec.encode(listOf(NdefRecord.text("Привет, мир 👋", "ru")))).single()
        assertEquals(RecordContent.Text("Привет, мир 👋", "ru"), record.content())
    }

    @Test
    fun `utf16 text record is decoded`() {
        val payload = byteArrayOf(0x82.toByte()) + "en".toByteArray() + "Hi".toByteArray(Charsets.UTF_16BE)
        val record = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, payload)
        assertEquals(RecordContent.Text("Hi", "en"), record.content())
    }

    @Test
    fun `uri record uses the longest prefix`() {
        val record = NdefRecord.uri("https://www.example.com/path")
        assertEquals(0x02, record.payload[0].toInt())
        assertArrayEquals("example.com/path".toByteArray(), record.payload.copyOfRange(1, record.payload.size))
        assertEquals(RecordContent.Uri("https://www.example.com/path"), record.content())
        assertEquals(RecordContent.Uri("tel:+79990000000"), NdefRecord.uri("tel:+79990000000").content())
        assertEquals(RecordContent.Uri("geo:55.75,37.61"), NdefRecord.uri("geo:55.75,37.61").content())
    }

    @Test
    fun `several records and a long payload round trip`() {
        val records = listOf(
            NdefRecord.text("a".repeat(300), "en"),
            NdefRecord(NdefRecord.TNF_MIME_MEDIA, "text/plain".toByteArray(), "plain".toByteArray(), id = "1".toByteArray()),
            NdefRecord.uri("https://example.com"),
        )
        val bytes = NdefMessageCodec.encode(records)
        assertEquals(0x81, bytes[0].toInt() and 0xFF) // MB, без SR: длинная запись
        assertEquals(records, NdefMessageCodec.decode(bytes))
    }

    @Test
    fun `chunked record is reassembled`() {
        // Первый фрагмент: MB|CF|SR, TNF=1, тип "T"; второй: ME|SR, TNF=Unchanged.
        val bytes = "B1 01 03 54 02 65 6E   56 00 02 68 69".hexToBytes()
        val record = NdefMessageCodec.decode(bytes).single()
        assertEquals(RecordContent.Text("hi", "en"), record.content())
    }

    @Test
    fun `malformed messages are rejected`() {
        val invalid = listOf(
            "",
            "91 01 08 54 02 65 6E 68 65 6C 6C 6F", // нет ME
            "51 01 08 54 02 65 6E 68 65 6C 6C 6F", // нет MB
            "D1 01 20 54 02 65 6E", // длина больше данных
            "D1 01 08 54 02 65 6E 68 65 6C 6C 6F 00", // лишний байт
            "D6 00 00", // TNF Unchanged без фрагментации
        )
        invalid.forEach { hex ->
            assertThrows(hex, NdefFormatException::class.java) { NdefMessageCodec.decode(hex.hexToBytes()) }
        }
    }
}
