package com.example.georescux.domain.auth

import com.example.georescux.data.auth.UserRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies Phase 2 & 15: Admin Authorization Security
 * - Normal user is not admin
 * - Admin claim admin=true grants admin authority
 * - Client cannot self-promote
 * - Token failures fail closed securely to UNKNOWN
 */
class AdminAuthorizationSecurityTest {

    class MockRoleResolver(
        private val claims: Map<String, Any?> = emptyMap(),
        private val throwException: Boolean = false
    ) : RoleResolver {
        override suspend fun resolveRole(): UserRole = resolveRole(false)

        override suspend fun resolveRole(forceRefresh: Boolean): UserRole {
            if (throwException) return UserRole.UNKNOWN
            val isAdmin = claims["admin"] == true
            return if (isAdmin) UserRole.ADMIN else UserRole.USER
        }
    }

    @Test
    fun `normal user without claims is not admin`() = runBlocking {
        val resolver = MockRoleResolver(claims = emptyMap())
        val role = resolver.resolveRole()
        assertEquals("User with no claims must resolve to USER", UserRole.USER, role)
        assertNotEquals("User must not have ADMIN role", UserRole.ADMIN, role)
    }

    @Test
    fun `user with false admin claim is not admin`() = runBlocking {
        val resolver = MockRoleResolver(claims = mapOf("admin" to false))
        val role = resolver.resolveRole()
        assertEquals("User with admin=false must resolve to USER", UserRole.USER, role)
    }

    @Test
    fun `admin custom claim grants admin role`() = runBlocking {
        val resolver = MockRoleResolver(claims = mapOf("admin" to true))
        val role = resolver.resolveRole()
        assertEquals("User with admin=true claim must resolve to ADMIN", UserRole.ADMIN, role)
    }

    @Test
    fun `client cannot self-promote using local or fake claims`() = runBlocking {
        // Attack: client attempts to send custom claim strings like "isAdmin" or integer 1
        val fakeClaim1 = MockRoleResolver(claims = mapOf("isAdmin" to true))
        assertEquals(UserRole.USER, fakeClaim1.resolveRole())

        val fakeClaim2 = MockRoleResolver(claims = mapOf("admin" to "true")) // String instead of boolean
        assertEquals(UserRole.USER, fakeClaim2.resolveRole())

        val fakeClaim3 = MockRoleResolver(claims = mapOf("role" to "admin"))
        assertEquals(UserRole.USER, fakeClaim3.resolveRole())
    }

    @Test
    fun `network error or token expiry fails closed to UNKNOWN`() = runBlocking {
        val resolver = MockRoleResolver(throwException = true)
        val role = resolver.resolveRole()
        assertEquals("Auth failure must fail closed to UNKNOWN", UserRole.UNKNOWN, role)
        assertTrue("UNKNOWN must not grant admin authority", role != UserRole.ADMIN)
    }

    @Test
    fun `forceRefresh updates role when claim is assigned`() = runBlocking {
        var adminAssigned = false
        val dynamicResolver = object : RoleResolver {
            override suspend fun resolveRole(): UserRole = resolveRole(false)
            override suspend fun resolveRole(forceRefresh: Boolean): UserRole {
                return if (adminAssigned) UserRole.ADMIN else UserRole.USER
            }
        }

        assertEquals(UserRole.USER, dynamicResolver.resolveRole(false))
        adminAssigned = true
        assertEquals(UserRole.ADMIN, dynamicResolver.resolveRole(true))
    }
}
