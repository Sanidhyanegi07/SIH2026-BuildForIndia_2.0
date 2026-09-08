package com.example.georescux.core.validation

/**
 * Input validation for the authentication screens.
 * Returns an error message to show, or null when the input is acceptable.
 * Written with pure Kotlin (no Android classes) so it can be unit tested.
 */
object AuthInputValidator {

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validateLogin(email: String, password: String): String? {
        if (email.isBlank() || password.isEmpty()) {
            return "Please enter your email and password."
        }
        return null
    }

    fun validateRegister(email: String, password: String, confirmPassword: String): String? {
        if (email.isBlank() || password.isEmpty() || confirmPassword.isEmpty()) {
            return "Please fill in all fields."
        }
        if (!EMAIL_REGEX.matches(email)) {
            return "Please enter a valid email address."
        }
        if (password.length < 6) {
            return "Password must be at least 6 characters long."
        }
        if (password != confirmPassword) {
            return "Passwords do not match."
        }
        return null
    }
}
