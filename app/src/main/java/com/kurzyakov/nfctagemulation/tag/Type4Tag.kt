package com.kurzyakov.nfctagemulation.tag

/** Что сделала команда, в структурированном виде: из этого строится журнал. */
sealed interface TagOperation {
    /** @property ndefVersion 2 или 1 для NDEF-приложения, `null` для чужого AID. */
    data class SelectApplication(val aid: String, val ndefVersion: Int?) : TagOperation
    data class SelectFile(val fileId: Int?) : TagOperation
    data class ReadBinary(val fileId: Int?, val offset: Int, val length: Int) : TagOperation
    data class UpdateBinary(val fileId: Int?, val offset: Int, val length: Int) : TagOperation
    data class Unsupported(val header: String) : TagOperation
    data object Malformed : TagOperation
}

/**
 * NFC Forum Type 4 Tag (Mapping 2.0) с одним NDEF-файлом.
 *
 * Метка не зависит от Android: на вход байты команды, на выход байты ответа.
 * Последовательность «считыватель выбирает приложение → читает CC → читает или пишет NDEF-файл»
 * описана в NFC Forum Type 4 Tag Technical Specification.
 *
 * Методы синхронизированы: команды HCE и действия из интерфейса могут прийти с разных потоков.
 */
class Type4Tag(
    initialMessage: ByteArray = ByteArray(0),
    val fileSize: Int = DEFAULT_NDEF_FILE_SIZE,
) {
    /**
     * @property committedMessage новое NDEF-сообщение, если эта команда завершила запись, иначе `null`.
     */
    class Result(
        val operation: TagOperation,
        val response: ByteArray,
        val committedMessage: ByteArray? = null,
    ) {
        val statusWord: Int get() = response.u16(response.size - 2)
    }

    init {
        require(fileSize in MIN_NDEF_FILE_SIZE..MAX_NDEF_FILE_SIZE) { "Недопустимый размер NDEF-файла: $fileSize" }
    }

    /** Сколько байт сообщения помещается в файл (без двух байт NLEN). */
    val maxMessageSize: Int get() = fileSize - NLEN_SIZE

    private val capabilityContainer = buildCapabilityContainer(fileSize)
    private val ndefFile = ByteArray(fileSize)

    private var applicationSelected = false
    private var selectedFile: Int? = null

    /** Считыватель что-то записал, но запись ещё не завершена установкой NLEN. */
    private var dirty = false

    init {
        setMessage(initialMessage)
    }

    /** Текущее NDEF-сообщение (по значению NLEN). Пустой массив, если метка пуста. */
    val message: ByteArray
        @Synchronized get() = currentMessage()

    @Synchronized
    fun setMessage(message: ByteArray) {
        require(message.size <= maxMessageSize) { "Сообщение (${message.size} Б) не помещается в метку ($maxMessageSize Б)" }
        ndefFile.fill(0)
        message.size.toU16Bytes().copyInto(ndefFile, 0)
        message.copyInto(ndefFile, NLEN_SIZE)
        dirty = false
    }

    @Synchronized
    fun process(command: ByteArray): Result {
        val apdu = CommandApdu.parse(command)
            ?: return Result(TagOperation.Malformed, StatusWord.toBytes(StatusWord.WRONG_LENGTH))
        return when (apdu.ins) {
            INS_SELECT -> when (apdu.p1) {
                P1_SELECT_BY_NAME -> selectApplication(apdu)
                P1_SELECT_BY_FILE_ID -> selectFile(apdu)
                else -> fail(TagOperation.Unsupported(apdu.header), StatusWord.INCORRECT_P1P2)
            }
            INS_READ_BINARY -> readBinary(apdu)
            INS_UPDATE_BINARY -> updateBinary(apdu)
            else -> fail(TagOperation.Unsupported(apdu.header), StatusWord.INS_NOT_SUPPORTED)
        }
    }

    /**
     * Считыватель ушёл из поля. Сбрасывает выбор приложения и файла.
     *
     * @return новое сообщение, если после последнего изменения запись не была явно завершена
     *   (например, считыватель записал NLEN раньше данных или запись прервалась), иначе `null`.
     */
    @Synchronized
    fun deactivate(): ByteArray? {
        applicationSelected = false
        selectedFile = null
        if (!dirty) return null
        dirty = false
        return currentMessage()
    }

    private fun selectApplication(apdu: CommandApdu): Result {
        val aid = apdu.data
        val version = when {
            aid.contentEquals(NDEF_AID_V2) -> 2
            aid.contentEquals(NDEF_AID_V1) -> 1
            else -> null
        }
        val operation = TagOperation.SelectApplication(aid.toHex(""), version)
        if (apdu.p2 != P2_FIRST_OR_ONLY && apdu.p2 != P2_NO_RESPONSE_DATA) return fail(operation, StatusWord.INCORRECT_P1P2)
        if (aid.isEmpty()) return fail(operation, StatusWord.WRONG_LENGTH)

        applicationSelected = version != null
        selectedFile = null
        return if (applicationSelected) ok(operation) else fail(operation, StatusWord.FILE_NOT_FOUND)
    }

    private fun selectFile(apdu: CommandApdu): Result {
        val fileId = if (apdu.data.size == 2) apdu.data.u16(0) else null
        val operation = TagOperation.SelectFile(fileId)
        if (apdu.p2 != P2_FIRST_OR_ONLY && apdu.p2 != P2_NO_RESPONSE_DATA) return fail(operation, StatusWord.INCORRECT_P1P2)
        if (fileId == null) return fail(operation, StatusWord.WRONG_LENGTH)
        if (!applicationSelected || (fileId != CC_FILE_ID && fileId != NDEF_FILE_ID)) {
            return fail(operation, StatusWord.FILE_NOT_FOUND)
        }
        selectedFile = fileId
        return ok(operation)
    }

    private fun readBinary(apdu: CommandApdu): Result {
        val offset = apdu.offset()
        val requested = apdu.ne ?: 256
        val operation = TagOperation.ReadBinary(selectedFile, offset, requested)
        if (apdu.p1 and SFI_FLAG != 0) return fail(operation, StatusWord.FUNCTION_NOT_SUPPORTED)

        val file = when (selectedFile) {
            CC_FILE_ID -> capabilityContainer
            NDEF_FILE_ID -> ndefFile
            else -> return fail(operation, StatusWord.NO_CURRENT_EF)
        }
        if (offset >= file.size) return fail(operation, StatusWord.OFFSET_OUTSIDE_EF)

        // Если просят больше, чем осталось до конца файла, отдаём остаток.
        val length = minOf(requested, file.size - offset)
        return ok(operation.copy(length = length), data = file.copyOfRange(offset, offset + length))
    }

    private fun updateBinary(apdu: CommandApdu): Result {
        val offset = apdu.offset()
        val data = apdu.data
        val operation = TagOperation.UpdateBinary(selectedFile, offset, data.size)
        if (apdu.p1 and SFI_FLAG != 0) return fail(operation, StatusWord.FUNCTION_NOT_SUPPORTED)
        when (selectedFile) {
            NDEF_FILE_ID -> Unit
            CC_FILE_ID -> return fail(operation, StatusWord.SECURITY_STATUS_NOT_SATISFIED)
            else -> return fail(operation, StatusWord.NO_CURRENT_EF)
        }
        if (data.isEmpty()) return fail(operation, StatusWord.WRONG_LENGTH)
        if (offset >= fileSize) return fail(operation, StatusWord.OFFSET_OUTSIDE_EF)
        if (offset + data.size > fileSize) return fail(operation, StatusWord.WRONG_LENGTH)

        data.copyInto(ndefFile, offset)
        dirty = true

        // По спецификации считыватель сначала обнуляет NLEN, пишет данные и последним
        // шагом записывает настоящую длину. Ненулевой NLEN означает, что запись завершена.
        var committed: ByteArray? = null
        if (offset < NLEN_SIZE && ndefFile.u16(0) in 1..maxMessageSize) {
            committed = currentMessage()
            dirty = false
        }
        return ok(operation, committed = committed)
    }

    private fun currentMessage(): ByteArray {
        val length = ndefFile.u16(0)
        return if (length in 0..maxMessageSize) ndefFile.copyOfRange(NLEN_SIZE, NLEN_SIZE + length) else ByteArray(0)
    }

    private fun CommandApdu.offset(): Int = ((p1 and 0x7F) shl 8) or p2

    private fun ok(operation: TagOperation, data: ByteArray = ByteArray(0), committed: ByteArray? = null) =
        Result(operation, data + StatusWord.toBytes(StatusWord.OK), committed)

    private fun fail(operation: TagOperation, statusWord: Int) =
        Result(operation, StatusWord.toBytes(statusWord))

    companion object {
        /** AID приложения NDEF Tag Application, Mapping 2.0. */
        val NDEF_AID_V2 = "D2760000850101".hexToBytes()

        /** AID приложения NDEF Tag Application, Mapping 1.0. */
        val NDEF_AID_V1 = "D2760000850100".hexToBytes()

        const val CC_FILE_ID = 0xE103
        const val NDEF_FILE_ID = 0xE104
        const val CC_FILE_SIZE = 15

        const val DEFAULT_NDEF_FILE_SIZE = 4096
        const val MIN_NDEF_FILE_SIZE = 5
        const val MAX_NDEF_FILE_SIZE = 0xFFFE

        /** MLe: сколько байт считыватель может прочитать за одну команду READ BINARY. */
        const val MAX_READ_LENGTH = 0x3B

        /** MLc: сколько байт считыватель может записать за одну команду UPDATE BINARY. */
        const val MAX_WRITE_LENGTH = 0x34

        const val NLEN_SIZE = 2

        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
        private const val INS_UPDATE_BINARY = 0xD6
        private const val P1_SELECT_BY_FILE_ID = 0x00
        private const val P1_SELECT_BY_NAME = 0x04
        private const val P2_FIRST_OR_ONLY = 0x00
        private const val P2_NO_RESPONSE_DATA = 0x0C
        private const val SFI_FLAG = 0x80

        private fun buildCapabilityContainer(fileSize: Int): ByteArray =
            CC_FILE_SIZE.toU16Bytes() +                    // CCLEN
                byteArrayOf(0x20) +                        // Mapping Version 2.0
                MAX_READ_LENGTH.toU16Bytes() +             // MLe
                MAX_WRITE_LENGTH.toU16Bytes() +            // MLc
                byteArrayOf(0x04, 0x06) +                  // NDEF File Control TLV
                NDEF_FILE_ID.toU16Bytes() +
                fileSize.toU16Bytes() +                    // максимальный размер NDEF-файла
                byteArrayOf(0x00, 0x00)                    // чтение и запись разрешены
    }
}
