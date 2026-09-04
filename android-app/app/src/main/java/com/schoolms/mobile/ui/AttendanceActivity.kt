package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.R
import com.schoolms.mobile.data.MobileAcademicGateway
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.data.StudentProfile
import com.schoolms.mobile.ui.adapter.AttendanceMarkAdapter
import com.schoolms.mobile.ui.adapter.AttendanceMarkEntry
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class AttendanceActivity : BaseActivity() {
    private lateinit var classFilterLayout: TextInputLayout
    private lateinit var classFilterInput: MaterialAutoCompleteTextView
    private lateinit var classAdapter: ArrayAdapter<String>
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var summaryText: TextView
    private lateinit var attendanceRecycler: RecyclerView
    private lateinit var saveButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var editButton: MaterialButton
    private lateinit var backToClassesButton: MaterialButton
    private lateinit var attendanceMarkAdapter: AttendanceMarkAdapter

    private var query: String = ""
    private var selectedClass: String = ""
    private var staffClasses = emptyList<String>()
    private var classStudents = emptyList<StudentProfile>()
    private var filteredStudents = emptyList<StudentProfile>()
    private var marksMap = mutableMapOf<String, Boolean>()
    private var currentEntries = emptyList<AttendanceMarkEntry>()
    private var editDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_attendance)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.attendance_title))

        classFilterLayout = findViewById(R.id.classFilterLayout)
        classFilterInput = findViewById(R.id.classFilterInput)
        classAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        classFilterInput.setAdapter(classAdapter)
        classFilterInput.setOnItemClickListener { _, _, position, _ ->
            classAdapter.getItem(position)?.let {
                selectedClass = it
                editDate = null
                marksMap.clear()
                refreshAttendanceEntries()
            }
        }
        searchLayout = findViewById(R.id.searchLayout)
        searchInput = findViewById(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                refreshAttendanceEntries()
            }
        })

        summaryText = findViewById(R.id.attendanceSummaryText)
        attendanceRecycler = findViewById(R.id.attendanceRecycler)
        attendanceRecycler.layoutManager = LinearLayoutManager(this)
        attendanceMarkAdapter = AttendanceMarkAdapter { username, present ->
            marksMap[username] = present
        }
        saveButton = findViewById(R.id.saveAttendanceButton)
        historyButton = findViewById(R.id.viewClassHistoryButton)
        editButton = findViewById(R.id.editAttendanceButton)
        backToClassesButton = findViewById(R.id.backToClassesButton)

        saveButton.setOnClickListener {
            saveAttendance(user)
        }
        backToClassesButton.setOnClickListener {
            selectedClass = ""
            editDate = null
            marksMap.clear()
            query = ""
            searchInput.text = null
            showClassCards(user)
        }
        historyButton.setOnClickListener {
            if (selectedClass.isBlank()) {
                Toast.makeText(this, "Choose a class to view history", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, ClassRecordsActivity::class.java)
                    .putExtra(ClassRecordsActivity.EXTRA_MODE, ClassRecordsActivity.MODE_ATTENDANCE)
                    .putExtra(ClassRecordsActivity.EXTRA_CLASS_NAME, selectedClass)
            )
        }
        editButton.setOnClickListener {
            showEditAttendanceDatePicker()
        }

        attendanceRecycler.adapter = attendanceMarkAdapter
        bindData()
    }

    private fun bindData() {
        val user = SessionManager.currentUser ?: return
        val isStaff = user.role == Role.ADMIN || user.role == Role.TEACHER

        if (isStaff) {
            refreshClassOptions(user)
        } else {
            classFilterLayout.visibility = View.GONE
            searchLayout.visibility = View.GONE
            saveButton.visibility = View.GONE
            historyButton.visibility = View.GONE
            editButton.visibility = View.GONE
            backToClassesButton.visibility = View.GONE
            displayStudentSummary(user)
        }
    }

    private fun refreshClassOptions(user: com.schoolms.mobile.data.User) {
        MobileAcademicGateway.staffClasses { result ->
            runOnUiThread {
                result.onSuccess { serverClasses ->
                    staffClasses = serverClasses.map { it.name }
                    classAdapter.clear()
                    classAdapter.addAll(staffClasses)
                    classAdapter.notifyDataSetChanged()
                    if (selectedClass.isBlank()) showClassCards(user) else {
                        classFilterInput.setText(selectedClass, false)
                        refreshAttendanceEntries()
                    }
                }.onFailure { error ->
                    summaryText.text = error.message ?: "School classes could not be loaded."
                    attendanceRecycler.adapter = SimpleListAdapter(emptyList())
                }
            }
        }
    }

    private fun showClassCards(user: com.schoolms.mobile.data.User) {
        classFilterLayout.visibility = View.GONE
        searchLayout.visibility = View.GONE
        saveButton.visibility = View.GONE
        historyButton.visibility = View.GONE
        editButton.visibility = View.GONE
        backToClassesButton.visibility = View.GONE

        val rows = staffClasses.map { className ->
            SimpleListItem(
                title = className,
                subtitle = "Server-configured class. Open to load enrolled students.",
                badge = "Open"
            )
        }
        summaryText.text = if (staffClasses.isEmpty()) {
            "No class assigned for attendance."
        } else if (user.role == Role.TEACHER) {
            "Choose your assigned class to mark attendance."
        } else {
            "Choose a class to mark attendance."
        }
        attendanceRecycler.adapter = SimpleListAdapter(rows) { position ->
            val className = staffClasses.getOrNull(position).orEmpty()
            if (className.isBlank()) return@SimpleListAdapter
            selectedClass = className
            editDate = null
            marksMap.clear()
            query = ""
            searchInput.text = null
            classFilterInput.setText(selectedClass, false)
            refreshAttendanceEntries()
        }
    }

    private fun refreshAttendanceEntries() {
        if (selectedClass.isBlank()) return
        classFilterLayout.visibility = View.GONE
        searchLayout.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE
        historyButton.visibility = View.VISIBLE
        editButton.visibility = View.VISIBLE
        backToClassesButton.visibility = View.VISIBLE
        summaryText.text = "$selectedClass - Loading enrolled students…"
        MobileAcademicGateway.staffClassStudents(selectedClass) { result ->
            runOnUiThread {
                result.onSuccess { students ->
                    classStudents = students.map {
                        StudentProfile(
                            username = it.username,
                            fullName = it.fullName,
                            className = selectedClass,
                            rollNumber = it.rollNumber,
                            guardianContact = "",
                            notes = ""
                        )
                    }
                    renderAttendanceEntries()
                }.onFailure { error ->
                    currentEntries = emptyList()
                    attendanceMarkAdapter.update(emptyList())
                    saveButton.isEnabled = false
                    summaryText.text = error.message ?: "Enrolled students could not be loaded."
                }
            }
        }
    }

    private fun renderAttendanceEntries() {
        filteredStudents = classStudents.filter {
            it.fullName.contains(query, true) ||
                it.username.contains(query, true) ||
                it.rollNumber.contains(query, true)
        }

        val activeEditDate = editDate
        val entries = filteredStudents.map { student ->
            val savedEditMark = activeEditDate?.let { SchoolRepository.attendanceMarkForDate(student.username, selectedClass, it) }
            val alreadyMarked = activeEditDate == null && SchoolRepository.wasAttendanceMarkedToday(student.username, selectedClass)
            val defaultPresent = marksMap.getOrPut(student.username) {
                savedEditMark?.present
                    ?: (!alreadyMarked || SchoolRepository.attendanceStatusLabel(student.username, selectedClass).contains("Present"))
            }
            val statusLabel = when {
                activeEditDate != null && savedEditMark != null -> "Editing $activeEditDate: ${if (savedEditMark.present) "Present" else "Absent"}"
                activeEditDate != null -> "No saved mark for $activeEditDate"
                alreadyMarked -> SchoolRepository.attendanceStatusLabel(student.username, selectedClass)
                else -> "Mark presence for today"
            }
            AttendanceMarkEntry(
                username = student.username,
                fullName = student.fullName,
                className = student.className,
                present = defaultPresent,
                alreadyMarked = alreadyMarked,
                statusLabel = statusLabel,
                locked = activeEditDate != null && savedEditMark == null
            )
        }

        currentEntries = entries
        attendanceMarkAdapter.update(entries)
        val pending = entries.count { !it.alreadyMarked }
        val total = entries.size
        summaryText.text = if (activeEditDate == null) {
            "$selectedClass - ${getString(R.string.attendance_pending_count, total, pending)}"
        } else {
            "$selectedClass - Editing attendance for $activeEditDate"
        }
        saveButton.text = if (activeEditDate == null) getString(R.string.save_attendance) else "Save edit"
        saveButton.isEnabled = if (activeEditDate == null) pending > 0 else entries.any { !it.locked }
        attendanceRecycler.adapter = attendanceMarkAdapter
    }

    private fun saveAttendance(user: com.schoolms.mobile.data.User) {
        if (selectedClass.isBlank()) return
        val activeEditDate = editDate
        if (activeEditDate != null) {
            val edited = currentEntries
                .filter { !it.locked }
                .associate { entry -> entry.username to (marksMap[entry.username] ?: entry.present) }
            saveAttendanceOnServer(edited, activeEditDate, "attendance records updated")
            return
        }
        val pending = currentEntries.filter { !it.alreadyMarked }.associate { entry ->
            entry.username to (marksMap[entry.username] ?: entry.present)
        }
        if (pending.isEmpty()) {
            Toast.makeText(this, "All students are already marked", Toast.LENGTH_SHORT).show()
            return
        }
        saveAttendanceOnServer(pending, "", "attendance records saved")
    }

    private fun saveAttendanceOnServer(marks: Map<String, Boolean>, attendanceDate: String, successSuffix: String) {
        saveButton.isEnabled = false
        MobileAcademicGateway.saveAttendance(selectedClass, marks, attendanceDate) { result ->
            runOnUiThread {
                saveButton.isEnabled = true
                result.onSuccess { savedCount ->
                    SchoolRepository.cacheServerAttendanceBatch(
                        selectedClass,
                        marks,
                        attendanceDate.ifBlank { java.time.LocalDate.now().toString() }
                    )
                    Toast.makeText(this, "$savedCount $successSuffix", Toast.LENGTH_SHORT).show()
                    editDate = null
                    marksMap.clear()
                    SchoolRepository.refreshPrivateAcademicContent { }
                    refreshAttendanceEntries()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "Attendance could not be saved on the school server.", Toast.LENGTH_LONG).show()
                    refreshAttendanceEntries()
                }
            }
        }
    }

    private fun showEditAttendanceDatePicker() {
        if (selectedClass.isBlank()) {
            Toast.makeText(this, "Choose a class first", Toast.LENGTH_SHORT).show()
            return
        }
        val dates = SchoolRepository.attendanceDatesForClass(selectedClass)
        if (dates.isEmpty()) {
            Toast.makeText(this, "No saved attendance dates for $selectedClass", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Edit attendance date")
            .setItems(dates.toTypedArray()) { _, which ->
                editDate = dates[which]
                marksMap.clear()
                refreshAttendanceEntries()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun displayStudentSummary(user: com.schoolms.mobile.data.User) {
        val records = SchoolRepository.attendanceHistoryForStudent(user.username, user.className)
        val rows = if (records.isEmpty()) {
            listOf(SimpleListItem("Attendance", "No daily history yet", "Pending"))
        } else {
            records.take(5).map {
                SimpleListItem("Date ${it.date.substring(5)}", if (it.present) "Present" else "Absent", if (it.present) "P" else "A")
            }
        }
        summaryText.text = SchoolRepository.attendanceSummaryText(user.username)
        attendanceRecycler.adapter = SimpleListAdapter(rows)
    }

    override fun onRepositoryChanged() {
        bindData()
    }
}
