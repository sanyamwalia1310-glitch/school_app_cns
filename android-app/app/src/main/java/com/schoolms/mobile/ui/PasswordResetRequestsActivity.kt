package com.schoolms.mobile.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.R
import com.schoolms.mobile.data.PasswordResetRequest
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class PasswordResetRequestsActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var refreshButton: MaterialButton
    private lateinit var adapter: SimpleListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin(Role.ADMIN)) return
        setContentView(R.layout.activity_info_list)

        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Password reset requests")
        refreshButton = findViewById<MaterialButton>(R.id.addItemButton)
        recyclerView = findViewById(R.id.recyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SimpleListAdapter(emptyList(), animateEntries = false) { position ->
            SchoolRepository.pendingPasswordResetRequests().getOrNull(position)?.let(::showApproveDialog)
        }
        recyclerView.adapter = adapter

        refreshButton.visibility = View.VISIBLE
        refreshButton.text = "Refresh"
        refreshButton.setOnClickListener { loadRequests(forceAdminSync = true) }

        bindItems()
        loadRequests(forceAdminSync = false)
    }

    override fun onResume() {
        super.onResume()
        bindItems()
    }

    override fun onRepositoryChanged() {
        bindItems()
    }

    private fun loadRequests(forceAdminSync: Boolean) {
        SessionManager.ensureFirebaseSession { sessionResult ->
            runOnUiThread {
                sessionResult.onFailure {
                    Toast.makeText(this, it.message ?: "Admin session expired.", Toast.LENGTH_LONG).show()
                }.onSuccess {
                    SchoolRepository.ensureAdminSessionAccessIfNeeded(force = forceAdminSync) {
                        runOnUiThread {
                            SchoolRepository.refreshPasswordResetRequests { success ->
                                runOnUiThread {
                                    if (!success) {
                                        Toast.makeText(this, "Unable to refresh reset requests.", Toast.LENGTH_SHORT).show()
                                    }
                                    bindItems()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bindItems() {
        val items = SchoolRepository.passwordResetRequestItems()
        adapter.updateItems(items)
        emptyStateText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyStateText.text = "No password reset requests."
    }

    private fun showApproveDialog(request: PasswordResetRequest) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val tempPasswordInput = TextInputEditText(this).apply {
            setText(defaultTemporaryPassword(request))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val layout = TextInputLayout(this).apply {
            hint = "Temporary password"
            addView(tempPasswordInput)
        }
        container.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("Approve reset for ${request.username}")
            .setMessage(
                "Role: ${request.role.name.lowercase().replaceFirstChar(Char::uppercase)}\n" +
                    "Contact: ${request.verificationContact.ifBlank { request.mobileNumber }}\n\n" +
                    "Issue a temporary password. The user will be forced to change it after login."
            )
            .setView(container)
            .setPositiveButton("Issue password") { _, _ ->
                val tempPassword = tempPasswordInput.text?.toString().orEmpty()
                SchoolRepository.approvePasswordResetRequest(request.username, tempPassword) { result ->
                    runOnUiThread {
                        result.onSuccess {
                            bindItems()
                            showTemporaryPasswordIssued(request.username, tempPassword)
                        }.onFailure { error ->
                            Toast.makeText(this, error.message ?: "Unable to approve reset request.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTemporaryPasswordIssued(username: String, temporaryPassword: String) {
        AlertDialog.Builder(this)
            .setTitle("Temporary password ready")
            .setMessage(
                "Share this temporary password with $username:\n\n$temporaryPassword\n\n" +
                    "The user will be required to change it after login."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun defaultTemporaryPassword(request: PasswordResetRequest): String {
        val suffix = (System.currentTimeMillis() % 1000000L).toString().padStart(6, '0')
        val prefix = if (request.role == Role.TEACHER) "Teach" else "Study"
        return "${prefix}@${suffix}"
    }
}
