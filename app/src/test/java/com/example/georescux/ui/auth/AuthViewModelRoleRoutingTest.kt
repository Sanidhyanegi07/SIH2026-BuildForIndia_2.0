package com.example.georescux.ui.auth

import com.example.georescux.core.result.AuthResult
import com.example.georescux.data.auth.UserRole
import com.example.georescux.domain.auth.RoleResolver
import com.example.georescux.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the AuthViewModel correctly asks the RoleResolver
 * for the role after a successful sign-in, and sets it in the state
 * so the LoginActivity can route the user securely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelRoleRoutingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    class FakeAuthRepository(var resultToReturn: AuthResult) : AuthRepository {
        override val isSignedIn = false
        override val currentUserEmail: String? = null
        override val currentUserId: String? = null
        override suspend fun signIn(email: String, password: String) = resultToReturn
        override suspend fun signUp(email: String, password: String) = resultToReturn
        override fun signOut() {}
    }

    class FakeRoleResolver(var roleToReturn: UserRole) : RoleResolver {
        override suspend fun resolveRole(): UserRole = roleToReturn
    }

    @Test
    fun `successful sign-in as ADMIN exposes ADMIN role in state`() = runTest {
        val repo = FakeAuthRepository(AuthResult.Success("admin@test.com"))
        val resolver = FakeRoleResolver(UserRole.ADMIN)
        val viewModel = AuthViewModel(repo, resolver)

        viewModel.signIn("admin@test.com", "password")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected isDone to be true", state.isDone)
        assertEquals("Expected ADMIN role", UserRole.ADMIN, state.role)
    }

    @Test
    fun `successful sign-in as USER exposes USER role in state`() = runTest {
        val repo = FakeAuthRepository(AuthResult.Success("user@test.com"))
        val resolver = FakeRoleResolver(UserRole.USER)
        val viewModel = AuthViewModel(repo, resolver)

        viewModel.signIn("user@test.com", "password")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected isDone to be true", state.isDone)
        assertEquals("Expected USER role", UserRole.USER, state.role)
    }

    @Test
    fun `failed network during role resolution exposes UNKNOWN role safely`() = runTest {
        val repo = FakeAuthRepository(AuthResult.Success("offline@test.com"))
        val resolver = FakeRoleResolver(UserRole.UNKNOWN)
        val viewModel = AuthViewModel(repo, resolver)

        viewModel.signIn("offline@test.com", "password")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected isDone to be true, letting activity handle the UNKNOWN role", state.isDone)
        assertEquals("Expected UNKNOWN role for secure failure", UserRole.UNKNOWN, state.role)
    }

    @Test
    fun `failed login does not resolve role`() = runTest {
        val repo = FakeAuthRepository(AuthResult.Error("Wrong password"))
        val resolver = FakeRoleResolver(UserRole.ADMIN)
        val viewModel = AuthViewModel(repo, resolver)

        viewModel.signIn("fail@test.com", "password")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Expected error message", "Wrong password", state.errorMessage)
        assertNull("Role should not be populated on failure", state.role)
    }
}
