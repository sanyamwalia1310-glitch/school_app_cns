package com.schoolms.mobile.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.AppUpdateNotice
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager

class MainDashboardActivity : BaseActivity() {
    private var passwordChangeDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_main_dashboard)

        val user = SessionManager.currentUser ?: return
        val toolbar = findViewById<MaterialToolbar>(R.id.mainDashboardToolbar)
        val welcomeText = findViewById<TextView>(R.id.welcomeText)
        val portalBadge = findViewById<TextView>(R.id.portalBadge)
        val homeSummaryText = findViewById<TextView>(R.id.homeSummaryText)
        val heroPanel = findViewById<LinearLayout>(R.id.homeHeroPanel)
        val dashboardProfileCard = findViewById<MaterialCardView>(R.id.dashboardProfileCard)
        val dashboardAvatar = findViewById<ImageView>(R.id.dashboardProfileAvatar)
        val topAnnouncementCard = findViewById<MaterialCardView>(R.id.topAnnouncementCard)
        val topAnnouncementTitle = findViewById<TextView>(R.id.topAnnouncementTitle)
        val topAnnouncementBody = findViewById<TextView>(R.id.topAnnouncementBody)
        val appUpdateCard = findViewById<MaterialCardView>(R.id.appUpdateCard)
        val appUpdateTitle = findViewById<TextView>(R.id.appUpdateTitle)
        val appUpdateBody = findViewById<TextView>(R.id.appUpdateBody)
        val appUpdateVersionText = findViewById<TextView>(R.id.appUpdateVersionText)
        val appUpdateActionButton = findViewById<MaterialButton>(R.id.appUpdateActionButton)
        val appUpdateEditButton = findViewById<MaterialButton>(R.id.appUpdateEditButton)
        val scholarsMarquee = findViewById<TextView>(R.id.ourScholarsMarquee)
        val editScholarsButton = findViewById<MaterialButton>(R.id.editScholarsButton)
        val contactPhoneText = findViewById<TextView>(R.id.contactPhoneText)
        val contactEmailText = findViewById<TextView>(R.id.contactEmailText)
        val contactOtherText = findViewById<TextView>(R.id.contactOtherText)
        val editContactButton = findViewById<MaterialButton>(R.id.editContactButton)

        setupToolbar(toolbar, getString(R.string.home_title), showBack = false)
        welcomeText.text = welcomeTitleFor(user.role)
        portalBadge.text = when (user.role) {
            Role.ADMIN -> getString(R.string.admin_console)
            Role.TEACHER -> getString(R.string.teacher_hub)
            Role.STUDENT -> getString(R.string.student_portal)
        }
        homeSummaryText.text = when (user.role) {
            Role.ADMIN -> "Review approvals, lead school operations, and manage shared academic workflows from one control point."
            Role.TEACHER -> "Handle class attendance, homework, marks, and day-to-day teaching updates from one workspace."
            Role.STUDENT -> "Open your student home for class updates, attendance, homework, marks, timetable, and progress."
        }
        bindDashboardAvatar(user, dashboardAvatar)
        heroPanel.setBackgroundResource(
            when (user.role) {
                Role.ADMIN -> R.drawable.bg_hero_admin
                Role.TEACHER -> R.drawable.bg_hero_teacher
                Role.STUDENT -> R.drawable.bg_hero_student
            }
        )

        topAnnouncementCard.setOnClickListener { openInfo("notifications") }
        bindTopAnnouncement(topAnnouncementTitle, topAnnouncementBody)
        bindAppUpdateCard(
            user,
            appUpdateCard,
            appUpdateTitle,
            appUpdateBody,
            appUpdateVersionText,
            appUpdateActionButton,
            appUpdateEditButton
        )
        bindOurScholars(scholarsMarquee)
        findViewById<MaterialCardView>(R.id.ourScholarsCard).setOnClickListener {
            open(OurScholarsActivity::class.java)
        }
        editScholarsButton.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        editScholarsButton.setOnClickListener {
            showOurScholarsDialog(scholarsMarquee)
        }
        bindSchoolContact(contactPhoneText, contactEmailText, contactOtherText)
        editContactButton.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        editContactButton.setOnClickListener {
            showSchoolContactDialog(contactPhoneText, contactEmailText, contactOtherText)
        }

        findViewById<MaterialButton>(R.id.roleDashboardButton).apply {
            text = when (user.role) {
                Role.ADMIN -> "Open admin workspace"
                Role.TEACHER -> "Open teacher workspace"
                Role.STUDENT -> "Open student home"
            }
            setOnClickListener { open(RoleDashboardActivity::class.java) }
        }
        findViewById<MaterialButton>(R.id.pendingApprovalsButton).apply {
            visibility = if (user.role == Role.ADMIN) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { open(PendingApprovalsActivity::class.java) }
        }
        findViewById<MaterialButton>(R.id.passwordResetRequestsButton).apply {
            visibility = if (user.role == Role.ADMIN) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { open(PasswordResetRequestsActivity::class.java) }
        }
        findViewById<MaterialButton>(R.id.logoutButton).setOnClickListener { logoutToLogin() }
        findViewById<MaterialButton>(R.id.facilitiesButton).setOnClickListener { open(FacilitiesActivity::class.java) }
        findViewById<MaterialButton>(R.id.eventsButton).setOnClickListener { openInfo("events") }
        findViewById<MaterialButton>(R.id.admissionButton).setOnClickListener { open(AdmissionEnquiryActivity::class.java) }
        findViewById<MaterialButton>(R.id.feedbackButton).setOnClickListener { open(FeedbackActivity::class.java) }
        findViewById<MaterialButton>(R.id.timetableButton).setOnClickListener { open(TimetableActivity::class.java) }
        findViewById<MaterialButton>(R.id.galleryButton).setOnClickListener { open(GalleryActivity::class.java) }
        findViewById<MaterialButton>(R.id.contentButton).setOnClickListener { open(ContentActivity::class.java) }
        findViewById<MaterialButton>(R.id.ourStaffButton).setOnClickListener { open(OurStaffActivity::class.java) }
        dashboardProfileCard.isClickable = true
        dashboardProfileCard.isFocusable = true
        val openProfile = { open(ProfileActivity::class.java) }
        dashboardProfileCard.setOnClickListener { openProfile() }
        dashboardAvatar.setOnClickListener { openProfile() }
        animateContentEntrance(
            heroPanel,
            topAnnouncementCard,
            findViewById(R.id.roleDashboardButton),
            findViewById(R.id.pendingApprovalsButton),
            findViewById(R.id.passwordResetRequestsButton),
            findViewById(R.id.ourScholarsCard),
            findViewById(R.id.contactDetailsSection),
            appUpdateCard,
            findViewById(R.id.logoutButton)
        )
        enforcePasswordChangeIfNeeded(user)
    }

    override fun onRepositoryChanged() {
        val user = SessionManager.refreshCurrentUser() ?: SessionManager.currentUser ?: return
        findViewById<TextView>(R.id.welcomeText).text = welcomeTitleFor(user.role)
        bindDashboardAvatar(user, findViewById(R.id.dashboardProfileAvatar))
        bindTopAnnouncement(findViewById(R.id.topAnnouncementTitle), findViewById(R.id.topAnnouncementBody))
        bindAppUpdateCard(
            user,
            findViewById(R.id.appUpdateCard),
            findViewById(R.id.appUpdateTitle),
            findViewById(R.id.appUpdateBody),
            findViewById(R.id.appUpdateVersionText),
            findViewById(R.id.appUpdateActionButton),
            findViewById(R.id.appUpdateEditButton)
        )
        bindOurScholars(findViewById(R.id.ourScholarsMarquee))
        bindSchoolContact(
            findViewById(R.id.contactPhoneText),
            findViewById(R.id.contactEmailText),
            findViewById(R.id.contactOtherText)
        )
        enforcePasswordChangeIfNeeded(user)
    }

    private fun bindTopAnnouncement(titleView: TextView, bodyView: TextView) {
        val topAnnouncement = SchoolRepository.announcements().firstOrNull()
        titleView.text = topAnnouncement?.title ?: "Latest school announcement"
        val tickerText = when {
            topAnnouncement?.subtitle.isNullOrBlank() -> "School announcements will appear here after login. | Tap the card to open all updates."
            else -> "${topAnnouncement?.subtitle?.trim()} | Tap the card to open all updates."
        }
        bodyView.text = tickerText
        bodyView.isSelected = false
        bodyView.post {
            bodyView.isSelected = true
        }
    }

    private fun bindOurScholars(marqueeView: TextView) {
        val item = SchoolRepository.ourScholars()
        marqueeView.text = "${item.title}     |     ${item.subtitle}"
        marqueeView.isSelected = true
    }

    private fun bindAppUpdateCard(
        user: com.schoolms.mobile.data.User,
        card: MaterialCardView,
        titleView: TextView,
        bodyView: TextView,
        versionView: TextView,
        actionButton: MaterialButton,
        editButton: MaterialButton
    ) {
        val notice = SchoolRepository.appUpdateNotice()
        val activeNotice = notice?.takeIf { it.isActive() }
        val (currentVersion, currentVersionName) = AppUpdateSupport.installedVersionInfo(this)
        val minVersion = activeNotice?.minimumVersionCode ?: 0
        val shouldShowCard = user.role == Role.ADMIN || activeNotice != null
        card.visibility = if (shouldShowCard) View.VISIBLE else View.GONE
        card.isClickable = shouldShowCard
        card.isFocusable = shouldShowCard

        val isOutdated = minVersion > 0 && currentVersion < minVersion
        val hasForcedUpdate = activeNotice?.forceUpdate == true && isOutdated
        titleView.text = when {
            user.role == Role.ADMIN && activeNotice == null -> "Set app update notice"
            activeNotice == null -> "You're up to date"
            else -> activeNotice.title.ifBlank { "App update" }
        }
        bodyView.text = when {
            activeNotice == null && user.role == Role.ADMIN -> "Publish an update message, APK link, or minimum version so all users see it."
            activeNotice == null -> "No new app update has been published yet."
            else -> activeNotice.subtitle.ifBlank { "A new version is available. Download and install it here." }
        }
        versionView.text = when {
            activeNotice == null -> "Installed version: $currentVersionName"
            minVersion > 0 -> "Installed: $currentVersionName | Minimum required: $minVersion"
            else -> "Installed version: $currentVersionName"
        }
        actionButton.text = when {
            user.role == Role.ADMIN && activeNotice == null -> "Add update"
            activeNotice == null -> "Up to date"
            else -> activeNotice.buttonText.orEmpty().ifBlank { "Update now" }
        }
        actionButton.isEnabled = user.role == Role.ADMIN || activeNotice != null
        actionButton.setOnClickListener {
            when {
                user.role == Role.ADMIN && activeNotice == null -> showAppUpdateDialog()
                activeNotice != null -> openAppUpdateAction(activeNotice)
                else -> Toast.makeText(this, "Up to date", Toast.LENGTH_SHORT).show()
            }
        }

        if (user.role == Role.ADMIN) {
            editButton.visibility = View.VISIBLE
            editButton.setOnClickListener { showAppUpdateDialog() }
            card.setOnClickListener { showAppUpdateDialog() }
        } else {
            editButton.visibility = View.GONE
            card.setOnClickListener {
                if (activeNotice != null) openAppUpdateAction(activeNotice) else Toast.makeText(this, "Up to date", Toast.LENGTH_SHORT).show()
            }
        }

        card.strokeColor = when {
            hasForcedUpdate -> getColor(R.color.dashboard_red)
            isOutdated -> getColor(R.color.dashboard_green)
            else -> getColor(R.color.stroke_soft)
        }
    }

    private fun bindDashboardAvatar(user: com.schoolms.mobile.data.User, avatar: ImageView) {
        val imageUrl = SchoolRepository.profileImageUrlFor(user)
        ImageLoader.loadInto(avatar, imageUrl, R.drawable.ic_school_crest)
    }

    private fun resolveDisplayName(user: com.schoolms.mobile.data.User): String {
        return when (user.role) {
            Role.STUDENT -> SchoolRepository.profileFor(user.username)?.fullName ?: user.fullName
            else -> SchoolRepository.userByUsername(user.username)?.fullName ?: user.fullName
        }
    }

    private fun welcomeTitleFor(role: Role): String {
        return when (role) {
            Role.ADMIN -> "Hello Admin"
            Role.TEACHER -> "Hello Teacher"
            Role.STUDENT -> "Hello Student"
        }
    }

    private fun showOurScholarsDialog(marqueeView: TextView) {
        val current = SchoolRepository.ourScholars()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val titleInput = EditText(this).apply {
            hint = "Heading"
            setText(current.title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val messageInput = EditText(this).apply {
            hint = "Message"
            setText(current.subtitle)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        container.addView(titleInput)
        container.addView(messageInput)

        AlertDialog.Builder(this)
            .setTitle("Update Our Scholars")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                if (SchoolRepository.updateOurScholars(titleInput.text.toString(), messageInput.text.toString())) {
                    bindOurScholars(marqueeView)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindSchoolContact(phoneView: TextView, emailView: TextView, otherView: TextView) {
        val contact = SchoolRepository.schoolContact()
        phoneView.text = contact.title
        emailView.text = contact.subtitle
        otherView.text = contact.badge.orEmpty()
    }

    private fun showSchoolContactDialog(phoneView: TextView, emailView: TextView, otherView: TextView) {
        val current = SchoolRepository.schoolContact()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val phoneInput = EditText(this).apply {
            hint = "Contact number"
            setText(current.title.removePrefix("Contact: ").trim())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val emailInput = EditText(this).apply {
            hint = "Email ID"
            setText(current.subtitle.removePrefix("Email: ").trim())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val otherInput = EditText(this).apply {
            hint = "Other details"
            setText(current.badge.orEmpty())
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        container.addView(phoneInput)
        container.addView(emailInput)
        container.addView(otherInput)

        AlertDialog.Builder(this)
            .setTitle("Update Contact Details")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                if (SchoolRepository.updateSchoolContact(phoneInput.text.toString(), emailInput.text.toString(), otherInput.text.toString())) {
                    bindSchoolContact(phoneView, emailView, otherView)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInfo(mode: String) {
        startActivity(Intent(this, InfoListActivity::class.java).putExtra(InfoListActivity.EXTRA_MODE, mode))
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    private fun openAppUpdateAction(notice: AppUpdateNotice?) {
        AppUpdateSupport.openUpdateAction(this, notice)
    }

    private fun showAppUpdateDialog() {
        val current = SchoolRepository.appUpdateNotice()
        val (installedVersion, installedVersionName) = AppUpdateSupport.installedVersionInfo(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val titleInput = inputField(container, "Title", current?.title.orEmpty().ifBlank { "New app update available" })
        val bodyInput = inputField(
            container,
            "Message",
            current?.subtitle.orEmpty().ifBlank { "Please install the latest school app version to continue receiving updates." },
            minLines = 3
        )
        val buttonInput = inputField(container, "Button text", current?.buttonText.orEmpty().ifBlank { "Update" })
        val urlInput = inputField(container, "Download URL", current?.downloadUrl.orEmpty().ifBlank { "https://school-65f1a.web.app/downloads/school-management-latest.apk" })
        val suggestedMinimumVersion = current?.minimumVersionCode?.takeIf { it > 0 } ?: installedVersion
        val versionInput = inputField(container, "Minimum version code", suggestedMinimumVersion.toString())
        val helperText = TextView(this).apply {
            text = "Installed admin app: $installedVersionName ($installedVersion). For forcing old apps, minimum version should be $installedVersion or higher."
            setPadding(0, 10, 0, 0)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        }
        container.addView(helperText)
        val forceCheckbox = CheckBox(this).apply {
            text = "Force update"
            isChecked = current?.forceUpdate ?: true
        }
        container.addView(forceCheckbox)

        AlertDialog.Builder(this)
            .setTitle("Update app notice")
            .setView(container)
            .setPositiveButton("Send update") { _, _ ->
                sendAppUpdateNotice(
                    title = titleInput.text?.toString().orEmpty(),
                    subtitle = bodyInput.text?.toString().orEmpty(),
                    buttonText = buttonInput.text?.toString().orEmpty(),
                    downloadUrl = urlInput.text?.toString().orEmpty(),
                    minimumVersionCode = versionInput.text?.toString().orEmpty().toIntOrNull() ?: 0,
                    forceUpdate = forceCheckbox.isChecked,
                    installedVersion = installedVersion
                )
            }
            .setNeutralButton("Clear") { _, _ ->
                clearAppUpdateNotice()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendAppUpdateNotice(
        title: String,
        subtitle: String,
        buttonText: String,
        downloadUrl: String,
        minimumVersionCode: Int,
        forceUpdate: Boolean,
        installedVersion: Int
    ) {
        val cleanUrl = downloadUrl.trim()
        if (cleanUrl.isBlank() || !(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"))) {
            Toast.makeText(this, "Add a valid APK download link before sending update", Toast.LENGTH_LONG).show()
            return
        }
        val effectiveMinimumVersion = when {
            forceUpdate && minimumVersionCode < installedVersion -> installedVersion
            else -> minimumVersionCode.coerceAtLeast(0)
        }
        Toast.makeText(this, "Sending update to users...", Toast.LENGTH_SHORT).show()
        SessionManager.ensureFirebaseSession { authResult ->
            runOnUiThread {
                authResult.onFailure {
                    Toast.makeText(this, it.message ?: "Admin login expired. Update not sent.", Toast.LENGTH_LONG).show()
                }.onSuccess {
                    val accepted = SchoolRepository.updateAppUpdateNotice(
                        title = title.ifBlank { "New app update available" },
                        subtitle = subtitle.ifBlank { "Please install the latest school app version to continue receiving updates." },
                        buttonText = buttonText.ifBlank { "Update" },
                        downloadUrl = cleanUrl,
                        minimumVersionCode = effectiveMinimumVersion,
                        forceUpdate = forceUpdate
                    ) { synced ->
                        runOnUiThread {
                            val message = if (synced) {
                                "Update sent to users. Minimum version: $effectiveMinimumVersion"
                            } else {
                                "Update saved here, but sending failed. Check internet and try again."
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            showAppUpdateSentResult(synced, effectiveMinimumVersion, cleanUrl)
                        }
                    }
                    if (!accepted) {
                        Toast.makeText(this, "Update not sent. Add title, message, link, or force update.", Toast.LENGTH_LONG).show()
                    }
                    refreshAppUpdateCard()
                }
            }
        }
    }

    private fun clearAppUpdateNotice() {
        Toast.makeText(this, "Clearing update notice...", Toast.LENGTH_SHORT).show()
        SchoolRepository.updateAppUpdateNotice("", "", "", "", 0, false) { synced ->
            runOnUiThread {
                Toast.makeText(this, if (synced) "Update notice cleared for users" else "Unable to clear update notice", Toast.LENGTH_LONG).show()
                refreshAppUpdateCard()
            }
        }
        refreshAppUpdateCard()
    }

    private fun refreshAppUpdateCard() {
        bindAppUpdateCard(
            SessionManager.currentUser ?: return,
            findViewById(R.id.appUpdateCard),
            findViewById(R.id.appUpdateTitle),
            findViewById(R.id.appUpdateBody),
            findViewById(R.id.appUpdateVersionText),
            findViewById(R.id.appUpdateActionButton),
            findViewById(R.id.appUpdateEditButton)
        )
    }

    private fun showAppUpdateSentResult(sent: Boolean, minimumVersionCode: Int, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle(if (sent) "Update sent" else "Update not confirmed")
            .setMessage(
                if (sent) {
                    "The update notice was sent successfully.\n\nUsers below version code $minimumVersionCode will see the force update prompt.\n\nLink:\n$downloadUrl"
                } else {
                    "The app saved the update locally, but sending was not confirmed. Check internet and press Send update again."
                }
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun enforcePasswordChangeIfNeeded(user: com.schoolms.mobile.data.User) {
        if (!user.forcePasswordChange || passwordChangeDialogShowing) return
        passwordChangeDialogShowing = true

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val passwordInput = inputField(container, "New password", "", minLines = 1).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmInput = inputField(container, "Confirm password", "", minLines = 1).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Change temporary password")
            .setMessage("Admin issued a temporary password for this account. Set a new password before continuing.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Update password", null)
            .setNegativeButton("Logout") { _, _ ->
                passwordChangeDialogShowing = false
                logoutToLogin()
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newPassword = passwordInput.text?.toString().orEmpty()
                        val confirmPassword = confirmInput.text?.toString().orEmpty()
                        if (newPassword.length < 6 || newPassword != confirmPassword) {
                            Toast.makeText(this, "Passwords must match and be at least 6 characters.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        SchoolRepository.changeOwnPassword(newPassword) { result ->
                            runOnUiThread {
                                result.onSuccess {
                                    passwordChangeDialogShowing = false
                                    SessionManager.refreshCurrentUser()
                                    Toast.makeText(this, "Password updated.", Toast.LENGTH_LONG).show()
                                    dialog.dismiss()
                                }.onFailure { error ->
                                    Toast.makeText(this, error.message ?: "Unable to update password.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
                dialog.setOnDismissListener {
                    if (!(SessionManager.currentUser?.forcePasswordChange ?: false)) {
                        passwordChangeDialogShowing = false
                    }
                }
                dialog.show()
            }
    }

    private fun inputField(container: LinearLayout, label: String, value: String, minLines: Int = 1): TextInputEditText {
        val layout = TextInputLayout(this).apply {
            hint = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            this.minLines = minLines
            if (minLines > 1) isSingleLine = false
        }
        layout.addView(input)
        container.addView(layout)
        return input
    }
}
