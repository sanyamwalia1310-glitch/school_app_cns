package com.schoolms.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.schoolms.mobile.R
import com.schoolms.mobile.data.MobileAcademicGateway
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

/** Profile-authorized Tests & Assignments list backed by Flask, not shared Firestore. */
class TestsActivity : BaseActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_info_list)
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Tests & assignments")
        findViewById<View>(R.id.addItemButton).visibility = View.GONE
        recycler = findViewById<RecyclerView>(R.id.recyclerView).apply { layoutManager = LinearLayoutManager(this@TestsActivity) }
        empty = findViewById(R.id.emptyStateText)
        bind()
    }

    override fun onResume() {
        super.onResume()
        SchoolRepository.refreshPrivateAcademicContent { }
        bind()
    }

    override fun onRepositoryChanged() = bind()

    private fun bind() {
        if (!::recycler.isInitialized) return
        val rows = SchoolRepository.privateTestsForActiveProfile()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "No tests or assignments are assigned to this selected profile."
        recycler.adapter = SimpleListAdapter(rows.map { test ->
            SimpleListItem(
                test.title,
                buildString {
                    append("${test.subject} • ${test.date}\n${test.className}\nTeacher: ${test.teacher}")
                    if (test.maximumMarks != null) append(" • Max: ${test.maximumMarks}")
                    if (test.syllabus.isNotBlank()) append("\nSyllabus: ${test.syllabus}")
                    if (test.instructions.isNotBlank()) append("\n${test.instructions}")
                },
                if (test.attachments.isEmpty()) "Scheduled" else "Files"
            )
        }) { position ->
            val test = rows.getOrNull(position) ?: return@SimpleListAdapter
            if (test.attachments.isEmpty()) return@SimpleListAdapter
            val labels = test.attachments.mapIndexed { index, file -> file.name.ifBlank { "Attachment ${index + 1}" } }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(test.title)
                .setItems(labels.toTypedArray()) { _, which ->
                    MobileAcademicGateway.attachmentDownload(test.attachments[which].id) { result ->
                        runOnUiThread {
                            result.onSuccess { download ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download.url))) }
                                catch (_: ActivityNotFoundException) { Toast.makeText(this, "No app found to open attachment", Toast.LENGTH_LONG).show() }
                            }.onFailure { Toast.makeText(this, it.message ?: "Unable to open attachment", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
}
