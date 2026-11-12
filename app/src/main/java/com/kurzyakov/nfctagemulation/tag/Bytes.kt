package com.kurzyakov.nfctagemulation.tag

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

/** Байты в hex с разделителем: `00 A4 04 00`. */
fun ByteArray.toHex(separator: String = " "): String {
    if (isEmpty()) return ""
    val out = StringBuilder(size * (2 + separator.length))
    forEachIndexed { index, byte ->
        if (index > 0) out.append(separator)
        val value = byte.u8()
        out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/** Строка hex (пробелы допускаются) в байты. */
fun String.hexToBytes(): ByteArray {
    val digits = filterNot { it.isWhitespace() }
    require(digits.length % 2 == 0) { "Нечётное число hex-символов: $this" }
    return ByteArray(digits.length / 2) { i -> digits.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun Byte.u8(): Int = toInt() and 0xFF

/** Беззнаковое 16-битное число big-endian, начиная с [offset]. */
internal fun ByteArray.u16(offset: Int): Int = (this[offset].u8() shl 8) or this[offset + 1].u8()

internal fun Int.toU16Bytes(): ByteArray = byteArrayOf((this shr 8).toByte(), toByte())
