package utils

import DataLayer.UserEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileUtilsTest {

    @Test
    fun buildDisplayNamePrefersDeviceInfoNames() {
        val json = """{"firstName":"Ada","lastName":"Lovelace"}"""
        val user = UserEntity(username = "ada", passwordHash = "hash", deviceInfo = json)
        assertEquals("Ada Lovelace", ProfileUtils.buildDisplayName(user))
    }

    @Test
    fun buildDisplayNameFallsBackToUsernameWhenBlank() {
        val user = UserEntity(username = "ada", passwordHash = "hash", deviceInfo = "{}")
        assertEquals("ada", ProfileUtils.buildDisplayName(user))
    }

    @Test
    fun extractProfileNamesParsesJson() {
        val json = """{"firstName":"Grace","lastName":"Hopper"}"""
        val (first, last) = ProfileUtils.extractProfileNames(json)
        assertEquals("Grace", first)
        assertEquals("Hopper", last)
    }

    @Test
    fun extractProfileNamesHandlesInvalidJson() {
        val (first, last) = ProfileUtils.extractProfileNames("not-json")
        assertNull(first)
        assertNull(last)
    }
}
