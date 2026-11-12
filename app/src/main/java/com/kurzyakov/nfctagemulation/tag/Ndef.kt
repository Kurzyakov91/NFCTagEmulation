package com.kurzyakov.nfctagemulation.tag

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Запись NDEF (NFC Forum NDEF 1.0). Своя реализация вместо `android.nfc.NdefRecord`,
 * чтобы логику можно было проверять обычными JVM-тестами.
 */
class NdefRecord(
    val tnf: Int,
    val type: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0),
    val id: ByteArray = ByteArray(0),
) {
    init {
        require(tnf in TNF_EMPTY..TNF_UNCHANGED) { "Неверный TNF: $tnf" }
        require(type.size <= 255) { "Тип записи длиннее 255 байт" }
        require(id.size <= 255) { "Идентификатор записи длиннее 255 байт" }
    }

    override fun equals(other: Any?): Boolean = other is NdefRecord &&
        tnf == other.tnf &&
        type.contentEquals(other.type) &&
        payload.contentEquals(other.payload) &&
        id.contentEquals(other.id)

    override fun hashCode(): Int =
        ((tnf * 31 + type.contentHashCode()) * 31 + payload.contentHashCode()) * 31 + id.contentHashCode()

    override fun toString(): String = "NdefRecord(tnf=$tnf, type=${type.toHex()}, payload=${payload.size} B)"

    companion object {
        const val TNF_EMPTY = 0x00
        const val TNF_WELL_KNOWN = 0x01
        const val TNF_MIME_MEDIA = 0x02
        const val TNF_ABSOLUTE_URI = 0x03
        const val TNF_EXTERNAL_TYPE = 0x04
        const val TNF_UNKNOWN = 0x05
        const val TNF_UNCHANGED = 0x06

        val RTD_TEXT = byteArrayOf(0x54) // "T"
        val RTD_URI = byteArrayOf(0x55) // "U"

        /** Текстовая запись RTD Text в UTF-8. */
        fun text(text: String, languageCode: String = "en"): NdefRecord {
            val language = languageCode.toByteArray(Charsets.US_ASCII)
            require(language.size in 1..0x3F) { "Неверный код языка: $languageCode" }
            val body = text.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + language.size + body.size)
            payload[0] = language.size.toByte()
            language.copyInto(payload, 1)
            body.copyInto(payload, 1 + language.size)
            return NdefRecord(TNF_WELL_KNOWN, RTD_TEXT, payload)
        }

        /** Ссылка RTD URI с сокращением стандартного префикса. */
        fun uri(uri: String): NdefRecord {
            val code = URI_PREFIXES.indices
                .filter { it > 0 && uri.startsWith(URI_PREFIXES[it]) }
                .maxByOrNull { URI_PREFIXES[it].length } ?: 0
            val rest = uri.substring(URI_PREFIXES[code].length).toByteArray(Charsets.UTF_8)
            return NdefRecord(TNF_WELL_KNOWN, RTD_URI, byteArrayOf(code.toByte()) + rest)
        }

        /** Таблица сокращений префиксов из NFC Forum URI RTD. */
        internal val URI_PREFIXES = listOf(
            "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
            "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
            "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
            "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://",
            "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
            "urn:nfc:",
        )
    }
}

class NdefFormatException(message: String) : Exception(message)

/** Кодирование и разбор NDEF-сообщений. */
object NdefMessageCodec {
    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_CF = 0x20
    private const val FLAG_SR = 0x10
    private const val FLAG_IL = 0x08
    private const val TNF_MASK = 0x07

    fun encode(records: List<NdefRecord>): ByteArray {
        require(records.isNotEmpty()) { "NDEF-сообщение должно содержать хотя бы одну запись" }
        val out = ByteArrayOutputStream()
        records.forEachIndexed { index, record ->
            val shortRecord = record.payload.size < 256
            var header = record.tnf
            if (index == 0) header = header or FLAG_MB
            if (index == records.lastIndex) header = header or FLAG_ME
            if (shortRecord) header = header or FLAG_SR
            if (record.id.isNotEmpty()) header = header or FLAG_IL

            out.write(header)
            out.write(record.type.size)
            if (shortRecord) {
                out.write(record.payload.size)
            } else {
                val size = record.payload.size
                out.write(size ushr 24)
                out.write(size ushr 16)
                out.write(size ushr 8)
                out.write(size)
            }
            if (record.id.isNotEmpty()) out.write(record.id.size)
            out.write(record.type)
            out.write(record.id)
            out.write(record.payload)
        }
        return out.toByteArray()
    }

