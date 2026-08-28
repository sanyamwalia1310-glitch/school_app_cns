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

class AdmissionEnquiryActivity : BaseActivity() {
    private var isAdminView = false
    private var admissionRecycler: RecyclerView? = null
    private var currentUsername: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_admission_enquiry)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.admission_title))
        currentUsername = user.username.trim().lowercase()
        val studentName = findViewById<TextInputEditText>(R.id.studentNameInput)
        val contact = findViewById<TextInputEditText>(R.id.contactInput)
        val grade = findViewById<TextInputEditText>(R.id.gradeInput)
        val message = findViewById<TextInputEditText>(R.id.enquiryMessageInput)
        val submitButton = findViewById<MaterialButton>(R.id.submitButton)
        admissionRecycler = findViewById(R.id.admissionRecycler)
        admissionRecycler?.layoutManager = LinearLayoutManager(this)

        if (user.role == Role.ADMIN) {
            isAdminView = true
            findViewById<View>(R.id.adminAdmissionHeading).visibility = View.VISIBLE
            findViewById<View>(R.id.studentNameLayout).visibility = View.GONE
            findViewById<View>(R.id.contactLayout).visibility = View.GONE
            findViewById<View>(R.id.gradeLayout).visibility = View.GONE
            findViewById<View>(R.id.enquiryMessageLayout).visibility = View.GONE
            submitButton.visibility = View.GONE
            admissionRecycler?.visibility = View.VISIBLE
            bindAdminAdmissions()
            return
        }

        findViewById<View>(R.id.myAdmissionHeading).visibility = View.VISIBLE
        admissionRecycler?.visibility = View.VISIBLE
        bindMyAdmissions()

        submitButton.setOnClickListener {
            val success = SchoolRepository.submitAdmission(
                studentName = studentName.text?.toString().orEmpty(),
                contact = contact.text?.toString().orEmpty(),
                grade = grade.text?.toString().orEmpty(),
                message = message.text?.toString().orEmpty()
            )
            if (!success) {
                Toast.makeText(this, "Fill the enquiry details", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Admission enquiry sent", Toast.LENGTH_SHORT).show()
                studentName.text = null
                contact.text = null
                grade.text = null
                message.text = null
                bindMyAdmissions()
            }
        }
    }

    override fun onRepositoryChanged() {
        if (isAdminView) {
            bindAdminAdmissions()
        } else {
            bindMyAdmissions()
        }
    }

    private fun bindMyAdmissions() {
        val entries = SchoolRepository.admissionEntries()
            .filter { it.submitterUsername.orEmpty().trim().lowercase() == currentUsername }
        admissionRecycler?.adapter = SimpleListAdapter(
            entries.map {
                val adminReply = it.adminReply.orEmpty()
                val replyLine = if (adminReply.isBlank()) "\n\nResponse: Not replied yet" else "\n\nResponse:\n$adminReply"
                SimpleListItem(
                    it.studentName,
                    "Contact: ${it.contact}\nGrade: ${it.grade}\n${it.message}$replyLine",
                    if (adminReply.isBlank()) "Pending" else "Replied"
                )
            }
        ) { position ->
            showAdmissionDetailsDialog(entries[position])
        }
    }

    private fun bindAdminAdmissions() {
        val entries = SchoolRepository.admissionEntries()
        admissionRecycler?.adapter = SimpleListAdapter(
            entries.map {
                val adminReply = it.adminReply.orEmpty()
                val replyLine = if (adminReply.isBlank()) "\n\nAdmin reply: Not replied yet" else "\n\nAdmin reply:\n$adminReply"
                SimpleListItem(it.studentName, "Contact: ${it.contact}\nGrade: ${it.grade}\n${it.message}$replyLine", if (adminReply.isBlank()) "Pending" else "Replied")
            }
        ) { position ->
            showAdmissionActions(position, entries[position])
        }
    }

    private fun showAdmissionActions(index: Int, entry: com.schoolms.mobile.data.AdmissionEntry) {
        val options = arrayOf("View response", "Reply", "Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle(entry.studentName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAdmissionDetailsDialog(entry)
                    1 -> showReplyDialog(index, entry.adminReply.orEmpty())
                    2 -> showEditAdmissionDialog(index, entry)
                    3 -> confirmDeleteAdmission(index, entry.studentName)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAdmissionDetailsDialog(entry: com.schoolms.mobile.data.AdmissionEntry) {
        val replyLine = entry.adminReply.orEmpty().ifBlank { "Not replied yet" }
        AlertDialog.Builder(this)
            .setTitle(entry.studentName)
            .setMessage(
                "Contact: ${entry.contact}\n" +
                    "Grade: ${entry.grade}\n\n" +
                    "Enquiry:\n${entry.message}\n\n" +
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
            hint = "Write admission reply"
            minLines = 3
            setText(currentReply)
        }
        container.addView(replyInput)
        AlertDialog.Builder(this)
            .setTitle("Reply to admission enquiry")
            .setView(container)
            .setPositiveButton("Send reply") { _, _ ->
                val success = SchoolRepository.replyToAdmission(index, replyInput.text.toString())
                Toast.makeText(this, if (success) "Reply sent" else "Write a reply first", Toast.LENGTH_SHORT).show()
                bindAdminAdmissions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditAdmissionDialog(index: Int, entry: com.schoolms.mobile.data.AdmissionEntry) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val studentNameInput = TextInputEditText(this).apply {
            hint = "Student name"
            setText(entry.studentName)
        }
        val contactInput = TextInputEditText(this).apply {
            hint = "Contact"
            setText(entry.contact)
        }
        val gradeInput = TextInputEditText(this).apply {
            hint = "Grade"
            setText(entry.grade)
        }
        val messageInput = TextInputEditText(this).apply {
            hint = "Enquiry"
            minLines = 3
            setText(entry.message)
        }
        container.addView(studentNameInput)
        container.addView(contactInput)
        container.addView(gradeInput)
        container.addView(messageInput)
        AlertDialog.Builder(this)
            .setTitle("Edit admission enquiry")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.editAdmission(
                    index = index,
                    studentName = studentNameInput.text?.toString().orEmpty(),
                    contact = contactInput.text?.toString().orEmpty(),
                    grade = gradeInput.text?.toString().orEmpty(),
                    message = messageInput.text?.toString().orEmpty()
                )
                Toast.makeText(this, if (success) "Admission updated" else "Fill all fields", Toast.LENGTH_SHORT).show()
                bindAdminAdmissions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAdmission(index: Int, studentName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete admission enquiry")
            .setMessage("Delete the enquiry from $studentName?")
            .setPositiveButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteAdmission(index)
                Toast.makeText(this, if (success) "Admission deleted" else "Unable to delete admission", Toast.LENGTH_SHORT).show()
                bindAdminAdmissions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
