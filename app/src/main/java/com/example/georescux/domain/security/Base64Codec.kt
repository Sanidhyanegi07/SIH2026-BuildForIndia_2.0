package com.example.georescux.domain.security

/**
 * Minimal standard-alphabet Base64 codec (RFC 4648, padded, no line
 * wraps). Pure Kotlin so the security layer works identically on the JVM
 * (unit tests) and on every supported Android version — minSdk 24 has no
 * java.util.Base64, and android.util.Base64 is unavailable in JVM tests.
 * [decode] throws [IllegalArgumentException] on invalid input, which
 * callers treat as a tampered/foreign message.
 */
object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index + 3 <= bytes.size) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            out.append(ALPHABET[chunk and 0x3F])
            index += 3
        }
        val remaining = bytes.size - index
        if (remaining == 1) {
            val chunk = (bytes[index].toInt() and 0xFF) shl 16
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append("==")
        } else if (remaining == 2) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            out.append(ALPHABET[(chunk ushr 18) and 0x3F])
            out.append(ALPHABET[(chunk ushr 12) and 0x3F])
            out.append(ALPHABET[(chunk ushr 6) and 0x3F])
            out.append("=")
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val cleaned = text.trim()
        require(cleaned.length % 4 == 0) { "invalid Base64 length" } // "" = zero bytes: valid
        require(cleaned.all { it == '=' || it.code < DECODE_TABLE.size && DECODE_TABLE[it.code] >= 0 }) {
            "invalid Base64 character"
        }
        // Padding may only appear as the final 1–2 characters.
        val paddingStart = cleaned.indexOf('=')
        if (paddingStart >= 0) {
            require(cleaned.length - paddingStart <= 2 && cleaned.substring(paddingStart).all { it == '=' }) {
                "invalid Base64 padding"
            }
        }

        val out = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0
        cleaned.forEach { char ->
            if (char == '=') return@forEach
            buffer = (buffer shl 6) or DECODE_TABLE[char.code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        // Leftover bits from valid padding are necessarily zero; anything
        // else was covered by the alphabet/padding checks above.
        return out.toByteArray()
    }
}
