package com.schoolms.mobile.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.schoolms.mobile.firebase.MessagingTopics
import com.schoolms.mobile.util.PhoneNumberSupport

object SessionManager {
    private const val SESSION_PREFS = "schoolhub_session"
    private const val SESSION_DURATION_MS = 8 * 60 * 60 * 1000L
    private lateinit var appContext: Context
    private lateinit var secureStore: SecureSessionStore

    var currentUser: User? = null
    /** Flask profile ID, required for private FCM audience checks (UID alone is not enough). */
    var activeProfileId: Int? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        secureStore = SecureSessionStore(appContext)
        // Old releases stored the actual password here. Never migrate it.
        prefs().edit().clear().apply()
        currentUser = null
        activeProfileId = null
    }

    fun logout() {
        MessagingTopics.refreshUserTopics(null)
        runCatching { FirebaseAuth.getInstance().signOut() }
        currentUser = null
        activeProfileId = null
        secureStore.clear()
    }

    fun signIn(
        role: Role,
        username: String,
        password: String,
        onResult: (Result<User>) -> Unit
    ) {
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()
        if (normalizedUsername.isBlank() || normalizedPassword.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Enter username and password.")))
            return
        }

        val cachedCandidate = resolveLoginCandidate(role, normalizedUsername)
        val firebaseUsername = cachedCandidate?.username ?: normalizedUsername

        signInWithFirebase(firebaseUsername, normalizedPassword) { authResult ->
            authResult.onSuccess {
                resolveApprovedUserAfterSignIn(firebaseUsername, role, normalizedPassword, 8, onResult)
            }.onFailure { error ->
                if (cachedCandidate == null) {
                    onResult(Result.failure(IllegalArgumentException("No account found for this Student ID or phone number.")))
                    return@signInWithFirebase
                }

                if (cachedCandidate.role != role) {
                    onResult(Result.failure(IllegalArgumentException("This account is not allowed to log in as ${role.name.lowercase()}.")))
                    return@signInWithFirebase
                }

                val shouldMigrate = cachedCandidate.password == normalizedPassword
                if (shouldMigrate && isFirebaseError(error, "ERROR_USER_NOT_FOUND")) {
                    createFirebaseAccount(
                        username = cachedCandidate.username,
                        password = normalizedPassword,
                        preserveCurrentSession = false
                    ) { migrationResult ->
                        migrationResult.onSuccess {
                            finishLogin(cachedCandidate, normalizedPassword, onResult)
                        }.onFailure { migrationError ->
                            onResult(Result.failure(migrationError))
                        }
                    }
                } else if (!cachedCandidate.approved) {
                    onResult(Result.failure(IllegalArgumentException("Your account is waiting for admin approval.")))
                } else {
                    onResult(Result.failure(error))
                }
            }
        }
    }

    /** Complete Android session setup only for a profile already authorized by Flask for this UID. */
    fun signInLinkedFirebaseProfile(role: Role, schoolId: String, onResult: (Result<User>) -> Unit) {
        SchoolRepository.refreshSharedStateOnce {
            val user = SchoolRepository.userByUsername(schoolId)
            when {
                user == null -> onResult(Result.failure(IllegalArgumentException("This linked school profile is not available on this device yet.")))
                user.role != role || !user.approved -> onResult(Result.failure(IllegalArgumentException("This profile is not approved for the selected role.")))
                else -> finishLogin(user, "", onResult)
            }
        }
    }

    fun selectAuthorizedProfile(profileId: Int) {
        require(profileId > 0)
        activeProfileId = profileId
    }

    fun registerRoleAccount(
        role: Role,
        username: String,
        password: String,
        fullName: String,
        mobileNumber: String,
        className: String,
        subject: String,
        rollNumber: String,
        guardianContact: String,
        notes: String,
        approved: Boolean,
        autoLogin: Boolean,
        onResult: (Result<User>) -> Unit
    ) {
        onResult(Result.failure(IllegalStateException("Public registration is disabled. Ask the school administrator to create your record, then use Activate Account.")))
        return

        if (role == Role.ADMIN) {
            onResult(Result.failure(IllegalArgumentException("Admin registration is not allowed.")))
            return
        }

        val normalizedUsername = username.trim().lowercase()
        val normalizedPassword = password.trim()
        val rawMobile = mobileNumber.trim()
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        val normalizedClass = className.trim()
        val normalizedFullName = fullName.trim()

        if (normalizedUsername.isBlank() || normalizedPassword.length < 6 || normalizedFullName.isBlank() || (role == Role.STUDENT && normalizedClass.isBlank())) {
            onResult(Result.failure(IllegalArgumentException("Enter username, full name, and a password of at least 6 characters. Class is required only for students.")))
            return
        }

        if (rawMobile.isNotBlank() && normalizedMobile.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Enter a valid mobile number or leave it empty.")))
            return
        }

        SchoolRepository.refreshSharedStateOnce {
            val existingUser = SchoolRepository.userByUsername(normalizedUsername)
            val existingRequest = SchoolRepository.registrationRequestByUsername(normalizedUsername)
            val wasDeletedAccount = SchoolRepository.isAccountDeleted(normalizedUsername)
            if (existingUser != null) {
                val suggestions = SchoolRepository.suggestAvailableUsernames(normalizedUsername)
                val suggestionSuffix = suggestions.takeIf { it.isNotEmpty() }?.joinToString(prefix = " Try: ", separator = ", ").orEmpty()
                val message = if (existingUser.approved) {
                    "This username is already registered.$suggestionSuffix"
                } else {
                    "This username is already registered and waiting for admin approval.$suggestionSuffix"
                }
                onResult(Result.failure(IllegalArgumentException(message)))
                return@refreshSharedStateOnce
            }
            if (existingRequest != null) {
                val suggestions = SchoolRepository.suggestAvailableUsernames(normalizedUsername)
                val suggestionSuffix = suggestions.takeIf { it.isNotEmpty() }?.joinToString(prefix = " Try: ", separator = ", ").orEmpty()
                onResult(Result.failure(IllegalArgumentException("This username is already registered and waiting for admin approval.$suggestionSuffix")))
                return@refreshSharedStateOnce
            }
            if (approved && SchoolRepository.isAccountDeleted(normalizedUsername)) {
                onResult(Result.failure(IllegalArgumentException("This username was removed by admin. Use a new username or ask admin to create a new account.")))
                return@refreshSharedStateOnce
            }

            val preserveExistingSession = !autoLogin && FirebaseAuth.getInstance().currentUser != null
            createFirebaseAccount(
                username = normalizedUsername,
                password = normalizedPassword,
                preserveCurrentSession = preserveExistingSession
            ) { authResult ->
                authResult.onFailure {
                    if (!approved && wasDeletedAccount && isFirebaseError(it, "ERROR_EMAIL_ALREADY_IN_USE")) {
                        signInWithFirebase(normalizedUsername, normalizedPassword) { reusedAuthResult ->
                            reusedAuthResult.onSuccess {
                                finalizeRegistrationAfterAuth(
                                    role = role,
                                    username = normalizedUsername,
                                    password = normalizedPassword,
                                    fullName = normalizedFullName,
                                    mobileNumber = normalizedMobile,
                                    className = normalizedClass,
                                    subject = subject,
                                    rollNumber = rollNumber,
                                    guardianContact = guardianContact,
                                    notes = notes,
                                    approved = approved,
                                    autoLogin = autoLogin,
                                    onResult = onResult
                                )
                            }.onFailure {
                                onResult(Result.failure(IllegalArgumentException("This removed username can register again, but it still has an old password. Use the previous password once or ask admin to recreate the account.")))
                            }
                        }
                    } else {
                        onResult(Result.failure(it))
                    }
                    return@createFirebaseAccount
                }

                finalizeRegistrationAfterAuth(
                    role = role,
                    username = normalizedUsername,
                    password = normalizedPassword,
                    fullName = normalizedFullName,
                    mobileNumber = normalizedMobile,
                    className = normalizedClass,
                    subject = subject,
                    rollNumber = rollNumber,
                    guardianContact = guardianContact,
                    notes = notes,
                    approved = approved,
                    autoLogin = autoLogin,
                    onResult = onResult
                )
            }
        }
    }

    fun restoreSession(onResult: (Boolean) -> Unit) {
        if (currentUser != null && isSessionMetadataValid()) {
            onResult(true)
            return
        }

        if (!isSessionMetadataValid() || secureStore.isBiometricEnabled()) {
            onResult(false)
            return
        }
        val username = secureStore.username()
        val role = secureStore.role() ?: run {
            onResult(false)
            return
        }
        if (FirebaseAuth.getInstance().currentUser == null) {
            logout()
            onResult(false)
            return
        }

        SchoolRepository.refreshSharedStateOnce { syncSuccess ->
            val restored = SchoolRepository.userByUsername(username)
            if (restored == null || !restored.approved || restored.role != role) {
                logout()
                onResult(false)
            } else if (FirebaseAuth.getInstance().currentUser != null || !syncSuccess) {
                currentUser = restored.sanitized(password = "")
                MessagingTopics.refreshUserTopics(currentUser)
                onResult(true)
            } else {
                logout()
                onResult(false)
            }
        }
    }

    fun restoreSessionFromCache(afterBiometric: Boolean = false): Boolean {
        if (currentUser != null && isSessionMetadataValid()) return true
        if (!isSessionMetadataValid() || (secureStore.isBiometricEnabled() && !afterBiometric)) return false
        if (FirebaseAuth.getInstance().currentUser == null) {
            logout()
            return false
        }
        val username = secureStore.username()
        val role = secureStore.role() ?: return false
        val restored = SchoolRepository.userByUsername(username) ?: return false
        if (!restored.approved || restored.role != role) return false
        currentUser = restored.sanitized(password = "")
        MessagingTopics.refreshUserTopics(currentUser)
        return true
    }

    fun hasBiometricUnlock(): Boolean = isSessionMetadataValid() && secureStore.isBiometricEnabled()

    fun hasActiveSession(): Boolean = currentUser != null && isSessionMetadataValid()

    fun enableBiometricUnlock() {
        if (isSessionMetadataValid()) secureStore.setBiometricEnabled(true)
    }

    fun disableBiometricUnlock() {
        secureStore.setBiometricEnabled(false)
    }

    fun ensureFirebaseSession(onResult: (Result<Unit>) -> Unit) {
        val user = currentUser
        if (user == null) {
            onResult(Result.failure(IllegalStateException("Login again to continue.")))
            return
        }
        if (FirebaseAuth.getInstance().currentUser != null) {
            onResult(Result.success(Unit))
            return
        }
        onResult(Result.failure(IllegalStateException("Firebase session expired. Please log in again.")))
    }

    fun refreshFirebaseSessionSilently() {
        // Firebase Auth manages its own secure refresh credential. Never cache a password.
    }

    fun updateSessionPassword(password: String) {
        // Password changes are handled by Firebase Auth and are intentionally not cached.
    }

    fun refreshCurrentUser(): User? {
        val user = currentUser ?: return null
        val restored = SchoolRepository.userByUsername(user.username)?.sanitized(password = "") ?: return currentUser
        currentUser = restored
        MessagingTopics.refreshUserTopics(currentUser)
        return currentUser
    }

    private fun finishLogin(user: User, sessionPassword: String, onResult: (Result<User>) -> Unit) {
        MessagingTopics.clearPendingApproval(user.username)
        currentUser = user.sanitized(password = "")
        MessagingTopics.refreshUserTopics(currentUser)
        secureStore.save(user.username, user.role, System.currentTimeMillis() + SESSION_DURATION_MS)
        onResult(Result.success(currentUser!!))
    }

    private fun finalizeRegistrationAfterAuth(
        role: Role,
        username: String,
        password: String,
        fullName: String,
        mobileNumber: String,
        className: String,
        subject: String,
        rollNumber: String,
        guardianContact: String,
        notes: String,
        approved: Boolean,
        autoLogin: Boolean,
        onResult: (Result<User>) -> Unit
    ) {
        if (!approved) {
            val request = RegistrationRequest(
                username = username,
                authUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
                role = role,
                fullName = fullName,
                className = className,
                subject = subject,
                rollNumber = rollNumber,
                guardianContact = guardianContact,
                mobileNumber = mobileNumber,
                notes = notes.ifBlank { "Self registered ${role.name.lowercase()} account." },
                source = "app"
            )
            SchoolRepository.submitRegistrationRequest(request) { submitted ->
                if (!submitted) {
                    onResult(Result.failure(IllegalStateException("Registration request could not be sent to admin. Check your internet connection and try again.")))
                    return@submitRegistrationRequest
                }
                MessagingTopics.watchPendingApproval(username)
                if (!autoLogin) {
                    runCatching { FirebaseAuth.getInstance().signOut() }
                }
                onResult(
                    Result.success(
                        User(
                            username = username,
                            password = "",
                            role = role,
                            fullName = fullName,
                            className = className,
                            classNames = className.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList(),
                            subject = subject,
                            approved = false,
                            mobileNumber = mobileNumber
                        )
                    )
                )
            }
            return
        }

        val added = when (role) {
            Role.STUDENT -> SchoolRepository.addStudentProfile(
                username = username,
                password = password,
                fullName = fullName,
                className = className,
                rollNumber = rollNumber,
                guardianContact = guardianContact,
                notes = notes.ifBlank { "Self registered student account." },
                approved = true,
                mobileNumber = mobileNumber
            )
            Role.TEACHER -> SchoolRepository.addTeacher(
                username = username,
                password = password,
                fullName = fullName,
                classNames = className.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
                subject = subject,
                approved = true,
                mobileNumber = mobileNumber
            )
            Role.ADMIN -> false
        }

        if (!added) {
            onResult(Result.failure(IllegalArgumentException(validationMessageFor(role, username, className, password))))
            return
        }

        SchoolRepository.refreshSharedStateOnce {
            val appUser = SchoolRepository.userByUsername(username)?.sanitized(password = "") ?: run {
                onResult(Result.failure(IllegalStateException("Unable to prepare account.")))
                return@refreshSharedStateOnce
            }

            if (autoLogin) {
                finishLogin(appUser, password, onResult)
            } else {
                runCatching { FirebaseAuth.getInstance().signOut() }
                onResult(Result.success(appUser))
            }
        }
    }

    private fun resolveApprovedUserAfterSignIn(
        username: String,
        role: Role,
        sessionPassword: String,
        retriesRemaining: Int,
        onResult: (Result<User>) -> Unit
    ) {
        SchoolRepository.refreshSharedStateOnce {
            val candidate = SchoolRepository.userByUsername(username)
            when {
                candidate != null && candidate.role == role && candidate.approved -> {
                    finishLogin(candidate, sessionPassword, onResult)
                }
                retriesRemaining > 0 -> {
                    Handler(Looper.getMainLooper()).postDelayed({
                        resolveApprovedUserAfterSignIn(username, role, sessionPassword, retriesRemaining - 1, onResult)
                    }, 1200)
                }
                else -> {
                    SchoolRepository.registrationRequestByUsernameOnce(username) { request ->
                        val message = when {
                            candidate == null && request != null && request.role == role -> "Your account is waiting for admin approval."
                            candidate == null -> "No account found for this username."
                            candidate.role != role -> "This account is not allowed to log in as ${role.name.lowercase()}."
                            else -> "Your account is waiting for admin approval."
                        }
                        runCatching { FirebaseAuth.getInstance().signOut() }
                        onResult(Result.failure(IllegalArgumentException(message)))
                    }
                }
            }
        }
    }

    private fun validationMessageFor(role: Role, username: String, className: String, password: String): String {
        return when (role) {
            Role.STUDENT -> when {
                username.isBlank() -> "Enter a student username."
                className.isBlank() -> "Enter class, class head, or other."
                password.length < 6 -> "Student password must be at least 6 characters."
                else -> "Student account could not be created. The username may already exist."
            }
            Role.TEACHER -> when {
                username.isBlank() -> "Enter a teacher username."
                password.length < 6 -> "Teacher password must be at least 6 characters."
                else -> "Teacher account could not be created. The username may already exist."
            }
            Role.ADMIN -> "Admin account creation is not allowed."
        }
    }

    private fun prefs() =
        appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    private fun authEmailFor(username: String): String =
        username.trim().takeIf { it.contains("@") }?.lowercase()
            ?: "${username.trim().lowercase()}@cns-paunta.app"

    private fun signInWithFirebase(username: String, password: String, onResult: (Result<Unit>) -> Unit) {
        FirebaseAuth.getInstance()
            .signInWithEmailAndPassword(authEmailFor(username), password)
            .addOnSuccessListener { result ->
                // Accounts created through Flask remain pending until Firebase has confirmed the
                // normal email-verification link.  Legacy synthetic-email accounts are preserved.
                val firebaseUser = result.user
                if (firebaseUser?.email?.contains("@cns-paunta.app") == false && !firebaseUser.isEmailVerified) {
                    FirebaseAuth.getInstance().signOut()
                    onResult(Result.failure(IllegalArgumentException("Please verify your email before logging in.")))
                } else {
                    onResult(Result.success(Unit))
                }
            }
            .addOnFailureListener { error ->
                val message = when ((error as? FirebaseAuthException)?.errorCode) {
                    "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD" -> "Incorrect password."
                    "ERROR_USER_NOT_FOUND" -> "No login exists for this user yet."
                    else -> error.message ?: "Sign-in failed."
                }
                onResult(Result.failure(IllegalArgumentException(message, error)))
            }
    }

    private fun createFirebaseAccount(
        username: String,
        password: String,
        preserveCurrentSession: Boolean,
        onResult: (Result<Unit>) -> Unit
    ) {
        val email = authEmailFor(username)
        if (preserveCurrentSession) {
            createFirebaseAccountInSecondaryApp(email, password, onResult)
        } else {
            FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { onResult(Result.success(Unit)) }
                .addOnFailureListener { error ->
                    val message = when ((error as? FirebaseAuthException)?.errorCode) {
                        "ERROR_EMAIL_ALREADY_IN_USE" -> "This username is already registered."
                        "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
                        else -> error.message ?: "Unable to create account."
                    }
                    onResult(Result.failure(IllegalArgumentException(message, error)))
                }
        }
    }

    private fun createFirebaseAccountInSecondaryApp(
        email: String,
        password: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val primaryApp = FirebaseApp.getInstance()
        val secondaryName = "secondary_auth_${System.currentTimeMillis()}"
        val secondaryApp = FirebaseApp.initializeApp(appContext, primaryApp.options, secondaryName)
        val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)
        secondaryAuth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                secondaryAuth.signOut()
                secondaryApp.delete()
                onResult(Result.success(Unit))
            }
            .addOnFailureListener { error ->
                secondaryAuth.signOut()
                secondaryApp.delete()
                val message = when ((error as? FirebaseAuthException)?.errorCode) {
                    "ERROR_EMAIL_ALREADY_IN_USE" -> "This username is already registered."
                    "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
                    else -> error.message ?: "Unable to create account."
                }
                onResult(Result.failure(IllegalArgumentException(message, error)))
            }
    }

    private fun isFirebaseError(error: Throwable, code: String): Boolean {
        val direct = error as? FirebaseAuthException
        val cause = error.cause as? FirebaseAuthException
        return direct?.errorCode == code || cause?.errorCode == code
    }

    private fun resolveLoginCandidate(role: Role, identifier: String): User? {
        val byUsername = SchoolRepository.userByUsername(identifier)
        if (byUsername != null) return byUsername
        return if (role == Role.STUDENT) SchoolRepository.userByMobile(identifier) else null
    }

    private fun isSessionMetadataValid(): Boolean {
        if (!::secureStore.isInitialized) return false
        if (secureStore.expiresAtMillis() <= System.currentTimeMillis()) {
            if (secureStore.expiresAtMillis() > 0L) logout()
            return false
        }
        return secureStore.username().isNotBlank() && secureStore.role() != null
    }

    private fun User.sanitized(password: String = this.password): User =
        User(
            username = (username as String?).orEmpty(),
            password = password,
            role = role,
            fullName = (fullName as String?).orEmpty(),
            className = (className as String?).orEmpty(),
            classNames = (classNames as List<String>?).orEmpty().map { (it as String?).orEmpty() }.filter { it.isNotBlank() },
            subject = (subject as String?).orEmpty(),
            approved = approved,
            mobileNumber = (mobileNumber as String?).orEmpty(),
            profileImageUrl = (profileImageUrl as String?).orEmpty(),
            forcePasswordChange = forcePasswordChange,
            qualification = (qualification as String?).orEmpty(),
            experience = (experience as String?).orEmpty(),
            specialization = (specialization as String?).orEmpty(),
            staffBio = (staffBio as String?).orEmpty()
        )
}
