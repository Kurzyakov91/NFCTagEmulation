package com.kurzyakov.nfctagemulation

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MyHostApduService : HostApduService() {

    companion object {
        private const val TAG = "MyHostApduService"
        private val SUCCESS_RESPONSE = byteArrayOf(0x90.toByte(), 0x00.toByte()) // Успешный ответ
        private val UNKNOWN_COMMAND_RESPONSE = byteArrayOf(0x6F.toByte(), 0x00.toByte()) // Неизвестная ошибка
        private val FILE_NOT_FOUND_RESPONSE = byteArrayOf(0x6A.toByte(), 0x82.toByte()) // Файл не найден
        private val WRONG_P1P2_RESPONSE = byteArrayOf(0x6B.toByte(), 0x00.toByte()) // Неверные параметры P1 P2
        private val COMMAND_NOT_ALLOWED_RESPONSE = byteArrayOf(0x69.toByte(), 0x86.toByte()) // Команда не разрешена

        // Список ожидаемых AID
        private val EXPECTED_AIDS = listOf(
            byteArrayOf(0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x00.toByte()),
            byteArrayOf(0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte()),
            byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        )

        // Идентификаторы файлов
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03.toByte())
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04.toByte())
    }

    // Хранение состояния выбранного файла
    private var selectedFileId: ByteArray? = null

    // Содержимое файлов
    private val ccFile = createCcFile()
    private var ndefFile = createNdefFile()
    private val ndefFileLock = ReentrantLock() // Для обеспечения потокобезопасности при записи

    // Дополнительные переменные для отслеживания состояния
    private var maxOffsetWritten = 0
    private var totalNdefMessageLength = -1
    private var ndefMessageProcessed = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        if (commandApdu == null) {
            logAndBroadcast("Получена пустая команда APDU.")
            return UNKNOWN_COMMAND_RESPONSE
        }

        // Логируем весь поток данных (команда APDU)
        val rawCommandData = commandApdu.joinToString(" ") { String.format("%02X", it) }
        logAndBroadcast("Получена команда APDU: $rawCommandData")


        if (isLikelyAPDU(commandApdu)) {
            val cla = commandApdu[0]
            val ins = commandApdu[1]
            val p1 = commandApdu[2]
            val p2 = commandApdu[3]

            logAndBroadcast("APDU команда: CLA=${String.format("%02X", cla)}, " +
                    "INS=${String.format("%02X", ins)}, " +
                    "P1=${String.format("%02X", p1)}, " +
                    "P2=${String.format("%02X", p2)}")

            val response: ByteArray = when (ins) {
                0xA4.toByte() -> {
                    // Обработка команды SELECT
                    if (p1 == 0x04.toByte()) {
                        // SELECT по AID
                        handleSelectByAid(commandApdu)
                    } else if (p1 == 0x00.toByte()) {
                        // SELECT по идентификатору файла
                        handleSelectByFileId(commandApdu)
                    } else {
                        logAndBroadcast("Неизвестный P1 для команды SELECT: ${String.format("%02X", p1)}")
                        UNKNOWN_COMMAND_RESPONSE
                    }
                }
                0xB0.toByte() -> {
                    // Обработка команды READ BINARY
                    handleReadBinary(commandApdu)
                }
                0xD6.toByte() -> {
                    // Обработка команды WRITE BINARY
                    handleWriteBinary(commandApdu)
                }
                else -> {
                    logAndBroadcast("Неизвестная команда INS: ${String.format("%02X", ins)}")
                    UNKNOWN_COMMAND_RESPONSE
                }
            }

            // Логируем отправляемый ответ
            val responseString = response.joinToString(" ") { String.format("%02X", it) }
            logAndBroadcast("Отправляем ответ: $responseString")

            return response
        } else {
            logAndBroadcast("Получены данные, которые не соответствуют формату APDU.")
            return UNKNOWN_COMMAND_RESPONSE
        }
    }

    override fun onDeactivated(reason: Int) {
        logAndBroadcast("NFC отключён, причина: $reason")
        selectedFileId = null // Сбрасываем выбранный файл при деактивации
        // Сбрасываем состояния
        ndefMessageProcessed = false
        totalNdefMessageLength = -1
        maxOffsetWritten = 0
    }

    private fun handleSelectByAid(commandApdu: ByteArray): ByteArray {
        // Проверяем, что длина команды достаточна для извлечения Lc
        if (commandApdu.size < 5) {
            logAndBroadcast("Команда SELECT по AID слишком короткая.")
            return UNKNOWN_COMMAND_RESPONSE
        }

        val lc = commandApdu[4].toInt() and 0xFF // Значение Lc
        val expectedLength = 5 + lc // 5 байт заголовка + длина данных

        // Проверяем, что общая длина команды соответствует ожидаемой длине
        if (commandApdu.size < expectedLength) {
            logAndBroadcast("Команда имеет некорректную длину. Ожидается $expectedLength байт, получено ${commandApdu.size}.")
            return UNKNOWN_COMMAND_RESPONSE
        }

        // Извлекаем AID
        val aid = commandApdu.copyOfRange(5, 5 + lc)
        val aidString = aid.joinToString(" ") { String.format("%02X", it) }
        logAndBroadcast("Полученный AID: $aidString")

        // Проверяем, соответствует ли AID одному из ожидаемых
        val isAidExpected = EXPECTED_AIDS.any { it.contentEquals(aid) }

        return if (isAidExpected) {
            logAndBroadcast("AID соответствует одному из ожидаемых. Отправляем успешный ответ.")
            selectedFileId = null // Сбрасываем выбранный файл
            // Сбрасываем состояния
            ndefMessageProcessed = false
            totalNdefMessageLength = -1
            maxOffsetWritten = 0
            SUCCESS_RESPONSE
        } else {
            logAndBroadcast("AID не соответствует ожидаемым. Отправляем ответ об ошибке.")
            UNKNOWN_COMMAND_RESPONSE
        }
    }

    private fun handleSelectByFileId(commandApdu: ByteArray): ByteArray {
        // Проверяем, что длина команды достаточна
        if (commandApdu.size < 7) {
            logAndBroadcast("Команда SELECT по File ID слишком короткая.")
            return UNKNOWN_COMMAND_RESPONSE
        }

        val lc = commandApdu[4].toInt() and 0xFF
        val expectedLength = 5 + lc
        if (commandApdu.size < expectedLength) {
            logAndBroadcast("Некорректная длина команды SELECT по File ID.")
            return UNKNOWN_COMMAND_RESPONSE
        }

        // Извлекаем File ID
        val fileId = commandApdu.copyOfRange(5, 5 + lc)
        val fileIdString = fileId.joinToString(" ") { String.format("%02X", it) }
        logAndBroadcast("Полученный File ID: $fileIdString")

        // Проверяем, соответствует ли File ID ожидаемому
        return if (fileId.contentEquals(CC_FILE_ID) || fileId.contentEquals(NDEF_FILE_ID)) {
            selectedFileId = fileId
            logAndBroadcast("File ID соответствует ожидаемому. Отправляем успешный ответ.")
            // Сбрасываем состояния при выборе NDEF файла
            if (fileId.contentEquals(NDEF_FILE_ID)) {
                ndefMessageProcessed = false
                totalNdefMessageLength = -1
                maxOffsetWritten = 0
            }
            SUCCESS_RESPONSE
        } else {
            logAndBroadcast("Неизвестный File ID. Отправляем ответ об ошибке.")
            FILE_NOT_FOUND_RESPONSE
        }
    }

    private fun handleReadBinary(commandApdu: ByteArray): ByteArray {
        if (selectedFileId == null) {
            logAndBroadcast("Файл не выбран. Отправляем ответ: 69 86")
            return COMMAND_NOT_ALLOWED_RESPONSE
        }

        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF
        val offset = (p1 shl 8) or p2
        val le = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0

        val fileData = when {
            selectedFileId!!.contentEquals(CC_FILE_ID) -> ccFile
            selectedFileId!!.contentEquals(NDEF_FILE_ID) -> {
                ndefFileLock.withLock { ndefFile }
            }
            else -> {
                logAndBroadcast("Неизвестный выбранный файл.")
                return FILE_NOT_FOUND_RESPONSE
            }
        }

        // Проверяем, что запрошенные данные не выходят за пределы файла
        if (offset + le > fileData.size) {
            logAndBroadcast("Запрошено больше данных, чем доступно в файле.")
            return WRONG_P1P2_RESPONSE // Ошибка адресации
        }

        val responseData = fileData.copyOfRange(offset, offset + le)
        logAndBroadcast("Отправляем данные READ BINARY: ${responseData.joinToString(" ") { String.format("%02X", it) }}")
        return responseData + SUCCESS_RESPONSE
    }

    private fun handleWriteBinary(commandApdu: ByteArray): ByteArray {
        if (selectedFileId == null || !selectedFileId!!.contentEquals(NDEF_FILE_ID)) {
            logAndBroadcast("Файл для записи не выбран или неверный. Отправляем ответ: 69 86")
            return COMMAND_NOT_ALLOWED_RESPONSE
        }

        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF
        val offset = (p1 shl 8) or p2
        val lc = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0

        val data = commandApdu.copyOfRange(5, 5 + lc)

        ndefFileLock.withLock {
            // Проверяем, что данные не выходят за пределы файла
            if (offset + data.size > ndefFile.size) {
                logAndBroadcast("Запись выходит за пределы NDEF файла.")
                return WRONG_P1P2_RESPONSE
            }

            // Пишем данные в ndefFile
            System.arraycopy(data, 0, ndefFile, offset, data.size)
            logAndBroadcast("Записано ${data.size} байт в NDEF файл по смещению $offset")

            // Обновляем максимальный записанный смещение
            val newMaxOffset = maxOf(maxOffsetWritten, offset + data.size)
            maxOffsetWritten = newMaxOffset

            // Проверяем, получили ли мы хотя бы первые 2 байта для чтения длины NDEF сообщения
            if (maxOffsetWritten >= 2 && !ndefMessageProcessed) {
                // Всегда читаем длину из первых двух байт
                totalNdefMessageLength = ((ndefFile[0].toInt() and 0xFF) shl 8) or (ndefFile[1].toInt() and 0xFF)
                logAndBroadcast("Длина NDEF сообщения: $totalNdefMessageLength")

                val totalDataLength = 2 + totalNdefMessageLength

                if (maxOffsetWritten >= totalDataLength) {
                    // Получили все данные, можем извлечь NDEF сообщение
                    val ndefMessageBytes = ndefFile.copyOfRange(2, 2 + totalNdefMessageLength)
                    try {
                        val ndefMessage = NdefMessage(ndefMessageBytes)
                        val records = ndefMessage.records
                        for (record in records) {
                            // Обрабатываем каждый NDEF Record
                            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                                val text = parseTextRecord(record.payload)
                                logAndBroadcast("Получен текст: $text")
                            } else {
                                logAndBroadcast("Получен NDEF Record с неизвестным типом")
                            }
                        }
                        ndefMessageProcessed = true
                    } catch (e: Exception) {
                        logAndBroadcast("Ошибка при разборе NDEF сообщения: ${e.localizedMessage}")
                    }
                } else {
                    logAndBroadcast("Ожидаем больше данных. Текущий размер: $maxOffsetWritten, необходимый: $totalDataLength")
                }
            }
        }

        return SUCCESS_RESPONSE
    }

    private fun parseTextRecord(payload: ByteArray): String {
        try {
            val statusByte = payload[0].toInt()
            val encoding = if ((statusByte and 0x80) == 0) "UTF-8" else "UTF-16"
            val languageCodeLength = statusByte and 0x3F
            val text = String(payload, 1 + languageCodeLength, payload.size - 1 - languageCodeLength, charset(encoding))
            return text
        } catch (e: Exception) {
            logAndBroadcast("Ошибка при парсинге TextRecord: ${e.localizedMessage}")
            return ""
        }
    }

    private fun logAndBroadcast(message: String) {
        Log.d(TAG, message)
        val intent = Intent("com.kurzyakov.ACTION_NFC_LOG")
        intent.putExtra("log", message)
        sendBroadcast(intent)
    }

    private fun isLikelyAPDU(data: ByteArray): Boolean {
        // Простейшая проверка на APDU: длина должна быть не менее 4 байт (CLA, INS, P1, P2)
        return data.size >= 4
    }

    private fun saveRawDataToFile(data: ByteArray) {
        try {
            val file = File(applicationContext.filesDir, "nfc_raw_data.txt")
            val outputStream = FileOutputStream(file, true) // Режим добавления
            val hexData = data.joinToString(" ") { String.format("%02X", it) }
            outputStream.write((hexData + "\n").toByteArray(StandardCharsets.UTF_8))
            outputStream.close()
            logAndBroadcast("Данные сохранены в файл: ${file.absolutePath}")
        } catch (e: Exception) {
            logAndBroadcast("Ошибка при сохранении данных: ${e.localizedMessage}")
        }
    }

    private fun createCcFile(): ByteArray {
        // Создаем CC File в соответствии со спецификацией NFC Forum Type 4 Tag
        // Размер файла 15 байт (0x0F)
        return byteArrayOf(
            0x00.toByte(), 0x0F.toByte(), // CCLEN (15 байт)
            0x20.toByte(),               // Mapping Version 2.0
            0x00.toByte(), 0x3B.toByte(), // MLe (Максимальная длина команды READ BINARY) 59 байт
            0x00.toByte(), 0x34.toByte(), // MLc (Максимальная длина команды UPDATE BINARY) 52 байт
            0x04.toByte(),               // T (Tag) - NDEF File Control TLV
            0x06.toByte(),               // L (Length)
            0xE1.toByte(), 0x04.toByte(), // NDEF File ID
            0x0F.toByte(), 0xFF.toByte(), // NDEF File Size (максимальный размер файла) 4095 байт
            0x00.toByte(), 0x00.toByte()  // Read & Write Access
        )
    }

    private fun createNdefFile(): ByteArray {
        // Изначально создаём пустой NDEF файл размером 4096 байт
        val ndefFileSize = 4096
        val ndefFile = ByteArray(ndefFileSize)
        // Устанавливаем длину NDEF сообщения в 0
        ndefFile[0] = 0x00
        ndefFile[1] = 0x00
        return ndefFile
    }
}