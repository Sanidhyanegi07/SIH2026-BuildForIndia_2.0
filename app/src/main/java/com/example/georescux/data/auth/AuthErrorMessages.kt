package com.example.georescux.data.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/**
 * Translates Firebase Authentication exceptions into short, readable
 * messages that can be shown directly to the user.
 * All messages are identical to the ones the app used before the refactor.
 */
fun friendlyAuthError(error: Exception?): String {
    return when (error) {
        is FirebaseAuthInvalidUserException -> "No account found with this email."
        is FirebaseAuthInvalidCredentialsException ->
            if (error.errorCode == "ERROR_INVALID_EMAIL") {
                "This email address is not valid."
            } else {
                "Incorrect email or password."
            }
        is FirebaseAuthWeakPasswordException ->
            "Password is too weak. ${error.reason ?: "Use at least 6 characters."}"
        is FirebaseAuthUserCollisionException -> "An account with this email already exists."
        is FirebaseNetworkException -> "Network error. Please check your internet connection."
        is FirebaseTooManyRequestsException -> "Too many attempts. Please try again later."
        else -> "Something went wrong. Please try again."
    }
}
