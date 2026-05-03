// MessageCenter - Coordinates message synchronization across transport and database layers.
// Created by Tasima Hapazari. Edited by Thanyani Nemukumbini.
// Date: 2025-08-25
package logic

import DataLayer.DatabaseRepository
import DataLayer.models.StoredMessageEntity
import android.util.Log
import com.asylo.nexa.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MessageCenter private constructor(
    private val deviceId: String,
    private val ownerUserId: String,
    private val repository: DatabaseRepository,
    private val enhancedMsgHandler: EnhancedMsgHandler
) {
    companion object {
        private const val TAG = "MessageCenter"

        fun create(
            deviceId: String,
            ownerUserId: String,
            repository: DatabaseRepository,
            enhancedMsgHandler: EnhancedMsgHandler
        ): MessageCenter {
            val normalizedOwner = ownerUserId.trim()
            require(normalizedOwner.isNotEmpty()) { "ownerUserId must not be blank" }
            return MessageCenter(deviceId, normalizedOwner, repository, enhancedMsgHandler)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Thanyani: background scope keeps persistence off the UI thread while allowing multiple launches.

    private val _messageEvents = MutableSharedFlow<MessageEntity>(extraBufferCapacity = 32)
    // Thanyani: buffer allows quick UI bursts without blocking the DTN receive thread.
    val messageEvents: SharedFlow<MessageEntity> = _messageEvents.asSharedFlow()

    init {
        enhancedMsgHandler.onMessageReceiveCallback = { messageEntity ->
            // Tasima: hook DTN callbacks straight into repository pipeline so nothing gets dropped.
            handleIncomingMessage(messageEntity)
        }
    }

    fun newLocalMessageId(): String = UUID.randomUUID().toString()

    suspend fun getConversationHistory(
        peerDeviceId: String,
        limit: Int? = null
    ): List<StoredMessageEntity> {
        return repository.getConversationMessages(deviceId, peerDeviceId, ownerUserId, limit)
    }

    fun recordOutgoingMessage(
        messageId: String,
        receiverId: String,
        payloadType: Int,
        content: ByteArray,
        timestamp: Long
    ) {
        val storedMessage = StoredMessageEntity(
            // Thanyani: persist identical metadata so UI history matches what DTN hands us.
            messageId = messageId,
            senderId = deviceId,
            receiverId = receiverId,
            payloadType = payloadType,
            content = content,
            timestamp = timestamp,
            isSentByLocalDevice = true,
            ownerUserId = ownerUserId
        )

        val messageEntity = MessageEntity(
            // Tasima: mirror entity for real-time stream so live UI updates reuse same structure.
            messageID = messageId,
            senderID = deviceId,
            receiverID = receiverId,
            payloadType = payloadType,
            content = content,
            timestamp = timestamp
        )

        scope.launch {
            try {
                // Thanyani: writes + emits inside one scope so we never notify UI without persisted state.
                repository.saveMessage(storedMessage, ownerUserId)
                _messageEvents.emit(messageEntity)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist outgoing message", t)
            }
        }
    }


    private fun handleIncomingMessage(messageEntity: MessageEntity) {
        val payloadContent = messageEntity.content ?: ByteArray(0)
        // Tasima: ensure we never feed null into Room; empty byte array keeps history consistent.
        val storedMessage = StoredMessageEntity(
            // Thanyani: persist identical metadata so UI history matches what DTN hands us.
            messageId = messageEntity.messageID,
            senderId = messageEntity.senderID,
            receiverId = messageEntity.receiverID,
            payloadType = messageEntity.payloadType,
            content = payloadContent,
            timestamp = messageEntity.timestamp,
            isSentByLocalDevice = messageEntity.senderID == deviceId,
            ownerUserId = ownerUserId
        )

        scope.launch {
            try {
                // Thanyani: writes + emits inside one scope so we never notify UI without persisted state.
                repository.saveMessage(storedMessage, ownerUserId)
                _messageEvents.emit(messageEntity)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle incoming message", t)
            }
        }
    }
}







