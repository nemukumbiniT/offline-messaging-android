// ProfileScreen - Activity allowing users to review and edit profile details.
// Created by Siyabonga Popela. Edited by Thanyani Nemukumbini.
// Date: 2025-09-16
package ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import DataLayer.DatabaseRepository
import DataLayer.UserEntity
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import utils.SessionManager

class ProfileScreen : AppCompatActivity() {

    private lateinit var profileImage: CircleImageView
    private lateinit var etUsername: TextInputEditText
    private lateinit var etFirstName: TextInputEditText
    private lateinit var etLastName: TextInputEditText
    private lateinit var etCurrentPassword: TextInputEditText
    private lateinit var etNewPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnChangePassword: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnBack: ImageView

    private var selectedImageUri: Uri? = null
    private var currentUser: UserEntity? = null
    private var currentProfileImage: ByteArray? = null

    private val repository by lazy { DatabaseRepository(applicationContext) }
    private val sessionManager by lazy { SessionManager(applicationContext) }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                profileImage.setImageURI(uri)
            }
        }
    }

    // onCreate: prepares layout, loads profile data, and sets up UI interactions.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        initViews()
        setupClickListeners()
        loadProfileData()
    }

    private fun initViews() {
        profileImage = findViewById(R.id.profileImage)
        etUsername = findViewById(R.id.etUsername)
        etFirstName = findViewById(R.id.etFirstName)
        etLastName = findViewById(R.id.etLastName)
        etCurrentPassword = findViewById(R.id.etCurrentPassword)
        etNewPassword = findViewById(R.id.etNewPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnSave = findViewById(R.id.btnSave)
        btnChangePassword = findViewById(R.id.btnChangePassword)
        btnLogout = findViewById(R.id.btnLogout)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun setupClickListeners() {
        profileImage.setOnClickListener { openGallery() }
        btnSave.setOnClickListener { saveProfile() }
        btnChangePassword.setOnClickListener { changePassword() }
        btnLogout.setOnClickListener { performLogout() }
        btnBack.setOnClickListener { finish() }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncher.launch(intent)
    }

    private fun loadProfileData() {
        lifecycleScope.launch {
            // Thanyani: fetch from Room asynchronously so toolbar animations stay smooth.
            val user = repository.getCurrentUser()
            if (user == null) {
                Toast.makeText(this@ProfileScreen, getString(R.string.profile_not_logged_in), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            currentUser = user
            etUsername.setText(user.username)

            val (firstName, lastName) = parseDeviceInfo(user.deviceInfo)
            etFirstName.setText(firstName ?: "")
            etLastName.setText(lastName ?: "")

            user.profilePicture?.let { bytes ->
                currentProfileImage = bytes
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                profileImage.setImageBitmap(bitmap)
            }

            clearPasswordFields()
        }
    }

    private fun parseDeviceInfo(deviceInfo: String?): Pair<String?, String?> {
        if (deviceInfo.isNullOrBlank()) return null to null
        return try {
            val json = JSONObject(deviceInfo)
            json.optString("firstName").takeIf { it.isNotBlank() } to
                json.optString("lastName").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null to null
        }
    }

    private fun saveProfile() {
        val username = etUsername.text?.toString()?.trim().orEmpty()
        val firstName = etFirstName.text?.toString()?.trim().orEmpty()
        val lastName = etLastName.text?.toString()?.trim().orEmpty()

        if (username.isEmpty()) {
            Toast.makeText(this, getString(R.string.profile_invalid_input), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            // Thanyani: fetch from Room asynchronously so toolbar animations stay smooth.
            val imageBytes = selectedImageUri?.let { readBytesFromUri(it) } ?: currentProfileImage
            when (val result = repository.updateCurrentUserProfile(
                username = username,
                firstName = firstName.takeIf { it.isNotEmpty() },
                lastName = lastName.takeIf { it.isNotEmpty() },
                profilePicture = imageBytes
            )) {
                is DatabaseRepository.ProfileUpdateResult.Success -> {
                    currentUser = result.user
                    currentProfileImage = result.user.profilePicture
                    selectedImageUri = null
                    Nexa.getNearbyServiceInstance().refreshLocalDisplayName()
                    Toast.makeText(this@ProfileScreen, getString(R.string.profile_updated_success), Toast.LENGTH_SHORT).show()
                }
                DatabaseRepository.ProfileUpdateResult.UsernameTaken -> {
                    etUsername.error = getString(R.string.profile_username_taken)
                }
                DatabaseRepository.ProfileUpdateResult.InvalidInput -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.profile_invalid_input), Toast.LENGTH_SHORT).show()
                }
                DatabaseRepository.ProfileUpdateResult.NotLoggedIn -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.profile_not_logged_in), Toast.LENGTH_SHORT).show()
                    finish()
                }
                DatabaseRepository.ProfileUpdateResult.Failure -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.profile_update_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun changePassword() {
        val currentPassword = etCurrentPassword.text?.toString()?.trim().orEmpty()
        val newPassword = etNewPassword.text?.toString()?.trim().orEmpty()
        val confirmPassword = etConfirmPassword.text?.toString()?.trim().orEmpty()

        if (currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, getString(R.string.password_fields_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (newPassword != confirmPassword) {
            Toast.makeText(this, getString(R.string.passwords_do_not_match), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            // Thanyani: fetch from Room asynchronously so toolbar animations stay smooth.
            when (repository.changeCurrentUserPassword(currentPassword, newPassword)) {
                DatabaseRepository.PasswordChangeResult.Success -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.password_changed_success), Toast.LENGTH_SHORT).show()
                    clearPasswordFields()
                }
                DatabaseRepository.PasswordChangeResult.IncorrectPassword -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.password_incorrect), Toast.LENGTH_SHORT).show()
                }
                DatabaseRepository.PasswordChangeResult.TooShort -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.password_too_short), Toast.LENGTH_SHORT).show()
                }
                DatabaseRepository.PasswordChangeResult.NotLoggedIn -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.profile_not_logged_in), Toast.LENGTH_SHORT).show()
                    finish()
                }
                DatabaseRepository.PasswordChangeResult.Failure -> {
                    Toast.makeText(this@ProfileScreen, getString(R.string.password_change_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearPasswordFields() {
        etCurrentPassword.setText("")
        etNewPassword.setText("")
        etConfirmPassword.setText("")
    }

    private suspend fun readBytesFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun performLogout() {
        lifecycleScope.launch {
            // Thanyani: fetch from Room asynchronously so toolbar animations stay smooth.
            val ownerId = Nexa.getInstance().getActiveUserId()
            val localDeviceId = Nexa.currentDeviceID
            if (!ownerId.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        repository.cleanupConversationCache(ownerId, localDeviceId)
                        repository.cleanupOldPeers(ownerId)
                    }.onFailure { Log.w(TAG, "Failed to prune cached data", it) }
                }
            }

            repository.logoutCurrentUser()
            sessionManager.clearSession()
            sessionManager.setActiveUserId(null)

            val nexa = Nexa.getInstance()
            nexa.setActiveUserId(null)

            runCatching {
                nexa.stopNearbyServices()
                Nexa.getNearbyServiceInstance().shutdown()
            }.onFailure { Log.w(TAG, "Failed to stop Nearby services", it) }
            runCatching { Nexa.getDTNManagerInstance().stop() }
                .onFailure { Log.w(TAG, "Failed to stop DTN manager", it) }

            withContext(Dispatchers.IO) {
                runCatching { nexa.obtainKeyManager().clearKeyCache() }
                    .onFailure { Log.w(TAG, "Failed to clear key cache", it) }
            }

            val intent = Intent(this@ProfileScreen, LoginScreen::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    companion object {
        private const val TAG = "ProfileScreen"
    }
}




