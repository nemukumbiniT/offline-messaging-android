package DataLayer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class PeerProfileTest {

    @Test
    fun computeIdentityFingerprint_matchesManualDigest() {
        val displayName = "Alice"
        val serviceId = "service"
        val publicKey = byteArrayOf(1, 2, 3, 4)

        val expected = manualFingerprint(displayName, serviceId, publicKey)
        val actual = PeerProfile.computeIdentityFingerprint(displayName, serviceId, publicKey)

        assertEquals(expected, actual)
    }

    @Test
    fun computeIdentityFingerprint_handlesNulls() {
        val expected = manualFingerprint(null, null, null)
        val actual = PeerProfile.computeIdentityFingerprint(null, null, null)
        assertEquals(expected, actual)
    }

    private fun manualFingerprint(displayName: String?, serviceId: String?, publicKey: ByteArray?): String {
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
