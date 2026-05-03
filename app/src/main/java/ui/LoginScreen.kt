// LoginScreen - Activity handling user authentication and session bootstrap.
// Created by Siyabonga Popela. Edited by Thanyani Nemukumbini.
// Date: 2025-08-27
package ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import DataLayer.DatabaseRepository
import kotlinx.coroutines.launch
import utils.PermissionUtils
import utils.SessionManager

class LoginScreen : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var tvSignUp: TextView
    private lateinit var tvForgotPassword: TextView
    private lateinit var chkRememberMe: MaterialCheckBox

    private val repository by lazy { DatabaseRepository(applicationContext) }
    private val sessionManager by lazy { SessionManager(applicationContext) }
    private val requestAllPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val deniedPermissions = results.filterValues { !it }.keys
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.permissions_denied_warning),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    private var autoLoginAttempted = false

    // onCreate: inflates layout, binds views, wires listeners, and checks permissions.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initViews()
        setupClickListeners()
        ensureRuntimePermissions()
    }

    // onStart: attempts auto-login if remember-me is enabled and user exists.

    override fun onStart() {
        super.onStart()
        maybeAutoLogin()
        // Thanyani: auto-login is opt-in; short-circuit to avoid repeated DB hits.
    }

    private fun initViews() {
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvSignUp = findViewById(R.id.tvSignUp)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        chkRememberMe = findViewById(R.id.chkRememberMe)

        chkRememberMe.isChecked = sessionManager.isRememberMeEnabled()
    }

    private fun setupClickListeners() {
        btnLogin.setOnClickListener { attemptLogin() }
        tvSignUp.setOnClickListener { navigateToSignUp() }
        tvForgotPassword.setOnClickListener { showForgotPasswordDialog() }

        chkRememberMe.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setRememberMeEnabled(isChecked)
            if (!isChecked) {
                sessionManager.clearRememberedUser()
            }
        }
    }

    private fun ensureRuntimePermissions() {
        val missingPermissions = PermissionUtils.getAllMissingPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            requestAllPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun maybeAutoLogin() {
        if (autoLoginAttempted) {
            return
        }

        val rememberedId = sessionManager.getRememberedUserId()
        if (!sessionManager.isRememberMeEnabled() || rememberedId.isNullOrEmpty()) {
            return
        }

        autoLoginAttempted = true
        lifecycleScope.launch {
            // Thanyani: run off main thread so hashing + DB do not block UI.
            val currentUser = repository.getCurrentUser()
            val activeUser = when {
                currentUser?.id == rememberedId -> currentUser
                else -> repository.restoreRememberedUser(rememberedId)
            }

            if (activeUser != null) {
                Nexa.getInstance().setActiveUserId(activeUser.id)
                sessionManager.setActiveUserId(activeUser.id)
                navigateToChatsScreen()
            } else {
                autoLoginAttempted = false
                sessionManager.clearRememberedUser()
                sessionManager.setActiveUserId(null)
            }
        }
    }

    private fun attemptLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()

        if (username.isEmpty()) {
            etUsername.error = "Username is required"
            return
        }

        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            return
        }

        if (password.length < 6) {
            etPassword.error = "Password must be at least 6 characters"
            return
        }

        performLogin(username, password)
    }

    private fun performLogin(username: String, password: String) {
        lifecycleScope.launch {
            // Thanyani: run off main thread so hashing + DB do not block UI.
            setLoading(true)
            when (val result = repository.authenticateUser(username, password)) {
                is DatabaseRepository.AuthenticationResult.Success -> {
                    try {
                        Nexa.getInstance().obtainKeyManager().initializeDeviceKeys()
                    } catch (_: Exception) {
                        // Nearby init will retry later.
                    }
                    Nexa.getInstance().setActiveUserId(result.user.id)
                    sessionManager.setActiveUserId(result.user.id)
                    handleRememberMe(result.user.id)
                    navigateToChatsScreen()
                }
                DatabaseRepository.AuthenticationResult.UserNotFound -> {
                    Toast.makeText(this@LoginScreen, "Account not found", Toast.LENGTH_SHORT).show()
                }
                DatabaseRepository.AuthenticationResult.InvalidCredentials -> {
                    Toast.makeText(this@LoginScreen, "Invalid credentials", Toast.LENGTH_SHORT).show()
                }
            }
            setLoading(false)
        }
    }

    private fun handleRememberMe(userId: String) {
        val remember = chkRememberMe.isChecked
        sessionManager.setRememberMeEnabled(remember)
        if (remember) {
            sessionManager.rememberUser(userId)
        } else {
            sessionManager.clearRememberedUser()
        }
    }

    private fun setLoading(isLoading: Boolean) {
        btnLogin.isEnabled = !isLoading
        btnLogin.text = if (isLoading) getString(R.string.logging_in) else getString(R.string.login_button)
    }

    private fun navigateToChatsScreen() {
        val intent = Intent(this, ChatsScreen::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }

    private fun navigateToSignUp() {
        val intent = Intent(this, SignUpScreen::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun showForgotPasswordDialog() {
        Toast.makeText(this, "Forgot password feature coming soon", Toast.LENGTH_SHORT).show()
    }
}







