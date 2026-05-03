// StorageManager - Handles file persistence for chat attachments and exports.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-19
package DataLayer
/*
import android.content.Context
import com.asylo.nexa.data.database.MessageEntity
import com.asylo.nexa.data.database.MessageStatus
import com.asylo.nexa.data.repository.DatabaseRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
//import me.bridgefy.commons.TransmissionMode
import org.json.JSONObject
import java.util.UUID

/**
 * StorageManager - High-level manager for database and communication integration
 * Acts as a facade between UI/communication layers and the database repository
 * Handles coroutine scoping and error handling for database operations
 */
class StorageManager private constructor(context: Context) {
    // Repository instance for database operations
    private val repository = DatabaseRepository(context.applicationContext)

    // Coroutine scope for background operations with proper error handling
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @Volatile
        private var INSTANCE: StorageManager? = null

        /**
         * Singleton pattern to ensure single StorageManager instance
         * Uses double-checked locking for thread safety
         */
        // getInstance: returns singleton StorageManager backed by shared repository and scope.
        fun getInstance(context: Context): StorageManager {
            return INSTANCE ?: synchronized(this) {
                val instance = StorageManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    // ========== MESSAGE HANDLING ==========

    /**
     * Process incoming message from network (Nearby/DTN)
     * Handles JSON parsing, deduplication, and storage
     * Runs in background coroutine to avoid blocking UI
     */
    // processIncomingMessage: decrypts payload, saves metadata, and updates unread counters.
    fun processIncomingMessage(
        data: ByteArray,
        messageID: UUID,
        transmissionMode: TransmissionMode,
        senderID: UUID,
        receiverID: UUID? = null
    ) {
        scope.launch {
            try {
                // DTN deduplication check
                if (repository.messageExists(messageID)) return@launch

                // Parse message JSON (assuming standard format)
                val messageJson = String(data, Charsets.UTF_8)
                val json = JSONObject(messageJson)

                val message = MessageEntity(
                    messageID = messageID,
                    senderID = senderID,
                    receiverID = receiverID,
                    groupID = json.optString("groupID")?.let { UUID.fromString(it) },
                    fileType = json.getInt("fileType"),
                    content = json.getString("content").toByteArray(),
                    timestamp = json.getLong("timestamp"),
                    status = MessageStatus.DELIVERED,
                    transmissionMode = when(transmissionMode) {
                        is TransmissionMode.P2P -> "P2P"
                        is TransmissionMode.Mesh -> "MESH"
                        is TransmissionMode.Broadcast -> "BROADCAST"
                        else -> "UNKNOWN"
                    },
                    isRead = false
                )

                // Save to database
                repository.saveIncomingMessage(
                    messageID = message.messageID,
                    senderID = message.senderID,
                    receiverID = message.receiverID,
                    fileType = message.fileType,
                    content = message.content,
                    timestamp = message.timestamp,
                    transmissionMode = message.transmissionMode
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Prepare outgoing message for sending
     * Creates message entity and stores in database
     */
    // prepareOutgoingMessage: builds StoredMessageEntity for sending and persists local copy.
    suspend fun prepareOutgoingMessage(
        senderID: UUID,
        receiverID: UUID? = null,
        groupID: UUID? = null,
        fileType: Int,
        content: ByteArray,
        endpointId: String? = null
    ): MessageEntity? {
        return repository.createMessage(
            senderID = senderID,
            receiverID = receiverID,
            groupID = groupID,
            fileType = fileType,
            content = content,
            endpointId = endpointId
        )
    }

    /**
     * Update message delivery status
     */
    // updateMessageStatus: updates message delivery/read status in repository.
    suspend fun updateMessageStatus(messageID: UUID, status: MessageStatus) {
        repository.updateMessageStatus(messageID, status)
    }

    /**
     * Add message to DTN retry queue
     */
    // addToRetryQueue: queues failed message for future resend attempts with optional priority.
    suspend fun addToRetryQueue(messageID: UUID, priority: Int = 0, maxRetries: Int = 5): Boolean {
        return repository.addToMessageQueue(messageID, priority, maxRetries)
    }

    /**
     * Get messages that need retrying for DTN engine
     */
    // getMessagesForRetry: fetches retry candidates ordered by priority and attempts.
    suspend fun getMessagesForRetry(): List<MessageEntity> {
        val queueItems = repository.getMessagesForRetry()
        return queueItems.mapNotNull { queueItem ->
            repository.getMessage(queueItem.messageID)
        }
    }

    // ========== USER OPERATIONS ==========

    /**
     * User registration/login with device info
     */
    // registerOrLoginUser: delegates to repository to create or authenticate account in one call.
    suspend fun registerOrLoginUser(
        userID: UUID,
        username: String,
        password: String? = null,
        deviceInfo: String? = null
    ): Boolean {
        return repository.registerOrLoginUser(userID, username, password, deviceInfo)
    }

    /**
     * Get current logged-in user
     */
    // getCurrentUser: convenience wrapper returning active user entity.
    suspend fun getCurrentUser() = repository.getCurrentUser()

    /**
     * Update user profile information
     */
    // updateUserProfile: updates current user details and refreshes cached session values.
    suspend fun updateUserProfile(
        userID: UUID,
        username: String? = null,
        profilePicture: ByteArray? = null
    ) {
        repository.updateUserProfile(userID, username, profilePicture)
    }

    /**
     * Change user password with verification
     */
    // changePassword: validates old password then writes new hash for user.
    suspend fun changePassword(userID: UUID, oldPassword: String, newPassword: String): Boolean {
        return repository.changePassword(userID, oldPassword, newPassword)
    }

    // ========== GROUP OPERATIONS ==========

    /**
     * Create new chat group
     */
    // createGroup: inserts new group record and stores metadata for owner.
    suspend fun createGroup(
        groupName: String,
        createdBy: UUID,
        initialMembers: List<UUID> = emptyList()
    ): UUID? {
        return repository.createGroup(groupName, createdBy, initialMembers)
    }

    /**
     * Add member to group
     */
    // addGroupMember: links user to group and returns success state.
    suspend fun addGroupMember(groupID: UUID, userID: UUID): Boolean {
        return repository.addGroupMember(groupID, userID)
    }

    /**
     * Get group members
     */
    // getGroupMembers: returns current roster for given group id.
    suspend fun getGroupMembers(groupID: UUID) = repository.getGroupMembers(groupID)

    /**
     * Get user's groups with real-time updates
     */
    // getUserGroups: fetches groups a user belongs to for UI chips.
    fun getUserGroups(userID: UUID) = repository.getUserGroups(userID)

    // ========== CONTACT OPERATIONS ==========

    /**
     * Add contact to user's contact list
     */
    // addContact: stores contact mapping and optional alias for quick access.
    suspend fun addContact(userID: UUID, contactID: UUID, contactName: String? = null): Boolean {
        return repository.addContact(userID, contactID, contactName)
    }

    /**
     * Get user's contacts with real-time updates
     */
    // getUserContacts: returns saved contacts for drawer listings.
    fun getUserContacts(userID: UUID) = repository.getUserContacts(userID)

    /**
     * Block or unblock contact
     */
    // setContactBlockStatus: toggles blocked flag for contact to control messaging.
    suspend fun setContactBlockStatus(userID: UUID, contactID: UUID, isBlocked: Boolean) {
        repository.setContactBlockStatus(userID, contactID, isBlocked)
    }

    // ========== MESSAGE RETRIEVAL ==========

    /**
     * Get P2P messages with real-time updates
     */
    // getP2PMessages: fetches latest direct messages between user and peer up to limit.
    fun getP2PMessages(userID: UUID, peerID: UUID, limit: Int = 50) =
        repository.getP2PChatMessages(userID, peerID, limit)

    /**
     * Get group messages with real-time updates
     */
    // getGroupMessages: returns most recent group messages for conversation view.
    fun getGroupMessages(groupID: UUID, limit: Int = 50) =
        repository.getGroupChatMessages(groupID, limit)

    /**
     * Get broadcast messages
     */
    // getBroadcastMessages: loads broadcast channel history for quick display.
    fun getBroadcastMessages(limit: Int = 50) = repository.getBroadcastMessages(limit)

    // ========== UTILITY FUNCTIONS ==========

    /**
     * Get unread message count
     */
    // getUnreadCount: asks repository for aggregated unread badge count.
    suspend fun getUnreadCount(userID: UUID) = repository.getUnreadCount(userID)

    /**
     * Mark chat as read
     */
    // markChatAsRead: clears unread status in storage for conversation pair.
    suspend fun markChatAsRead(userID: UUID, peerID: UUID) {
        repository.markChatAsRead(userID, peerID)
    }

    /**
     * Get chat list for UI display
     */
    // getChatList: obtains conversation summaries for chats screen.
    suspend fun getChatList(userID: UUID) = repository.getChatList(userID)

    /**
     * Store discovered peer information
     */
    // storePeer: caches peer identity so it appears in discovery/contact lists.
    suspend fun storePeer(peerID: UUID, peerName: String? = null) {
        repository.storePeer(peerID, peerName)
    }

    // ========== PEER DISCOVERY (NEARBY) ==========

    /**
     * Update discovered peer information from Nearby
     */
    // updateDiscoveredPeer: refreshes metadata when a peer is rediscovered with new info.
    suspend fun updateDiscoveredPeer(
        endpointId: String,
        endpointName: String,
        serviceId: String,
        peerID: UUID? = null
    ) {
        val peer = com.asylo.nexa.data.database.DiscoveredPeerEntity(
            endpointId = endpointId,
            peerID = peerID,
            endpointName = endpointName,
            serviceId = serviceId,
            lastSeen = System.currentTimeMillis(),
            isConnected = false
        )
        repository.insertDiscoveredPeer(peer)
    }

    /**
     * Update peer connection status
     */
    // updatePeerConnectionStatus: records current connection flag for diagnostics and UI.
    suspend fun updatePeerConnectionStatus(endpointId: String, connected: Boolean) {
        repository.updatePeerConnectionStatus(endpointId, connected)
    }

    /**
     * Get connected peers with real-time updates
     */
    // getConnectedPeers: returns list of peers currently marked connected.
    fun getConnectedPeers() = repository.getConnectedPeers()

    // ========== CLEANUP ==========

    /**
     * Clean up old data for storage management
     */
    // cleanupOldData: purges messages older than retention window to save disk.
    suspend fun cleanupOldData(daysToKeep: Int = 30) {
        repository.deleteOldMessages(daysToKeep)
        // Remove peers not seen for 7 days
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        repository.removeOldPeers(cutoffTime)
    }

    /**
     * Shutdown coroutine scope
     */
    // shutdown: cancels background scope and releases resources when app exits.
    fun shutdown() {
        scope.cancel()
    }
}

 */


