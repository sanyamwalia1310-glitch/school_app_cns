package com.schoolms.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.schoolms.mobile.R
import com.schoolms.mobile.data.RegistrationRequest
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.data.User
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class PendingApprovalsActivity : BaseActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pendingRecycler: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var approvalsAdapter: SimpleListAdapter
    private var actionInProgress = false
    private var refreshInFlight = false
    private var currentPendingUsers: List<User> = emptyList()
    private var lastRenderedSignature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin(Role.ADMIN)) return
        setContentView(R.layout.activity_info_list)

        toolbar = findViewById(R.id.toolbar)
        setupToolbar(toolbar, "Pending approvals")
        findViewById<com.google.android.material.button.MaterialButton>(R.id.addItemButton).visibility = android.view.View.GONE
        pendingRecycler = findViewById(R.id.recyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        pendingRecycler.layoutManager = LinearLayoutManager(this)
        pendingRecycler.itemAnimator = null
        approvalsAdapter = SimpleListAdapter(
            emptyList(),
            onClick = { position ->
                currentPendingUsers.getOrNull(position)?.let(::showPendingActionDialog)
            },
            animateEntries = false
        )
        pendingRecycler.adapter = approvalsAdapter
        renderPendingUsers()
        refreshPendingUsers(importFirebaseAuthUsers = true, showErrors = true)
    }

    override fun onResume() {
        super.onResume()
        refreshPendingUsers()
    }

    override fun onRepositoryChanged() {
        if (::pendingRecycler.isInitialized) {
            runOnUiThread {
                renderPendingUsers()
            }
        }
    }

    private fun refreshPendingUsers(
        importFirebaseAuthUsers: Boolean = false,
        showErrors: Boolean = false
    ) {
        if (refreshInFlight) return
        refreshInFlight = true
        if (currentPendingUsers.isEmpty()) {
            emptyStateText.text = "Loading pending approvals..."
            emptyStateText.visibility = View.VISIBLE
            pendingRecycler.visibility = View.GONE
        }

        SchoolRepository.ensureAdminSessionAccessIfNeeded(force = false) { accessResult ->
            accessResult.onFailure {
                runOnUiThread {
                    if (showErrors) {
                        Toast.makeText(this, it.message ?: "Admin access is not ready yet.", Toast.LENGTH_LONG).show()
                    }
                    refreshInFlight = false
                    renderPendingUsers()
                }
                return@ensureAdminSessionAccessIfNeeded
            }

            SchoolRepository.refreshRegistrationRequestsOnce { success ->
                runOnUiThread {
                    if (!success && showErrors) {
                        Toast.makeText(this, "Unable to refresh pending approvals.", Toast.LENGTH_LONG).show()
                    }
                    refreshInFlight = false
                    renderPendingUsers()
                }

                if (importFirebaseAuthUsers) {
                    SchoolRepository.syncFirebaseAuthUsersForAdminIfNeeded(force = false) { importResult ->
                        importResult.onFailure {
                            runOnUiThread {
                                if (showErrors) {
                                    Toast.makeText(this, it.message ?: "Unable to import pending users for approval.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        if (importResult.getOrDefault(0) > 0) {
                            SchoolRepository.refreshRegistrationRequestsOnce {
                                runOnUiThread { renderPendingUsers() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderPendingUsers() {
        val pending = SchoolRepository.pendingUsers()
        currentPendingUsers = pending
        val title = if (pending.isEmpty()) {
            "Pending approvals"
        } else {
            "Pending approvals (${pending.size})"
        }
        toolbar.title = title
        emptyStateText.text = "No pending teacher or student approvals right now."
        emptyStateText.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE
        pendingRecycler.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        val rows = pending.map { user ->
            val request = SchoolRepository.registrationRequestByUsername(user.username)
            val detailLines = buildList {
                add(user.role.name.lowercase().replaceFirstChar(Char::uppercase))
                if (user.className.isNotBlank()) {
                    add("Class: ${user.className}")
                }
                if (user.mobileNumber.isNotBlank()) {
                    add("Phone: ${user.mobileNumber}")
                }
                add("Username: ${user.username}")
                if (request?.source == "auth_import") {
                    add("Imported from registered users")
                }
            }
            val badge = when {
                request?.needsReview == true -> "Review"
                else -> "Pending"
            }
            SimpleListItem(
                title = user.fullName.ifBlank { user.username },
                subtitle = detailLines.joinToString("\n"),
                badge = badge
            )
        }
        val signature = rows.joinToString(separator = "||") { "${it.title}|${it.subtitle}|${it.badge.orEmpty()}" }
        if (signature != lastRenderedSignature) {
            lastRenderedSignature = signature
            approvalsAdapter.updateItems(rows)
        }
    }

    private fun showPendingActionDialog(user: User) {
        val request = SchoolRepository.registrationRequestByUsername(user.username)
        AlertDialog.Builder(this)
            .setTitle(user.fullName)
            .setMessage("Approve or remove this ${user.role.name.lowercase()} account request?\n\n${user.className}")
            .setPositiveButton("Approve") { _, _ ->
                if (actionInProgress) return@setPositiveButton
                if (request != null && (request.needsReview || (request.role == Role.STUDENT && request.className.isBlank()))) {
                    showReviewAndApproveDialog(request)
                    return@setPositiveButton
                }
                actionInProgress = true
                SchoolRepository.refreshSharedStateOnce {
                    val result = SchoolRepository.approveUserDetailed(user.username)
                    runOnUiThread {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        refreshPendingUsers(showErrors = false)
                        actionInProgress = false
                    }
                }
            }
            .setNeutralButton("Remove") { _, _ ->
                if (actionInProgress) return@setNeutralButton
                actionInProgress = true
                SchoolRepository.refreshSharedStateOnce {
                    when (user.role) {
                        Role.STUDENT -> if (SchoolRepository.registrationRequestByUsername(user.username) != null) {
                            SchoolRepository.removeRegistrationRequest(user.username) { success ->
                                runOnUiThread {
                                    Toast.makeText(this, if (success) "Account removed" else "Unable to remove account", Toast.LENGTH_SHORT).show()
                                    refreshPendingUsers(showErrors = false)
                                    actionInProgress = false
                                }
                            }
                        } else {
                            val success = SchoolRepository.deleteStudent(user.username)
                            runOnUiThread {
                                Toast.makeText(this, if (success) "Account removed" else "Unable to remove account", Toast.LENGTH_SHORT).show()
                                refreshPendingUsers(showErrors = false)
                                actionInProgress = false
                            }
                        }
                        Role.TEACHER -> if (SchoolRepository.registrationRequestByUsername(user.username) != null) {
                            SchoolRepository.removeRegistrationRequest(user.username) { success ->
                                runOnUiThread {
                                    Toast.makeText(this, if (success) "Account removed" else "Unable to remove account", Toast.LENGTH_SHORT).show()
                                    refreshPendingUsers(showErrors = false)
                                    actionInProgress = false
                                }
                            }
                        } else {
                            val success = SchoolRepository.deleteTeacher(user.username)
                            runOnUiThread {
                                Toast.makeText(this, if (success) "Account removed" else "Unable to remove account", Toast.LENGTH_SHORT).show()
                                refreshPendingUsers(showErrors = false)
                                actionInProgress = false
                            }
                        }
                        Role.ADMIN -> runOnUiThread {
                            Toast.makeText(this, "Unable to remove account", Toast.LENGTH_SHORT).show()
                            actionInProgress = false
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReviewAndApproveDialog(request: RegistrationRequest) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val roleInput = MaterialAutoCompleteTextView(this).apply {
            setAdapter(ArrayAdapter(this@PendingApprovalsActivity, android.R.layout.simple_list_item_1, listOf("Student", "Teacher")))
            setText(request.role.name.lowercase().replaceFirstChar(Char::uppercase), false)
        }
        val nameInput = TextInputEditText(this).apply {
            hint = "Full name"
            setText(request.fullName)
        }
        val classInput = MaterialAutoCompleteTextView(this).apply {
            setAdapter(ArrayAdapter(this@PendingApprovalsActivity, android.R.layout.simple_list_item_1, SchoolRepository.availableClasses()))
            setText(request.className, false)
            hint = "Class"
        }
        val subjectInput = TextInputEditText(this).apply {
            hint = "Subject"
            setText(request.subject)
        }
        listOf(roleInput, nameInput, classInput, subjectInput).forEach(container::addView)

        AlertDialog.Builder(this)
            .setTitle("Review imported user")
            .setMessage("Complete the missing role or class details before approval.")
            .setView(container)
            .setPositiveButton("Save and approve") { _, _ ->
                val updatedRole = Role.fromLabel(roleInput.text?.toString().orEmpty())
                val updated = request.copy(
                    role = updatedRole,
                    fullName = nameInput.text?.toString().orEmpty().trim().ifBlank { request.fullName },
                    className = classInput.text?.toString().orEmpty().trim(),
                    subject = subjectInput.text?.toString().orEmpty().trim(),
                    needsReview = false
                )
                if (updated.role == Role.STUDENT && updated.className.isBlank()) {
                    Toast.makeText(this, "Student approvals need a class.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                actionInProgress = true
                SchoolRepository.updateRegistrationRequest(updated) { saved ->
                    if (!saved) {
                        runOnUiThread {
                            Toast.makeText(this, "Unable to save imported user details.", Toast.LENGTH_LONG).show()
                            actionInProgress = false
                        }
                        return@updateRegistrationRequest
                    }
                    val result = SchoolRepository.approveUserDetailed(updated.username)
                    runOnUiThread {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        refreshPendingUsers(showErrors = false)
                        actionInProgress = false
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
