package CommsLayer.DTN

import org.junit.Assert.assertEquals
import org.junit.Test

class DTNConfigTest {

    @Test
    fun constantsMatchExpectedValues() {
        assertEquals(12, DTNConfig.MAX_HOPS)
        assertEquals(3_600_000L, DTNConfig.MESSAGE_TTL_MS)
        assertEquals(3_600_000L, DTNConfig.STORE_AND_FORWARD_TTL_MS)
        assertEquals(3, DTNConfig.SPRAY_LIMIT)
        assertEquals(12, DTNConfig.MAX_RELAY_HOPS)
        assertEquals(5_000L, DTNConfig.RETRY_INTERVAL_MS)
        assertEquals(500, DTNConfig.MAX_BUFFER_SIZE)
        assertEquals(10_000L, DTNConfig.RETRY_TIMEOUT_MS)
    }
}
