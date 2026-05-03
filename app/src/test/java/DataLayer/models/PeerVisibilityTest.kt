package DataLayer.models

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerVisibilityTest {

    @Test
    fun fromDatabaseValue_returnsTrustedWhenStringTrusted() {
        assertEquals(PeerVisibility.TRUSTED, PeerVisibility.fromDatabaseValue("trusted"))
    }

    @Test
    fun fromDatabaseValue_returnsBlockedWhenStringBlocked() {
        assertEquals(PeerVisibility.BLOCKED, PeerVisibility.fromDatabaseValue("BLOCKED"))
    }

    @Test
    fun fromDatabaseValue_defaultsToOpen() {
        assertEquals(PeerVisibility.OPEN, PeerVisibility.fromDatabaseValue(null))
        assertEquals(PeerVisibility.OPEN, PeerVisibility.fromDatabaseValue(""))
        assertEquals(PeerVisibility.OPEN, PeerVisibility.fromDatabaseValue("unknown"))
    }

    @Test
    fun toDatabaseValue_roundTripsEnumName() {
        PeerVisibility.values().forEach { visibility ->
            assertEquals(visibility.name, PeerVisibility.toDatabaseValue(visibility))
        }
    }
}
