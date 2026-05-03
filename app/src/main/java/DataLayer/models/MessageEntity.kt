// MessageEntity - Room entity storing individual chat messages.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-17
package com.asylo.nexa.data

import android.util.Log
import java.util.UUID

/**
 * Message:
 * - Includes an id for de-duplication
 * - Includes a timestamp for ordering.
 * - Sender and receiver IDs are used for retrying and display
 */



/*
 * - MESSAGE JSON FORMAT
 * messageID: UUID
 * type: String
 * senderID: UUID
 * receiverID: UUID
 * content: ByteArray
 * timestamp: Long
 */
data class MessageEntity(
    var messageID: String,   // Unique ID to detect duplicates
    var senderID: String, //"Me or device nickname should be used when displaying"
    var receiverID: String, //"Peer or device nickname should be used when displaying"

    var payloadType: Int,
    var content: ByteArray?,
    var timestamp: Long = System.currentTimeMillis()  // For ordering / display (time of sending)
){

    override fun equals(other: Any?): Boolean {
        return messageID == (other as MessageEntity).messageID
    }

    override fun toString(): String {
        return content.toString()
    }

    fun toByteArray(): ByteArray? {
        return content
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }
    fun getMessage(messageID: UUID) : MessageEntity? {
        if (messageID.toString() == this.messageID) {
            return this
        }else{
            //println("Message not found")
            Log.d("MessageHandler", "Message not found")
            return null
        }
    }


    /*
    fun createMessage(userID: UUID, recID:UUID, msg_txt:String){
        this.messageID = UUID.randomUUID().toString()
        this.receiverID = recID.toString()
        this.fileType = 1 //text will be stored as Bytes
        this.senderID = userID.toString()
        this.content = msg_txt.toByteArray()
        this.timestamp = System.currentTimeMillis()
    }

     */

    /*
    /**
 * Entity representing a message in the system
 * Stores all messages (sent, received, queued) with metadata
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["senderID"]),        // Index for quick sender lookups
        Index(value = ["receiverID"]),      // Index for quick receiver lookups
        Index(value = ["groupID"]),         // Index for group message queries
        Index(value = ["timestamp"]),       // Index for message ordering
        Index(value = ["status"])           // Index for status-based queries
    ]
)
data class MessageEntity(
    @PrimaryKey val messageID: UUID,        // Unique message identifier for deduplication
    val senderID: UUID,                     // Who sent the message
    val receiverID: UUID? = null,           // Recipient for P2P messages
    val groupID: UUID? = null,              // Group for group messages
    val fileType: Int,                      // Type of content (matches Nearby Payload.Type)
    val content: ByteArray,                 // Actual message content/payload
    val timestamp: Long,                    // When message was created (for ordering)
    val status: MessageStatus = MessageStatus.PENDING, // Delivery status
    val transmissionMode: String = "P2P",   // P2P, MESH, or BROADCAST
    val retryCount: Int = 0,                // Number of retry attempts for failed messages
    val isRead: Boolean = false,            // Has recipient read this message
    val endpointId: String? = null,         // Nearby endpoint ID for routing
    val ttl: Int = 7                        // Time-to-live in hops (DTN store-and-forward)
) {
    // Custom equality based on messageID only
    override fun equals(other: Any?): Boolean = (other as? MessageEntity)?.messageID == messageID
    override fun hashCode(): Int = messageID.hashCode()
}

     */

}
