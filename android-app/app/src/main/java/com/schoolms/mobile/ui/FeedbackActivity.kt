package com.schoolms.mobile.ui

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
import com.google.android.material.textfield.TextInputEditText
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class FeedbackActivity : BaseActivity() {
    private var isAdminView = false
    private var feedbackRecycler: RecyclerView? = null
    private var currentUsername: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_feedback)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.feedback_title))
        currentUsername = user.username.trim().lowercase()
        val name = findViewById<TextInputEditText>(R.id.nameInput)
        val classOrRole = findViewById<TextInputEditText>(R.id.classInput)
        val message = findViewById<TextInputEditText>(R.id.messageInput)
        val submitButton = findViewById<MaterialButton>(R.id.submitButton)
        feedbackRecycler = findViewById(R.id.feedbackRecycler)
        feedbackRecycler?.layoutManager = LinearLayoutManager(this)

        if (user.role == Role.ADMIN) {
            isAdminView = true
            findViewById<View>(R.id.adminFeedbackHeading).visibility = View.VISIBLE
            findViewById<View>(R.id.nameLayout).visibility = View.GONE
            findViewById<View>(R.id.classLayout).visibility = View.GONE
            findViewById<View>(R.id.messageLayout).visibility = View.GONE
            submitButton.visibility = View.GONE
            feedbackRecycler?.visibility = View.VISIBLE
            bindFeedback()
            return
        }

        findViewById<View>(R.id.myFeedbackHeading).visibility = View.VISIBLE
        feedbackRecycler?.visibility = View.VISIBLE
        bindMyFeedback()

        submitButton.setOnClickListener {
            val success = SchoolRepository.submitFeedback(
                name = name.text?.toString().orEmpty(),
                roleOrClass = classOrRole.text?.toString().orEmpty(),
                message = message.text?.toString().orEmpty()
            )
            if (!success) {
                Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Feedback submitted successfully", Toast.LENGTH_SHORT).show()
                name.text = null
                classOrRole.text = null
                message.text = null
                bindMyFeedback()
            }
        }
    }

    override fun onRepositoryChanged() {
        if (isAdminView) {
            bindFeedback()
        } else {
            bindMyFeedback()
        }
    }

    private fun bindMyFeedback() {
        val entries = SchoolRepository.feedbackEntries()
            .filter { it.submitterUsername.orEmpty().trim().lowercase() == currentUsername }
        feedbackRecycler?.adapter = SimpleListAdapter(
            entries.map {
                val adminReply = it.adminReply.orEmpty()
                val replyLine = if (adminReply.isBlank()) "\n\nResponse: Not replied yet" else "\n\nResponse:\n$adminReply"
                SimpleListItem(
                    it.name,
                    "${it.roleOrClass}\n${it.message}$replyLine",
                    if (adminReply.isBlank()) "Pending" else "Replied"
                )
            }
        ) { position ->
            showFeedbackDetailsDialog(entries[position])
        }
    }

    private fun bindFeedback() {
        val entries = SchoolRepository.feedbackEntries()
        feedbackRecycler?.adapter = SimpleListAdapter(
            entries.map {
                val adminReply = it.adminReply.orEmpty()
                val replyLine = if (adminReply.isBlank()) "\n\nAdmin reply: Not replied yet" else "\n\nAdmin reply:\n$adminReply"
                SimpleListItem(it.name, "${it.roleOrClass}\n${it.message}$replyLine", if (adminReply.isBlank()) "Pending" else "Replied")
            }
        ) { position ->
            showFeedbackActions(position, entries[position])
        }
    }

    private fun showFeedbackActions(index: Int, entry: com.schoolms.mobile.data.FeedbackEntry) {
        val options = arrayOf("View response", "Reply", "Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFeedbackDetailsDialog(entry)
                    1 -> showReplyDialog(index, entry.adminReply.orEmpty())
                    2 -> showEditFeedbackDialog(index, entry)
                    3 -> confirmDeleteFeedback(index, entry.name)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFeedbackDetailsDialog(entry: com.schoolms.mobile.data.FeedbackEntry) {
        val replyLine = entry.adminReply.orEmpty().ifBlank { "Not replied yet" }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(
                "Role/Class: ${entry.roleOrClass}\n\n" +
                    "Feedback:\n${entry.message}\n\n" +
                    "Response:\n$replyLine"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showReplyDialog(index: Int, currentReply: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val replyInput = EditText(this).apply {
            hint = "Write admin reply"
            minLines = 3
            setText(currentReply)
        }
        container.addView(replyInput)
        AlertDialog.Builder(this)
            .setTitle("Reply to feedback")
            .setView(container)
            .setPositiveButton("Send reply") { _, _ ->
                val success = SchoolRepository.replyToFeedback(index, replyInput.text.toString())
                Toast.makeText(this, if (success) "Reply sent" else "Write a reply first", Toast.LENGTH_SHORT).show()
                bindFeedback()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditFeedbackDialog(index: Int, entry: com.schoolms.mobile.data.FeedbackEntry) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val nameInput = TextInputEditText(this).apply {
            hint = "Name"
            setText(entry.name)
        }
        val classInput = TextInputEditText(this).apply {
            hint = "Role / Class"
            setText(entry.roleOrClass)
        }
        val messageInput = TextInputEditText(this).apply {
            hint = "Feedback"
            minLines = 3
            setText(entry.message)
        }
        container.addView(nameInput)
        container.addView(classInput)
        container.addView(messageInput)
        AlertDialog.Builder(this)
            .setTitle("Edit feedback")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.editFeedback(
                    index = index,
                    name = nameInput.text?.toString().orEmpty(),
                    roleOrClass = classInput.text?.toString().orEmpty(),
                    message = messageInput.text?.toString().orEmpty()
                )
                Toast.makeText(this, if (success) "Feedback updated" else "Fill all fields", Toast.LENGTH_SHORT).show()
                bindFeedback()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFeedback(index: Int, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete feedback")
            .setMessage("Delete feedback from $name?")
            .setPositiveButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteFeedback(index)
                Toast.makeText(this, if (success) "Feedback deleted" else "Unable to delete feedback", Toast.LENGTH_SHORT).show()
                bindFeedback()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
