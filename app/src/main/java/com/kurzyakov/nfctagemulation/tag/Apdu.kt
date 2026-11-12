package com.kurzyakov.nfctagemulation.tag

/**
 * Команда APDU по ISO/IEC 7816-4 в короткой и расширенной форме.
 *
 * @property ne максимальная ожидаемая длина ответа (Le, уже раскрытая: 0 → 256 или 65536)
 *   либо `null`, если Le в команде нет.
 */
class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val data: ByteArray = ByteArray(0),
    val ne: Int? = null,
) {
    /** Первые четыре байта команды в hex: `00 A4 04 00`. */
    val header: String
        get() = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte()).toHex()

    companion object {
        private const val HEADER_SIZE = 4

        /** Разбирает байты команды. Возвращает `null`, если длина не соответствует ни одному случаю ISO 7816-4. */
        fun parse(raw: ByteArray): CommandApdu? {
            if (raw.size < HEADER_SIZE) return null

            fun apdu(data: ByteArray = ByteArray(0), ne: Int? = null) = CommandApdu(
                cla = raw[0].u8(), ins = raw[1].u8(), p1 = raw[2].u8(), p2 = raw[3].u8(),
                data = data, ne = ne,
            )

            // Случай 1: только заголовок.
            if (raw.size == HEADER_SIZE) return apdu()

            val b4 = raw[4].u8()
            // Случай 2S: заголовок + Le.
            if (raw.size == 5) return apdu(ne = if (b4 == 0) 256 else b4)

            if (b4 != 0) {
                // Случаи 3S и 4S: Lc в одном байте.
                val lc = b4
                return when (raw.size) {
                    5 + lc -> apdu(data = raw.copyOfRange(5, 5 + lc))
                    6 + lc -> {
                        val le = raw.last().u8()
                        apdu(data = raw.copyOfRange(5, 5 + lc), ne = if (le == 0) 256 else le)
                    }
                    else -> null
                }
            }

            // Расширенная форма: 00 и два байта длины.
            if (raw.size < 7) return null
            val n = raw.u16(5)
            // Случай 2E: только расширенный Le.
            if (raw.size == 7) return apdu(ne = if (n == 0) 65536 else n)
            val lc = n
            if (lc == 0) return null
            return when (raw.size) {
                7 + lc -> apdu(data = raw.copyOfRange(7, 7 + lc))
                9 + lc -> {
                    val le = raw.u16(7 + lc)
                    apdu(data = raw.copyOfRange(7, 7 + lc), ne = if (le == 0) 65536 else le)
                }
                else -> null
            }
        }
    }
}

/** Слова состояния (SW1 SW2), которые использует метка. */
object StatusWord {
    const val OK = 0x9000
    const val WRONG_LENGTH = 0x6700
    const val SECURITY_STATUS_NOT_SATISFIED = 0x6982
    const val NO_CURRENT_EF = 0x6986
    const val FUNCTION_NOT_SUPPORTED = 0x6A81
    const val FILE_NOT_FOUND = 0x6A82
    const val INCORRECT_P1P2 = 0x6A86
    const val OFFSET_OUTSIDE_EF = 0x6B00
    const val INS_NOT_SUPPORTED = 0x6D00

    fun toBytes(sw: Int): ByteArray = byteArrayOf((sw shr 8).toByte(), sw.toByte())

    /** Слово состояния из двух последних байт ответа или `null`, если ответ короче двух байт. */
    fun of(response: ByteArray): Int? =
        if (response.size < 2) null else response.u16(response.size - 2)
}
