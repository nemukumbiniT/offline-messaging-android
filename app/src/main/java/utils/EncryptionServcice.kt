// EncryptionServcice - Provides sodium-based encryption helpers for transport.
// Created by Tasima Hapazari. Edited by Thanyani Nemukumbini.
// Date: 2025-08-21
package utils

import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import org.libsodium.jni.SodiumJNI

/*
APP-LEVEL E2EE
USE LIBSODIUM
GENERATE AND STORE USER KEYPAIR
ENCRYPT AND DECRYPT METHODS
 */

class EncryptionServcice {

    init {
        // Initialize libsodium
        NaCl.sodium()
    }

    // generateKeyPair: creates a libsodium key pair for asymmetric operations.
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        // Tasima: libsodium handles seeding; we just surface pair to upstream managers.
        val publicKey = ByteArray(Sodium.crypto_box_publickeybytes())
        val privateKey = ByteArray(Sodium.crypto_box_secretkeybytes())

        val result = Sodium.crypto_box_keypair(publicKey, privateKey)

        if (result != 0) {
            throw RuntimeException("Keypair generation failed")
        }

        return Pair(publicKey, privateKey)
    }

    // encryptMessage: uses symmetric secretbox to encrypt payload with provided nonce and key.
    fun encryptMessage(content: ByteArray?, nonce: ByteArray, key: ByteArray): ByteArray {
        if (content == null || content.isEmpty()) {
            throw IllegalArgumentException("Content cannot be null or empty")
        }

        if (nonce.size != Sodium.crypto_secretbox_noncebytes()) {
            throw IllegalArgumentException("Invalid nonce size. Expected ${Sodium.crypto_secretbox_noncebytes()}, got ${nonce.size}")
        }

        if (key.size != Sodium.crypto_secretbox_keybytes()) {
            throw IllegalArgumentException("Invalid key size. Expected ${Sodium.crypto_secretbox_keybytes()}, got ${key.size}")
        }

        val cipherText = ByteArray(content.size + Sodium.crypto_secretbox_macbytes())

        val result = Sodium.crypto_secretbox_easy(
            cipherText, content, content.size, nonce, key
        )

        if (result != 0) {
            throw RuntimeException("Encryption failed in crypto_secretbox_easy()")
        }

        return cipherText
    }

    // decryptMessage: decrypts secretbox ciphertext validating nonce/key sizes.
    fun decryptMessage(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        if (cipher.isEmpty()) {
            throw IllegalArgumentException("Cipher cannot be empty")
        }

        if (cipher.size < Sodium.crypto_secretbox_macbytes()) {
            throw IllegalArgumentException("Cipher too short to contain valid encrypted data")
        }

        if (nonce.size != Sodium.crypto_secretbox_noncebytes()) {
            throw IllegalArgumentException("Invalid nonce size. Expected ${Sodium.crypto_secretbox_noncebytes()}, got ${nonce.size}")
        }

        if (key.size != Sodium.crypto_secretbox_keybytes()) {
            throw IllegalArgumentException("Invalid key size. Expected ${Sodium.crypto_secretbox_keybytes()}, got ${key.size}")
        }

        val decrypted = ByteArray(cipher.size - Sodium.crypto_secretbox_macbytes())

        val result = Sodium.crypto_secretbox_open_easy(
            decrypted, cipher, cipher.size, nonce, key
        )

        if (result != 0) {
            throw RuntimeException("Decryption failed - invalid key, nonce, or corrupted data")
        }

        return decrypted
    }

    // encryptMessageAsymmetric: applies crypto_box encryption using sender private and recipient public key.
    fun encryptMessageAsymmetric(content: ByteArray, nonce: ByteArray, recipientPublicKey: ByteArray, senderPrivateKey: ByteArray): ByteArray {
        if (content.isEmpty()) {
            throw IllegalArgumentException("Content cannot be empty")
        }

        if (nonce.size != Sodium.crypto_box_noncebytes()) {
            throw IllegalArgumentException("Invalid nonce size. Expected ${Sodium.crypto_box_noncebytes()}, got ${nonce.size}")
        }

        val cipherText = ByteArray(content.size + Sodium.crypto_box_macbytes())

        val result = Sodium.crypto_box_easy(
            cipherText, content, content.size, nonce, recipientPublicKey, senderPrivateKey
        )

        if (result != 0) {
            throw RuntimeException("Asymmetric encryption failed")
        }

        return cipherText
    }

    // decryptMessageAsymmetric: reverses crypto_box encryption verifying authenticity.
    fun decryptMessageAsymmetric(cipher: ByteArray, nonce: ByteArray, senderPublicKey: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
        if (cipher.isEmpty()) {
            throw IllegalArgumentException("Cipher cannot be empty")
        }

        if (cipher.size < Sodium.crypto_box_macbytes()) {
            throw IllegalArgumentException("Cipher too short to contain valid encrypted data")
        }

        val decrypted = ByteArray(cipher.size - Sodium.crypto_box_macbytes())

        val result = Sodium.crypto_box_open_easy(
            decrypted, cipher, cipher.size, nonce, senderPublicKey, recipientPrivateKey
        )

        if (result != 0) {
            throw RuntimeException("Asymmetric decryption failed")
        }

        return decrypted
    }

    // generateRandomBytes: returns secure random bytes of the requested size.
    fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        Sodium.randombytes_buf(bytes, size)
        return bytes
    }

    // generateSymmetricKey: produces a random key sized for secretbox encryption.
    fun generateSymmetricKey(): ByteArray {
        return generateRandomBytes(Sodium.crypto_secretbox_keybytes())
    }

    // generateNonce: creates a nonce suitable for secretbox operations.
    fun generateNonce(): ByteArray {
        return generateRandomBytes(Sodium.crypto_secretbox_noncebytes())
    }

    // generateAsymmetricNonce: creates a nonce sized for crypto_box exchanges.
    fun generateAsymmetricNonce(): ByteArray {
        return generateRandomBytes(Sodium.crypto_box_noncebytes())
    }

    // deriveSharedKey: runs crypto_box_beforenm to produce shared secret for trusted peers.
