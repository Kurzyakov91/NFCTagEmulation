package com.kurzyakov.nfctagemulation.tag

import java.io.ByteArrayOutputStream

/**
 * Считыватель Type 4 Tag: выполняет те же шаги, что телефон или ридер по спецификации NFC Forum.
 * Используется для пробного чтения в приложении и в тестах.
 */
object NdefTagReader {

    class TagReadException(message: String) : Exception(message)

    /** Читает NDEF-сообщение. [transceive] отправляет команду и возвращает ответ. */
    fun readMessage(transceive: (ByteArray) -> ByteArray): ByteArray {
        val cc = openCapabilityContainer(transceive)
        val nlen = expectOk(transceive(readBinary(0, Type4Tag.NLEN_SIZE)), "READ NLEN")
        if (nlen.size != Type4Tag.NLEN_SIZE) throw TagReadException("READ NLEN: неверная длина ответа")
        val length = nlen.u16(0)
        if (length > cc.fileSize - Type4Tag.NLEN_SIZE) throw TagReadException("NLEN больше размера файла")

        val out = ByteArrayOutputStream(length)
        var offset = Type4Tag.NLEN_SIZE
        val end = Type4Tag.NLEN_SIZE + length
        while (offset < end) {
            val chunk = minOf(cc.maxReadLength, end - offset, 255)
            val data = expectOk(transceive(readBinary(offset, chunk)), "READ BINARY")
            if (data.isEmpty()) throw TagReadException("READ BINARY: пустой ответ")
            out.write(data, 0, minOf(data.size, end - offset))
            offset += data.size
        }
        return out.toByteArray()
    }

    /** Записывает сообщение по процедуре спецификации: NLEN = 0, данные, затем настоящий NLEN. */
    fun writeMessage(transceive: (ByteArray) -> ByteArray, message: ByteArray) {
        val cc = openCapabilityContainer(transceive)
        if (message.size > cc.fileSize - Type4Tag.NLEN_SIZE) throw TagReadException("Сообщение не помещается в метку")
        expectOk(transceive(updateBinary(0, byteArrayOf(0, 0))), "UPDATE NLEN=0")
        var offset = 0
        while (offset < message.size) {
            val chunk = minOf(cc.maxWriteLength, message.size - offset)
            val data = message.copyOfRange(offset, offset + chunk)
            expectOk(transceive(updateBinary(Type4Tag.NLEN_SIZE + offset, data)), "UPDATE BINARY")
            offset += chunk
        }
        expectOk(transceive(updateBinary(0, message.size.toU16Bytes())), "UPDATE NLEN")
    }

    fun selectApplication(aid: ByteArray = Type4Tag.NDEF_AID_V2): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)

    fun selectFile(fileId: Int): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02) + fileId.toU16Bytes()

    fun readBinary(offset: Int, length: Int): ByteArray =
        byteArrayOf(0x00, 0xB0.toByte()) + offset.toU16Bytes() + byteArrayOf(length.toByte())

    fun updateBinary(offset: Int, data: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xD6.toByte()) + offset.toU16Bytes() + byteArrayOf(data.size.toByte()) + data

    private class CapabilityContainer(val maxReadLength: Int, val maxWriteLength: Int, val fileSize: Int)

    private fun openCapabilityContainer(transceive: (ByteArray) -> ByteArray): CapabilityContainer {
        expectOk(transceive(selectApplication()), "SELECT NDEF")
        expectOk(transceive(selectFile(Type4Tag.CC_FILE_ID)), "SELECT CC")
        val cc = expectOk(transceive(readBinary(0, Type4Tag.CC_FILE_SIZE)), "READ CC")
        if (cc.size < Type4Tag.CC_FILE_SIZE || cc[7] != 0x04.toByte()) {
            throw TagReadException("Некорректный файл CC")
        }
        val container = CapabilityContainer(
            maxReadLength = cc.u16(3),
            maxWriteLength = cc.u16(5),
            fileSize = cc.u16(11),
        )
        expectOk(transceive(selectFile(cc.u16(9))), "SELECT NDEF-файла")
        return container
    }

    private fun expectOk(response: ByteArray, step: String): ByteArray {
        val sw = StatusWord.of(response) ?: throw TagReadException("$step: ответ короче двух байт")
        if (sw != StatusWord.OK) throw TagReadException("$step: %04X".format(sw))
        return response.copyOfRange(0, response.size - 2)
    }
}
