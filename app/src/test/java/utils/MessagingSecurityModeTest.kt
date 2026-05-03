package utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingSecurityModeTest {

    @Test
    fun fromPreferenceReturnsMatchingEnum() {
        assertEquals(MessagingSecurityMode.REQUIRE_TRUSTED, MessagingSecurityMode.fromPreference("REQUIRE_TRUSTED"))
    }

    @Test
    fun fromPreferenceDefaultsToOpen() {
        assertEquals(MessagingSecurityMode.OPEN, MessagingSecurityMode.fromPreference(null))
        assertEquals(MessagingSecurityMode.OPEN, MessagingSecurityMode.fromPreference(""))
        assertEquals(MessagingSecurityMode.OPEN, MessagingSecurityMode.fromPreference("something"))
    }
}
