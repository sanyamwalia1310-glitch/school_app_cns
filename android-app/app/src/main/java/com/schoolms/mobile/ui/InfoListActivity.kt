package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class InfoListActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_info_list)

        mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val user = SessionManager.currentUser ?: return
        if (mode == "admin" && user.role != Role.ADMIN) {
            Toast.makeText(this, "Admin access only", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val addItemButton = findViewById<MaterialButton>(R.id.addItemButton)
        recyclerView = findViewById(R.id.recyclerView)

        val title = when (mode) {
            "facilities" -> "Facilities"
            "events" -> "Events"
            "notifications" -> "Announcements"
            "admin" -> "Admin panel"
            else -> "Details"
        }
        setupToolbar(toolbar, title)
        val canManageList = mode != "admin" && (user.role == Role.ADMIN || (user.role == Role.TEACHER && mode == "notifications"))
        addItemButton.visibility = if (canManageList) View.VISIBLE else View.GONE
        addItemButton.text = if (mode == "notifications") "Publish" else "Add item"
        addItemButton.setOnClickListener { showAddDialog(title) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        bindItems()
    }

    override fun onResume() {
        super.onResume()
        bindItems()
    }

    override fun onRepositoryChanged() {
        bindItems()
    }

    private fun currentItems() = when (mode) {
        "facilities" -> SchoolRepository.facilities()
        "events" -> SchoolRepository.events()
        "notifications" -> SchoolRepository.announcements()
        "admin" -> SchoolRepository.adminPanelItems()
        else -> emptyList()
    }

    private fun showAddDialog(title: String) {
        val isAnnouncementMode = mode == "notifications"
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val titleInput = EditText(this).apply { hint = if (isAnnouncementMode) "Announcement title" else "$title title" }
        val descriptionInput = EditText(this).apply { hint = if (isAnnouncementMode) "Announcement message" else "Description" }
        val badgeInput = EditText(this).apply { hint = "Badge" }
        container.addView(titleInput)
        container.addView(descriptionInput)
        if (!isAnnouncementMode) {
            container.addView(badgeInput)
        }

        val dialogTitle = if (isAnnouncementMode) "Publish announcement" else "Add $title"
        val positiveLabel = if (isAnnouncementMode) "Publish" else "Save"
        val successMessage = if (isAnnouncementMode) "Announcement published" else "$title added"

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(container)
            .setPositiveButton(positiveLabel) { _, _ ->
                val success = SchoolRepository.addManagedItem(
                    mode = mode,
                    title = titleInput.text.toString(),
                    subtitle = descriptionInput.text.toString(),
                    badge = if (isAnnouncementMode) "" else badgeInput.text.toString()
                )
                Toast.makeText(this, if (success) successMessage else "Fill all required fields", Toast.LENGTH_SHORT).show()
                recyclerView.adapter = SimpleListAdapter(currentItems())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindItems() {
        if (!::recyclerView.isInitialized) return
        val user = SessionManager.currentUser ?: return
        val items = currentItems()
        recyclerView.adapter = when {
            mode == "admin" -> SimpleListAdapter(items) { position ->
                openAdminSection(items[position].title)
            }
            user.role == Role.ADMIN || (user.role == Role.TEACHER && mode == "notifications") -> SimpleListAdapter(items) { position ->
                showEditDialog(position, items[position])
            }
            else -> SimpleListAdapter(items)
        }
    }

    private fun showEditDialog(index: Int, currentItem: com.schoolms.mobile.data.SimpleListItem) {
        val isAnnouncementMode = mode == "notifications"
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val titleInput = EditText(this).apply { hint = if (isAnnouncementMode) "Announcement title" else "Title"; setText(currentItem.title) }
        val descriptionInput = EditText(this).apply { hint = if (isAnnouncementMode) "Announcement message" else "Description"; setText(currentItem.subtitle) }
        val badgeInput = EditText(this).apply { hint = "Badge"; setText(currentItem.badge.orEmpty()) }
        container.addView(titleInput)
        container.addView(descriptionInput)
        if (!isAnnouncementMode) {
            container.addView(badgeInput)
        }

        AlertDialog.Builder(this)
            .setTitle(if (isAnnouncementMode) "Edit announcement" else "Edit item")
            .setView(container)
            .setPositiveButton(if (isAnnouncementMode) "Publish" else "Save") { _, _ ->
                val success = SchoolRepository.updateManagedItem(
                    mode = mode,
                    index = index,
                    title = titleInput.text.toString(),
                    subtitle = descriptionInput.text.toString(),
                    badge = if (isAnnouncementMode) "" else badgeInput.text.toString()
                )
                Toast.makeText(this, if (success) (if (isAnnouncementMode) "Announcement updated" else "Item updated") else "Fill all required fields", Toast.LENGTH_SHORT).show()
                bindItems()
            }
            .setNeutralButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteManagedItem(mode, index)
                Toast.makeText(this, if (success) (if (isAnnouncementMode) "Announcement deleted" else "Item deleted") else "Unable to delete item", Toast.LENGTH_SHORT).show()
                bindItems()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAdminSection(title: String) {
        when (title.lowercase()) {
            "users", "subjects", "teachers", "students & subjects", "student profiles" ->
                startActivity(Intent(this, StudentManagementActivity::class.java))
            "classes" ->
                startActivity(Intent(this, ClassManagementActivity::class.java))
            "announcements", "notifications" ->
                startActivity(Intent(this, InfoListActivity::class.java).putExtra(EXTRA_MODE, "notifications"))
            "facilities" ->
                startActivity(Intent(this, FacilitiesActivity::class.java))
            "events" ->
                startActivity(Intent(this, InfoListActivity::class.java).putExtra(EXTRA_MODE, "events"))
            "admissions" ->
                startActivity(Intent(this, AdmissionEnquiryActivity::class.java))
            "password reset requests" ->
                startActivity(Intent(this, PasswordResetRequestsActivity::class.java))
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}
