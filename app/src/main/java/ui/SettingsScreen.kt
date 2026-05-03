// SettingsScreen - Activity configuring application preferences and permissions.
// Created by Siyabonga Popela. Edited by Thanyani Nemukumbini.
// Date: 2025-09-17
package ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import DataLayer.DatabaseRepository
import kotlinx.coroutines.launch
import utils.PermissionUtils
import utils.MessagingSecurityMode
import utils.SessionManager

class SettingsScreen : AppCompatActivity() {

    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var switchAutoLogin: SwitchMaterial
    private lateinit var switchRequireTrusted: SwitchMaterial
    private lateinit var switchLocation: SwitchMaterial
    private lateinit var switchCamera: SwitchMaterial
    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var switchStorage: SwitchMaterial

    private val sessionManager by lazy { SessionManager(applicationContext) }
    private val repository by lazy { DatabaseRepository(applicationContext) }
    private var currentUserId: String? = null

    // onCreate: initializes switches, loads stored preferences, and wires navigation.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bottomNavigation = findViewById(R.id.bottomNavigation)
        switchAutoLogin = findViewById(R.id.switchAutoLogin)
        switchRequireTrusted = findViewById(R.id.switchRequireTrusted)
        switchLocation = findViewById(R.id.switchLocation)
        switchCamera = findViewById(R.id.switchCamera)
        switchNotifications = findViewById(R.id.switchNotifications)
        switchStorage = findViewById(R.id.switchStorage)

        setInitialPermissionStates()
        initializeAutoLoginState()
        initializeSecurityMode()
        setupBottomNavigation()
        setupSwitchListeners()
        setupProfileClickListeners()
    }

    private fun initializeAutoLoginState() {
        switchAutoLogin.isChecked = sessionManager.isRememberMeEnabled()
        lifecycleScope.launch {
            currentUserId = repository.getCurrentUser()?.id
            if (switchAutoLogin.isChecked && currentUserId != null) {
                sessionManager.rememberUser(currentUserId!!)
            }
        }
    }

    private fun initializeSecurityMode() {
        val mode = sessionManager.getMessagingSecurityMode()
        switchRequireTrusted.isChecked = mode == MessagingSecurityMode.REQUIRE_TRUSTED
    }

    private fun setupProfileClickListeners() {
        val profileLayout = findViewById<LinearLayout>(R.id.profileLayout)
        profileLayout?.setOnClickListener {
            val intent = Intent(this, ProfileScreen::class.java)
            startActivity(intent)
        }
    }

    private fun setInitialPermissionStates() {
        switchLocation.isChecked = PermissionUtils.hasAllPermissions(
            this,
            PermissionUtils.getLocationPermissions()
        )
        switchCamera.isChecked = PermissionUtils.hasAllPermissions(
            this,
            PermissionUtils.getCameraPermissions()
        )
        switchStorage.isChecked = PermissionUtils.hasAllPermissions(
            this,
            PermissionUtils.getStoragePermissions()
        )
    }

    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.nav_settings

        bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_chats -> {
                    navigateToActivity(ChatsScreen::class.java)
                    true
                }
                R.id.navigation_add -> {
                    navigateToActivity(HomeScreen::class.java)
                    true
                }
                R.id.nav_settings -> true
                R.id.nav_qr -> {
                    startActivity(Intent(this, QRCodeActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        if (this::class.java != activityClass) {
            val intent = Intent(this, activityClass)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun setupSwitchListeners() {
        switchAutoLogin.setOnCheckedChangeListener { _, isChecked ->
            // Thanyani: keep prefs in sync immediately so LoginScreen respects toggles.
            lifecycleScope.launch {
                sessionManager.setRememberMeEnabled(isChecked)
                if (isChecked) {
                    val userId = currentUserId ?: repository.getCurrentUser()?.id
                    if (userId != null) {
                        currentUserId = userId
                        sessionManager.rememberUser(userId)
                    }
                    Toast.makeText(this@SettingsScreen, getString(R.string.auto_login_enabled), Toast.LENGTH_SHORT).show()
                } else {
                    sessionManager.clearRememberedUser()
                    Toast.makeText(this@SettingsScreen, getString(R.string.auto_login_disabled), Toast.LENGTH_SHORT).show()
                }
            }
        }

        switchRequireTrusted.setOnCheckedChangeListener { _, isChecked ->
            // Tasima: messaging security drives DTN defaults; update Nexa singleton right away.
            val mode = if (isChecked) {
                MessagingSecurityMode.REQUIRE_TRUSTED
            } else {
                MessagingSecurityMode.OPEN
            }
            Nexa.getInstance().updateMessagingSecurityMode(mode)
            val message = if (isChecked) {
                getString(R.string.settings_require_trusted_enabled)
            } else {
                getString(R.string.settings_open_enabled)
            }
            Toast.makeText(this@SettingsScreen, message, Toast.LENGTH_SHORT).show()
        }

        switchLocation.setOnCheckedChangeListener { _, isChecked ->
            // Siyabonga: this surface only mirrors OS state; actual request flows happen in activities.
            if (isChecked) {
                val missing = PermissionUtils.getMissingPermissions(
                    this,
                    PermissionUtils.getLocationPermissions()
                )
                if (missing.isEmpty()) {
                    Toast.makeText(this, "Location already granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.permissions_denied_warning), Toast.LENGTH_LONG).show()
                    switchLocation.isChecked = false
                }
            } else {
                Toast.makeText(this, "Location disabled in system settings", Toast.LENGTH_LONG).show()
            }
        }

        switchCamera.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val missing = PermissionUtils.getMissingPermissions(
                    this,
                    PermissionUtils.getCameraPermissions()
                )
                if (missing.isEmpty()) {
                    Toast.makeText(this, "Camera already granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.permissions_denied_warning), Toast.LENGTH_LONG).show()
                    switchCamera.isChecked = false
                }
            } else {
                Toast.makeText(this, "Camera disabled in system settings", Toast.LENGTH_LONG).show()
            }
        }

        switchStorage.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val missing = PermissionUtils.getMissingPermissions(
                    this,
                    PermissionUtils.getStoragePermissions()
                )
                if (missing.isEmpty()) {
                    Toast.makeText(this, "Storage access already granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.permissions_denied_warning), Toast.LENGTH_LONG).show()
                    switchStorage.isChecked = false
                }
            } else {
                Toast.makeText(this, "Storage disabled in system settings", Toast.LENGTH_LONG).show()
            }
        }

        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notifications disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // onResume: refreshes permission toggles to reflect any changes made externally.

    override fun onResume() {
        super.onResume()
        setInitialPermissionStates()
    }
}







