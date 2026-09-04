package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class MarksActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var introText: TextView
    private lateinit var flowText: TextView
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_marks)

        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.marks_title))

        introText = findViewById(R.id.marksIntroText)
        flowText = findViewById(R.id.marksFlowText)
        recyclerView = findViewById(R.id.marksRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<TextInputEditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                bind()
            }
        })

        animateContentEntrance(
            introText,
            flowText,
            findViewById(R.id.searchInput),
            recyclerView
        )

        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    override fun onRepositoryChanged() {
        bind()
    }

    private fun bind() {
        val user = SessionManager.currentUser ?: return
        val isStaff = user.role == Role.ADMIN || user.role == Role.TEACHER

        introText.text = when {
            isStaff -> "Open a class to manage marks and grades inside the class records screen."
            else -> "Your saved marks and grade history are shown below."
        }
        flowText.text = when {
            isStaff -> "Classes -> Students -> Add grades"
            else -> "Marks and grades"
        }

        if (isStaff) {
            bindStaffClasses(user)
        } else {
            bindStudentMarks(user)
        }
    }

    private fun bindStaffClasses(user: com.schoolms.mobile.data.User) {
        val rows = SchoolRepository.classItems(user).filter {
            it.title.contains(query, true) || it.subtitle.contains(query, true) || it.badge.orEmpty().contains(query, true)
        }
        recyclerView.adapter = SimpleListAdapter(rows) { position ->
            val className = rows.getOrNull(position)?.title.orEmpty()
            if (className.isNotBlank()) {
                startActivity(
                    Intent(this, ClassRecordsActivity::class.java)
                        .putExtra(ClassRecordsActivity.EXTRA_MODE, ClassRecordsActivity.MODE_MARKS)
                        .putExtra(ClassRecordsActivity.EXTRA_CLASS_NAME, className)
                )
            }
        }
    }

    private fun bindStudentMarks(user: com.schoolms.mobile.data.User) {
        val rows = SchoolRepository.marksFor(user).map {
            SimpleListItem(
                title = it.subject,
                subtitle = "${it.assessment}\nScore: ${it.score}/${it.outOf}",
                badge = it.grade
            )
        }
        recyclerView.adapter = if (rows.isEmpty()) {
            SimpleListAdapter(listOf(SimpleListItem("Marks", "No marks recorded yet.", "Pending")))
        } else {
            SimpleListAdapter(rows) {
                startActivity(
                    Intent(this, StudentDetailActivity::class.java)
                        .putExtra(StudentDetailActivity.EXTRA_USERNAME, user.username)
                        .putExtra(StudentDetailActivity.EXTRA_SECTION, StudentDetailActivity.SECTION_MARKS)
                )
            }
        }
    }
}
