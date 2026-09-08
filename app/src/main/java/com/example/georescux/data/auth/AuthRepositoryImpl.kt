package com.example.georescux.data.auth

import com.example.georescux.core.result.AuthResult
import com.example.georescux.domain.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The ONLY class in the app that talks to FirebaseAuth directly.
 * It converts Firebase results and exceptions into [AuthResult] values,
 * preserving the friendly error messages the app has always shown.
 */
class AuthRepositoryImpl(private val firebaseAuth: FirebaseAuth) : AuthRepository {

    override val isSignedIn: Boolean
        get() = firebaseAuth.currentUser != null

    override val currentUserEmail: String?
        get() = firebaseAuth.currentUser?.email

    override val currentUserId: String?
        get() = firebaseAuth.currentUser?.uid

    override suspend fun signIn(email: String, password: String): AuthResult {
        return try {
            val user = firebaseAuth.signInWithEmailAndPassword(email, password).await().user
            AuthResult.Success(user?.email)
        } catch (e: CancellationException) {
            throw e // The caller cancelled; do not turn that into an error message.
        } catch (e: Exception) {
            AuthResult.Error(friendlyAuthError(e))
        }
    }

    override suspend fun signUp(email: String, password: String): AuthResult {
        return try {
            val user = firebaseAuth.createUserWithEmailAndPassword(email, password).await().user
            AuthResult.Success(user?.email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(friendlyAuthError(e))
        }
    }

    override fun signOut() {
        firebaseAuth.signOut()
    }
}
