package com.example.georescux.domain.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64 as JdkBase64

/** Verifies the pure-Kotlin codec round-trips and interoperates with the JDK encoder. */
class Base64CodecTest {

    @Test
    fun `round-trips arbitrary byte lengths`() {
        (0..64).forEach { length ->
            val bytes = ByteArray(length) { (it * 37 % 256).toByte() }
            assertArrayEquals(bytes, Base64Codec.decode(Base64Codec.encode(bytes)))
        }
    }

    @Test
    fun `matches the JDK standard encoder exactly`() {
        val bytes = ByteArray(77) { (it * 11 % 256).toByte() }
        assertEquals(JdkBase64.getEncoder().encodeToString(bytes), Base64Codec.encode(bytes))
    }

    @Test
    fun `rejects invalid input instead of guessing`() {
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("A") } // bad length
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("A===") } // bad padding
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("AB!D") } // bad character
        assertArrayEquals(ByteArray(0), Base64Codec.decode("")) // empty input = zero bytes (valid)
    }
}
