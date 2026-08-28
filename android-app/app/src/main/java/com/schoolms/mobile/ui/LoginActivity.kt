package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.schoolms.mobile.R
import com.schoolms.mobile.data.FlaskEmailGateway
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging

class LoginActivity : BaseActivity() {
    private val loginHandler = Handler(Looper.getMainLooper())
    private lateinit var roleDropdown: MaterialAutoCompleteTextView
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var biometricLoginButton: MaterialButton
    private lateinit var registerButton: MaterialButton
    private lateinit var forgotPasswordButton: MaterialButton
    private lateinit var updateCard: MaterialCardView
    private lateinit var updateTitle: TextView
    private lateinit var updateBody: TextView
    private lateinit var updateVersionText: TextView
    private lateinit var updateActionButton: MaterialButton
    private var loginAttemptId = 0
    private var loginInProgress = false
    private var forceUpdateRequired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        roleDropdown = findViewById(R.id.roleDropdown)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        biometricLoginButton = findViewById(R.id.biometricLoginButton)
        registerButton = findViewById(R.id.registerButton)
        registerButton.text = "Activate account / first login"
        forgotPasswordButton = findViewById(R.id.forgotPasswordButton)
        updateCard = findViewById(R.id.loginUpdateCard)
        updateTitle = findViewById(R.id.loginUpdateTitle)
        updateBody = findViewById(R.id.loginUpdateBody)
        updateVersionText = findViewById(R.id.loginUpdateVersionText)
        updateActionButton = findViewById(R.id.loginUpdateActionButton)

        bindAppUpdateCard()
        if (!AppUpdateSupport.isForceUpdateRequired(this) && SessionManager.restoreSessionFromCache()) {
            startActivity(Intent(this, MainDashboardActivity::class.java))
            finish()
            return
        }

