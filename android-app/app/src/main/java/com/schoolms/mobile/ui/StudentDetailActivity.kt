package com.schoolms.mobile.ui

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Intent
import android.content.ActivityNotFoundException
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.schoolms.mobile.R
import com.schoolms.mobile.data.MarkItem
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.ui.adapter.SimpleListAdapter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class StudentDetailActivity : BaseActivity() {
    private var username: String = ""
    private var selectedSection: String = SECTION_ATTENDANCE
    private lateinit var gestureDetector: GestureDetector
    private var selectedHomeworkFileName: String? = null
    private var currentHomework = emptyList<com.schoolms.mobile.data.HomeworkItem>()
    private var currentSubjectMarks = emptyList<Pair<String, List<MarkItem>>>()
    private var dialogSelectedHomework: com.schoolms.mobile.data.HomeworkItem? = null
    private var dialogSelectedFileName: String? = null
    private var dialogSelectedFileUri: Uri? = null
    private var dialogFileSelectedText: TextView? = null
    private val dialogHomeworkPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        dialogSelectedFileUri = uri
        dialogSelectedFileName = uri?.let { resolveDisplayName(it) } ?: getString(R.string.no_file_selected)
        dialogFileSelectedText?.text = dialogSelectedFileName ?: getString(R.string.no_file_selected)
    }

    private val homeworkFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedHomeworkFileName = uri?.let { resolveDisplayName(it) } ?: getString(R.string.no_file_selected)
        findViewById<TextView>(R.id.homeworkSelectedFileText).text = selectedHomeworkFileName ?: getString(R.string.no_file_selected)
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
        setContentView(R.layout.activity_student_detail)

        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val currentUser = SessionManager.currentUser ?: run {
            finish()
            return
        }
        if (currentUser.role == com.schoolms.mobile.data.Role.STUDENT && username != currentUser.username) {
            Toast.makeText(this, "You can only view your own student record.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        selectedSection = normalizeSection(intent.getStringExtra(EXTRA_SECTION).orEmpty())
        gestureDetector = GestureDetector(this, SectionSwipeListener())
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Student detail")
        findViewById<RecyclerView>(R.id.detailRecycler).layoutManager = LinearLayoutManager(this@StudentDetailActivity)
        findViewById<MaterialButton>(R.id.attendanceSummaryButton).setOnClickListener {
            showAttendanceSummarySheet()
        }
        findViewById<MaterialButton>(R.id.homeworkChooseFileButton).setOnClickListener {
            homeworkFilePicker.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.attendanceSectionButton).setOnClickListener {
            selectedSection = SECTION_ATTENDANCE
            bind()
        }
        findViewById<MaterialButton>(R.id.marksSectionButton).setOnClickListener {
            selectedSection = SECTION_MARKS
            bind()
        }
        findViewById<MaterialButton>(R.id.homeworkSectionButton).setOnClickListener {
            selectedSection = SECTION_HOMEWORK
            bind()
        }
        findViewById<MaterialButton>(R.id.progressSectionButton).setOnClickListener {
            selectedSection = SECTION_PROGRESS
            bind()
        }
        findViewById<View>(R.id.studentDetailRoot).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
        bind()
    }

    override fun onRepositoryChanged() {
        bind()
    }

    private fun bind() {
        val profile = SchoolRepository.profileFor(username) ?: run {
            finish()
            return
        }

        findViewById<TextView>(R.id.headerName).text = profile.fullName
        findViewById<TextView>(R.id.headerClass).text =
            "${profile.className} | Roll ${profile.rollNumber} | Guardian ${profile.guardianContact}"
        val imageUrl = SchoolRepository.userByUsername(username)?.let(SchoolRepository::profileImageUrlFor)
            ?: profile.imageUrl
        ImageLoader.loadInto(
            findViewById<ImageView>(R.id.studentProfileImage),
            imageUrl,
            R.drawable.ic_school_crest
        )
        findViewById<TextView>(R.id.attendanceSummaryText).text = SchoolRepository.attendanceSummaryText(username)
        findViewById<TextView>(R.id.marksSummaryText).text = SchoolRepository.marksSummaryText(username)
        findViewById<TextView>(R.id.homeworkSummaryText).text = SchoolRepository.homeworkSummaryText(username)

        val attendanceCard = findViewById<View>(R.id.attendanceCard)
        val marksCard = findViewById<View>(R.id.marksCard)
        val homeworkCard = findViewById<View>(R.id.homeworkCard)
        val homeworkHintText = findViewById<TextView>(R.id.homeworkHintText)
        val homeworkSelectedFileText = findViewById<TextView>(R.id.homeworkSelectedFileText)
        val homeworkChooseFileButton = findViewById<MaterialButton>(R.id.homeworkChooseFileButton)
        val attendanceButton = findViewById<MaterialButton>(R.id.attendanceSectionButton)
        val marksButton = findViewById<MaterialButton>(R.id.marksSectionButton)
        val homeworkButton = findViewById<MaterialButton>(R.id.homeworkSectionButton)
        val progressButton = findViewById<MaterialButton>(R.id.progressSectionButton)
        val currentUser = SessionManager.currentUser
        val isOwnStudentView = currentUser?.username == username && currentUser.role == com.schoolms.mobile.data.Role.STUDENT
        val rows = when (selectedSection) {
            SECTION_ATTENDANCE -> {
                attendanceCard.visibility = View.VISIBLE
                marksCard.visibility = View.GONE
                homeworkCard.visibility = View.GONE
                homeworkHintText.visibility = View.GONE
                homeworkSelectedFileText.visibility = View.GONE
                homeworkChooseFileButton.visibility = View.GONE
                listOf(
                    SimpleListItem(
                        "Latest attendance",
                        SchoolRepository.latestAttendanceStatusLabel(username, profile.className),
                        "Attendance"
                    )
                )
            }
            SECTION_MARKS -> {
                attendanceCard.visibility = View.GONE
                marksCard.visibility = View.VISIBLE
                homeworkCard.visibility = View.GONE
                homeworkHintText.visibility = View.GONE
                homeworkSelectedFileText.visibility = View.GONE
                homeworkChooseFileButton.visibility = View.GONE
                val assignedSubjects = SchoolRepository.subjectsForClass(profile.className)
                    .map { it.name.trim().lowercase() }
                    .toSet()
                currentSubjectMarks = SchoolRepository.marksForStudent(username)
                    .filter { mark -> assignedSubjects.isEmpty() || mark.subject.trim().lowercase() in assignedSubjects }
                    .groupBy { it.subject }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { it.key to it.value.sortedBy { mark -> mark.assessment } }
                currentSubjectMarks.map { (subject, marks) ->
                    val totalScore = marks.sumOf { it.score }
                    val totalOutOf = marks.sumOf { it.outOf }
                    val percentage = if (totalOutOf == 0) 0 else ((totalScore.toDouble() / totalOutOf.toDouble()) * 100).toInt()
                    val latest = marks.lastOrNull()
                    SimpleListItem(
                        subject,
                        "Overall percentage: $percentage%\nTests recorded: ${marks.size}\nLatest: ${latest?.assessment.orEmpty()} ${latest?.score ?: 0}/${latest?.outOf ?: 0}",
                        "${percentage}%"
                    )
                }.ifEmpty {
                    listOf(SimpleListItem("Marks", "No marks added yet.", "Pending"))
                }
            }
            SECTION_HOMEWORK -> {
                attendanceCard.visibility = View.GONE
                marksCard.visibility = View.GONE
                homeworkCard.visibility = View.VISIBLE
                homeworkHintText.visibility = if (isOwnStudentView) View.VISIBLE else View.GONE
                homeworkSelectedFileText.visibility = if (isOwnStudentView) View.VISIBLE else View.GONE
                homeworkChooseFileButton.visibility = if (isOwnStudentView) View.VISIBLE else View.GONE
                if (selectedHomeworkFileName.isNullOrBlank()) {
                    homeworkSelectedFileText.text = getString(R.string.no_file_selected)
                }
                currentHomework = SchoolRepository.homeworkForStudent(username)
                currentHomework.map {
                    val ownSubmission = it.submissions.firstOrNull { submission -> submission.studentUsername == username }
                    val teacherFiles = it.attachmentNames.ifEmpty { listOfNotNull(it.attachmentName) }
                    val submissionFiles = ownSubmission?.fileNames?.ifEmpty { listOfNotNull(ownSubmission.fileName) }.orEmpty()
                    SimpleListItem(
                        "${it.title} (${it.subject})",
                        "${it.description}\nDue: ${it.dueDate}\nTeacher files: ${teacherFiles.ifEmpty { listOf("No file") }.joinToString()}\nMy submission: ${submissionFiles.ifEmpty { listOf("Pending") }.joinToString()}",
                        if (ownSubmission != null) "Submitted" else "Pending"
                    )
                }.ifEmpty {
                    listOf(SimpleListItem("Homework", "No homework assigned yet.", "Pending"))
                }
            }
            SECTION_PROGRESS -> {
                attendanceCard.visibility = View.VISIBLE
                marksCard.visibility = View.VISIBLE
                homeworkCard.visibility = View.VISIBLE
                homeworkHintText.visibility = View.GONE
                homeworkSelectedFileText.visibility = View.GONE
                homeworkChooseFileButton.visibility = View.GONE
                buildProgressReportRows(profile)
            }
            else -> emptyList()
        }
        styleSectionButton(attendanceButton, selectedSection == SECTION_ATTENDANCE)
        styleSectionButton(marksButton, selectedSection == SECTION_MARKS)
        styleSectionButton(homeworkButton, selectedSection == SECTION_HOMEWORK)
        styleSectionButton(progressButton, selectedSection == SECTION_PROGRESS)
        findViewById<RecyclerView>(R.id.detailRecycler).adapter = SimpleListAdapter(rows) { position ->
            when (selectedSection) {
                SECTION_HOMEWORK -> {
                    if (isOwnStudentView) {
                        val item = currentHomework.getOrNull(position) ?: return@SimpleListAdapter
                        showHomeworkSubmissionDialog(item)
                    }
                }
                SECTION_MARKS -> {
                    val item = currentSubjectMarks.getOrNull(position) ?: return@SimpleListAdapter
                    showSubjectMarksDialog(item.first, item.second)
                }
            }
        }
    }

    private fun showSubjectMarksDialog(subject: String, marks: List<MarkItem>) {
        val rows = marks.map {
            SimpleListItem(
                it.assessment,
                "Score: ${it.score}/${it.outOf}\nPercentage: ${if (it.outOf == 0) 0 else ((it.score.toDouble() / it.outOf.toDouble()) * 100).toInt()}%",
                it.grade
            )
        }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StudentDetailActivity)
            adapter = SimpleListAdapter(rows)
            setPadding(18, 10, 18, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("$subject marks")
            .setView(recycler)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildProgressReportRows(profile: com.schoolms.mobile.data.StudentProfile): List<SimpleListItem> {
        val attendance = SchoolRepository.attendanceForStudent(profile.username)
        val attendancePercent = attendance?.let { SchoolRepository.attendancePercent(it) }
        val homework = SchoolRepository.homeworkForStudent(profile.username)
        val submittedHomework = homework.count { item ->
            item.submissions.any { submission -> submission.studentUsername == profile.username }
        }
        val homeworkPercent = homework.takeIf { it.isNotEmpty() }
            ?.let { (submittedHomework.toDouble() / it.size.toDouble() * 100).toInt() }
        val marks = SchoolRepository.marksForStudent(profile.username)
        val marksPercent = marks.takeIf { it.isNotEmpty() }?.let {
            val totalScore = it.sumOf { mark -> mark.score }
            val totalOutOf = it.sumOf { mark -> mark.outOf }
            if (totalOutOf == 0) null else (totalScore.toDouble() / totalOutOf.toDouble() * 100).toInt()
        }
        val overall = listOfNotNull(attendancePercent, homeworkPercent, marksPercent)
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()

        return listOf(
            SimpleListItem(
                "Overall progress",
                "Calculated from attendance, homework submission, and marks.\nScore: ${overall?.let { "$it%" } ?: "Not enough data"}",
                overall?.let { progressLabel(it) } ?: "Pending"
            ),
            SimpleListItem(
                "Attendance",
                attendance?.let { "Present ${it.presentDays}/${it.totalDays} days\nAttendance percentage: ${attendancePercent ?: 0}%" }
                    ?: "No attendance record saved yet.",
                attendancePercent?.let { "$it%" } ?: "Pending"
            ),
            SimpleListItem(
                "Homework submission",
                if (homework.isEmpty()) "No homework assigned yet." else "Submitted $submittedHomework/${homework.size} homework tasks\nSubmission percentage: ${homeworkPercent ?: 0}%",
                homeworkPercent?.let { "$it%" } ?: "Pending"
            ),
            SimpleListItem(
                "Marks performance",
                if (marks.isEmpty()) "No marks added yet." else "Assessments recorded: ${marks.size}\nMarks percentage: ${marksPercent ?: 0}%",
                marksPercent?.let { "$it%" } ?: "Pending"
            )
        )
    }

    private fun progressLabel(percent: Int): String = when {
        percent >= 85 -> "Excellent"
        percent >= 70 -> "Good"
        percent >= 50 -> "Improving"
        else -> "Needs care"
    }

    private fun showHomeworkSubmissionDialog(item: com.schoolms.mobile.data.HomeworkItem) {
        dialogSelectedHomework = item
        dialogSelectedFileName = null
        dialogSelectedFileUri = null
        val dialogView = layoutInflater.inflate(R.layout.dialog_homework_submission, null)
        dialogView.findViewById<TextView>(R.id.dialogHomeworkTitle).text = item.title
        dialogView.findViewById<TextView>(R.id.dialogHomeworkDescription).text = item.description
        dialogView.findViewById<TextView>(R.id.dialogHomeworkDue).text = "Due: ${item.dueDate}"
        dialogFileSelectedText = dialogView.findViewById(R.id.dialogSelectedFileText)
        val chooseBtn = dialogView.findViewById<MaterialButton>(R.id.dialogChooseFileButton)
        val uploadBtn = dialogView.findViewById<MaterialButton>(R.id.dialogUploadButton)
        val teacherAttachmentUrls = item.attachmentUrls.ifEmpty { listOfNotNull(item.attachmentUrl) }
        val teacherAttachmentNames = item.attachmentNames.ifEmpty { listOfNotNull(item.attachmentName) }

        chooseBtn.setOnClickListener {
            dialogHomeworkPicker.launch("*/*")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Submit ${item.title}")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ -> dialogFileSelectedText = null }
            .create()

        uploadBtn.setOnClickListener {
            val uri = dialogSelectedFileUri
            val fileName = dialogSelectedFileName
            if (uri == null || fileName.isNullOrBlank()) {
                Toast.makeText(this, "Choose a file before uploading", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            uploadBtn.isEnabled = false
            uploadStudentHomework(item, fileName, uri) { success, message ->
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    uploadBtn.isEnabled = true
                    if (success) {
                        dialog.dismiss()
                        bind()
                    }
                }
            }
        }

        if (teacherAttachmentUrls.isNotEmpty()) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Open teacher file") { _, _ ->
                openAttachmentList(teacherAttachmentNames, teacherAttachmentUrls)
            }
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let { neutralButton ->
            neutralButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            neutralButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.brand_primary)
        }
    }

    private fun uploadStudentHomework(
        item: com.schoolms.mobile.data.HomeworkItem,
        fileName: String,
        uri: Uri,
        callback: (Boolean, String) -> Unit
    ) {
        val user = SessionManager.currentUser ?: run {
            callback(false, "Please log in again")
            return
        }
        val storageRef = Firebase.storage.reference.child("homework_submissions/${System.currentTimeMillis()}_${fileName.replace("\\s+".toRegex(), "_")}")
        val progressDialog = UploadProgressDialog(this, "Uploading homework")
        progressDialog.show()
        SessionManager.ensureFirebaseSession { authResult ->
            authResult.onFailure {
                progressDialog.dismiss()
                callback(false, it.message ?: "Authentication required")
            }
                .onSuccess {
                    val optimizedUri = prepareOptimizedUpload(uri, "homework_submission")
                    val uploadTask = storageRef.putFile(optimizedUri)
                    uploadTask
                        .addOnProgressListener { snapshot ->
                            val percent = if (snapshot.totalByteCount > 0) {
                                ((snapshot.bytesTransferred * 100) / snapshot.totalByteCount).toInt()
                            } else {
                                0
                            }
                            progressDialog.update(1, 1, percent)
                        }
                        .continueWithTask { task ->
                            if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Upload failed")
                            storageRef.downloadUrl
                        }
                        .addOnSuccessListener { downloadUri ->
                            progressDialog.saving()
                            val success = SchoolRepository.submitHomework(user, item.id, fileName, downloadUri.toString())
                            progressDialog.dismiss()
                            callback(success, if (success) "Homework submitted" else "Submission failed")
                        }
                        .addOnFailureListener {
                            progressDialog.dismiss()
                            callback(false, it.message ?: "Upload failed")
                        }
                }
        }
    }

    private fun openAttachment(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "No attachment available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open attachment", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAttachmentList(names: List<String>, urls: List<String>) {
        if (urls.isEmpty()) {
            Toast.makeText(this, "No attachment available", Toast.LENGTH_SHORT).show()
            return
        }
        if (urls.size == 1) {
            openAttachment(urls.first())
            return
        }
        val labels = urls.mapIndexed { index, _ -> names.getOrNull(index).orEmpty().ifBlank { "Attachment ${index + 1}" } }
        AlertDialog.Builder(this)
            .setTitle("Open teacher file")
            .setItems(labels.toTypedArray()) { _, which -> openAttachment(urls[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun prepareOptimizedUpload(uri: Uri, cachePrefix: String): Uri {
        val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return uri
        val file = File(cacheDir, "${cachePrefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        }
        return Uri.fromFile(file)
    }

    private fun showAttendanceSummarySheet() {
        val profile = SchoolRepository.profileFor(username) ?: return
        val history = SchoolRepository.attendanceHistoryForStudent(username, profile.className)
        if (history.isEmpty()) {
            Toast.makeText(this, "No saved attendance history for this student yet", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 8)
        }

        history.groupBy { monthLabel(it.date) }.forEach { (month, entries) ->
            val monthText = TextView(this).apply {
                text = month
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 18, 0, 10)
            }
            container.addView(monthText)

            val table = TableLayout(this).apply {
                isStretchAllColumns = true
            }

            val headerRow = TableRow(this)
            headerRow.addView(buildCell("Date", true))
            headerRow.addView(buildCell("Status", true))
            table.addView(headerRow)

            entries.forEach { item ->
                val row = TableRow(this)
                row.addView(buildCell(item.date.substring(8), false))
                row.addView(buildStatusCell(item.present))
                table.addView(row)
            }
            container.addView(table)
        }

        AlertDialog.Builder(this)
            .setTitle("Attendance summary")
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildCell(value: String, isHeader: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = if (isHeader) 15f else 16f
            setPadding(0, 12, 0, 12)
            if (isHeader) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
    }

    private fun buildStatusCell(isPresent: Boolean): TextView {
        return TextView(this).apply {
            text = if (isPresent) "P" else "A"
            textSize = 14f
            setPadding(28, 12, 28, 12)
            setTextColor(getColor(android.R.color.white))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = getDrawable(
                if (isPresent) R.drawable.bg_attendance_status_present else R.drawable.bg_attendance_status_absent
            )
        }
    }

    private fun styleSectionButton(button: MaterialButton, selected: Boolean) {
        if (selected) {
            button.background = getDrawable(R.drawable.bg_student_tab_selected)
            button.setTextColor(getColor(android.R.color.white))
            button.iconTint = getColorStateList(android.R.color.white)
        } else {
            button.background = getDrawable(R.drawable.bg_student_tab_unselected)
            button.setTextColor(getColor(R.color.brand_primary))
            button.iconTint = getColorStateList(R.color.brand_primary)
        }
    }

    private fun moveSection(direction: Int) {
        val sections = listOf(SECTION_ATTENDANCE, SECTION_MARKS, SECTION_HOMEWORK, SECTION_PROGRESS)
        val currentIndex = sections.indexOf(selectedSection).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + direction).coerceIn(0, sections.lastIndex)
        if (nextIndex != currentIndex) {
            selectedSection = sections[nextIndex]
            bind()
        }
    }

    private fun normalizeSection(section: String): String = when (section) {
        SECTION_MARKS -> SECTION_MARKS
        SECTION_HOMEWORK -> SECTION_HOMEWORK
        SECTION_PROGRESS -> SECTION_PROGRESS
        else -> SECTION_ATTENDANCE
    }

    private inner class SectionSwipeListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val start = e1 ?: return false
            val diffX = e2.x - start.x
            val diffY = e2.y - start.y
            if (kotlin.math.abs(diffX) < 140 || kotlin.math.abs(diffX) < kotlin.math.abs(diffY)) return false
            if (diffX < 0) {
                moveSection(1)
            } else {
                moveSection(-1)
            }
            return true
        }
    }

    private fun monthLabel(date: String): String {
        return runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
            SimpleDateFormat("MMMM yyyy", Locale.US).format(parsed!!)
        }.getOrElse { date.substring(0, 7) }
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_SECTION = "section"
        const val SECTION_ATTENDANCE = "attendance"
        const val SECTION_MARKS = "marks"
        const val SECTION_HOMEWORK = "homework"
        const val SECTION_PROGRESS = "progress"
    }
}
