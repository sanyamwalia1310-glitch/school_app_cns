package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class ClassManagementActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_class_management)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.classes_title))
        val addClassButton = findViewById<MaterialButton>(R.id.addClassButton)
        addClassButton.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        addClassButton.setOnClickListener { showAddDialog() }
        findViewById<TextInputEditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                bind(user)
            }
        })

        recyclerView = findViewById(R.id.classesRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this@ClassManagementActivity)
        bind(user)
    }

    override fun onResume() {
        super.onResume()
        val user = SessionManager.currentUser ?: return
        if (::recyclerView.isInitialized) {
            bind(user)
        }
    }

    private fun bind(user: com.schoolms.mobile.data.User) {
        val rows = SchoolRepository.classItems(user).filter {
            it.title.contains(query, true) || it.subtitle.contains(query, true)
        }
        recyclerView.adapter = SimpleListAdapter(rows) { position ->
            val className = rows[position].title
            if (SchoolRepository.classExists(className) || user.role != Role.STUDENT) {
                startActivity(
                    Intent(this, ClassRecordsActivity::class.java)
                        .putExtra(ClassRecordsActivity.EXTRA_MODE, ClassRecordsActivity.MODE_PROFILES)
                        .putExtra(ClassRecordsActivity.EXTRA_CLASS_NAME, className)
                )
            }
        }
    }

    override fun onRepositoryChanged() {
        val user = SessionManager.currentUser ?: return
        if (::recyclerView.isInitialized) {
            bind(user)
        }
    }

    private fun showAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val titleInput = EditText(this).apply { hint = "Class name" }
        val subtitleInput = EditText(this).apply { hint = "Description" }
        val badgeInput = EditText(this).apply { hint = "Badge" }
        listOf(titleInput, subtitleInput, badgeInput).forEach(container::addView)

        AlertDialog.Builder(this)
            .setTitle("Add class")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.addClassItem(titleInput.text.toString(), subtitleInput.text.toString(), badgeInput.text.toString())
                Toast.makeText(this, if (success) "Class added" else "Fill required fields", Toast.LENGTH_SHORT).show()
                bind(SessionManager.currentUser ?: return@setPositiveButton)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
