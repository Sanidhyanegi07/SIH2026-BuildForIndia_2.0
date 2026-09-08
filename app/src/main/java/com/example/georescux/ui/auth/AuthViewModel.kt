package com.example.georescux.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.core.result.AuthResult
import com.example.georescux.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.example.georescux.data.auth.UserRole
import com.example.georescux.domain.auth.RoleResolver

/**
 * What the login/register screens currently show.
 * The state survives screen rotation because the ViewModel outlives the Activity.
 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isDone: Boolean = false, // login or registration succeeded -> navigate to Home
    val role: UserRole? = null // Resolved role for routing
)

/**
 * Performs authentication actions through [AuthRepository] and exposes the
 * progress as a StateFlow. Activities observe this state; they never touch
 * FirebaseAuth directly.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val roleResolver: RoleResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Work scope for auth requests. viewModelScope is not available in this
    // project's dependencies, so the scope is created and cancelled manually.
    private val authScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun signIn(email: String, password: String) = performAuth { authRepository.signIn(email, password) }

    fun register(email: String, password: String) = performAuth { authRepository.signUp(email, password) }

    fun signOut() = authRepository.signOut()

    private fun performAuth(action: suspend () -> AuthResult) {
        if (_uiState.value.isLoading) return // ignore double taps while a request is running
        _uiState.value = AuthUiState(isLoading = true)
        authScope.launch {
            when (val result = action()) {
                is AuthResult.Success -> {
                    // Resolve role securely after successful login
                    val resolvedRole = roleResolver.resolveRole()
                    _uiState.value = AuthUiState(isDone = true, role = resolvedRole)
                }
                is AuthResult.Error -> {
                    _uiState.value = AuthUiState(errorMessage = result.message)
                }
            }
        }
    }

    override fun onCleared() {
        authScope.cancel()
        super.onCleared()
    }

    /** Creates the ViewModel with the repository from the AppContainer. */
    class Factory(
        private val authRepository: AuthRepository,
        private val roleResolver: RoleResolver
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(authRepository, roleResolver) as T
        }
    }
}
