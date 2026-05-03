// QRCodeGenerator - Generates QR codes for peer sharing.
// Created by Siyabonga Popela. Edited by Tasima Hapazari.
// Date: 2025-09-12
package com.example.nexausingnearby.utils

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.util.UUID

class QRCodeGenerator {

    /**
     * Generates a QR code Bitmap from a UUID.
     *
     * @param userId The UUID to encode in the QR code.
     * @param width The desired width of the QR code image in pixels.
     * @param height The desired height of the QR code image in pixels.
     * @return A Bitmap representing the QR code, or null if generation fails.
     */
    // generateQRCodeBitmap: encodes the user id into a QR bitmap of the specified size.
fun generateQRCodeBitmap(userId: UUID, width: Int = 512, height: Int = 512): Bitmap? {
        return generateQRCodeBitmapFromString(userId.toString(), width, height)
    }

    /**
     * Generates a QR code Bitmap from a String.
     *
     * @param text The String to encode in the QR code.
     * @param width The desired width of the QR code image in pixels.
     * @param height The desired height of the QR code image in pixels.
     * @return A Bitmap representing the QR code, or null if generation fails.
     */
    // generateQRCodeBitmapFromString: encodes arbitrary text into a QR bitmap.
fun generateQRCodeBitmapFromString(text: String, width: Int = 512, height: Int = 512): Bitmap? {
        //text == userID
        return try {
            // Siyabonga: wrap ZXing so callers don't juggle writer + matrix themselves.
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                width,
                height
            )
            val barcodeEncoder = BarcodeEncoder()
            barcodeEncoder.createBitmap(bitMatrix)
        } catch (e: Exception) {
            // Log the exception or handle it as appropriate
            e.printStackTrace()
            null
        }
    }

    /**
     * Verifies if the scanned QR code content matches the given UUID.
     *
     * @param scannedQRCodeContent The content obtained from scanning a QR code.
     * @param expectedUserId The UUID to compare against.
     * @return True if the scanned content matches the string representation of the expectedUserId, false otherwise.
     */
    // verifyQRCode: checks scanned QR content by comparing embedded UUID against expected user id.
fun verifyQRCode(scannedQRCodeContent: String, expectedUserId: UUID): Boolean {
        return scannedQRCodeContent == expectedUserId.toString()
    }

    /**
     * Verifies if the scanned QR code content matches the given String.
     *
     * @param scannedQRCodeContent The content obtained from scanning a QR code.
     * @param expectedText The String to compare against.
     * @return True if the scanned content matches the expectedText, false otherwise.
     */
    // verifyQRCodeFromString: validates raw text QR payload against expected string.
fun verifyQRCodeFromString(scannedQRCodeContent: String, expectedText: String): Boolean {
        return scannedQRCodeContent == expectedText
    }



}



