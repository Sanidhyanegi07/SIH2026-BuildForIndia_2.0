package com.example.georescux.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the part of the error mapper that does not need the Firebase SDK:
 * constructing real Firebase exceptions requires Android runtime classes
 * (android.text.TextUtils) that are not available in plain JVM unit tests,
 * so those branches are verified through the running app instead.
 */
class AuthErrorMessagesTest {

    @Test
    fun `unknown error maps to generic message`() {
        assertEquals("Something went wrong. Please try again.", friendlyAuthError(null))
    }
}
