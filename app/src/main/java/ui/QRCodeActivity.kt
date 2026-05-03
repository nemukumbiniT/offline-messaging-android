// QRCodeActivity - Activity launching QR scanner for quick peer onboarding.
// Created by Siyabonga Popela. Edited by Tasima Hapazari.
// Date: 2025-09-14
package ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.example.nexausingnearby.databinding.ActivityQrcodeBinding
import utils.PermissionUtils
import com.example.nexausingnearby.utils.QRCodeGenerator
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.io.FileOutputStream

class QRCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrcodeBinding
    private val qrCodeGenerator = QRCodeGenerator()
    private var currentUserQRBitmap: Bitmap? = null
    private var connectingDialog: AlertDialog? = null

    private val TAG = "QRCodeActivity"

    // onCreate: inflates layout, sets up tabs, and prepares QR generator + scanner UI.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrcodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        generateUserQRCode()
    }

    private fun setupUI() {
        // Toolbar setup
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "QR Code"
        }

        // Button click listeners
        binding.btnScanQR.setOnClickListener {
            checkCameraPermissionAndScan()
        }

        binding.btnShareQR.setOnClickListener {
            shareQRCode()
        }

        binding.btnRegenerateQR.setOnClickListener {
            generateUserQRCode()
        }

        // Tab layout setup
        binding.tabMyQR.setOnClickListener {
            showMyQRTab()
        }

        binding.tabScanQR.setOnClickListener {
            showScanQRTab()
        }

        // Initial tab selection
        showMyQRTab()
    }

    private fun showMyQRTab() {
        binding.tabMyQR.isSelected = true
        binding.tabScanQR.isSelected = false

        binding.layoutMyQR.visibility = android.view.View.VISIBLE
        binding.layoutScanQR.visibility = android.view.View.GONE

        // Update tab appearance
        binding.tabMyQR.setBackgroundResource(R.drawable.tab_selected_background)
        binding.tabScanQR.setBackgroundResource(R.drawable.tab_unselected_background)

        // Update text colors
        binding.tabMyQR.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        binding.tabScanQR.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun showScanQRTab() {
        binding.tabMyQR.isSelected = false
        binding.tabScanQR.isSelected = true

        binding.layoutMyQR.visibility = android.view.View.GONE
        binding.layoutScanQR.visibility = android.view.View.VISIBLE

        // Update tab appearance
        binding.tabMyQR.setBackgroundResource(R.drawable.tab_unselected_background)
        binding.tabScanQR.setBackgroundResource(R.drawable.tab_selected_background)

        // Update text colors
        binding.tabMyQR.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tabScanQR.setTextColor(ContextCompat.getColor(this, android.R.color.white))
    }

    private fun generateUserQRCode() {
        lifecycleScope.launch {
            try {
                // Show loading
                binding.progressBarQR.visibility = android.view.View.VISIBLE
                binding.imgUserQR.visibility = android.view.View.GONE
                binding.btnShareQR.isEnabled = false
                binding.btnRegenerateQR.isEnabled = false

                val userID = withContext(Dispatchers.IO) {
                    Nexa.currentDeviceID
                }

                Log.d(TAG, "Generating QR code for user ID: $userID")

                val qrBitmap = withContext(Dispatchers.IO) {
                    qrCodeGenerator.generateQRCodeBitmapFromString(
                        text = userID,
                        width = 512,
                        height = 512
                    )
                }

                if (qrBitmap != null) {
                    // Recycle old bitmap if exists
                    currentUserQRBitmap?.recycle()
                    currentUserQRBitmap = qrBitmap

                    binding.imgUserQR.setImageBitmap(qrBitmap)
                    binding.tvUserIdDisplay.text = "User ID: ${userID.take(8)}..."

                    binding.btnShareQR.isEnabled = true
                    Log.d(TAG, "QR code generated successfully")
                } else {
                    Log.e(TAG, "Failed to generate QR code bitmap")
                    Toast.makeText(this@QRCodeActivity, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error generating QR code", e)
                Toast.makeText(this@QRCodeActivity, "Error generating QR code: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                // Hide loading
                binding.progressBarQR.visibility = android.view.View.GONE
                binding.imgUserQR.visibility = android.view.View.VISIBLE
                binding.btnRegenerateQR.isEnabled = true
            }
        }
    }

    private fun checkCameraPermissionAndScan() {
        val missing = PermissionUtils.getMissingPermissions(this, PermissionUtils.getCameraPermissions())
        if (missing.isEmpty()) {
            startQRScanner()
        } else {
            showCameraPermissionInfo()
        }
    }
    private fun showCameraPermissionInfo() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Required")
            .setMessage(getString(R.string.permissions_denied_warning))
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun startQRScanner() {
        try {
            val integrator = IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        // Tasima: limit formats for speed—scanning multi-format slowed down older devices.
            integrator.setPrompt("Scan a user's QR code to connect")
            integrator.setCameraId(0) // Use rear camera
            integrator.setBeepEnabled(true)
            integrator.setBarcodeImageEnabled(true)
            integrator.setOrientationLocked(false)
            integrator.initiateScan()

            Log.d(TAG, "QR scanner started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QR scanner", e)
            Toast.makeText(this, "Failed to start QR scanner: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // onActivityResult: handles ZXing scan results and routes outcome accordingly.

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)

        if (result != null) {
            if (result.contents == null) {
                Log.d(TAG, "QR scan cancelled")
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "QR scan result: ${result.contents}")
                handleScannedQRCode(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleScannedQRCode(scannedContent: String) {
        try {
            val currentUserID = Nexa.currentDeviceID

            Log.d(TAG, "Processing scanned QR: $scannedContent")
            Log.d(TAG, "Current user ID: $currentUserID")

            // Verify the QR code is not the current user's own QR
            if (qrCodeGenerator.verifyQRCodeFromString(scannedContent, currentUserID)) {
                showSelfScanDialog()
                return
            }

            // Validate scanned content format
            if (scannedContent.isBlank() || scannedContent.length < 8) {
                Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_SHORT).show()
                return
            }

            // Try to parse as UUID first, then fallback to string verification
            val isValidQR = try {
                UUID.fromString(scannedContent) // Will throw if not valid UUID
                true
            } catch (e: IllegalArgumentException) {
                // If not a valid UUID, check if it looks like a device ID
                scannedContent.matches(Regex("^[a-fA-F0-9-]{8,}$")) || scannedContent.length >= 16
            }

            if (isValidQR) {
                showConnectionConfirmDialog(scannedContent)
            } else {
                showInvalidQRDialog(scannedContent)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling scanned QR code", e)
            Toast.makeText(this, "Error processing QR code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSelfScanDialog() {
        AlertDialog.Builder(this)
            .setTitle("That's Your QR Code!")
            .setMessage("You scanned your own QR code. Ask someone else to scan this code to connect with you, or scan their QR code instead.")
            .setPositiveButton("Scan Another Code") { _, _ ->
                startQRScanner()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showInvalidQRDialog(scannedContent: String) {
        AlertDialog.Builder(this)
            .setTitle("Invalid QR Code")
            .setMessage("The scanned QR code doesn't appear to be a valid Nexa user ID. Please make sure you're scanning a Nexa QR code.")
            .setPositiveButton("Scan Again") { _, _ ->
                startQRScanner()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConnectionConfirmDialog(targetUserID: String) {
        val shortId = if (targetUserID.length > 12) {
            targetUserID.take(8) + "..." + targetUserID.takeLast(4)
        } else {
            targetUserID
        }

        AlertDialog.Builder(this)
            .setTitle("Connect to User")
            .setMessage("Do you want to connect and start chatting with user:\n\n$shortId?")
            .setPositiveButton("Connect") { _, _ ->
                initiateConnectionAndChat(targetUserID)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initiateConnectionAndChat(targetUserID: String) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Initiating connection to user: $targetUserID")

                // Show connecting dialog
                connectingDialog = AlertDialog.Builder(this@QRCodeActivity)
                    .setTitle("Connecting...")
                    .setMessage("Attempting to connect to user...")
                    .setCancelable(true)
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()

                connectingDialog?.show()

                // Try to connect via Nearby Service
                val connectionResult = withContext(Dispatchers.IO) {
                    try {
                        val nearbyService = Nexa.getNearbyServiceInstance()

                        // Request connection to the target endpoint encoded in the QR value
                        nearbyService.requestConnection(targetUserID)

                        // Wait for connection attempt with timeout
                        var attempts = 0
                        val maxAttempts = 10

                        while (attempts < maxAttempts) {
                            delay(500) // Check every 500ms
                            if (nearbyService.isEndpointConnected(targetUserID) || nearbyService.isDeviceConnected(targetUserID)) {
                                return@withContext ConnectionResult.SUCCESS
                            }
                            attempts++
                        }

                        ConnectionResult.TIMEOUT
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during connection attempt", e)
                        ConnectionResult.ERROR
                    }
                }

                connectingDialog?.dismiss()

                when (connectionResult) {
                    ConnectionResult.SUCCESS -> {
                        Log.d(TAG, "Connection successful, opening chat")
                        Toast.makeText(this@QRCodeActivity, "Connected successfully!", Toast.LENGTH_SHORT).show()
                        openChatWithUser(targetUserID, true)
                    }
                    ConnectionResult.TIMEOUT -> {
                        Log.w(TAG, "Connection attempt timed out")
                        showConnectionFailedDialog(targetUserID)
                    }
                    ConnectionResult.ERROR -> {
                        Log.e(TAG, "Connection attempt failed with error")
                        showConnectionErrorDialog(targetUserID)
                    }
                }

            } catch (e: Exception) {
                connectingDialog?.dismiss()
                Log.e(TAG, "Error initiating connection", e)
                Toast.makeText(this@QRCodeActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showConnectionFailedDialog(targetUserID: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Timeout")
            .setMessage(
                """
                    Unable to connect to user within the timeout period. This could be because:

                    - The user is not nearby
                    - Bluetooth/WiFi is disabled
                    - The user is not running the app

                    You can still start a chat - messages will be delivered when the user comes online.
                """.trimIndent()
            )
            .setPositiveButton("Start Chat Anyway") { _, _ ->
                openChatWithUser(targetUserID, false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConnectionErrorDialog(targetUserID: String) {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage(
                """
                    There was an error trying to connect to the user. This might be a temporary issue.

                    Would you like to start a chat anyway? Messages will be delivered when possible.
                """.trimIndent()
            )
            .setPositiveButton("Start Chat") { _, _ ->
                openChatWithUser(targetUserID, false)
            }
            .setNeutralButton("Try Again") { _, _ ->
                initiateConnectionAndChat(targetUserID)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openChatWithUser(targetUserID: String, isConnected: Boolean) {
        val shortName = "User-${targetUserID.take(8)}"
        val intent = Intent(this, InChatsActivity::class.java).apply {
            putExtra("FRIEND_NAME", shortName)
            putExtra("FRIEND_ID", targetUserID)
            putExtra("CONNECTED_VIA_QR", true)
            putExtra("IS_CURRENTLY_CONNECTED", isConnected)
        }
        startActivity(intent)
        finish() // Close QR activity
    }

    private fun shareQRCode() {
        currentUserQRBitmap?.let {
            val missing = PermissionUtils.getMissingPermissions(this, PermissionUtils.getStoragePermissions())
            if (missing.isNotEmpty()) {
                showStoragePermissionInfo()
                return
            }
            shareQRCodeImage()
        } ?: run {
            Toast.makeText(this, "No QR code to share", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStoragePermissionInfo() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage(getString(R.string.permissions_denied_warning))
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun shareQRCodeImage() {
        currentUserQRBitmap?.let { bitmap ->
            lifecycleScope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) {
                        saveImageToCache(bitmap)
                    }

                    if (uri != null) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, "Scan this QR code to connect with me on Nexa!")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share QR Code"))
                    } else {
                        Toast.makeText(this@QRCodeActivity, "Failed to save QR code for sharing", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sharing QR code", e)
                    Toast.makeText(this@QRCodeActivity, "Failed to share QR code: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveImageToCache(bitmap: Bitmap): Uri? {
        return try {
            val cachePath = File(cacheDir, "images")
            cachePath.mkdirs()

            val file = File(cachePath, "nexa_qr_code.png")
            val fileOutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
            fileOutputStream.flush()
            fileOutputStream.close()

            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image to cache", e)
            null
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
    // onSupportNavigateUp: handles toolbar up navigation by delegating to onBackPressed.
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    // onDestroy: cleans up any outstanding scanner state before activity finishes.

    override fun onDestroy() {
        super.onDestroy()
        connectingDialog?.dismiss()
        currentUserQRBitmap?.recycle()
        currentUserQRBitmap = null
    }

    private enum class ConnectionResult {
        SUCCESS, TIMEOUT, ERROR
    }
}










