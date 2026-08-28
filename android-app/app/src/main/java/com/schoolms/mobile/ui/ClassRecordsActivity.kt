package com.schoolms.mobile.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.R
import com.schoolms.mobile.data.HomeworkItem
import com.schoolms.mobile.data.MobileAcademicGateway
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.ui.adapter.SimpleListAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClassRecordsActivity : BaseActivity() {
    private val assessmentOptions = listOf("Term 1", "Term 2", "Term 3", "Custom")
    private lateinit var recyclerView: RecyclerView
    private lateinit var className: String
    private lateinit var mode: String
    private var markStudentInput: MaterialAutoCompleteTextView? = null
    private var markSubjectInput: MaterialAutoCompleteTextView? = null
    private var homeworkSubjectInput: MaterialAutoCompleteTextView? = null
    private var query: String = ""
    private var selectedFileNames: MutableList<String> = mutableListOf()
    private var selectedFileUris: MutableList<Uri> = mutableListOf()
    private var selectedCameraBitmaps: MutableList<Pair<String, Bitmap>> = mutableListOf()
    private var editingHomeworkId: Int? = null
    private var editingAttachmentNames: List<String> = emptyList()
    private var editingAttachmentUrls: List<String> = emptyList()
    private var classStudents = emptyList<com.schoolms.mobile.data.StudentProfile>()
    private var studentLabels = emptyList<String>()
    private var subjectNames = emptyList<String>()
    private var homeworkComposerOpen = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedFileUris.clear()
        selectedFileUris.addAll(uris)
        selectedCameraBitmaps.clear()
        selectedFileNames = uris.map { resolveDisplayName(it) }.toMutableList()
        updateSelectedAttachmentText()
    }

    private val cameraPreview = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            if (selectedFileUris.isNotEmpty() || selectedCameraBitmaps.isEmpty()) {
                selectedFileNames.clear()
            }
            selectedFileUris.clear()
            val name = "homework_camera_${System.currentTimeMillis()}.jpg"
            selectedCameraBitmaps.add(name to bitmap)
            selectedFileNames.add(name)
            updateSelectedAttachmentText()
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index).orEmpty().ifBlank { getString(R.string.no_file_selected) }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.no_file_selected)
    }

    private fun selectedAttachmentsLabel(): String =
        if (selectedFileNames.isEmpty()) {
            getString(R.string.no_file_selected)
        } else {
            selectedFileNames.joinToString(limit = 3, truncated = " +${selectedFileNames.size - 3} more")
        }

    private fun updateSelectedAttachmentText() {
        findViewById<TextView>(R.id.selectedFileText).text = selectedAttachmentsLabel()
    }

    private fun resetSelectedAttachments() {
        selectedFileNames.clear()
        selectedFileUris.clear()
        selectedCameraBitmaps.clear()
        updateSelectedAttachmentText()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_class_records)

        val user = SessionManager.currentUser ?: return
        className = intent.getStringExtra(EXTRA_CLASS_NAME).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val canOpenClass = when (mode) {
            MODE_ATTENDANCE -> SchoolRepository.canMarkAttendanceForClass(user, className)
            MODE_PROFILES, MODE_MARKS, MODE_HOMEWORK -> user.role == Role.ADMIN || user.role == Role.TEACHER
            else -> SchoolRepository.canAccessClass(user, className)
        }
        if (!canOpenClass) {
            Toast.makeText(this, "You can access only the allowed class records", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val teacherName = SchoolRepository.teacherNameForClass(className)
        findViewById<TextView>(R.id.classTeacherName).text = "Class teacher: $teacherName"
        findViewById<TextView>(R.id.classHeadLabel).text = "Head of class: $teacherName"

        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "$mode - $className")
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<TextInputEditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                bind()
            }
        })

        val createMarkSection = findViewById<View>(R.id.createMarkSection)
        val toggleHomeworkComposerButton = findViewById<MaterialButton>(R.id.toggleHomeworkComposerButton)

        if (mode == MODE_HOMEWORK && (user.role == Role.ADMIN || user.role == Role.TEACHER) && SchoolRepository.classExists(className)) {
            toggleHomeworkComposerButton.visibility = View.VISIBLE
            toggleHomeworkComposerButton.setOnClickListener {
                setHomeworkComposerVisible(!homeworkComposerOpen)
            }
            val subjectInput = findViewById<MaterialAutoCompleteTextView>(R.id.homeworkSubjectInput)
            homeworkSubjectInput = subjectInput
            findViewById<MaterialButton>(R.id.chooseFileButton).setOnClickListener { filePicker.launch("*/*") }
            findViewById<MaterialButton>(R.id.cameraButton).setOnClickListener { cameraPreview.launch(null) }
            findViewById<MaterialButton>(R.id.addHomeworkButton).setOnClickListener {
                saveHomeworkForm(user)
            }
            findViewById<MaterialButton>(R.id.cancelHomeworkButton).setOnClickListener {
                cancelHomeworkEditing()
            }
            if (intent.getBooleanExtra(EXTRA_OPEN_HOMEWORK_COMPOSER, false)) {
                setHomeworkComposerVisible(true)
            }
        }

        if (mode == MODE_MARKS && (user.role == Role.ADMIN || user.role == Role.TEACHER) && SchoolRepository.classExists(className)) {
            createMarkSection.visibility = View.GONE
        }

        refreshSelectionOptions()
        bind()
        val editHomeworkId = intent.getIntExtra(EXTRA_EDIT_HOMEWORK_ID, -1)
        if (mode == MODE_HOMEWORK && editHomeworkId > 0) {
            SchoolRepository.homeworkItemsForClass(className, "")
                .firstOrNull { it.id == editHomeworkId }
                ?.let { startEditHomework(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::recyclerView.isInitialized) {
            refreshSelectionOptions()
            bind()
        }
    }

    private fun refreshSelectionOptions() {
        val user = SessionManager.currentUser ?: return
        classStudents = SchoolRepository.studentsForClass(className)
        studentLabels = classStudents.map { "${it.fullName} (${it.username})" }
        subjectNames = (
            SchoolRepository.subjectsForClass(className).map { it.name } +
                SchoolRepository.timetableMatrixSubjects(user) +
                SchoolRepository.homeworkItemsForClass(className, "").map { it.subject }
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        markStudentInput?.let { input ->
            val current = input.text?.toString().orEmpty()
            input.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, studentLabels))
            if (current in studentLabels) {
                input.setText(current, false)
            } else if (studentLabels.isNotEmpty()) {
                input.setText(studentLabels.first(), false)
            } else {
                input.setText("", false)
            }
        }

        markSubjectInput?.let { input ->
            val current = input.text?.toString().orEmpty()
            input.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, subjectNames))
            if (current in subjectNames) {
                input.setText(current, false)
            } else if (subjectNames.isNotEmpty()) {
                input.setText(subjectNames.first(), false)
            } else {
                input.setText("", false)
            }
        }

        homeworkSubjectInput?.let { input ->
            val current = input.text?.toString().orEmpty()
            input.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, subjectNames))
            if (current in subjectNames) {
                input.setText(current, false)
            } else if (subjectNames.isNotEmpty()) {
                input.setText(subjectNames.first(), false)
            } else {
                input.setText("", false)
            }
        }

        // Class/subject assignments are server-owned.  Refresh them so a teacher
        // never sees an empty or stale subject picker after the Firestore split.
        MobileAcademicGateway.staffSubjects(className) { result ->
            runOnUiThread {
                result.onSuccess { serverSubjects ->
                    val merged = (subjectNames + serverSubjects.map { it.name })
                        .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                    if (merged == subjectNames) return@onSuccess
                    subjectNames = merged
                    markSubjectInput?.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, subjectNames))
                    homeworkSubjectInput?.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, subjectNames))
                    if (homeworkSubjectInput?.text.isNullOrBlank()) {
                        homeworkSubjectInput?.setText(subjectNames.firstOrNull().orEmpty(), false)
                    }
                }
            }
        }
    }

    private fun bind() {
        when (mode) {
            MODE_PROFILES -> {
                val rows = SchoolRepository.profileRowsForClass(className, query)
                recyclerView.adapter = SimpleListAdapter(rows.map { it.second }) { position ->
                    startActivity(
                        Intent(this, StudentDetailActivity::class.java)
                            .putExtra(StudentDetailActivity.EXTRA_USERNAME, rows[position].first)
                            .putExtra(StudentDetailActivity.EXTRA_SECTION, StudentDetailActivity.SECTION_ATTENDANCE)
                    )
                }
            }
            MODE_ATTENDANCE -> {
                val rows = SchoolRepository.attendanceRowsWithUsernamesForClass(className, query)
                recyclerView.adapter = SimpleListAdapter(rows.map { it.second }) { position ->
                    showAttendanceHistoryDialog(rows[position].first)
                }
            }
            MODE_MARKS -> {
                val rows = SchoolRepository.marksRowsWithUsernamesForClass(className, query)
                recyclerView.adapter = SimpleListAdapter(rows.map { it.second }) { position ->
                    startActivity(
                        Intent(this, GradeEntryActivity::class.java)
                            .putExtra(GradeEntryActivity.EXTRA_USERNAME, rows[position].first)
                            .putExtra(GradeEntryActivity.EXTRA_CLASS_NAME, className)
                    )
                }
            }
            MODE_HOMEWORK -> {
                val user = SessionManager.currentUser ?: return
                val items = SchoolRepository.homeworkFor(user).filter {
                    it.className.equals(className, true) &&
                        (it.title.contains(query, true) || it.subject.contains(query, true) || it.description.contains(query, true))
                }.filter {
                    user.role != Role.TEACHER || it.teacherUsername == user.username
                }
                recyclerView.adapter = SimpleListAdapter(items.map {
                    val submissionSummary = when (it.submissions.size) {
                        0 -> "No submissions yet"
                        1 -> "1 submission: ${it.submissions.first().fileNames.ifEmpty { listOf(it.submissions.first().fileName) }.size} file(s)"
                        else -> "${it.submissions.size} submissions"
                    }
                    val attachments = it.attachmentNames.ifEmpty { listOfNotNull(it.attachmentName) }
                    SimpleListItem(
                        "${it.title} (${it.subject})",
                        "${it.description}\nDue: ${it.dueDate}\nAttachments: ${attachments.ifEmpty { listOf("No file") }.joinToString()}\n$submissionSummary",
                        className
                    )
                }) { position ->
                    showHomeworkActions(items[position])
                }
            }
            else -> {
                recyclerView.adapter = SimpleListAdapter(emptyList<SimpleListItem>())
            }
        }
    }

    override fun onRepositoryChanged() {
        if (::recyclerView.isInitialized) {
            refreshSelectionOptions()
            bind()
        }
    }

    private fun showAttendanceHistoryDialog(username: String) {
        val profile = SchoolRepository.profileFor(username)
        val history = SchoolRepository.attendanceHistoryForStudent(username, className).sortedBy { it.date }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_attendance_history, null, false)
        val summaryText = dialogView.findViewById<TextView>(R.id.attendanceHistorySummary)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.attendanceHistoryRecycler)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = AttendanceHistoryAdapter(
            history.mapIndexed { index, mark ->
                AttendanceHistoryRow(
                    serial = index + 1,
                    date = mark.date,
                    status = if (mark.present) "Present" else "Absent"
                )
            }
        )

        val presentDays = history.count { it.present }
        summaryText.text = if (history.isEmpty()) {
            "No attendance history saved for ${profile?.fullName ?: username}."
        } else {
            "Total: ${history.size} days   Present: $presentDays   Absent: ${history.size - presentDays}"
        }

        AlertDialog.Builder(this)
            .setTitle("${profile?.fullName ?: username} attendance")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    companion object {
        const val EXTRA_CLASS_NAME = "class_name"
        const val EXTRA_MODE = "mode"
        const val EXTRA_OPEN_HOMEWORK_COMPOSER = "open_homework_composer"
        const val EXTRA_EDIT_HOMEWORK_ID = "edit_homework_id"
        const val MODE_PROFILES = "Profiles"
        const val MODE_ATTENDANCE = "Attendance"
        const val MODE_MARKS = "Marks"
        const val MODE_HOMEWORK = "Homework"
    }

    private data class AttendanceHistoryRow(
        val serial: Int,
        val date: String,
        val status: String
    )

    private class AttendanceHistoryAdapter(
        private val items: List<AttendanceHistoryRow>
    ) : RecyclerView.Adapter<AttendanceHistoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_attendance_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.serialText.text = item.serial.toString()
            holder.dateText.text = item.date
            holder.statusText.text = item.status
            holder.statusText.setTextColor(
                holder.itemView.context.getColor(
                    if (item.status == "Present") android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val serialText: TextView = view.findViewById(R.id.serialText)
            val dateText: TextView = view.findViewById(R.id.dateText)
            val statusText: TextView = view.findViewById(R.id.statusText)
        }
    }

    private fun saveHomeworkForm(user: com.schoolms.mobile.data.User) {
        val titleInput = findViewById<TextInputEditText>(R.id.homeworkTitleInput)
        val descriptionInput = findViewById<TextInputEditText>(R.id.homeworkDescriptionInput)
        val subjectInput = findViewById<MaterialAutoCompleteTextView>(R.id.homeworkSubjectInput)
        val dueDateInput = findViewById<TextInputEditText>(R.id.homeworkDueDateInput)
        val selectedText = findViewById<TextView>(R.id.selectedFileText)

        val subject = subjectInput.text?.toString().orEmpty()
        val title = titleInput.text?.toString().orEmpty()
        val description = descriptionInput.text?.toString().orEmpty()
        val dueDate = dueDateInput.text?.toString().orEmpty()

        fun finishSave(success: Boolean, created: Boolean, message: String = "") {
            Toast.makeText(this, if (success) (if (created) "Homework added in $className" else "Homework updated") else message.ifBlank { "Fill all required fields" }, Toast.LENGTH_LONG).show()
            if (!success) return
            subjectInput.setText(subjectNames.firstOrNull().orEmpty(), false)
            titleInput.text = null
            descriptionInput.text = null
            dueDateInput.text = null
            resetSelectedAttachments()
            editingHomeworkId = null
            editingAttachmentNames = emptyList()
            editingAttachmentUrls = emptyList()
            setHomeworkComposerVisible(false)
            findViewById<MaterialButton>(R.id.addHomeworkButton).text = getString(R.string.add_homework)
            selectedText.text = getString(R.string.no_file_selected)
            bind()
        }

        if (editingHomeworkId != null) {
            val names = selectedFileNames.toList().ifEmpty { editingAttachmentNames }
            val success = SchoolRepository.updateHomework(user, editingHomeworkId!!, className, subject, title, description, dueDate, names, editingAttachmentUrls)
            finishSave(success, false)
            return
        }

        fun createOnServer(uploads: List<MobileAcademicGateway.Upload>) {
            MobileAcademicGateway.createHomework(
                className = className, subjectName = subject, title = title,
                description = description, dueDate = dueDate,
                attachmentMediaIds = uploads.map { it.mediaId }
            ) { result ->
                runOnUiThread {
                    result.onSuccess {
                        SchoolRepository.refreshPrivateAcademicContent { }
                        finishSave(true, true)
                    }.onFailure { error ->
                        finishSave(false, true, error.message ?: "Homework could not be saved on the school server.")
                    }
                }
            }
        }

        if (selectedFileUris.isNotEmpty() || selectedCameraBitmaps.isNotEmpty()) {
            uploadSelectedTeacherFiles { result ->
                result.onSuccess(::createOnServer).onFailure { error ->
                    Toast.makeText(this, error.message ?: "Attachment upload failed", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        createOnServer(emptyList())
    }

    private fun uploadSelectedTeacherFiles(onComplete: (Result<List<MobileAcademicGateway.Upload>>) -> Unit) {
        val files = selectedFileUris.mapIndexed { index, uri ->
            prepareOptimizedUpload(uri, "homework_teacher") to selectedFileNames.getOrElse(index) { "homework_file_${index + 1}" }
        } + selectedCameraBitmaps.map { (fileName, bitmap) ->
            prepareOptimizedUpload(bitmap, "homework_teacher_camera") to fileName
        }
        val uploads = mutableListOf<MobileAcademicGateway.Upload>()
        val totalFiles = files.size.coerceAtLeast(1)
        val progressDialog = UploadProgressDialog(this, "Uploading homework attachments")
        val saveButton = findViewById<MaterialButton>(R.id.addHomeworkButton)
        progressDialog.show()
        saveButton.isEnabled = false

        fun failUpload(message: String) {
            progressDialog.dismiss()
            saveButton.isEnabled = true
            onComplete(Result.failure(IllegalStateException(message)))
        }

        fun uploadAt(index: Int) {
            if (index >= files.size) {
                progressDialog.saving()
                progressDialog.dismiss()
                saveButton.isEnabled = true
                onComplete(Result.success(uploads))
                return
            }
            val (fileUri, fileName) = files[index]
            MobileAcademicGateway.uploadHomeworkAttachment(contentResolver, fileUri, fileName) { result ->
                runOnUiThread {
                    result.onSuccess { upload ->
                        uploads.add(upload)
                        progressDialog.update(index + 1, totalFiles, 100)
                        uploadAt(index + 1)
                    }.onFailure { error -> failUpload(error.message ?: "Attachment upload failed") }
                }
            }
        }
        uploadAt(0)
    }

    private fun showHomeworkActions(item: HomeworkItem) {
        val user = SessionManager.currentUser ?: return
        val actions = mutableListOf("View submissions (${item.submissions.size})", "Edit homework", "Delete homework")
        val attachmentUrls = item.attachmentUrls.ifEmpty { listOfNotNull(item.attachmentUrl) }
        if (attachmentUrls.isNotEmpty()) {
            actions.add(0, "Open attachments (${attachmentUrls.size})")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("${item.title} | ${item.subject}")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Open attachments (${attachmentUrls.size})" -> openAttachmentList(
                        item.attachmentNames.ifEmpty { listOfNotNull(item.attachmentName) },
                        attachmentUrls
                    )
                    "View submissions (${item.submissions.size})" -> showSubmissionList(item)
                    "Edit homework" -> startEditHomework(item)
                    "Delete homework" -> {
                        val success = SchoolRepository.deleteHomework(user, item.id)
                        Toast.makeText(this, if (success) "Homework deleted" else "Unable to delete homework", Toast.LENGTH_SHORT).show()
                        if (success) bind()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSubmissionList(item: HomeworkItem) {
        val formatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val students = SchoolRepository.studentsForClass(item.className).sortedBy { it.fullName }
        val submissionsByStudent = item.submissions.associateBy { it.studentUsername.trim().lowercase() }
        val rows = students.map { student ->
            val submission = submissionsByStudent[student.username.trim().lowercase()]
            if (submission == null) {
                SimpleListItem(
                    title = student.fullName,
                    subtitle = "Roll ${student.rollNumber} | ${student.className}\nNo homework submitted yet",
                    badge = "Pending"
                )
            } else {
                val submittedLabel = if (submission.submittedAt > 0L) {
                    formatter.format(Date(submission.submittedAt))
                } else {
                    "Time not available"
                }
                val fileCount = submission.fileNames.ifEmpty { listOf(submission.fileName) }.filter { it.isNotBlank() }.size
                SimpleListItem(
                    title = student.fullName,
                    subtitle = "Roll ${student.rollNumber} | ${student.className}\nSubmitted: $submittedLabel\nFiles: $fileCount",
                    badge = "Submitted"
                )
            }
        }

        if (rows.isEmpty()) {
            Toast.makeText(this, "No students found in ${item.className}", Toast.LENGTH_SHORT).show()
            return
        }

        val submittedCount = students.count { submissionsByStudent.containsKey(it.username.trim().lowercase()) }
        val pendingCount = students.size - submittedCount

        val listAdapter = SimpleListAdapter(rows) { position ->
            val student = students.getOrNull(position) ?: return@SimpleListAdapter
            val submission = submissionsByStudent[student.username.trim().lowercase()]
            if (submission == null) {
                Toast.makeText(this, "${student.fullName} has not submitted yet", Toast.LENGTH_SHORT).show()
                return@SimpleListAdapter
            }
            val urls = submission.fileUrls.ifEmpty { listOfNotNull(submission.fileUrl) }
            if (urls.isEmpty()) {
                Toast.makeText(this, "This submission has no file link saved", Toast.LENGTH_SHORT).show()
            } else {
                openAttachmentList(submission.fileNames.ifEmpty { listOf(submission.fileName) }, urls)
            }
        }
        val listView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ClassRecordsActivity)
            adapter = listAdapter
            setPadding(24, 16, 24, 8)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("${item.subject}: $submittedCount submitted, $pendingCount pending")
            .setView(listView)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startEditHomework(item: HomeworkItem) {
        editingHomeworkId = item.id
        editingAttachmentNames = item.attachmentNames.ifEmpty { listOfNotNull(item.attachmentName) }
        editingAttachmentUrls = item.attachmentUrls.ifEmpty { listOfNotNull(item.attachmentUrl) }
        selectedFileNames = editingAttachmentNames.toMutableList()
        selectedFileUris.clear()
        selectedCameraBitmaps.clear()
        findViewById<TextInputEditText>(R.id.homeworkTitleInput).setText(item.title)
        findViewById<TextInputEditText>(R.id.homeworkDescriptionInput).setText(item.description)
        findViewById<MaterialAutoCompleteTextView>(R.id.homeworkSubjectInput).setText(item.subject, false)
        findViewById<TextInputEditText>(R.id.homeworkDueDateInput).setText(item.dueDate)
        updateSelectedAttachmentText()
        setHomeworkComposerVisible(true)
        findViewById<MaterialButton>(R.id.addHomeworkButton).text = "Update homework"
        findViewById<NestedScrollView>(R.id.createHomeworkSection).post {
            findViewById<NestedScrollView>(R.id.createHomeworkSection).smoothScrollTo(0, 0)
        }
    }

    private fun setHomeworkComposerVisible(show: Boolean) {
        val createHomeworkSection = findViewById<View>(R.id.createHomeworkSection)
        val recycler = findViewById<View>(R.id.recyclerView)
        val searchLayout = findViewById<View>(R.id.searchLayout)
        val toggleButton = findViewById<MaterialButton>(R.id.toggleHomeworkComposerButton)
        homeworkComposerOpen = show
        createHomeworkSection.visibility = if (show) View.VISIBLE else View.GONE
        recycler.visibility = if (show) View.GONE else View.VISIBLE
        searchLayout.visibility = if (show) View.GONE else View.VISIBLE
        toggleButton.text = if (show) "Close homework form" else getString(R.string.add_homework)
        if (!show) {
            resetHomeworkComposerForm()
        }
    }

    private fun resetHomeworkComposerForm() {
        resetSelectedAttachments()
        editingHomeworkId = null
        editingAttachmentNames = emptyList()
        editingAttachmentUrls = emptyList()
        findViewById<MaterialButton>(R.id.addHomeworkButton).text = getString(R.string.add_homework)
        findViewById<TextInputEditText>(R.id.homeworkTitleInput).text = null
        findViewById<TextInputEditText>(R.id.homeworkDescriptionInput).text = null
        findViewById<TextInputEditText>(R.id.homeworkDueDateInput).text = null
        findViewById<MaterialAutoCompleteTextView>(R.id.homeworkSubjectInput).setText(subjectNames.firstOrNull().orEmpty(), false)
        updateSelectedAttachmentText()
    }

    private fun cancelHomeworkEditing() {
        setHomeworkComposerVisible(false)
    }

    private fun openAttachmentList(names: List<String>, urls: List<String>) {
        if (urls.size == 1) {
            openAttachment(urls.first())
            return
        }
        val labels = urls.mapIndexed { index, _ -> names.getOrNull(index).orEmpty().ifBlank { "Attachment ${index + 1}" } }
        android.app.AlertDialog.Builder(this)
            .setTitle("Open attachment")
            .setItems(labels.toTypedArray()) { _, which -> openAttachment(urls[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAttachment(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open attachment", Toast.LENGTH_SHORT).show()
        }
    }
}
