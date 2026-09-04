package com.schoolms.mobile.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.R
import com.schoolms.mobile.data.MarkItem
import com.schoolms.mobile.data.MobileAcademicGateway
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.data.StudentProfile
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class GradeEntryActivity : BaseActivity() {
    private lateinit var studentNameText: TextView
    private lateinit var studentMetaText: TextView
    private lateinit var subjectInput: MaterialAutoCompleteTextView
    private lateinit var subjectAdapter: ArrayAdapter<String>
    private lateinit var assessmentFormContainer: LinearLayout
    private lateinit var historyRecycler: RecyclerView
    private lateinit var historyLabel: TextView

    private var currentProfile: StudentProfile? = null
    private var currentSubject: String = ""
    private var scoreInput: TextInputEditText? = null
    private var outOfInput: TextInputEditText? = null
    private var assessmentInput: MaterialAutoCompleteTextView? = null
    private var customAssessmentInput: TextInputEditText? = null
    private var customAssessmentLayout: TextInputLayout? = null
    private var statusView: TextView? = null
    private var submitButton: MaterialButton? = null
    private var currentHistoryMarks: List<MarkItem> = emptyList()
    private val serverSavedMarks = mutableListOf<MarkItem>()
    // Network/repository refreshes must not overwrite a teacher's unfinished
    // form.  This activity intentionally keeps the form state keyed to the
    // current student + subject until the save succeeds or a new subject is
    // selected.
    private var formDirty = false
    private var applyingFormState = false

    private val assessmentOptions = listOf("Term 1", "Term 2", "Term 3", "Custom")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_grade_entry)

        setupToolbar(findViewById(R.id.toolbar), "Add grades")

        studentNameText = findViewById(R.id.studentNameText)
        studentMetaText = findViewById(R.id.studentMetaText)
        subjectInput = findViewById(R.id.subjectInput)
        assessmentFormContainer = findViewById(R.id.assessmentFormContainer)
        historyRecycler = findViewById(R.id.historyRecycler)
        historyLabel = findViewById(R.id.historyLabel)

        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyRecycler.adapter = SimpleListAdapter(emptyList())

        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val profile = SchoolRepository.profileFor(username) ?: run {
            val serverClass = intent.getStringExtra(EXTRA_CLASS_NAME).orEmpty()
            val fullName = intent.getStringExtra(EXTRA_FULL_NAME).orEmpty()
            if (serverClass.isBlank() || fullName.isBlank()) null else StudentProfile(
                username = username,
                fullName = fullName,
                className = serverClass,
                rollNumber = intent.getStringExtra(EXTRA_ROLL_NUMBER).orEmpty(),
                guardianContact = "",
                notes = ""
            )
        }
        if (profile == null) {
            Toast.makeText(this, "Student profile not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentProfile = profile
        studentNameText.text = profile.fullName
        studentMetaText.text = "${profile.className} | Roll ${profile.rollNumber.ifBlank { "--" }}"

        subjectAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        subjectInput.setAdapter(subjectAdapter)
        configureDropdown(subjectInput)
        subjectInput.setOnItemClickListener { _, _, position, _ ->
            currentSubject = subjectAdapter.getItem(position).orEmpty()
            formDirty = false
            bindHistory()
            bindAssessmentState()
        }

        inflateAssessmentForm()
        refreshSubjects(profile.className)
        bindHistory()
    }

    override fun onResume() {
        super.onResume()
        if (currentProfile != null) {
            refreshSubjects(currentProfile!!.className)
            bindHistory()
        }
    }

    override fun onRepositoryChanged() {
        if (currentProfile != null) {
            refreshSubjects(currentProfile!!.className)
            bindHistory()
        }
    }

    private fun configureDropdown(input: MaterialAutoCompleteTextView) {
        input.threshold = 0
        input.setOnClickListener { input.showDropDown() }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) input.showDropDown()
        }
    }

    private fun inflateAssessmentForm() {
        assessmentFormContainer.removeAllViews()
        val block = layoutInflater.inflate(R.layout.item_assessment_block, assessmentFormContainer, false)
        val assessmentName = block.findViewById<TextView>(R.id.assessmentName)
        val assessmentTypeInput = block.findViewById<MaterialAutoCompleteTextView>(R.id.assessmentTypeInput)
        val customLayout = block.findViewById<TextInputLayout>(R.id.customAssessmentLayout)
        val customInput = block.findViewById<TextInputEditText>(R.id.customAssessmentInput)
        val score = block.findViewById<TextInputEditText>(R.id.assessmentScoreInput)
        val outOf = block.findViewById<TextInputEditText>(R.id.assessmentOutOfInput)
        val submit = block.findViewById<MaterialButton>(R.id.assessmentSubmitButton)
        val status = block.findViewById<TextView>(R.id.assessmentStatus)

        assessmentName.text = "Add grades"
        assessmentTypeInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, assessmentOptions))
        configureDropdown(assessmentTypeInput)
        assessmentTypeInput.setText("Custom", false)
        customLayout.visibility = View.VISIBLE
        customInput.hint = "Example: Class Test 4"
        customInput.setText("")
        score.setText("")
        outOf.setText("")
        status.text = "Choose a subject to see the latest grade."
        submit.text = "Save grade"

        assessmentInput = assessmentTypeInput
        customAssessmentLayout = customLayout
        customAssessmentInput = customInput
        scoreInput = score
        outOfInput = outOf
        statusView = status
        submitButton = submit

        listOf(customInput, score, outOf).forEach { input ->
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!applyingFormState) formDirty = true
                }
            })
        }

        assessmentTypeInput.setOnItemClickListener { _, _, _, _ ->
            customLayout.visibility = if (assessmentTypeInput.text.toString() == "Custom") View.VISIBLE else View.GONE
        }

        submit.setOnClickListener { submitGrade() }
        assessmentFormContainer.addView(block)
    }

    private fun refreshSubjects(className: String) {
        applySubjects(SchoolRepository.subjectsForClass(className).map { it.name })
        MobileAcademicGateway.staffSubjects(className) { result ->
            runOnUiThread {
                result.onSuccess { serverSubjects ->
                    // An empty or delayed server response must never erase the
                    // class subjects already available on the phone.
                    val current = (0 until subjectAdapter.count).mapNotNull { subjectAdapter.getItem(it) }
                    applySubjects(current + serverSubjects.map { it.name }
                        .filterNot { it.equals("Daily Attendance", true) })
                }.onFailure { error ->
                    if (currentSubject.isBlank()) {
                        statusView?.text = "Subjects could not be refreshed: ${error.message ?: "server unavailable"}"
                    }
                }
            }
        }
    }

    private fun applySubjects(names: List<String>) {
        val subjects = names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (subjectAdapter.count != subjects.size || (0 until subjectAdapter.count).any { subjectAdapter.getItem(it) != subjects[it] }) {
            subjectAdapter.clear()
            subjectAdapter.addAll(subjects)
            subjectAdapter.notifyDataSetChanged()
        }

        currentSubject = when {
            currentSubject.isNotBlank() && subjects.any { it.equals(currentSubject, true) } -> currentSubject
            else -> subjects.firstOrNull().orEmpty()
        }
        if (!subjectInput.text.toString().equals(currentSubject, true)) subjectInput.setText(currentSubject, false)
        if (!formDirty) bindAssessmentState()
    }

    private fun bindAssessmentState() {
        val profile = currentProfile ?: return
        val subject = currentSubject.trim()
        val existing = subject.takeIf { it.isNotBlank() }?.let { latestSubjectMark(profile.username, it) }

        if (subject.isBlank()) {
            statusView?.text = "Choose a subject first."
            submitButton?.isEnabled = false
            historyLabel.text = "Recent grades"
            return
        }

        submitButton?.isEnabled = true
        submitButton?.text = if (existing == null) "Save grade" else "Update grade"
        statusView?.text = existing?.let { "Last: ${it.assessment} | ${it.score}/${it.outOf} | ${it.grade}" }
            ?: "No marks recorded for this subject yet."

        if (formDirty) return
        applyingFormState = true
        try {
            val knownAssessment = existing?.assessment?.takeIf { assessmentOptions.any { option -> option.equals(it, true) } }
            if (knownAssessment == null) {
                assessmentInput?.setText("Custom", false)
                customAssessmentLayout?.visibility = View.VISIBLE
                customAssessmentInput?.setText(existing?.assessment.orEmpty())
            } else {
                assessmentInput?.setText(knownAssessment, false)
                customAssessmentLayout?.visibility = View.GONE
                customAssessmentInput?.setText("")
            }
            scoreInput?.setText(existing?.score?.toString().orEmpty())
            outOfInput?.setText(existing?.outOf?.toString().orEmpty())
        } finally {
            applyingFormState = false
        }
    }

    private fun bindHistory() {
        val profile = currentProfile ?: return
        val subject = currentSubject.trim()
        currentHistoryMarks = if (subject.isBlank()) {
            emptyList()
        } else {
            (SchoolRepository.marksForStudent(profile.username) + serverSavedMarks)
                .filter { it.subject.equals(subject, true) }
                .asReversed()
        }
        val rows = currentHistoryMarks.map {
            SimpleListItem(
                title = it.assessment,
                subtitle = "Score: ${it.score}/${it.outOf}\nGrade: ${it.grade}\nTap to edit or remove",
                badge = it.grade
            )
        }
        historyLabel.text = if (subject.isBlank()) "Recent grades" else "Recent grades for $subject"
        historyRecycler.adapter = SimpleListAdapter(
            if (rows.isEmpty()) listOf(SimpleListItem("No grades yet", "This subject has no saved grade entries.", "Pending")) else rows
        ) { position ->
            currentHistoryMarks.getOrNull(position)?.let { showMarkActions(it) }
        }
    }

    private fun showMarkActions(mark: MarkItem) {
        AlertDialog.Builder(this)
            .setTitle(mark.assessment)
            .setMessage("${mark.subject}\nScore: ${mark.score}/${mark.outOf}\nGrade: ${mark.grade}")
            .setItems(arrayOf("Edit marks", "Remove marks")) { _, which ->
                when (which) {
                    0 -> fillMarkForEdit(mark)
                    1 -> confirmRemoveMark(mark)
                }
            }
            .show()
    }

    private fun fillMarkForEdit(mark: MarkItem) {
        formDirty = false
        applyingFormState = true
        currentSubject = mark.subject
        subjectInput.setText(mark.subject, false)
        val knownAssessment = mark.assessment.takeIf { assessmentOptions.any { option -> option.equals(it, true) } }
        if (knownAssessment == null) {
            assessmentInput?.setText("Custom", false)
            customAssessmentLayout?.visibility = View.VISIBLE
            customAssessmentInput?.setText(mark.assessment)
        } else {
            assessmentInput?.setText(knownAssessment, false)
            customAssessmentLayout?.visibility = View.GONE
            customAssessmentInput?.setText("")
        }
        scoreInput?.setText(mark.score.toString())
        outOfInput?.setText(mark.outOf.toString())
        applyingFormState = false
        submitButton?.text = "Update grade"
        statusView?.text = "Editing: ${mark.assessment} | ${mark.score}/${mark.outOf} | ${mark.grade}"
    }

    private fun confirmRemoveMark(mark: MarkItem) {
        AlertDialog.Builder(this)
            .setTitle("Remove marks?")
            .setMessage("This will remove ${mark.assessment} marks for ${mark.studentName}.")
            .setPositiveButton("Remove") { _, _ -> removeMark(mark) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeMark(mark: MarkItem) {
        val user = SessionManager.currentUser ?: return
        SessionManager.ensureFirebaseSession { authResult ->
            runOnUiThread {
                authResult.onFailure {
                    Toast.makeText(this, it.message ?: "Please log in again before updating marks", Toast.LENGTH_SHORT).show()
                }.onSuccess {
                    val success = SchoolRepository.deleteMark(
                        user = user,
                        studentUsername = mark.studentUsername,
                        subject = mark.subject,
                        assessment = mark.assessment
                    )
                    Toast.makeText(this, if (success) "Marks removed" else "Unable to remove marks", Toast.LENGTH_SHORT).show()
                    if (success) {
                        bindHistory()
                        bindAssessmentState()
                    }
                }
            }
        }
    }

    private fun submitGrade() {
        val profile = currentProfile ?: return
        val subject = currentSubject.trim()
        val assessment = selectedAssessment()
        val obtained = scoreInput?.text?.toString()?.trim()?.toIntOrNull()
        val total = outOfInput?.text?.toString()?.trim()?.toIntOrNull()

        if (subject.isBlank()) {
            Toast.makeText(this, "Select a subject first", Toast.LENGTH_SHORT).show()
            return
        }
        if (assessment.isBlank()) {
            Toast.makeText(this, "Select or write an assessment name", Toast.LENGTH_SHORT).show()
            return
        }
        if (obtained == null || total == null || total <= 0 || obtained < 0 || obtained > total) {
            Toast.makeText(this, "Enter obtained marks and valid max marks", Toast.LENGTH_SHORT).show()
            return
        }

        submitButton?.isEnabled = false
        MobileAcademicGateway.saveMark(
            studentUsername = profile.username,
            className = profile.className,
            subjectName = subject,
            assessment = assessment,
            score = obtained,
            outOf = total
        ) { result ->
            runOnUiThread {
                submitButton?.isEnabled = true
                result.onSuccess {
                    serverSavedMarks.removeAll {
                        it.studentUsername.equals(profile.username, true) &&
                            it.subject.equals(subject, true) && it.assessment.equals(assessment, true)
                    }
                    serverSavedMarks.add(MarkItem(profile.username, profile.fullName, subject, obtained, total, assessment))
                    SchoolRepository.refreshPrivateAcademicContent { }
                    formDirty = false
                    Toast.makeText(this, "Grades saved for ${profile.fullName}", Toast.LENGTH_SHORT).show()
                    bindHistory()
                    bindAssessmentState()
                }.onFailure { error ->
                    val user = SessionManager.currentUser
                    val savedLocally = user != null && SchoolRepository.addMark(
                        user, profile.username, profile.fullName, subject, obtained, total, assessment
                    )
                    if (savedLocally) {
                        serverSavedMarks.removeAll {
                            it.studentUsername.equals(profile.username, true) &&
                                it.subject.equals(subject, true) && it.assessment.equals(assessment, true)
                        }
                        serverSavedMarks.add(MarkItem(profile.username, profile.fullName, subject, obtained, total, assessment))
                        formDirty = false
                        Toast.makeText(this, "Grades saved for ${profile.fullName}", Toast.LENGTH_SHORT).show()
                        bindHistory()
                        bindAssessmentState()
                    } else {
                        Toast.makeText(this, error.message ?: "Marks could not be saved.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun selectedAssessment(): String {
        val selected = assessmentInput?.text?.toString().orEmpty().trim()
        return if (selected.equals("Custom", true)) {
            customAssessmentInput?.text?.toString().orEmpty().trim()
        } else {
            selected
        }
    }

    private fun latestSubjectMark(username: String, subject: String) =
        (SchoolRepository.marksForStudent(username) + serverSavedMarks)
            .lastOrNull { it.subject.equals(subject, true) }

    companion object {
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_CLASS_NAME = "extra_class_name"
        const val EXTRA_FULL_NAME = "extra_full_name"
        const val EXTRA_ROLL_NUMBER = "extra_roll_number"
    }
}
