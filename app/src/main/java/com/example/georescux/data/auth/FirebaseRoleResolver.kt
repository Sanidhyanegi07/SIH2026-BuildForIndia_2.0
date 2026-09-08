package com.example.georescux.data.auth

import com.example.georescux.domain.auth.RoleResolver
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Resolves the user's role securely by inspecting the Firebase Auth Custom Claims.
 * This guarantees the claim comes directly from a trusted backend and fails safely
 * if offline and expired.
 */
class FirebaseRoleResolver(private val firebaseAuth: FirebaseAuth) : RoleResolver {

    override suspend fun resolveRole(): UserRole {
        val user = firebaseAuth.currentUser ?: return UserRole.UNKNOWN
        return try {
            // getIdToken(false) uses the cached cryptographically-signed JWT. 
            // If the token is expired, Firebase implicitly attempts to refresh it.
            val result = user.getIdToken(false).await()
            val isAdmin = result.claims["admin"] == true
            if (isAdmin) UserRole.ADMIN else UserRole.USER
        } catch (e: Exception) {
            // Network failure during refresh or auth error.
            // Fail closed securely to prevent fake cached access.
            UserRole.UNKNOWN
        }
    }
}
