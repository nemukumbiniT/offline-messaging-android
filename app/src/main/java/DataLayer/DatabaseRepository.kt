// DatabaseRepository - Provides data access operations across Room DAOs.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-18
package DataLayer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import DataLayer.models.PeerVisibility
import DataLayer.models.StoredMessageEntity
import DataLayer.models.PendingBundleEntity
import org.json.JSONObject

class DatabaseRepository(context: Context) {

    private val database = AppDatabase.getDatabase(context)


    @Volatile
    private var activeOwnerUserId: String? = null

    // setActiveOwnerUserId: caches the trimmed owner id so lookups reuse latest session.
    fun setActiveOwnerUserId(userId: String?) {
        activeOwnerUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
    }

    // getActiveOwnerUserId: returns cached owner or falls back to current user from DB.
    suspend fun getActiveOwnerUserId(): String? = withContext(Dispatchers.IO) {
        activeOwnerUserId ?: database.getCurrentUser()?.id?.also { activeOwnerUserId = it }
    }

    // normalizeOwnerId: trims the owner id, ensures it is non-blank, and updates cache.
    private fun normalizeOwnerId(ownerUserId: String): String {
        val trimmed = ownerUserId.trim()
        require(trimmed.isNotEmpty()) { "ownerUserId must not be blank" }
        activeOwnerUserId = trimmed
        return trimmed
    }

    // registerUser: validates uniqueness, hashes password, stores user, and marks them current.
    suspend fun registerUser(
        // Thanyani: validation stays lightweight here—Room enforces uniqueness via DAO.
        username: String,
        rawPassword: String,
        deviceInfo: String? = null
    ): RegistrationResult = withContext(Dispatchers.IO) {
        val normalizedUsername = username.trim()

        if (normalizedUsername.isEmpty()) {
            return@withContext RegistrationResult.InvalidInput
        }

        if (database.findUserByUsername(normalizedUsername) != null) {
            return@withContext RegistrationResult.UsernameTaken
        }

        val hashedPassword = hashPassword(rawPassword)
        // Thanyani: compare hashed value to avoid leaking plaintext into logs.
        // Thanyani: SHA-256 keeps client-side auth deterministic; server would salt if expanded.
        val user = UserEntity(
            // Siyabonga: UI expects username mirrored back so mark as current immediately.
            username = normalizedUsername,
            email = "",
            passwordHash = hashedPassword,
            deviceInfo = deviceInfo,
            isCurrentUser = true
        )

        val inserted = database.insertUser(user)
        if (!inserted) {
            // Thanyani: bail gracefully if insert fails—Room likely rejected duplicate id.
            return@withContext RegistrationResult.Failure
        }

        database.markCurrentUser(user.id)
        // Thanyani: mark before emitting success so session manager sees consistent state.
        activeOwnerUserId = user.id
        RegistrationResult.Success(user)
    }
    // authenticateUser: verifies credentials, stamps last seen, and marks account current.
    suspend fun authenticateUser(
        username: String,
        rawPassword: String
    ): AuthenticationResult = withContext(Dispatchers.IO) {
        val user = database.findUserByUsername(username.trim())
            // Thanyani: username is normalized earlier so we skip extra formatting here.
            ?: return@withContext AuthenticationResult.UserNotFound

        val hashedPassword = hashPassword(rawPassword)
        // Thanyani: compare hashed value to avoid leaking plaintext into logs.
        // Thanyani: SHA-256 keeps client-side auth deterministic; server would salt if expanded.
        if (user.passwordHash != hashedPassword) {
            return@withContext AuthenticationResult.InvalidCredentials
        }

        val updated = user.copy(
            lastSeen = System.currentTimeMillis(),
            isCurrentUser = true
        )
        database.markCurrentUser(user.id)
        // Thanyani: mark before emitting success so session manager sees consistent state.
        database.updateLastSeen(user.id, updated.lastSeen)
        activeOwnerUserId = updated.id
        AuthenticationResult.Success(updated)
    }

    // upsertPeerProfile: merges peer attributes from discovery and persists them for owner.
    suspend fun upsertPeerProfile(
        deviceId: String,
        displayName: String?,
        lastEndpointId: String?,
        lastSeen: Long = System.currentTimeMillis(),
        visibility: PeerVisibility = PeerVisibility.OPEN,
        publicKey: ByteArray? = null,
        handshakeTimestamp: Long? = null,
        serviceId: String? = null,
        identityHash: String? = null,
        ownerUserId: String,
        lastRelayAttempt: Long? = null
    ) = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        val resolvedInteraction = handshakeTimestamp?.let { maxOf(lastSeen, it) } ?: lastSeen
        val resolvedRelay = lastRelayAttempt ?: 0L
        val fingerprint = identityHash ?: when {
            publicKey != null || !serviceId.isNullOrBlank() || !displayName.isNullOrBlank() ->
                PeerProfile.computeIdentityFingerprint(displayName, serviceId, publicKey)
            else -> null
        }