fun deriveSharedKey(peerPublicKey: ByteArray, myPrivateKey: ByteArray): ByteArray {
        if (peerPublicKey.size != Sodium.crypto_box_publickeybytes()) {
            throw IllegalArgumentException("Peer public key must be ${Sodium.crypto_box_publickeybytes()} bytes")
        }
        if (myPrivateKey.size != Sodium.crypto_box_secretkeybytes()) {
            throw IllegalArgumentException("Private key must be ${Sodium.crypto_box_secretkeybytes()} bytes")
        }

        val sharedKey = ByteArray(Sodium.crypto_box_beforenmbytes())
        val result = Sodium.crypto_box_beforenm(sharedKey, peerPublicKey, myPrivateKey)
        if (result != 0) {
            throw RuntimeException("Failed to derive shared key via crypto_box_beforenm")
        }
        return sharedKey
    }

    /**
     * Hashes a password using crypto_pwhash_str.
     *
     * @param password The password string to hash.
     * @return A string containing the hashed password, including the salt and algorithm parameters.
     *         This string is safe to store.
     */
    // hashPassword: derives a salted hash using crypto_pwhash for secure password storage.
fun hashPassword(password: String): String {
        if (password.isEmpty()) {
            throw IllegalArgumentException("Password cannot be empty")
        }

        val opsLimit = SodiumJNI.crypto_pwhash_opslimit_moderate()
        val memLimit = SodiumJNI.crypto_pwhash_memlimit_moderate()
        val hashedPassword = ByteArray(Sodium.crypto_pwhash_strbytes())
        val passwordBytes = password.toByteArray(Charsets.UTF_8)

        val result = Sodium.crypto_pwhash_str(
            hashedPassword, passwordBytes, passwordBytes.size, opsLimit, memLimit
        )

        if (result != 0) {
            throw RuntimeException("Password hashing failed")
        }

        // Convert the byte array to a String. The output of crypto_pwhash_str is designed to be a C string (null-terminated).
        val actualLength = hashedPassword.indexOf(0.toByte())
        if (actualLength == -1) {
            throw RuntimeException("Hashed password string is not null-terminated as expected.")
        }

        return String(hashedPassword, 0, actualLength, Charsets.UTF_8)
    }

    /**
     * Verifies a password against a stored hash.
     *
     * @param password The password string to verify.
     * @param storedHash The stored hash string (generated by hashPassword).
     * @return True if the password matches the hash, false otherwise.
     */
    // verifyPassword: checks plaintext password against stored hash using libsodium verifier.
fun verifyPassword(password: String, storedHash: String): Boolean {
        if (password.isEmpty() || storedHash.isEmpty()) {
            return false
        }

        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val storedHashBytes = storedHash.toByteArray(Charsets.UTF_8)

        val result = SodiumJNI.crypto_pwhash_str_verify(
            storedHashBytes,
            passwordBytes,
            passwordBytes.size
        )

        return result == 0 // 0 means verification successful, else failed
    }

    /*
    * USAGE EXAMPLES:
    *
    * 1. Symmetric Encryption (for shared secrets):
    * val service = EncryptionServcice()
    * val key = service.generateSymmetricKey()
    * val nonce = service.generateNonce()
    *
    * val originalMessage = "Hello Symmetric!"
    * val cipherText = service.encryptMessage(originalMessage.toByteArray(Charsets.UTF_8), nonce, key)
    * val decryptedBytes = service.decryptMessage(cipherText, nonce, key)
    * val decryptedMessage = String(decryptedBytes, Charsets.UTF_8)
    *
    * 2. Asymmetric Encryption (for public key cryptography):
    * val service = EncryptionServcice()
    * val aliceKeys = service.generateKeyPair()
    * val bobKeys = service.generateKeyPair()
    * val nonce = service.generateAsymmetricNonce()
    *
    * val message = "Hello Bob!"
    * val encrypted = service.encryptMessageAsymmetric(
    *     message.toByteArray(), nonce, bobKeys.first, aliceKeys.second
    * )
    * val decrypted = service.decryptMessageAsymmetric(
    *     encrypted, nonce, aliceKeys.first, bobKeys.second
    * )
    * val decryptedMessage = String(decrypted, Charsets.UTF_8)
    */
}






