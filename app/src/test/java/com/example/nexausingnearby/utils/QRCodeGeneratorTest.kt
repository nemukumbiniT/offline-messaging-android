package com.example.nexausingnearby.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QRCodeGeneratorTest {

    private val generator = QRCodeGenerator()

    @Test
    fun verifyQrCodeMatchesUuidString() {
        val uuid = UUID.randomUUID()
        assertTrue(generator.verifyQRCode(uuid.toString(), uuid))
        assertFalse(generator.verifyQRCode("different", uuid))
    }

    @Test
    fun verifyQrCodeFromStringMatchesExpected() {
        assertTrue(generator.verifyQRCodeFromString("hello", "hello"))
        assertFalse(generator.verifyQRCodeFromString("hello", "world"))
    }
}
