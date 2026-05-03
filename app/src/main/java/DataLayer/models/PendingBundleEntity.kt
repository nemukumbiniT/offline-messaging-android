// PendingBundleEntity - Persists outbound bundles awaiting delivery.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-19
package DataLayer.models

data class PendingBundleEntity(
    val bundleId: String,
    val serializedBundle: ByteArray,
    val targetDeviceId: String?,
    val mode: String,
    val expiryAt: Long,
    val hopCount: Int,
    val lastAttemptAt: Long,
    val attemptedPeersCsv: String,
    val createdAt: Long
)

