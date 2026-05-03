// KeyManager - Manages cryptographic keys for secure messaging.
// Created by Thanyani Nemukumbini. Edited by Tasima Hapazari.
// Date: 2025-08-22
package utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.libsodium.jni.Sodium
import java.util.concurrent.ConcurrentHashMap

/**
 * Secure key management helper for the Nexa messaging system
 * Handles storage and retrieval of encryption keys using Android's secure storage
 */
class KeyManager(private val context: Context) {

    companion object {
        private const val TAG = "KeyManager"
        private const val SHARED_PREFS_NAME = "nexa_secure_keys"
        private const val DEVICE_PUBLIC_KEY = "device_public_key"
        private const val DEVICE_PRIVATE_KEY = "device_private_key"
        private const val PEER_KEY_PREFIX = "peer_key_"
        private const val PEER_PUBLIC_KEY_PREFIX = "peer_pub_"
        private const val PEER_SHARED_KEY_PREFIX = "peer_shared_"
        private const val GROUP_KEY_PREFIX = "group_key_"
        private const val HANDSHAKE_STATE_PREFIX = "handshake_state_"
    }

    private val encryptionService = EncryptionServcice()

    // In-memory cache for frequently used keys
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    // Encrypted SharedPreferences for secure storage
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SHARED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted preferences, falling back to regular prefs", e)
            context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Initialize device keypair if it doesn't exist
     */
    // initializeDeviceKeys: ensures device key pair exists, generating and storing if missing.
    fun initializeDeviceKeys(): Pair<ByteArray, ByteArray>? {
        // Thanyani: synchronized to ensure we never generate two key pairs under contention.
        return try {
            val existingKeys = getDeviceKeys()
            if (existingKeys != null) {
                Log.d(TAG, "Device keys already exist")
                return existingKeys
            }

            // Generate new keypair
            val keyPair = encryptionService.generateKeyPair()
            val publicKey = keyPair.first
            val privateKey = keyPair.second

            // Store securely
            storeDeviceKeys(publicKey, privateKey)

            Log.d(TAG, "New device keypair generated and stored")
            Pair(publicKey, privateKey)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize device keys", e)
            null
        }
    }

