package com.example.georescux.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInputValidatorTest {

    @Test
    fun `login with blank email or password returns message`() {
        assertEquals(
            "Please enter your email and password.",
            AuthInputValidator.validateLogin("", "secret1")
        )
        assertEquals(
            "Please enter your email and password.",
            AuthInputValidator.validateLogin("user@example.com", "")
        )
    }

    @Test
    fun `login with complete input returns null`() {
        assertNull(AuthInputValidator.validateLogin("user@example.com", "secret1"))
    }

    @Test
    fun `register with missing fields returns message`() {
        assertEquals(
            "Please fill in all fields.",
            AuthInputValidator.validateRegister("", "", "")
        )
    }

    @Test
    fun `register with invalid email returns message`() {
        assertEquals(
            "Please enter a valid email address.",
            AuthInputValidator.validateRegister("not-an-email", "secret1", "secret1")
        )
    }

    @Test
    fun `register with short password returns message`() {
        assertEquals(
            "Password must be at least 6 characters long.",
            AuthInputValidator.validateRegister("user@example.com", "abc", "abc")
        )
    }

    @Test
    fun `register with mismatched passwords returns message`() {
        assertEquals(
            "Passwords do not match.",
            AuthInputValidator.validateRegister("user@example.com", "secret1", "secret2")
        )
    }

    @Test
    fun `register with valid input returns null`() {
        assertNull(AuthInputValidator.validateRegister("user@example.com", "secret1", "secret1"))
    }
}
