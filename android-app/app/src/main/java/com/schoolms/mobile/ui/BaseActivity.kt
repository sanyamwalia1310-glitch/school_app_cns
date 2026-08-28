package com.schoolms.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.firebase.MessagingTopics

open class BaseActivity : AppCompatActivity() {
    private var shownUpdatePromptKey = ""
    private var activeUpdatePromptVisible = false
    private val repositoryListener = {
        runOnUiThread {
            enforceForcedUpdateGateIfNeeded()
            showGlobalUpdatePromptIfNeeded()
            onRepositoryChanged()
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    protected fun setupToolbar(toolbar: MaterialToolbar, title: String, showBack: Boolean = true) {
        toolbar.title = title
        if (showBack) {
            toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            toolbar.setNavigationOnClickListener { finish() }
        } else {
            toolbar.navigationIcon = null
        }
    }

    protected fun requireLogin(vararg allowedRoles: Role): Boolean {
        val user = SessionManager.currentUser?.takeIf { SessionManager.hasActiveSession() } ?: run {
            if (SessionManager.restoreSessionFromCache()) SessionManager.currentUser else null
        }
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return false
        }
        if (allowedRoles.isNotEmpty() && user.role !in allowedRoles) {
            Toast.makeText(this, "Access denied", Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (this !is LoginActivity && this !is SplashActivity &&
            SessionManager.currentUser != null && !SessionManager.hasActiveSession()) {
            logoutToLogin()
        }
    }

    protected fun logoutToLogin() {
        SessionManager.logout()
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    protected fun animateContentEntrance(vararg views: View) {
        views.filter { it.visibility == View.VISIBLE }.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setStartDelay(index * 55L)
                .start()
        }
    }

    protected open fun onRepositoryChanged() = Unit

    override fun onStart() {
        super.onStart()
        ensureNotificationPermission()
        MessagingTopics.refreshUserTopics(SessionManager.currentUser)
        SessionManager.refreshFirebaseSessionSilently()
        if (SessionManager.currentUser?.role == Role.ADMIN) {
            SchoolRepository.ensureAdminSessionAccessIfNeeded { }
        }
        SchoolRepository.addChangeListener(repositoryListener)
        SchoolRepository.refreshPersonalNotificationsForCurrentUser(true)
        SchoolRepository.refreshSharedStateOnce { }
        enforceForcedUpdateGateIfNeeded()
        showGlobalUpdatePromptIfNeeded()
    }

    override fun onStop() {
        SchoolRepository.removeChangeListener(repositoryListener)
        super.onStop()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun enforceForcedUpdateGateIfNeeded() {
        if (this is SplashActivity) return
    }

    private fun showGlobalUpdatePromptIfNeeded() {
        if (this is SplashActivity) return
        if (activeUpdatePromptVisible) return
        val notice = AppUpdateSupport.activeNotice() ?: return
        if (!AppUpdateSupport.shouldShowPrompt(this, notice)) return
        val promptKey = AppUpdateSupport.promptKey(notice)
        if (shownUpdatePromptKey == promptKey) return
        val forceRequired = AppUpdateSupport.isForceUpdateRequired(this, notice)
        shownUpdatePromptKey = promptKey
        activeUpdatePromptVisible = true
        val dialog = AppUpdateSupport.showUpdatePrompt(this, notice, forceRequired)
        dialog.setOnDismissListener {
            activeUpdatePromptVisible = false
        }
    }
}
