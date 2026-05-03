// SignUpScreen - Activity guiding new user registration and onboarding.
// Created by Siyabonga Popela. Edited by Thanyani Nemukumbini.
// Date: 2025-08-28
package ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.google.android.material.button.MaterialButton
import DataLayer.DatabaseRepository
import kotlinx.coroutines.launch
import utils.SessionManager

class SignUpScreen : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var tvLogin: TextView

    private val repository by lazy { DatabaseRepository(applicationContext) }
    private val sessionManager by lazy { SessionManager(applicationContext) }

    // onCreate: sets up layout, binds views, and attaches interaction handlers.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up)
        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnSignUp = findViewById(R.id.btnSignUp)
        tvLogin = findViewById(R.id.tvLogin)
    }

    private fun setupClickListeners() {
        btnSignUp.setOnClickListener { attemptSignUp() }
        tvLogin.setOnClickListener { navigateToLogin() }
    }

    private fun attemptSignUp() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val confirmPassword = etConfirmPassword.text.toString()

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

        if (confirmPassword.isEmpty()) {
            etConfirmPassword.error = "Please confirm your password"
            return
        }

        if (password != confirmPassword) {
            etConfirmPassword.error = "Passwords do not match"
            return
        }

        createAccount(username, password)
    }

    private fun createAccount(username: String, password: String) {
        lifecycleScope.launch {
            // Thanyani: wrap repository call so Room writes happen off the main thread.
            setLoading(true)
            when (val result = repository.registerUser(username, password)) {
                is DatabaseRepository.RegistrationResult.Success -> {
                    try {
                        Nexa.getInstance().obtainKeyManager().initializeDeviceKeys()
                    } catch (_: Exception) {
                        // Key initialization will be retried the next time services start.
                    }
                    Nexa.getInstance().setActiveUserId(result.user.id)
                    sessionManager.setActiveUserId(result.user.id)

                    Toast.makeText(
                        this@SignUpScreen,
                        "Account created successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                    sessionManager.setRememberMeEnabled(true)
                    sessionManager.rememberUser(result.user.id)
                    navigateToChats()
                }
                DatabaseRepository.RegistrationResult.UsernameTaken -> {
                    etUsername.error = "That username is already taken"
                }
                DatabaseRepository.RegistrationResult.InvalidInput -> {
                    Toast.makeText(
                        this@SignUpScreen,
                        "Please enter a valid username",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                DatabaseRepository.RegistrationResult.Failure -> {
                    Toast.makeText(
                        this@SignUpScreen,
                        "Unable to create account. Please try again",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            setLoading(false)
        }
    }

    private fun setLoading(isLoading: Boolean) {
        btnSignUp.isEnabled = !isLoading
        btnSignUp.text = if (isLoading) getString(R.string.creating_account) else getString(R.string.sign_up_button)
    }

    private fun navigateToChats() {
        val intent = Intent(this, ChatsScreen::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginScreen::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        finish()
    }

    // onBackPressed: defers to default behavior then applies slide transition.

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}






