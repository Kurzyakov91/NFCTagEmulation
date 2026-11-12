package com.kurzyakov.nfctagemulation.tag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Type4TagTest {

    private val hello = NdefMessageCodec.encode(listOf(NdefRecord.text("hello", "en")))

    private fun Type4Tag.send(hex: String): Type4Tag.Result = process(hex.hexToBytes())

    private fun Type4Tag.openNdefFile() {
        assertEquals(StatusWord.OK, send("00 A4 04 00 07 D2 76 00 00 85 01 01 00").statusWord)
        assertEquals(StatusWord.OK, send("00 A4 00 0C 02 E1 04").statusWord)
    }

    @Test
    fun `capability container describes the NDEF file`() {
        val tag = Type4Tag()
        tag.send("00 A4 04 00 07 D2 76 00 00 85 01 01 00")
        tag.send("00 A4 00 0C 02 E1 03")
        val response = tag.send("00 B0 00 00 0F").response
        assertArrayEquals("00 0F 20 00 3B 00 34 04 06 E1 04 10 00 00 00 90 00".hexToBytes(), response)
    }

    @Test
    fun `reader reads the stored message`() {
        val tag = Type4Tag(hello)
        assertArrayEquals(hello, NdefTagReader.readMessage { tag.process(it).response })
    }

    @Test
    fun `reader reads a message longer than MLe in chunks`() {
        val long = NdefMessageCodec.encode(listOf(NdefRecord.text("x".repeat(1000), "en")))
        val tag = Type4Tag(long)
        assertArrayEquals(long, NdefTagReader.readMessage { tag.process(it).response })
    }

    @Test
    fun `mapping 1_0 application is accepted`() {
        val tag = Type4Tag()
        assertEquals(StatusWord.OK, tag.send("00 A4 04 00 07 D2 76 00 00 85 01 00").statusWord)
    }

    @Test
    fun `write by the spec procedure commits once when NLEN is set`() {
        val tag = Type4Tag()
        val message = NdefMessageCodec.encode(listOf(NdefRecord.text("y".repeat(200), "en")))
        val commits = mutableListOf<ByteArray>()
        NdefTagReader.writeMessage({ command ->
            val result = tag.process(command)
            result.committedMessage?.let(commits::add)
            result.response
        }, message)

        assertEquals(1, commits.size)
        assertArrayEquals(message, commits.single())
        assertArrayEquals(message, tag.message)
        assertNull(tag.deactivate())
    }

    @Test
    fun `single write of NLEN and data commits immediately`() {
        val tag = Type4Tag()
        tag.openNdefFile()
        val data = byteArrayOf(0, hello.size.toByte()) + hello
        val result = tag.process(NdefTagReader.updateBinary(0, data))
        assertEquals(StatusWord.OK, result.statusWord)
        assertArrayEquals(hello, result.committedMessage)
    }

    @Test
    fun `interrupted write is reported on deactivation`() {
        val tag = Type4Tag(hello)
        tag.openNdefFile()
        tag.process(NdefTagReader.updateBinary(0, byteArrayOf(0, 0)))
        tag.process(NdefTagReader.updateBinary(2, "D1 01".hexToBytes()))
        // Считыватель ушёл до записи NLEN: метка пуста, как и у настоящей метки.
        assertArrayEquals(ByteArray(0), tag.deactivate())
        assertArrayEquals(ByteArray(0), tag.message)
    }

    @Test
    fun `Le larger than the rest of the file returns the rest`() {
        val tag = Type4Tag(fileSize = 32)
        tag.openNdefFile()
        val result = tag.send("00 B0 00 1E 00") // смещение 30, Le = 256
        assertEquals(StatusWord.OK, result.statusWord)
        assertEquals(2 + 2, result.response.size)
    }

    @Test
    fun `errors use proper status words`() {
        val tag = Type4Tag(fileSize = 32)

        // Файл до выбора приложения.
        assertEquals(StatusWord.FILE_NOT_FOUND, tag.send("00 A4 00 0C 02 E1 04").statusWord)
        // Чтение без выбранного файла.
        assertEquals(StatusWord.NO_CURRENT_EF, tag.send("00 B0 00 00 02").statusWord)
        // Чужой AID.
        assertEquals(StatusWord.FILE_NOT_FOUND, tag.send("00 A4 04 00 07 A0 00 00 02 47 10 01 00").statusWord)

        tag.send("00 A4 04 00 07 D2 76 00 00 85 01 01 00")
        assertEquals(StatusWord.FILE_NOT_FOUND, tag.send("00 A4 00 0C 02 E1 05").statusWord)
        assertEquals(StatusWord.WRONG_LENGTH, tag.send("00 A4 00 0C 01 E1").statusWord)

        tag.send("00 A4 00 0C 02 E1 03")
        assertEquals(StatusWord.SECURITY_STATUS_NOT_SATISFIED, tag.send("00 D6 00 00 01 00").statusWord)
        assertEquals(StatusWord.OFFSET_OUTSIDE_EF, tag.send("00 B0 00 0F 01").statusWord)

        tag.send("00 A4 00 0C 02 E1 04")
        assertEquals(StatusWord.OFFSET_OUTSIDE_EF, tag.send("00 D6 00 20 01 00").statusWord)
        assertEquals(StatusWord.WRONG_LENGTH, tag.send("00 D6 00 1F 02 00 00").statusWord)
        assertEquals(StatusWord.FUNCTION_NOT_SUPPORTED, tag.send("00 B0 81 00 02").statusWord)

        // Длина данных не совпадает с Lc: раньше это роняло сервис.
        assertEquals(StatusWord.WRONG_LENGTH, tag.send("00 D6 00 02 10 01 02").statusWord)
        assertEquals(StatusWord.INS_NOT_SUPPORTED, tag.send("00 CA 00 00 00").statusWord)
        assertEquals(StatusWord.WRONG_LENGTH, tag.send("00").statusWord)
    }

    @Test
    fun `deactivation resets the selection`() {
        val tag = Type4Tag(hello)
        tag.openNdefFile()
        tag.deactivate()
        assertEquals(StatusWord.NO_CURRENT_EF, tag.send("00 B0 00 00 02").statusWord)
    }
}