        val roles = resources.getStringArray(R.array.roles)
        roleDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, roles))
        roleDropdown.setText(roles.first(), false)
        roleDropdown.setOnClickListener { roleDropdown.showDropDown() }
        roleDropdown.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) roleDropdown.showDropDown()
        }
        biometricLoginButton.visibility = if (SessionManager.hasBiometricUnlock() && biometricIsAvailable()) View.VISIBLE else View.GONE
        biometricLoginButton.setOnClickListener { authenticateForBiometricLogin() }
        registerButton.setOnClickListener {
            if (AppUpdateSupport.isForceUpdateRequired(this)) {
                Toast.makeText(this, "Upgrade the app before continuing.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, RegistrationActivity::class.java))
        }
        forgotPasswordButton.setOnClickListener {
            if (AppUpdateSupport.isForceUpdateRequired(this)) {
                Toast.makeText(this, "Upgrade the app before continuing.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, PasswordRecoveryActivity::class.java))
        }
        animateContentEntrance(
            findViewById(R.id.loginHeroCard),
            updateCard,
            findViewById(R.id.loginFormCard),
            roleDropdown,
            usernameInput,
            passwordInput,
            loginButton,
            biometricLoginButton,
            registerButton,
            forgotPasswordButton
        )

        loginButton.setOnClickListener {
            if (AppUpdateSupport.isForceUpdateRequired(this)) {
                Toast.makeText(this, "Upgrade the app before logging in.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val role = Role.fromLabel(roleDropdown.text.toString())
            val username = usernameInput.text?.toString().orEmpty().trim()
            val password = passwordInput.text?.toString().orEmpty()

            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val attemptId = ++loginAttemptId
            loginInProgress = true
            setLoginPending(true)
            loginHandler.postDelayed({
                if (attemptId == loginAttemptId && loginInProgress) {
                    loginAttemptId++
                    loginInProgress = false
                    setLoginPending(false)
                    Toast.makeText(this, "Login is taking too long. Check your internet connection and try again.", Toast.LENGTH_LONG).show()
                }
            }, LOGIN_TIMEOUT_MS)
            if (username.contains("@")) {
                signInSharedFirebaseEmail(username, password, attemptId)
            } else SessionManager.signIn(role, username, password) { result ->
                runOnUiThread {
                    if (attemptId != loginAttemptId || !loginInProgress) return@runOnUiThread
                    loginHandler.removeCallbacksAndMessages(null)
                    loginInProgress = false
                    setLoginPending(false)
                    result.onSuccess {
                        offerBiometricUnlockThenOpenDashboard()
                    }.onFailure { error ->
                        Toast.makeText(
                            this,
                            error.message ?: "Login failed. Check your account details and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun signInSharedFirebaseEmail(email: String, password: String, attemptId: Int) {
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).addOnSuccessListener { credential ->
            credential.user?.reload()?.addOnSuccessListener {
                val user = credential.user
                if (user == null || !user.isEmailVerified) { completeSharedLogin(attemptId, Result.failure(IllegalArgumentException("Please verify your email before logging in."))); return@addOnSuccessListener }
                user.getIdToken(true).addOnSuccessListener { token -> FlaskEmailGateway.linkedProfiles(token.token.orEmpty()) { result -> runOnUiThread {
                    result.onSuccess { profiles ->
                        if (profiles.isEmpty()) completeSharedLogin(attemptId, Result.failure(IllegalArgumentException("No school profile is linked to this Firebase email.")))
                        else if (profiles.size == 1) selectSharedProfile(token.token.orEmpty(), profiles.first(), attemptId)
                        else AlertDialog.Builder(this).setTitle("Choose school profile").setItems(profiles.map { "${it.fullName} — ${it.identifier} (${it.role})" }.toTypedArray()) { _, index -> selectSharedProfile(token.token.orEmpty(), profiles[index], attemptId) }.show()
                    }.onFailure { completeSharedLogin(attemptId, Result.failure(it)) }
                } } }
            }
        }.addOnFailureListener { completeSharedLogin(attemptId, Result.failure(it)) }
    }

    private fun selectSharedProfile(token: String, profile: FlaskEmailGateway.LinkedProfile, attemptId: Int) {
        FlaskEmailGateway.selectProfile(token, profile.id) { selected -> runOnUiThread { selected.onSuccess {
            SessionManager.selectAuthorizedProfile(profile.id)
            SessionManager.signInLinkedFirebaseProfile(Role.fromLabel(profile.role), profile.identifier) { result ->
                if (result.isSuccess) {
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { deviceToken ->
                        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.addOnSuccessListener { currentToken ->
                            FlaskEmailGateway.registerFcmToken(currentToken.token.orEmpty(), profile.id, deviceToken) { }
                        }
                    }
                }
                completeSharedLogin(attemptId, result)
            }
        }.onFailure { completeSharedLogin(attemptId, Result.failure(it)) } } }
    }

    private fun completeSharedLogin(attemptId: Int, result: Result<com.schoolms.mobile.data.User>) {
        if (attemptId != loginAttemptId || !loginInProgress) return
        loginHandler.removeCallbacksAndMessages(null); loginInProgress=false; setLoginPending(false)
        result.onSuccess {
            registerSingleProfileTokenIfNeeded()
            offerBiometricUnlockThenOpenDashboard()
        }.onFailure { Toast.makeText(this, it.message ?: "Login failed.", Toast.LENGTH_LONG).show() }
    }

    private fun registerSingleProfileTokenIfNeeded() {
        if (SessionManager.activeProfileId != null) return
        val firebaseUser = FirebaseAuth.getInstance().currentUser ?: return
        firebaseUser.getIdToken(false).addOnSuccessListener { idToken ->
            FlaskEmailGateway.linkedProfiles(idToken.token.orEmpty()) { profilesResult -> runOnUiThread {
                val profile = profilesResult.getOrNull()?.singleOrNull() ?: return@runOnUiThread
                FlaskEmailGateway.selectProfile(idToken.token.orEmpty(), profile.id) { selected ->
                    if (selected.isSuccess) {
                        SessionManager.selectAuthorizedProfile(profile.id)
                        FirebaseMessaging.getInstance().token.addOnSuccessListener { deviceToken ->
                            FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.addOnSuccessListener { freshToken ->
                                FlaskEmailGateway.registerFcmToken(freshToken.token.orEmpty(), profile.id, deviceToken) { }
                            }
                        }
                    }
                }
            } }
        }
    }

    override fun onDestroy() {
        loginHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setLoginPending(isPending: Boolean) {
        val inputsEnabled = !isPending && !forceUpdateRequired
        roleDropdown.isEnabled = inputsEnabled
        usernameInput.isEnabled = inputsEnabled
        passwordInput.isEnabled = inputsEnabled
        loginButton.isEnabled = inputsEnabled
        biometricLoginButton.isEnabled = inputsEnabled
        registerButton.isEnabled = inputsEnabled
        forgotPasswordButton.isEnabled = inputsEnabled
        loginButton.text = if (isPending) "Signing in..." else getString(R.string.login)
    }

    private fun biometricIsAvailable(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticateForBiometricLogin() {
        if (!SessionManager.hasBiometricUnlock()) {
            biometricLoginButton.visibility = View.GONE
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (SessionManager.restoreSessionFromCache(afterBiometric = true)) {
                        openDashboard()
                    } else {
                        SessionManager.disableBiometricUnlock()
                        biometricLoginButton.visibility = View.GONE
                        Toast.makeText(this@LoginActivity, "Your session expired. Sign in with your password.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        prompt.authenticate(biometricPromptInfo("Unlock School app"))
    }

    private fun offerBiometricUnlockThenOpenDashboard() {
        if (!biometricIsAvailable()) {
            openDashboard()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enable biometric login?")
            .setMessage("Use your enrolled fingerprint or face to unlock this app on this device. You can still log out at any time.")
            .setPositiveButton("Enable") { _, _ -> authenticateToEnableBiometrics() }
            .setNegativeButton("Not now") { _, _ -> openDashboard() }
            .setOnCancelListener { openDashboard() }
            .show()
    }

    private fun authenticateToEnableBiometrics() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    SessionManager.enableBiometricUnlock()
                    openDashboard()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    openDashboard()
                }
            }
        )
        prompt.authenticate(biometricPromptInfo("Confirm biometric login"))
    }

    private fun biometricPromptInfo(title: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use your enrolled fingerprint or face")
            .setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
            .setNegativeButtonText("Use password")
            .build()

    private fun openDashboard() {
        startActivity(Intent(this, MainDashboardActivity::class.java))
        finish()
    }

    override fun onRepositoryChanged() {
        if (::updateCard.isInitialized) {
            bindAppUpdateCard()
        }
    }

    private fun bindAppUpdateCard() {
        val notice = AppUpdateSupport.activeNotice()
        val (installedVersion, installedVersionName) = AppUpdateSupport.installedVersionInfo(this)
        val forceRequired = AppUpdateSupport.isForceUpdateRequired(this, notice)
        val outdated = AppUpdateSupport.isOutdated(this, notice)
        forceUpdateRequired = forceRequired

        updateCard.visibility = if (notice == null) View.GONE else View.VISIBLE
        if (notice == null) {
            updateCard.strokeColor = getColor(R.color.stroke_soft)
        }

        updateTitle.text = when {
            notice == null -> "Upgrade app"
            forceRequired -> notice.title.ifBlank { "Upgrade required" }
            else -> notice.title.ifBlank { "App upgrade available" }
        }
        updateBody.text = when {
            notice == null -> "No upgrade is published right now. If the school releases a new APK, you can open it from here before login."
            forceRequired -> notice.subtitle.ifBlank { "A newer version is required before this app can be used." }
            else -> notice.subtitle.ifBlank { "A newer version is available. Download and install it here before login." }
        }
        updateVersionText.text = when {
            notice == null -> "Installed version: $installedVersionName ($installedVersion)"
            notice.minimumVersionCode > 0 -> "Installed: $installedVersionName ($installedVersion) | Required: ${notice.minimumVersionCode}"
            else -> "Installed version: $installedVersionName ($installedVersion)"
        }
        updateActionButton.text = when {
            notice == null -> "No upgrade"
            forceRequired -> notice.buttonText.ifBlank { "Upgrade now" }
            outdated -> notice.buttonText.ifBlank { "Upgrade now" }
            else -> notice.buttonText.ifBlank { "Update now" }
        }
        updateActionButton.isEnabled = notice != null
        updateActionButton.setOnClickListener {
            AppUpdateSupport.openUpdateAction(this, notice)
        }

        setLoginPending(loginInProgress)
        updateCard.strokeColor = getColor(if (forceRequired) R.color.dashboard_red else R.color.stroke_soft)
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 20_000L
        const val BIOMETRIC_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK
    }
}