    /** Разбирает сообщение, включая записи, разбитые на фрагменты (chunked). */
    @Throws(NdefFormatException::class)
    fun decode(bytes: ByteArray): List<NdefRecord> {
        if (bytes.isEmpty()) throw NdefFormatException("Пустое сообщение")
        val records = mutableListOf<NdefRecord>()
        var position = 0
        var chunk: ChunkedRecord? = null

        fun take(count: Long): ByteArray {
            if (count < 0 || position + count > bytes.size) {
                throw NdefFormatException("Запись выходит за пределы сообщения")
            }
            val result = bytes.copyOfRange(position, position + count.toInt())
            position += count.toInt()
            return result
        }

        while (true) {
            val header = take(1)[0].u8()
            val tnf = header and TNF_MASK
            val first = position == 1
            if (first && header and FLAG_MB == 0) throw NdefFormatException("Нет флага MB у первой записи")
            if (!first && header and FLAG_MB != 0) throw NdefFormatException("Флаг MB не у первой записи")

            val typeLength = take(1)[0].u8()
            val payloadLength = if (header and FLAG_SR != 0) {
                take(1)[0].u8().toLong()
            } else {
                take(4).fold(0L) { acc, b -> (acc shl 8) or b.u8().toLong() }
            }
            val idLength = if (header and FLAG_IL != 0) take(1)[0].u8() else 0
            val type = take(typeLength.toLong())
            val id = take(idLength.toLong())
            val payload = take(payloadLength)

            val chunked = header and FLAG_CF != 0
            val current = chunk
            if (current == null) {
                if (tnf == NdefRecord.TNF_UNCHANGED) throw NdefFormatException("TNF Unchanged вне фрагментированной записи")
                if (tnf == NdefRecord.TNF_EMPTY && (typeLength != 0 || idLength != 0 || payloadLength != 0L)) {
                    throw NdefFormatException("Пустая запись содержит данные")
                }
                if (chunked) {
                    chunk = ChunkedRecord(tnf, type, id).also { it.payload.write(payload) }
                } else {
                    records += NdefRecord(tnf, type, payload, id)
                }
            } else {
                if (tnf != NdefRecord.TNF_UNCHANGED || typeLength != 0 || idLength != 0) {
                    throw NdefFormatException("Неверный промежуточный фрагмент записи")
                }
                current.payload.write(payload)
                if (!chunked) {
                    records += NdefRecord(current.tnf, current.type, current.payload.toByteArray(), current.id)
                    chunk = null
                }
            }

            if (header and FLAG_ME != 0) {
                if (chunk != null) throw NdefFormatException("Сообщение закончилось посреди фрагментированной записи")
                if (position != bytes.size) throw NdefFormatException("Лишние данные после последней записи")
                return records
            }
            if (position == bytes.size) throw NdefFormatException("Нет флага ME у последней записи")
        }
    }

    private class ChunkedRecord(val tnf: Int, val type: ByteArray, val id: ByteArray) {
        val payload = ByteArrayOutputStream()
    }
}

/** Содержимое записи в удобном для показа виде. */
sealed interface RecordContent {
    data class Text(val text: String, val language: String) : RecordContent
    data class Uri(val uri: String) : RecordContent
    data class Mime(val mimeType: String, val size: Int, val preview: String?) : RecordContent
    data class External(val type: String, val size: Int, val preview: String?) : RecordContent
    data object Empty : RecordContent
    data class Other(val tnf: Int, val type: String, val size: Int) : RecordContent
}

fun NdefRecord.content(): RecordContent {
    val typeString = type.toString(Charsets.US_ASCII)
    return when (tnf) {
        NdefRecord.TNF_EMPTY -> RecordContent.Empty
        NdefRecord.TNF_WELL_KNOWN -> when {
            type.contentEquals(NdefRecord.RTD_TEXT) -> decodeText(payload)
            type.contentEquals(NdefRecord.RTD_URI) -> decodeUri(payload)
            else -> null
        }
        NdefRecord.TNF_MIME_MEDIA -> RecordContent.Mime(typeString, payload.size, textPreview(payload))
        NdefRecord.TNF_ABSOLUTE_URI -> RecordContent.Uri(typeString)
        NdefRecord.TNF_EXTERNAL_TYPE -> RecordContent.External(typeString, payload.size, textPreview(payload))
        else -> null
    } ?: RecordContent.Other(tnf, typeString, payload.size)
}

private fun decodeText(payload: ByteArray): RecordContent.Text? {
    if (payload.isEmpty()) return null
    val status = payload[0].u8()
    val languageLength = status and 0x3F
    if (1 + languageLength > payload.size) return null
    val charset: Charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
    val language = String(payload, 1, languageLength, Charsets.US_ASCII)
    val text = String(payload, 1 + languageLength, payload.size - 1 - languageLength, charset)
    return RecordContent.Text(text, language)
}

private fun decodeUri(payload: ByteArray): RecordContent.Uri? {
    if (payload.isEmpty()) return null
    val prefix = NdefRecord.URI_PREFIXES.getOrNull(payload[0].u8()) ?: ""
    return RecordContent.Uri(prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8))
}

/** Текстовое превью полезной нагрузки, если она похожа на UTF-8 текст. */
private fun textPreview(payload: ByteArray, limit: Int = 200): String? {
    if (payload.isEmpty()) return null
    val text = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString()
    }.getOrNull() ?: return null
    if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
    return if (text.length > limit) text.take(limit) + "…" else text
}