        database.upsertPeer(
            deviceId = deviceId,
            displayName = displayName,
            lastEndpoint = lastEndpointId,
            lastSeen = lastSeen,
            visibility = visibility,
            handshakeTimestamp = handshakeTimestamp,
            publicKey = publicKey,
            serviceId = serviceId,
            identityHash = fingerprint,
            ownerUserId = resolvedOwnerId,
            lastInteraction = resolvedInteraction,
            lastRelayAttempt = resolvedRelay
        )
    }

    // markPeerTrusted: flips visibility and relay metadata so peer becomes trusted.
    suspend fun markPeerTrusted(
        deviceId: String,
        displayName: String?,
        lastEndpointId: String?,
        publicKey: ByteArray?,
        serviceId: String?,
        ownerUserId: String
    ): PeerProfile = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        val fingerprint = PeerProfile.computeIdentityFingerprint(displayName, serviceId, publicKey)

        database.upsertPeer(
            deviceId = deviceId,
            displayName = displayName,
            lastEndpoint = lastEndpointId,
            lastSeen = timestamp,
            visibility = PeerVisibility.TRUSTED,
            handshakeTimestamp = timestamp,
            publicKey = publicKey,
            serviceId = serviceId,
            identityHash = fingerprint,
            ownerUserId = resolvedOwnerId,
            lastInteraction = timestamp,
            lastRelayAttempt = 0L
        )

        database.getPeer(deviceId, resolvedOwnerId) ?: PeerProfile(
            deviceId = deviceId,
            displayName = displayName,
            lastEndpointId = lastEndpointId,
            lastSeen = timestamp,
            visibility = PeerVisibility.TRUSTED,
            handshakeTimestamp = timestamp,
            publicKey = publicKey,
            serviceId = serviceId,
            identityHash = fingerprint,
            ownerUserId = resolvedOwnerId,
            lastInteraction = timestamp,
            lastRelayAttempt = 0L
        )
    }

    // saveMessage: writes message to store, pruning oldest when thresholds exceed.
    suspend fun saveMessage(message: StoredMessageEntity, ownerUserId: String): Boolean = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        val scopedMessage = if (resolvedOwnerId == message.ownerUserId.trim()) {
            message
        } else {
            message.copy(ownerUserId = resolvedOwnerId)
        }
        database.insertMessage(scopedMessage)
    }

    // getConversationMessages: delegates to DB helper to gather history between devices.
    suspend fun getConversationMessages(
        localDeviceId: String,
        peerDeviceId: String,
        ownerUserId: String,
        limit: Int? = null
    ): List<StoredMessageEntity> = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        database.getConversationMessages(localDeviceId, peerDeviceId, resolvedOwnerId, limit)
    }

    // getPeerProfile: fetches single peer profile for UI or routing decisions.
    suspend fun getPeerProfile(deviceId: String, ownerUserId: String): PeerProfile? = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        database.getPeer(deviceId, resolvedOwnerId)
    }

    // getPeerProfiles: loads all peer records tied to owner for overview screens.
    suspend fun getPeerProfiles(ownerUserId: String): List<PeerProfile> = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        database.getPeers(resolvedOwnerId)
    }

    // getTrustedPeers: filters peer list to only trusted entries for secure messaging flows.
    suspend fun getTrustedPeers(ownerUserId: String): List<PeerProfile> = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        database.getPeers(resolvedOwnerId).filter { it.visibility == PeerVisibility.TRUSTED }
    }

    // cleanupOldPeers: trims peer table using database helper when exceeding cap.
    suspend fun cleanupOldPeers(ownerUserId: String, maxCount: Int = 100) = withContext(Dispatchers.IO) {
        if (maxCount <= 0) {
            return@withContext
        }
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        database.prunePeersForOwner(resolvedOwnerId, maxCount)
    }

    // cleanupConversationCache: prunes stored messages for owner/local device pair.
    suspend fun cleanupConversationCache(ownerUserId: String, localDeviceId: String) = withContext(Dispatchers.IO) {
        val resolvedOwnerId = normalizeOwnerId(ownerUserId)
        // Thanyani: stash normalized owner for downstream queries (Room expects trimmed values).
        database.pruneMessagesForOwner(resolvedOwnerId, localDeviceId)
    }

    // getCurrentUser: retrieves currently marked user for session handoff.
    suspend fun getCurrentUser(): UserEntity? = withContext(Dispatchers.IO) {
        database.getCurrentUser()
    }

    // restoreRememberedUser: re-activates remembered account when auto-login kicks in.
    suspend fun restoreRememberedUser(userId: String): UserEntity? = withContext(Dispatchers.IO) {
        val user = database.findUserById(userId) ?: return@withContext null
        val refreshed = user.copy(
            lastSeen = System.currentTimeMillis(),
            isCurrentUser = true
        )
        database.markCurrentUser(user.id)
        // Thanyani: mark before emitting success so session manager sees consistent state.
        database.updateLastSeen(user.id, refreshed.lastSeen)
        activeOwnerUserId = refreshed.id
        refreshed
    }

    // logoutCurrentUser: clears active flag and cached owner to fully sign out.
    suspend fun logoutCurrentUser() = withContext(Dispatchers.IO) {
        database.clearCurrentUser()
        activeOwnerUserId = null
    }

    // updateCurrentUserProfile: applies profile edits to active user row and returns updated entity.
    suspend fun updateCurrentUserProfile(
        username: String,
        firstName: String?,
        lastName: String?,
        profilePicture: ByteArray?
    ): ProfileUpdateResult = withContext(Dispatchers.IO) {
        val current = database.getCurrentUser() ?: return@withContext ProfileUpdateResult.NotLoggedIn

        val normalizedUsername = username.trim()
        if (normalizedUsername.isEmpty()) {
            return@withContext ProfileUpdateResult.InvalidInput
        }

        database.findUserByUsername(normalizedUsername)?.let { existing ->
            if (existing.id != current.id) {
                return@withContext ProfileUpdateResult.UsernameTaken
            }
        }

        val updated = current.copy(
            username = normalizedUsername,
            profilePicture = profilePicture ?: current.profilePicture,
            deviceInfo = buildDeviceInfoPayload(firstName, lastName)
        )

        if (database.updateUser(updated)) {
            ProfileUpdateResult.Success(updated)
        } else {
            ProfileUpdateResult.Failure
        }
    }

    // changeCurrentUserPassword: verifies old password then stores new hash in place.
    suspend fun changeCurrentUserPassword(
        currentPassword: String,
        newPassword: String
    ): PasswordChangeResult = withContext(Dispatchers.IO) {
        val current = database.getCurrentUser() ?: return@withContext PasswordChangeResult.NotLoggedIn

        if (newPassword.length < 6) {
            return@withContext PasswordChangeResult.TooShort
        }

        val currentHash = hashPassword(currentPassword)
        if (current.passwordHash != currentHash) {
            return@withContext PasswordChangeResult.IncorrectPassword
        }

        val updated = current.copy(
            passwordHash = hashPassword(newPassword),
            lastSeen = System.currentTimeMillis(),
            isCurrentUser = true
        )

        if (database.updateUser(updated)) {
            PasswordChangeResult.Success
        } else {
            PasswordChangeResult.Failure
        }
    }

    // savePendingBundle: writes DTN pending entry using database helper.
    suspend fun savePendingBundle(entity: PendingBundleEntity) = withContext(Dispatchers.IO) {
        database.upsertPendingBundle(entity)
    }

    // removePendingBundle: deletes DTN pending record once fulfilled.
    suspend fun removePendingBundle(bundleId: String) = withContext(Dispatchers.IO) {
        database.deletePendingBundle(bundleId)
    }

    // loadPendingBundles: fetches all pending bundles awaiting delivery.
    suspend fun loadPendingBundles(): List<PendingBundleEntity> = withContext(Dispatchers.IO) {
        database.getPendingBundles()
    }

    // updatePendingBundleAttempt: stores retry metadata after each attempt.
    suspend fun updatePendingBundleAttempt(
        bundleId: String,
        attemptedPeers: Set<String>,
        lastAttemptAt: Long,
        hopCount: Int
    ) = withContext(Dispatchers.IO) {
        val csv = attemptedPeers.joinToString(separator = ",")
        database.updatePendingBundleAttempt(bundleId, csv, lastAttemptAt, hopCount)
    }

    // pruneExpiredPendingBundles: removes stale pending entries and returns count.
    suspend fun pruneExpiredPendingBundles(now: Long): Int = withContext(Dispatchers.IO) {
        database.cleanupExpiredPendingBundles(now)
    }

    // buildDeviceInfoPayload: constructs small JSON payload for profile display names.
    private fun buildDeviceInfoPayload(firstName: String?, lastName: String?): String? {
        val json = JSONObject()
        if (!firstName.isNullOrBlank()) {
            json.put("firstName", firstName.trim())
        }
        if (!lastName.isNullOrBlank()) {
            json.put("lastName", lastName.trim())
        }
        return if (json.length() == 0) null else json.toString()
    }

    // hashPassword: runs SHA-256 over password and returns lowercase hex digest.
    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString(separator = "") { byte ->
            ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
    }

    sealed class RegistrationResult {
        data class Success(val user: UserEntity) : RegistrationResult()
        data object UsernameTaken : RegistrationResult()
        data object InvalidInput : RegistrationResult()
        data object Failure : RegistrationResult()
    }

    sealed class AuthenticationResult {
        data class Success(val user: UserEntity) : AuthenticationResult()
        data object UserNotFound : AuthenticationResult()
        data object InvalidCredentials : AuthenticationResult()
    }

    sealed class ProfileUpdateResult {
        data class Success(val user: UserEntity) : ProfileUpdateResult()
        data object UsernameTaken : ProfileUpdateResult()
        data object InvalidInput : ProfileUpdateResult()
        data object NotLoggedIn : ProfileUpdateResult()
        data object Failure : ProfileUpdateResult()
    }

    sealed class PasswordChangeResult {
        data object Success : PasswordChangeResult()
        data object IncorrectPassword : PasswordChangeResult()
        data object TooShort : PasswordChangeResult()
        data object NotLoggedIn : PasswordChangeResult()
        data object Failure : PasswordChangeResult()
    }
}

















