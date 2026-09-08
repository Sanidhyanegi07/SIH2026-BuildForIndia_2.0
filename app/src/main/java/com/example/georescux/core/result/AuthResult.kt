package com.example.georescux.core.result

/**
 * Outcome of an authentication operation, ready to be shown to the user.
 * The error message is already user-friendly, so the UI never needs to
 * know anything about Firebase exceptions.
 */
sealed class AuthResult {
    data class Success(val userEmail: String?) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
