// PeerProfile - Room entity storing peer profile and visibility.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-18
package DataLayer

import DataLayer.models.PeerVisibility
import java.security.MessageDigest

/**
 * Persisted profile for a peer device. Tracks both the transient endpoint it was last
 * seen on and the long lived metadata learned from nearby announcements.
 */
data class PeerProfile(
    val deviceId: String,
    val displayName: String?,
    val lastEndpointId: String?,
    val lastSeen: Long,
    val visibility: PeerVisibility,
    val handshakeTimestamp: Long?,
    val publicKey: ByteArray?,
    val serviceId: String?,
    val identityHash: String?,
    val ownerUserId: String,
    val lastInteraction: Long,
    val lastRelayAttempt: Long
) {
    companion object {
        fun computeIdentityFingerprint(
            displayName: String?,
            serviceId: String?,
            publicKey: ByteArray?
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val separator = "|".toByteArray(Charsets.UTF_8)

            digest.update((displayName ?: "").toByteArray(Charsets.UTF_8))
            digest.update(separator)
            digest.update((serviceId ?: "").toByteArray(Charsets.UTF_8))
            digest.update(separator)
            publicKey?.let { digest.update(it) }

            return digest.digest().joinToString(separator = "") { byte ->
                ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            }
        }
    }
}

