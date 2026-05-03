// StoredMessageEntity - Tracks deferred or archived messages.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-19
package DataLayer.models

data class StoredMessageEntity(
    val messageId: String,
    val senderId: String,
    val receiverId: String,
    val payloadType: Int,
    val content: ByteArray,
    val timestamp: Long,
    val isSentByLocalDevice: Boolean,
    val ownerUserId: String
)


