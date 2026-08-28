package com.schoolms.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager

class ContentActivity : BaseActivity() {
    private lateinit var contentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_content)

        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.content_title))
        contentText = findViewById(R.id.contentText)
        val editButton = findViewById<MaterialButton>(R.id.editContentButton)
        editButton.visibility = if (SessionManager.currentUser?.role == Role.ADMIN) View.VISIBLE else View.GONE
        editButton.setOnClickListener { showEditContentDialog() }
        bindContent()
    }

    override fun onRepositoryChanged() {
        bindContent()
    }

    private fun bindContent() {
        if (::contentText.isInitialized) {
            contentText.text = SchoolRepository.content()
        }
    }

    private fun showEditContentDialog() {
        val input = EditText(this).apply {
            minLines = 8
            setText(SchoolRepository.content())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit shared school content")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.updateSchoolContent(input.text.toString())
                Toast.makeText(this, if (success) "School content updated" else "Enter school content", Toast.LENGTH_SHORT).show()
                if (success) bindContent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
