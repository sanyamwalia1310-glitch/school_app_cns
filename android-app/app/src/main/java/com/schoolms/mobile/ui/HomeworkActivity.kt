package com.schoolms.mobile.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.schoolms.mobile.R
import com.schoolms.mobile.data.HomeworkItem
import com.schoolms.mobile.data.MobileAcademicGateway
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.ui.adapter.SimpleListAdapter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeworkActivity : BaseActivity() {
    private lateinit var adapter: SimpleListAdapter
    private var query: String = ""
    private var selectedFileNames: MutableList<String> = mutableListOf()
    private var selectedFileUris: MutableList<Uri> = mutableListOf()
    private var selectedCameraBitmaps: MutableList<Pair<String, Bitmap>> = mutableListOf()
    private var selectedHomeworkId: Int? = null
    private var selectedFileStatusView: TextView? = null
    private var activeSubmissionDialog: AlertDialog? = null
    private var dialogUploadButton: MaterialButton? = null
    private var currentStudentHomework: List<HomeworkItem> = emptyList()
    private var selectedPreviousSubmissionUrls: List<String> = emptyList()
    private var teacherSelectedClass: String? = null
    private var teacherSelectedSubject: String? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedFileUris.clear()
        selectedFileUris.addAll(uris)
        selectedCameraBitmaps.clear()
        selectedFileNames = uris.map { resolveDisplayName(it) }.toMutableList()
        updateSelectedFileUi()
        updateStudentUploadState()
    }

    private val cameraPreview = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            if (selectedFileUris.isNotEmpty()) {
                selectedFileNames.clear()
            }
            selectedFileUris.clear()
            val name = "camera_homework_${System.currentTimeMillis()}.jpg"
            selectedCameraBitmaps.add(name to bitmap)
            selectedFileNames.add(name)
        }
        updateSelectedFileUi()
        updateStudentUploadState()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_homework)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.homework_title))

        val studentHintText = findViewById<TextView>(R.id.studentHintText)
        val selectedFileText = findViewById<TextView>(R.id.selectedFileText)
        val studentUploadActions = findViewById<View>(R.id.studentUploadActions)
        val chooseFileButton = findViewById<MaterialButton>(R.id.chooseFileButton)
        val cameraButton = findViewById<MaterialButton>(R.id.cameraButton)
        val uploadButton = findViewById<MaterialButton>(R.id.uploadHomeworkButton)
        val addHomeworkButton = findViewById<MaterialButton>(R.id.addHomeworkDashboardButton)
        val homeworkListHeader = findViewById<TextView>(R.id.homeworkListHeader)
        val homeworkBackButton = findViewById<MaterialButton>(R.id.homeworkBackButton)
        val selectedHomeworkText = findViewById<TextView>(R.id.selectedHomeworkTargetText)
        val searchLayout = findViewById<TextInputLayout>(R.id.searchLayout)
        searchLayout.visibility = if (user.role == Role.STUDENT) View.GONE else View.VISIBLE
        searchLayout.hint = if (user.role == Role.TEACHER) "Search your homework" else "Search class"
        findViewById<TextInputEditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                bindData()
            }
        })

        studentHintText.visibility = if (user.role == Role.STUDENT || user.role == Role.TEACHER) View.VISIBLE else View.GONE
        selectedFileText.visibility = View.GONE
        studentUploadActions.visibility = View.GONE
        uploadButton.visibility = View.GONE
        addHomeworkButton.visibility = if (user.role == Role.TEACHER || user.role == Role.ADMIN) View.VISIBLE else View.GONE
        homeworkListHeader.visibility = if (user.role == Role.TEACHER || user.role == Role.ADMIN) View.VISIBLE else View.GONE
        homeworkListHeader.text = if (user.role == Role.TEACHER) {
            "Select homework class"
        } else {
            "Class homework records"
        }
        homeworkBackButton.setOnClickListener {
            if (teacherSelectedSubject != null) {
                teacherSelectedSubject = null
            } else {
                teacherSelectedClass = null
            }
            bindData()
        }
        addHomeworkButton.setOnClickListener { showAddHomeworkClassPicker() }
        selectedHomeworkText.visibility = View.GONE
        chooseFileButton.setOnClickListener { filePicker.launch("*/*") }
        cameraButton.setOnClickListener { cameraPreview.launch(null) }
        uploadButton.setOnClickListener { submitSelectedHomework() }
        studentHintText.text = if (user.role == Role.TEACHER) {
            "First choose a class. Then choose the subject where you have shared homework."
        } else {
            "Homework is shown below. Tap one homework to choose file, use camera, and upload to that exact task."
        }
        selectedHomeworkText.text = getString(R.string.no_homework_selected)

        adapter = SimpleListAdapter(emptyList()) { position ->
            if (user.role == Role.STUDENT) {
                val homework = currentStudentHomework.getOrNull(position) ?: return@SimpleListAdapter
                showStudentHomeworkDialog(homework)
            }
        }

        findViewById<RecyclerView>(R.id.homeworkRecycler).apply {
            layoutManager = LinearLayoutManager(this@HomeworkActivity)
            adapter = this@HomeworkActivity.adapter
        }

        bindData()
    }

    private fun bindData() {
        val user = SessionManager.currentUser ?: return
        if (user.role == Role.TEACHER) {
            bindTeacherHomework(user)
            return
        }

        if (user.role == Role.ADMIN) {
            // Do not reopen legacy locally cached classes (for example
            // "Class 1") that no longer exist in the server database.
            MobileAcademicGateway.staffClasses { result ->
                runOnUiThread {
                    result.onSuccess { serverClasses ->
                        val rows = serverClasses.map {
                            SimpleListItem(it.name, "Server-configured class", "Open")
                        }.filter {
                            it.title.contains(query, true) || it.subtitle.contains(query, true)
                        }
                        findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = SimpleListAdapter(rows) { position ->
                            val className = rows.getOrNull(position)?.title.orEmpty()
                            if (className.isNotBlank()) {
                                startActivity(
                                    Intent(this, ClassRecordsActivity::class.java)
                                        .putExtra(ClassRecordsActivity.EXTRA_MODE, ClassRecordsActivity.MODE_HOMEWORK)
                                        .putExtra(ClassRecordsActivity.EXTRA_CLASS_NAME, className)
                                )
                            }
                        }
                    }.onFailure { error ->
                        findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = SimpleListAdapter(
                            listOf(SimpleListItem("Classes unavailable", error.message ?: "Could not load school classes", "Retry"))
                        )
                    }
                }
            }
            return
        }

        findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = adapter
        val studentHomework = SchoolRepository.homeworkFor(user).filter {
            it.title.contains(query, true) || it.className.contains(query, true) || it.description.contains(query, true)
        }
        currentStudentHomework = studentHomework
        adapter.updateItems(studentHomework.map {
            val ownSubmission = it.submissions.firstOrNull { submission -> submission.studentUsername == user.username }
            SimpleListItem(
                title = "${it.title} (${it.subject})",
                    subtitle = "${it.description}\nDue: ${it.dueDate}\nTeacher files: ${it.attachmentNames.ifEmpty { listOfNotNull(it.attachmentName) }.ifEmpty { listOf("No file") }.joinToString()}\nMy submission: ${ownSubmission?.fileNames?.ifEmpty { listOfNotNull(ownSubmission.fileName) }?.joinToString() ?: "Pending"}",
                badge = it.className
            )
        })
        updateStudentUploadState()
    }

    private fun bindTeacherHomework(user: com.schoolms.mobile.data.User) {
        val items = SchoolRepository.homeworkFor(user)
            .filter { it.teacherUsername == user.username }
            .filter {
                query.isBlank() ||
                    it.title.contains(query, true) ||
                    it.subject.contains(query, true) ||
                    it.className.contains(query, true) ||
                    it.description.contains(query, true)
            }
            .sortedWith(compareBy<HomeworkItem> { it.className }.thenBy { it.subject }.thenBy { it.dueDate })
        val header = findViewById<TextView>(R.id.homeworkListHeader)
        val backButton = findViewById<MaterialButton>(R.id.homeworkBackButton)

        if (items.isEmpty()) {
            header.text = "Select homework class"
            backButton.visibility = View.GONE
            findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = SimpleListAdapter(
                listOf(SimpleListItem("No shared homework yet", "Tap Add homework with attachments to share work with any class.", "Empty"))
            )
            return
        }
        val availableClasses = SchoolRepository.availableClasses()
        if (teacherSelectedClass != null && teacherSelectedClass !in availableClasses) {
            teacherSelectedClass = null
            teacherSelectedSubject = null
        }
        if (teacherSelectedSubject != null && items.none { it.className == teacherSelectedClass && it.subject == teacherSelectedSubject }) {
            teacherSelectedSubject = null
        }

        when {
            teacherSelectedClass == null -> {
                header.text = "Select homework class"
                backButton.visibility = View.GONE
                bindTeacherClassRows(items)
            }
            teacherSelectedSubject == null -> {
                header.text = "${teacherSelectedClass.orEmpty()} subjects"
                backButton.visibility = View.VISIBLE
                backButton.text = "Back to classes"
                bindTeacherSubjectRows(items, teacherSelectedClass.orEmpty())
            }
            else -> {
                header.text = "${teacherSelectedClass.orEmpty()} | ${teacherSelectedSubject.orEmpty()}"
                backButton.visibility = View.VISIBLE
                backButton.text = "Back to subjects"
                bindTeacherHomeworkRows(items, teacherSelectedClass.orEmpty(), teacherSelectedSubject.orEmpty())
            }
        }
    }

    private fun bindTeacherClassRows(items: List<HomeworkItem>) {
        val grouped = items.groupBy { it.className }
        val classNames = (SchoolRepository.availableClasses() + grouped.keys).distinct()
        val rows = classNames.map { className ->
            val classItems = grouped[className].orEmpty()
            val subjectCount = classItems.map { it.subject.trim().lowercase() }.distinct().size
            val submitted = classItems.sumOf { it.submissions.size }
            SimpleListItem(
                title = className,
                subtitle = if (classItems.isEmpty()) {
                    "No homework shared yet for this class.\nOpen it to add the first homework."
                } else {
                    "${classItems.size} homework task(s)\n$subjectCount subject(s) with shared homework\n$submitted submission(s)"
                },
                badge = if (classItems.isEmpty()) "Add" else "Open"
            )
        }
        findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = SimpleListAdapter(rows) { position ->
            teacherSelectedClass = classNames.getOrNull(position)
            teacherSelectedSubject = null
            bindData()
        }
    }

    private fun bindTeacherSubjectRows(items: List<HomeworkItem>, className: String) {
        val grouped = items
            .filter { it.className == className }
            .groupBy { it.subject }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (grouped.isEmpty()) {
            findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = SimpleListAdapter(
                listOf(SimpleListItem("No homework shared in $className", "Tap Add homework with attachments to create the first homework for this class.", "Empty"))
            )
            return
        }
        val rows = grouped.map { (subject, subjectItems) ->
            val submitted = subjectItems.sumOf { it.submissions.size }
            SimpleListItem(
                title = subject,
                subtitle = "${subjectItems.size} homework task(s) shared by you\n$submitted submission(s) in $className",
                badge = "Open"
            )
        }
        findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = SimpleListAdapter(rows) { position ->
            teacherSelectedSubject = grouped.keys.elementAtOrNull(position)
            bindData()
        }
    }

    private fun bindTeacherHomeworkRows(items: List<HomeworkItem>, className: String, subject: String) {
        val homeworkItems = items
            .filter { it.className == className && it.subject == subject }
            .sortedWith(compareBy<HomeworkItem> { it.dueDate }.thenBy { it.title })
        findViewById<RecyclerView>(R.id.homeworkRecycler).adapter = TeacherHomeworkAdapter(
            items = homeworkItems,
            onViewSubmissions = { showTeacherHomeworkStatus(it) },
            onEdit = { openHomeworkEditor(it) },
            onDelete = { deleteTeacherHomework(it) },
            onOpenAttachments = { openTeacherHomeworkAttachments(it) }
        )
    }

    private fun openTeacherHomeworkAttachments(item: HomeworkItem) {
        if (item.attachmentIds.isNotEmpty()) {
            openPrivateAttachmentList(item.attachmentNames, item.attachmentIds)
            return
        }
        val urls = item.attachmentUrls.ifEmpty { listOfNotNull(item.attachmentUrl) }
        if (urls.isEmpty()) {
            Toast.makeText(this, "No attachment saved for this homework", Toast.LENGTH_SHORT).show()
        } else {
            openAttachmentList(item.attachmentNames.ifEmpty { listOfNotNull(item.attachmentName) }, urls)
        }
    }

    private fun showAddHomeworkClassPicker() {
        MobileAcademicGateway.staffClasses { result ->
            runOnUiThread {
                result.onSuccess { serverClasses ->
                    val classes = serverClasses.map { it.name }
                    if (classes.isEmpty()) {
                        Toast.makeText(this, "No server class is assigned to this account", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Add homework for class")
                        .setItems(classes.toTypedArray()) { _, which ->
                            startActivity(
                                Intent(this, ClassRecordsActivity::class.java)
                                    .putExtra(ClassRecordsActivity.EXTRA_MODE, ClassRecordsActivity.MODE_HOMEWORK)
                                    .putExtra(ClassRecordsActivity.EXTRA_CLASS_NAME, classes[which])
                                    .putExtra(ClassRecordsActivity.EXTRA_OPEN_HOMEWORK_COMPOSER, true)
                            )
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "Server classes could not be loaded", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTeacherHomeworkActions(item: HomeworkItem) {
        val attachmentUrls = item.attachmentUrls.ifEmpty { listOfNotNull(item.attachmentUrl) }
        val actions = mutableListOf("View submissions (${item.submissions.size})", "Edit homework", "Delete homework")
        if (attachmentUrls.isNotEmpty()) {
            actions.add(0, "Open attachments (${attachmentUrls.size})")
        }
        AlertDialog.Builder(this)
            .setTitle("${item.title} | ${item.subject}")
            .setMessage("${item.className} | Due ${item.dueDate}\n${item.description}")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Open attachments (${attachmentUrls.size})" -> openAttachmentList(
                        item.attachmentNames.ifEmpty { listOfNotNull(item.attachmentName) },
                        attachmentUrls
                    )
                    "View submissions (${item.submissions.size})" -> showTeacherHomeworkStatus(item)
                    "Edit homework" -> openHomeworkEditor(item)
                    "Delete homework" -> deleteTeacherHomework(item)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openHomeworkEditor(item: HomeworkItem) {
        startActivity(
            Intent(this, ClassRecordsActivity::class.java)
                .putExtra(ClassRecordsActivity.EXTRA_MODE, ClassRecordsActivity.MODE_HOMEWORK)
                .putExtra(ClassRecordsActivity.EXTRA_CLASS_NAME, item.className)
                .putExtra(ClassRecordsActivity.EXTRA_EDIT_HOMEWORK_ID, item.id)
        )
    }

    private fun deleteTeacherHomework(item: HomeworkItem) {
        val user = SessionManager.currentUser ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete homework?")
            .setMessage("${item.title}\n${item.className} | ${item.subject}")
            .setPositiveButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteHomework(user, item.id)
                Toast.makeText(this, if (success) "Homework deleted" else "Unable to delete homework", Toast.LENGTH_SHORT).show()
                if (success) bindData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTeacherHomeworkStatus(item: HomeworkItem) {
        val formatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val students = SchoolRepository.studentsForClass(item.className).sortedBy { it.fullName }
        val submissionsByStudent = item.submissions.associateBy { it.studentUsername.trim().lowercase() }
        val rows = students.map { student ->
            val submission = submissionsByStudent[student.username.trim().lowercase()]
            if (submission == null) {
                SimpleListItem(
                    student.fullName,
                    "Roll ${student.rollNumber} | ${student.className}\nNo homework submitted yet",
                    "Pending"
                )
            } else {
                val submittedLabel = if (submission.submittedAt > 0L) {
                    formatter.format(Date(submission.submittedAt))
                } else {
                    "Time not available"
                }
                val fileCount = submission.fileNames.ifEmpty { listOf(submission.fileName) }.filter { it.isNotBlank() }.size
                SimpleListItem(
                    student.fullName,
                    "Roll ${student.rollNumber} | ${student.className}\nSubmitted: $submittedLabel\nFiles: $fileCount",
                    "Submitted"
                )
            }
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, "No students found in ${item.className}", Toast.LENGTH_SHORT).show()
            return
        }
        val submittedCount = students.count { submissionsByStudent.containsKey(it.username.trim().lowercase()) }
        val pendingCount = students.size - submittedCount
        val listView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomeworkActivity)
            adapter = SimpleListAdapter(rows) { position ->
                val student = students.getOrNull(position) ?: return@SimpleListAdapter
                val submission = submissionsByStudent[student.username.trim().lowercase()]
                if (submission == null) {
                    Toast.makeText(this@HomeworkActivity, "${student.fullName} has not submitted yet", Toast.LENGTH_SHORT).show()
                    return@SimpleListAdapter
                }
                val urls = submission.fileUrls.ifEmpty { listOfNotNull(submission.fileUrl) }
                if (urls.isEmpty()) {
                    Toast.makeText(this@HomeworkActivity, "This submission has no file link saved", Toast.LENGTH_SHORT).show()
                } else {
                    openAttachmentList(submission.fileNames.ifEmpty { listOf(submission.fileName) }, urls)
                }
            }
            setPadding(24, 16, 24, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("${item.subject}: $submittedCount submitted, $pendingCount pending")
            .setMessage("${item.title}\n${item.className} | Due ${item.dueDate}")
            .setView(listView)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAttachmentList(names: List<String>, urls: List<String>) {
        if (urls.size == 1) {
            openAttachment(urls.first())
            return
        }
        val labels = urls.mapIndexed { index, _ -> names.getOrNull(index).orEmpty().ifBlank { "Attachment ${index + 1}" } }
        AlertDialog.Builder(this)
            .setTitle("Open submitted file")
            .setItems(labels.toTypedArray()) { _, which -> openAttachment(urls[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAttachment(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open attachment", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPrivateAttachmentList(names: List<String>, attachmentIds: List<Int>) {
        if (attachmentIds.isEmpty()) return
        if (attachmentIds.size == 1) {
            openPrivateAttachment(attachmentIds.first())
            return
        }
        val labels = attachmentIds.mapIndexed { index, _ ->
            names.getOrNull(index).orEmpty().ifBlank { "Attachment ${index + 1}" }
        }
        AlertDialog.Builder(this)
            .setTitle("Open teacher file")
            .setItems(labels.toTypedArray()) { _, which -> openPrivateAttachment(attachmentIds[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openPrivateAttachment(attachmentId: Int) {
        Toast.makeText(this, "Preparing secure download…", Toast.LENGTH_SHORT).show()
        MobileAcademicGateway.attachmentDownload(attachmentId) { result ->
            runOnUiThread {
                result.onSuccess { download -> openAttachment(download.url) }
                    .onFailure { error -> Toast.makeText(this, error.message ?: "Unable to open attachment", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onRepositoryChanged() {
        bindData()
    }

    private fun updateStudentUploadState() {
        val user = SessionManager.currentUser ?: return
        if (user.role != Role.STUDENT) return
        val enabled = selectedHomeworkId != null && selectedFileNames.isNotEmpty()
        findViewById<MaterialButton>(R.id.uploadHomeworkButton).isEnabled = enabled
        dialogUploadButton?.isEnabled = enabled
    }

    private fun updateSelectedFileUi() {
        val label = selectedFilesLabel()
        findViewById<TextView>(R.id.selectedFileText).text = label
        selectedFileStatusView?.text = "Selected files: $label"
    }

    private fun resetSubmissionFile() {
        selectedFileNames.clear()
        selectedFileUris.clear()
        selectedCameraBitmaps.clear()
        updateSelectedFileUi()
    }

    private fun selectedFilesLabel(): String =
        if (selectedFileNames.isEmpty()) {
            getString(R.string.no_file_selected)
        } else {
            selectedFileNames.joinToString(limit = 3, truncated = " +${selectedFileNames.size - 3} more")
        }

    private fun showStudentHomeworkDialog(homework: HomeworkItem) {
        val user = SessionManager.currentUser ?: return
        selectedHomeworkId = homework.id
        resetSubmissionFile()
        val ownSubmission = homework.submissions.firstOrNull { it.studentUsername == user.username }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 18, 36, 8)
        }
        val titleView = TextView(this).apply {
            text = homework.title
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@HomeworkActivity, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val detailView = TextView(this).apply {
            val teacherFiles = homework.attachmentNames.ifEmpty { listOfNotNull(homework.attachmentName) }
            val submissionFiles = ownSubmission?.fileNames?.ifEmpty { listOfNotNull(ownSubmission.fileName) }.orEmpty()
            text = "${homework.subject} | ${homework.className}\nDue: ${homework.dueDate}\n${homework.description}\n\nTeacher attachments: ${teacherFiles.ifEmpty { listOf("No file") }.joinToString()}\nMy submission: ${submissionFiles.ifEmpty { listOf("Pending") }.joinToString()}"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@HomeworkActivity, R.color.text_secondary))
            setPadding(0, 12, 0, 14)
        }
        val fileStatus = TextView(this).apply {
            text = "Selected files: ${getString(R.string.no_file_selected)}"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@HomeworkActivity, R.color.brand_primary))
            setPadding(0, 8, 0, 8)
        }
        selectedFileStatusView = fileStatus

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val teacherAttachmentUrls = homework.attachmentUrls.ifEmpty { listOfNotNull(homework.attachmentUrl) }
        val privateAttachmentIds = homework.attachmentIds
        val teacherAttachmentNames = homework.attachmentNames.ifEmpty { listOfNotNull(homework.attachmentName) }
        val openTeacherFilesButton = MaterialButton(this).apply {
            text = if ((privateAttachmentIds.size.takeIf { it > 0 } ?: teacherAttachmentUrls.size) > 1) "Open teacher files" else "Open teacher file"
            isEnabled = privateAttachmentIds.isNotEmpty() || teacherAttachmentUrls.isNotEmpty()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(ContextCompat.getColor(this@HomeworkActivity, android.R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(this@HomeworkActivity, R.color.brand_primary)
            setOnClickListener {
                if (privateAttachmentIds.isNotEmpty()) openPrivateAttachmentList(teacherAttachmentNames, privateAttachmentIds)
                else openAttachmentList(teacherAttachmentNames, teacherAttachmentUrls)
            }
        }
        val chooseButton = MaterialButton(this).apply {
            text = getString(R.string.choose_file)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setOnClickListener { filePicker.launch("*/*") }
        }
        val cameraButton = MaterialButton(this).apply {
            text = getString(R.string.use_camera)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            setOnClickListener { cameraPreview.launch(null) }
        }
        actionRow.addView(chooseButton)
        actionRow.addView(cameraButton)

        val uploadButton = MaterialButton(this).apply {
            text = if (ownSubmission == null) getString(R.string.upload_homework) else "Resubmit homework"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            setTextColor(ContextCompat.getColor(this@HomeworkActivity, android.R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(this@HomeworkActivity, R.color.brand_primary)
            setOnClickListener { submitSelectedHomework() }
        }
        dialogUploadButton = uploadButton

        container.addView(titleView)
        container.addView(detailView)
        container.addView(openTeacherFilesButton)
        if (ownSubmission != null) {
            container.addView(TextView(this).apply {
                text = "To replace a wrong file, choose new file(s) or camera image, then tap Resubmit homework. The previous submitted files will be removed."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@HomeworkActivity, R.color.text_secondary))
                setPadding(0, 12, 0, 4)
            })
        }
        container.addView(fileStatus)
        container.addView(actionRow)
        container.addView(uploadButton)

        activeSubmissionDialog = AlertDialog.Builder(this)
            .setTitle(if (ownSubmission == null) "Submit homework" else "Resubmit homework")
            .setView(container)
            .setNegativeButton("Close") { _, _ ->
                selectedHomeworkId = null
                selectedPreviousSubmissionUrls = emptyList()
                selectedFileStatusView = null
                dialogUploadButton = null
                resetSubmissionFile()
            }
            .show()
        selectedPreviousSubmissionUrls = ownSubmission?.fileUrls?.ifEmpty { listOfNotNull(ownSubmission.fileUrl) }.orEmpty()
    }

    private fun submitSelectedHomework() {
        val user = SessionManager.currentUser ?: return
        val homeworkId = selectedHomeworkId
        val fileNames = selectedFileNames.toList()
        if (user.role != Role.STUDENT || homeworkId == null || fileNames.isEmpty()) {
            Toast.makeText(this, "Select homework and at least one file first", Toast.LENGTH_SHORT).show()
            return
        }

        fun finishUpload(fileUrls: List<String>) {
            val success = SchoolRepository.submitHomework(user, homeworkId, fileNames, fileUrls)
            Toast.makeText(this, if (success) "Homework submitted" else "Unable to submit homework", Toast.LENGTH_SHORT).show()
            if (success) {
                deletePreviousSubmissionFiles(selectedPreviousSubmissionUrls)
                resetSubmissionFile()
                selectedHomeworkId = null
                selectedPreviousSubmissionUrls = emptyList()
                findViewById<TextView>(R.id.selectedHomeworkTargetText).text = getString(R.string.no_homework_selected)
                activeSubmissionDialog?.dismiss()
                activeSubmissionDialog = null
                selectedFileStatusView = null
                dialogUploadButton = null
                bindData()
            }
        }

        SessionManager.ensureFirebaseSession { authResult ->
            runOnUiThread {
                authResult.onFailure {
                    Toast.makeText(this, it.message ?: "Please log in again before uploading", Toast.LENGTH_SHORT).show()
                }.onSuccess {
                    uploadSelectedSubmissionFiles(fileNames) { finishUpload(it) }
                }
            }
        }
    }

    private fun uploadSelectedSubmissionFiles(fileNames: List<String>, onComplete: (List<String>) -> Unit) {
        val uploadedUrls = mutableListOf<String>()
        val uriFiles = selectedFileUris.toList()
        val cameraFiles = selectedCameraBitmaps.toList()
        val totalFiles = (uriFiles.size + cameraFiles.size).coerceAtLeast(1)
        val progressDialog = UploadProgressDialog(this, "Uploading homework")
        progressDialog.show()
        dialogUploadButton?.isEnabled = false

        fun failUpload(message: String) {
            progressDialog.dismiss()
            dialogUploadButton?.isEnabled = true
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        fun uploadCameraAt(index: Int) {
            if (index >= cameraFiles.size) {
                progressDialog.saving()
                onComplete(uploadedUrls)
                progressDialog.dismiss()
                dialogUploadButton?.isEnabled = true
                return
            }
            val (fileName, bitmap) = cameraFiles[index]
            val storageRef = Firebase.storage.reference.child("homework_submissions/${System.currentTimeMillis()}_${fileName.replace("\\s+".toRegex(), "_")}")
            val optimizedUri = prepareOptimizedUpload(bitmap, "homework_submission_camera")
            val fileNumber = uriFiles.size + index + 1
            val uploadTask = storageRef.putFile(optimizedUri)
            uploadTask
                .addOnProgressListener { snapshot ->
                    val percent = if (snapshot.totalByteCount > 0) {
                        ((snapshot.bytesTransferred * 100) / snapshot.totalByteCount).toInt()
                    } else {
                        0
                    }
                    progressDialog.update(fileNumber, totalFiles, percent)
                }
                .continueWithTask { task ->
                    if (!task.isSuccessful) throw (task.exception ?: IllegalStateException("Upload failed"))
                    storageRef.downloadUrl
                }
                .addOnSuccessListener {
                    uploadedUrls.add(it.toString())
                    uploadCameraAt(index + 1)
                }
                .addOnFailureListener { error ->
                    failUpload(error.message ?: "Submission upload failed")
                }
        }
        fun uploadUriAt(index: Int) {
            if (index >= uriFiles.size) {
                uploadCameraAt(0)
                return
            }
            val safeName = fileNames.getOrElse(index) { "homework_file_$index" }.replace("\\s+".toRegex(), "_")
            val storageRef = Firebase.storage.reference.child("homework_submissions/${System.currentTimeMillis()}_$safeName")
            val optimizedUri = prepareOptimizedUpload(uriFiles[index], "homework_submission")
            val fileNumber = index + 1
            val uploadTask = storageRef.putFile(optimizedUri)
            uploadTask
                .addOnProgressListener { snapshot ->
                    val percent = if (snapshot.totalByteCount > 0) {
                        ((snapshot.bytesTransferred * 100) / snapshot.totalByteCount).toInt()
                    } else {
                        0
                    }
                    progressDialog.update(fileNumber, totalFiles, percent)
                }
                .continueWithTask { task ->
                    if (!task.isSuccessful) throw (task.exception ?: IllegalStateException("Upload failed"))
                    storageRef.downloadUrl
                }
                .addOnSuccessListener {
                    uploadedUrls.add(it.toString())
                    uploadUriAt(index + 1)
                }
                .addOnFailureListener { error ->
                    failUpload(error.message ?: "Submission upload failed")
                }
        }
        uploadUriAt(0)
    }

    private fun deletePreviousSubmissionFiles(urls: List<String>) {
        urls.filter { it.isNotBlank() }.forEach { url ->
            try {
                Firebase.storage.getReferenceFromUrl(url).delete()
            } catch (_: Exception) {
                // Old submissions are still replaced in app data even if Storage cleanup is not possible.
            }
        }
    }

    private fun prepareOptimizedUpload(uri: Uri, cachePrefix: String): Uri {
        val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return uri
        return prepareOptimizedUpload(bitmap, cachePrefix)
    }

    private fun prepareOptimizedUpload(bitmap: Bitmap, cachePrefix: String): Uri {
        val file = File(cacheDir, "${cachePrefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        return Uri.fromFile(file)
    }

    private class TeacherHomeworkAdapter(
        private val items: List<HomeworkItem>,
        private val onViewSubmissions: (HomeworkItem) -> Unit,
        private val onEdit: (HomeworkItem) -> Unit,
        private val onDelete: (HomeworkItem) -> Unit,
        private val onOpenAttachments: (HomeworkItem) -> Unit
    ) : RecyclerView.Adapter<TeacherHomeworkAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_teacher_homework, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val totalStudents = SchoolRepository.studentsForClass(item.className).size
            val submitted = item.submissions.size
            val pending = (totalStudents - submitted).coerceAtLeast(0)
            val attachments = item.attachmentNames.ifEmpty { listOfNotNull(item.attachmentName) }
            holder.metaText.text = "${item.className} | ${item.subject}"
            holder.titleText.text = item.title
            holder.detailText.text = "Due ${item.dueDate}\n${item.description}\nAttachments: ${attachments.ifEmpty { listOf("No file") }.joinToString()}\nSubmitted $submitted | Pending $pending"
            holder.itemView.setOnClickListener { onViewSubmissions(item) }
            holder.openButton.isEnabled = attachments.isNotEmpty()
            holder.openButton.setOnClickListener { onOpenAttachments(item) }
            holder.viewButton.text = "View $submitted/$totalStudents"
            holder.viewButton.setOnClickListener { onViewSubmissions(item) }
            holder.editButton.setOnClickListener { onEdit(item) }
            holder.deleteButton.setOnClickListener { onDelete(item) }
            holder.metaText.setOnClickListener { onOpenAttachments(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val metaText: TextView = view.findViewById(R.id.homeworkMetaText)
            val titleText: TextView = view.findViewById(R.id.homeworkTitleText)
            val detailText: TextView = view.findViewById(R.id.homeworkDetailText)
            val openButton: MaterialButton = view.findViewById(R.id.openHomeworkButton)
            val viewButton: MaterialButton = view.findViewById(R.id.viewSubmissionsButton)
            val editButton: MaterialButton = view.findViewById(R.id.editHomeworkButton)
            val deleteButton: MaterialButton = view.findViewById(R.id.deleteHomeworkButton)
        }
    }
}
