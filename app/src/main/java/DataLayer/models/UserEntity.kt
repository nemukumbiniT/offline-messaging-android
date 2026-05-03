// UserEntity - Room entity for registered users.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-17
package DataLayer

import java.util.UUID

/**
 * Simple value object representing a user profile stored in the local database.
 */
data class UserEntity(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val email: String = "",
    val passwordHash: String,
    val profilePicture: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val isCurrentUser: Boolean = false,
    val deviceInfo: String? = null
)

