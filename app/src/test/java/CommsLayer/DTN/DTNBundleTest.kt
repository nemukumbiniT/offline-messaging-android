package CommsLayer.DTN

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DTNBundleTest {

    @Test
    fun incrementHopsIncreasesHopCount() {
        val original = DTNBundle(messageType = 1, sourceID = "A", destinationID = "B", content = null)
        val incremented = original.incrementHops()
        assertEquals(original.hopCount + 1, incremented.hopCount)
        assertEquals(original.bundleID, incremented.bundleID)
    }

    @Test
    fun addToPathAppendsDeviceAndUpdatesMetadata() {
        val original = DTNBundle(messageType = 1, sourceID = "A", destinationID = "B", content = null)
        val updated = original.addToPath("relay")
        assertEquals(listOf("relay"), updated.routingPath)
        assertEquals("relay", updated.metadata.previousHopDeviceId)
    }

    @Test
    fun isExpiredUsesTtlAndMaxHops() {
        val expiredByTime = DTNBundle(
            messageType = 1,
            sourceID = "A",
            destinationID = "B",
            content = null,
            timestamp = System.currentTimeMillis() - 100,
            messageTTL = 50
        )
        assertTrue(expiredByTime.isExpired())

        val expiredByHops = DTNBundle(
            messageType = 1,
            sourceID = "A",
            destinationID = "B",
            content = null,
            hopCount = 5,
            maxHops = 5
        )
        assertTrue(expiredByHops.isExpired())
    }
}
