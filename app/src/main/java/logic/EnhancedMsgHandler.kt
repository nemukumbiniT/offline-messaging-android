// EnhancedMsgHandler - Secures and routes DTN payloads between peers and storage.
// Created by Tasima Hapazari. Edited by Thanyani Nemukumbini.
// Date: 2025-08-24
package logic

import CommsLayer.DTN.DTNBundle
import CommsLayer.DTN.DTNMessageManager
import CommsLayer.DTN.MessageMode
import DataLayer.models.PeerVisibility
import android.util.Log
import com.asylo.nexa.data.MessageEntity
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.libsodium.jni.Sodium
import utils.EncryptionServcice
import utils.KeyManager

class EnhancedMsgHandler(
    private val dtnManager: DTNMessageManager,
    private val deviceId: String,
    private val keyManager: KeyManager,
    private val encryptionService: EncryptionServcice
) {
    companion object {
        private const val TAG = "EnhancedMsgHandler"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val nonceSize = Sodium.crypto_secretbox_noncebytes()

    private val myPublicKey: ByteArray
    private val myPrivateKey: ByteArray

    init {
        val keys = keyManager.getDeviceKeys()
            // Thanyani: prefer persisted key pair; generating ad-hoc keys is last resort to keep trust intact.
            ?: keyManager.initializeDeviceKeys()
            ?: encryptionService.generateKeyPair().also {
                Log.w(TAG, "Generated ephemeral key pair because persistent keys were unavailable")
            }

        myPublicKey = keys.first
        myPrivateKey = keys.second

        setupDTNCallbacks()
    }

    private fun setupDTNCallbacks() {
        dtnManager.onMessageReceived = { bundle, mode ->
            handleReceivedMessage(bundle, mode)
        }
        dtnManager.onGroupMessageReceived = { groupID, bundle ->
            handleReceivedGroupMessage(groupID, bundle)
        }
    }

    fun sendP2PMessage(recipientId: String, payload: Payload) {
        coroutineScope.launch {
            val plaintextContent = payload.asBytes() ?: ByteArray(0)
            val requiredVisibility = dtnManager.getDefaultVisibilityRequirement()
            val result = if (requiredVisibility == PeerVisibility.TRUSTED) {
                sendSignedMessage(recipientId, plaintextContent)
            } else {
                sendOpenMessage(recipientId, plaintextContent)
            }
            if (result == null) {
                Log.w(TAG, "Failed to dispatch message to $recipientId using $requiredVisibility mode")
            }
        }
    }

    suspend fun sendSignedMessage(recipientId: String, plaintext: ByteArray): String? {
        return try {
            val sharedKey = getSharedKey(recipientId)
            // Tasima: shared key gives us sealed boxes without redoing handshakes per message.
            val nonce = ByteArray(nonceSize).also { Sodium.randombytes_buf(it, it.size) }
            val encryptedContent = encryptionService.encryptMessage(plaintext, nonce, sharedKey)
            val encryptedPayload = Payload.fromBytes(nonce + encryptedContent)
            val bundleId = dtnManager.sendP2PMessage(
                // Thanyani: reuse DTN manager so retries & visibility rules stay consistent.
                receiverID = recipientId,
                message = encryptedPayload,
                requiredVisibility = PeerVisibility.TRUSTED
            )
            Log.d(TAG, "Encrypted P2P message sent with bundle ID: $bundleId")
            bundleId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send encrypted P2P message", e)
            null
        }
    }

    suspend fun sendOpenMessage(recipientId: String, plaintext: ByteArray): String? {
        return try {
            val payload = Payload.fromBytes(plaintext)
            val bundleId = dtnManager.sendP2PMessage(
                // Thanyani: reuse DTN manager so retries & visibility rules stay consistent.
                receiverID = recipientId,
                message = payload,
                requiredVisibility = PeerVisibility.OPEN
            )
            Log.d(TAG, "Open P2P message sent with bundle ID: $bundleId")
            bundleId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send open P2P message", e)
            null
        }
    }

    fun sendGroupMessage(groupId: String, payload: Payload) {
        coroutineScope.launch {
            try {
                val plaintextContent = payload.asBytes() ?: ByteArray(0)
                val groupKey = keyManager.getGroupKey(groupId)
                val nonce = ByteArray(nonceSize).also {
                    Sodium.randombytes_buf(it, it.size)
                }
                val encryptedContent = encryptionService.encryptMessage(plaintextContent, nonce, groupKey)
                val encryptedPayload = Payload.fromBytes(nonce + encryptedContent)
                val bundleId = dtnManager.sendGroupMessage(groupId, encryptedPayload)
                Log.d(TAG, "Encrypted group message sent with bundle ID: $bundleId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send encrypted group message", e)
            }
        }
    }

    fun processIncomingPayload(endpointId: String, payload: Payload) {
        coroutineScope.launch {
            dtnManager.handleIncomingPayload(endpointId, payload)
        }
    }

    private fun handleReceivedMessage(bundle: DTNBundle, mode: MessageMode) {
        try {
            val rawContent = bundle.content ?: ByteArray(0)
            val requiredVisibility = bundle.metadata.requiredVisibility

            val messageBytes = if (requiredVisibility == PeerVisibility.TRUSTED) {
                // Tasima: decrypt trusted payloads; open messages skip this path to save cycles.
                if (rawContent.size < nonceSize) {
                    Log.e(TAG, "Received trusted message too short to contain valid encryption data")
                    return
                }
                val nonce = rawContent.copyOfRange(0, nonceSize)
                val ciphertext = rawContent.copyOfRange(nonceSize, rawContent.size)
                val sharedKey = getSharedKey(bundle.sourceID)
                encryptionService.decryptMessage(ciphertext, nonce, sharedKey)
            } else {
                rawContent
            }

            val msgEntity = MessageEntity(
                messageID = bundle.bundleID,
                senderID = bundle.sourceID,
                receiverID = deviceId,
                payloadType = bundle.messageType,
                content = messageBytes,
                timestamp = System.currentTimeMillis()
            )

            Log.d(TAG, "Received ${mode.name} message from ${bundle.sourceID} with visibility $requiredVisibility")
            onMessageReceiveCallback?.invoke(msgEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process received message", e)
        }
    }

    private fun handleReceivedGroupMessage(groupId: String, bundle: DTNBundle) {
        try {
            val encryptedData = bundle.content ?: ByteArray(0)
            if (encryptedData.size < nonceSize) {
                Log.e(TAG, "Received group message too short to contain valid encryption data")
                return
            }
            val nonce = encryptedData.sliceArray(0 until nonceSize)
            val ciphertext = encryptedData.sliceArray(nonceSize until encryptedData.size)
            val groupKey = keyManager.getGroupKey(groupId)
            val decryptedContent = encryptionService.decryptMessage(ciphertext, nonce, groupKey)

            Log.d(TAG, "Received and decrypted group message for $groupId from ${bundle.sourceID}")

            val msgEntity = MessageEntity(
                messageID = bundle.bundleID,
                senderID = bundle.sourceID,
                receiverID = groupId,
                payloadType = bundle.messageType,
                content = decryptedContent,
                timestamp = System.currentTimeMillis()
            )

            onMessageReceiveCallback?.invoke(msgEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt received group message", e)
        }
    }

    private fun getSharedKey(peerDeviceId: String): ByteArray {
        keyManager.getOrDeriveSharedKey(peerDeviceId, myPrivateKey)?.let { return it }
        val fallback = keyManager.getPeerKey(peerDeviceId)
        Log.w(TAG, "Falling back to legacy shared key for peer $peerDeviceId")
        return fallback
        // Tasima: legacy path keeps compatibility with peers that have not rotated keys yet.
    }

    fun getMyPublicKey(): ByteArray = myPublicKey.copyOf()

    fun addPublicKey(peerDeviceId: String, publicKey: ByteArray) {
        keyManager.storePeerPublicKey(peerDeviceId, publicKey)
        Log.d(TAG, "Stored public key for device: $peerDeviceId")
    }

    var onGroupMessageReceivedCallback: ((String, ByteArray, String) -> Unit)? = null
    var onMessageReceiveCallback: ((MessageEntity) -> Unit)? = null

    fun joinGroup(groupID: String) {
        dtnManager.joinGroup(groupID)
    }

    fun leaveGroup(groupID: String) {
        dtnManager.leaveGroup(groupID)
    }
}














