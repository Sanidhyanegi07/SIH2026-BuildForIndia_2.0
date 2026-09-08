package com.example.georescux.domain.auth

import com.example.georescux.data.auth.UserRole

/**
 * Interface for securely resolving the authenticated user's role.
 */
interface RoleResolver {
    suspend fun resolveRole(): UserRole
}
