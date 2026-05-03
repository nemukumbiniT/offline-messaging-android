// PeerEntity - Room entity for discovered peer devices.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-18
package DataLayer

/**
 * Represents a nearby peer discovered via the transport layer.
 * Maintains both the transient Nearby endpoint identifier and (once exchanged)
 * the long-lived device identity plus crypto material.
 */
data class PeerEntity(
    val endpointId: String,
    val endpointName: String,
    val serviceId: String,
    val deviceId: String? = null,
    val deviceDisplayName: String? = null,
    val publicKey: ByteArray? = null,
    val lastSeenMillis: Long = System.currentTimeMillis()
) {
    fun withDeviceIdentity(
        deviceId: String,
        displayName: String?,
        publicKey: ByteArray?
    ): PeerEntity = copy(
        deviceId = deviceId,
        deviceDisplayName = displayName,
        publicKey = publicKey,
        lastSeenMillis = System.currentTimeMillis()
    )

    fun touch(timestamp: Long = System.currentTimeMillis()): PeerEntity = copy(lastSeenMillis = timestamp)
}