    /**
     * Get device's public and private keys
     */
    // getDeviceKeys: retrieves the stored device key pair if available.
    fun getDeviceKeys(): Pair<ByteArray, ByteArray>? {
        return try {
            val publicKey = getStoredKey(DEVICE_PUBLIC_KEY)
            val privateKey = getStoredKey(DEVICE_PRIVATE_KEY)

            if (publicKey != null && privateKey != null) {
                Pair(publicKey, privateKey)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve device keys", e)
            null
        }
    }

    /**
     * Get device's public key only
     */
    // getDevicePublicKey: returns the persisted device public key.
    fun getDevicePublicKey(): ByteArray? {
        return getStoredKey(DEVICE_PUBLIC_KEY)
    }

    /**
     * Get device's private key only
     */
    // getDevicePrivateKey: returns the persisted device private key.
    fun getDevicePrivateKey(): ByteArray? {
        return getStoredKey(DEVICE_PRIVATE_KEY)
    }

    /**
     * Store a shared key for a peer
     */
    // storePeerKey: saves a symmetric shared key for the specified peer.
    fun storePeerKey(peerDeviceId: String, sharedKey: ByteArray) {
        try {
            val keyName = PEER_KEY_PREFIX + peerDeviceId
            storeKey(keyName, sharedKey)
            keyCache[keyName] = sharedKey.copyOf()
            Log.d(TAG, "Stored shared key for peer: $peerDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store peer key for $peerDeviceId", e)
        }
    }

    // storePeerPublicKey: persists the peer public key for later trust negotiations.
    fun storePeerPublicKey(peerDeviceId: String, publicKey: ByteArray) {
        try {
            val keyName = PEER_PUBLIC_KEY_PREFIX + peerDeviceId
            storeKey(keyName, publicKey)
            keyCache[keyName] = publicKey.copyOf()
            Log.d(TAG, "Stored public key for peer: $peerDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store public key for $peerDeviceId", e)
        }
    }

    // getPeerPublicKey: fetches the stored public key for a peer if present.
    fun getPeerPublicKey(peerDeviceId: String): ByteArray? {
        val keyName = PEER_PUBLIC_KEY_PREFIX + peerDeviceId
        keyCache[keyName]?.let { return it.copyOf() }
        val stored = getStoredKey(keyName) ?: return null
        keyCache[keyName] = stored.copyOf()
        return stored
    }

    // cacheSharedKey: caches a shared key in memory for quick reuse during messaging.
    fun cacheSharedKey(peerDeviceId: String, sharedKey: ByteArray) {
        val keyName = PEER_SHARED_KEY_PREFIX + peerDeviceId
        storeKey(keyName, sharedKey)
        keyCache[keyName] = sharedKey.copyOf()
    }

    // getCachedSharedKey: returns cached shared key for a peer if previously stored.
    fun getCachedSharedKey(peerDeviceId: String): ByteArray? {
        val keyName = PEER_SHARED_KEY_PREFIX + peerDeviceId
        keyCache[keyName]?.let { return it.copyOf() }
        val stored = getStoredKey(keyName)
        if (stored != null) {
            keyCache[keyName] = stored.copyOf()
        }
        return stored
    }

    // getOrDeriveSharedKey: returns cached shared key or derives a new one using provided private key.
    fun getOrDeriveSharedKey(peerDeviceId: String, myPrivateKey: ByteArray): ByteArray? {
        getCachedSharedKey(peerDeviceId)?.let { return it }
        val peerPublic = getPeerPublicKey(peerDeviceId) ?: return null
        val shared = encryptionService.deriveSharedKey(peerPublic, myPrivateKey)
        cacheSharedKey(peerDeviceId, shared)
        return shared
    }
    /**
     * Get shared key for a peer, generate if not exists
     */
    // getPeerKey: retrieves persistent shared key for peer, throwing if unavailable.
    fun getPeerKey(peerDeviceId: String): ByteArray {
        val keyName = PEER_KEY_PREFIX + peerDeviceId

        // Check cache first
        keyCache[keyName]?.let { return it.copyOf() }

        // Try to load from storage
        getStoredKey(keyName)?.let {
            keyCache[keyName] = it.copyOf()
            return it
        }

        // Generate new shared key if not found
        val newKey = encryptionService.generateSymmetricKey()
        storePeerKey(peerDeviceId, newKey)
        return newKey
    }

    /**
     * Store a group key
     */
    // storeGroupKey: stores encrypted messaging key for a specific group.
    fun storeGroupKey(groupId: String, groupKey: ByteArray) {
        try {
            val keyName = GROUP_KEY_PREFIX + groupId
            storeKey(keyName, groupKey)
            keyCache[keyName] = groupKey.copyOf()
            Log.d(TAG, "Stored group key for: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store group key for $groupId", e)
        }
    }

    /**
     * Get group key, generate if not exists
     */
    // getGroupKey: retrieves stored group key or throws if missing.
    fun getGroupKey(groupId: String): ByteArray {
        val keyName = GROUP_KEY_PREFIX + groupId

        // Check cache first
        keyCache[keyName]?.let { return it.copyOf() }

        // Try to load from storage
        getStoredKey(keyName)?.let {
            keyCache[keyName] = it.copyOf()
            return it
        }

        // Generate new group key if not found
        val newKey = encryptionService.generateSymmetricKey()
        storeGroupKey(groupId, newKey)
        return newKey
    }

    /**
     * Remove a peer's key (e.g., when they leave or for security rotation)
     */
    // removePeerKey: removes all stored keys and state associated with a peer.
    fun removePeerKey(peerDeviceId: String) {
        try {
            val privKeyName = PEER_KEY_PREFIX + peerDeviceId
            removeKey(privKeyName)
            keyCache.remove(privKeyName)

            val pubKeyName = PEER_PUBLIC_KEY_PREFIX + peerDeviceId
            removeKey(pubKeyName)
            keyCache.remove(pubKeyName)

            val sharedKeyName = PEER_SHARED_KEY_PREFIX + peerDeviceId
            removeKey(sharedKeyName)
            keyCache.remove(sharedKeyName)

            Log.d(TAG, "Removed peer key material for: $peerDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove peer key material for $peerDeviceId", e)
        }
    }

    /**
     * Remove a group key
     */
    // removeGroupKey: deletes persisted key material for the given group.
    fun removeGroupKey(groupId: String) {
        try {
            val keyName = GROUP_KEY_PREFIX + groupId
            removeKey(keyName)
            keyCache.remove(keyName)
            Log.d(TAG, "Removed group key for: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove group key for $groupId", e)
        }
    }

    /**
     * Generate a shared key using Diffie-Hellman (placeholder for actual implementation)
     * In a real implementation, you'd use proper key exchange protocols
     */
    // performKeyExchange: derives shared secret using peer public key and our private key.
    fun performKeyExchange(peerPublicKey: ByteArray, myPrivateKey: ByteArray): ByteArray {
        return encryptionService.deriveSharedKey(peerPublicKey, myPrivateKey)
    }

    /**
     * Clear all cached keys (call on logout or security event)
     */
    // markHandshakeComplete: records timestamp indicating handshake success for peer.
    fun markHandshakeComplete(peerDeviceId: String, timestamp: Long = System.currentTimeMillis()) {
        try {
            val keyName = HANDSHAKE_STATE_PREFIX + peerDeviceId
            securePrefs.edit().putLong(keyName, timestamp).apply()
            Log.d(TAG, "Marked handshake complete for $peerDeviceId at $timestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist handshake state for $peerDeviceId", e)
        }
    }

    // getHandshakeTimestamp: returns stored handshake completion time for a peer.
    fun getHandshakeTimestamp(peerDeviceId: String): Long? {
        return try {
            val keyName = HANDSHAKE_STATE_PREFIX + peerDeviceId
            val stored = securePrefs.getLong(keyName, -1L)
            if (stored <= 0L) null else stored
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read handshake timestamp for ", e)
            null
        }
    }

    // isHandshakeComplete: checks whether peer handshake is recorded and fresh enough.
    fun isHandshakeComplete(peerDeviceId: String, freshnessMs: Long = 0L): Boolean {
        val timestamp = getHandshakeTimestamp(peerDeviceId) ?: return false
        return if (freshnessMs > 0) {
            System.currentTimeMillis() - timestamp <= freshnessMs
        } else {
            true
        }
    }

    // clearHandshakeState: removes stored handshake metadata for the peer.
    fun clearHandshakeState(peerDeviceId: String) {
        try {
            val keyName = HANDSHAKE_STATE_PREFIX + peerDeviceId
            removeKey(keyName)
            Log.d(TAG, "Cleared handshake state for $peerDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear handshake state for $peerDeviceId", e)
        }
    }

    // getHandshakeStates: exposes snapshot of handshake timestamps for diagnostics.
    fun getHandshakeStates(): Map<String, Long> {
        return try {
            securePrefs.all.mapNotNull { entry ->
                val key = entry.key
                val value = entry.value
                if (key.startsWith(HANDSHAKE_STATE_PREFIX) && value is Long) {
                    key.removePrefix(HANDSHAKE_STATE_PREFIX) to value
                } else {
                    null
                }
            }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate handshake states", e)
            emptyMap()
        }
    }
    // clearKeyCache: wipes in-memory cache of keys to force reload from secure storage.
    fun clearKeyCache() {
        keyCache.clear()
        Log.d(TAG, "Key cache cleared")
    }

    /**
     * Rotate device keys (generate new keypair)
     */
    // rotateDeviceKeys: generates a new device key pair and overwrites stored values.
    fun rotateDeviceKeys(): Pair<ByteArray, ByteArray>? {
        return try {
            // Clear old keys first
            removeKey(DEVICE_PUBLIC_KEY)
            removeKey(DEVICE_PRIVATE_KEY)

            // Generate new keys
            initializeDeviceKeys()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate device keys", e)
            null
        }
    }

    // Private helper methods
    private fun storeDeviceKeys(publicKey: ByteArray, privateKey: ByteArray) {
        storeKey(DEVICE_PUBLIC_KEY, publicKey)
        storeKey(DEVICE_PRIVATE_KEY, privateKey)
    }

    private fun storeKey(keyName: String, key: ByteArray) {
        val encoded = android.util.Base64.encodeToString(key, android.util.Base64.DEFAULT)
        securePrefs.edit().putString(keyName, encoded).apply()
    }

    private fun getStoredKey(keyName: String): ByteArray? {
        return try {
            val encoded = securePrefs.getString(keyName, null)
            encoded?.let {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode stored key: $keyName", e)
            null
        }
    }

    private fun removeKey(keyName: String) {
        securePrefs.edit().remove(keyName).apply()
    }

    /**
     * Get all peer device IDs that have stored keys
     */
    // getAllPeerIds: returns set of peer identifiers that have stored keys.
    fun getAllPeerIds(): Set<String> {
        return try {
            val prefixes = listOf(PEER_KEY_PREFIX, PEER_PUBLIC_KEY_PREFIX, PEER_SHARED_KEY_PREFIX)
            securePrefs.all.keys.mapNotNull { key ->
                val prefix = prefixes.firstOrNull { key.startsWith(it) }
                prefix?.let { key.substring(it.length) }
            }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get peer IDs", e)
            emptySet()
        }
    }

    /**
     * Get all group IDs that have stored keys
     */
    // getAllGroupIds: returns set of group identifiers with stored group keys.
    fun getAllGroupIds(): Set<String> {
        return try {
            securePrefs.all.keys
                .filter { it.startsWith(GROUP_KEY_PREFIX) }
                .map { it.substring(GROUP_KEY_PREFIX.length) }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get group IDs", e)
            emptySet()
        }
    }
}
















