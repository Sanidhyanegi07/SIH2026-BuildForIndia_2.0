package com.example.georescux.domain.repository

import com.example.georescux.core.result.AuthResult

/**
 * Authentication operations the rest of the app can use.
 * Pure Kotlin: no Android UI classes and no Firebase types here,
 * so this layer stays easy to test and can be reused later from
 * services or background workers, not just from screens.
 */
interface AuthRepository {
    /** True when a user is currently signed in. */
    val isSignedIn: Boolean

    /** Email of the signed-in user, or null when nobody is signed in. */
    val currentUserEmail: String?

    /** Firebase UID of the signed-in user, or null when nobody is signed in. */
    val currentUserId: String?

    /** Signs in with email and password, or returns an error result. */
    suspend fun signIn(email: String, password: String): AuthResult

    /** Creates a new account with email and password, or returns an error result. */
    suspend fun signUp(email: String, password: String): AuthResult

    /** Signs the current user out. */
    fun signOut()
}
