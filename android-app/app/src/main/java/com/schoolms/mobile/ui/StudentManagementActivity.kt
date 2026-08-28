package com.schoolms.mobile.ui

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.data.User
import com.schoolms.mobile.ui.adapter.SimpleListAdapter
import com.schoolms.mobile.util.UsernameAvailabilitySupport
import java.io.File
import java.io.FileOutputStream

class StudentManagementActivity : BaseActivity() {
    private enum class Section {
        STUDENTS,
        TEACHERS,
        SUBJECTS,
        RECOVERY
    }

    private lateinit var studentRecycler: RecyclerView
    private lateinit var teacherRecycler: RecyclerView
    private lateinit var subjectRecycler: RecyclerView
    private lateinit var recoveryRecycler: RecyclerView
    private var currentSection: Section? = null
    private var selectedStudentPhotoUri: Uri? = null
    private var selectedTeacherPhotoUri: Uri? = null

    private val studentPhotoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedStudentPhotoUri = uri
        findViewById<TextView>(R.id.studentPhotoText).text = uri?.let { "Student photo: ${resolveDisplayName(it)}" } ?: "No student photo selected"
    }

    private val teacherPhotoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedTeacherPhotoUri = uri
        findViewById<TextView>(R.id.teacherPhotoText).text = uri?.let { "Teacher photo: ${resolveDisplayName(it)}" } ?: "No teacher photo selected"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_student_management)

        val currentUser = SessionManager.currentUser ?: return
        if (currentUser.role == Role.STUDENT) {
            finish()
            return
        }

        setupToolbar(
            findViewById<MaterialToolbar>(R.id.toolbar),
            if (currentUser.role == Role.ADMIN) "Students, teachers and subjects" else "Students and subjects"
        )

        val studentUsernameInput = findViewById<TextInputEditText>(R.id.studentUsernameInput)
        val studentUsernameLayout = findViewById<TextInputLayout>(R.id.studentUsernameLayout)
        val studentNameInput = findViewById<TextInputEditText>(R.id.studentNameInput)
        val studentClassInput = findViewById<MaterialAutoCompleteTextView>(R.id.studentClassInput)
        val studentRollInput = findViewById<TextInputEditText>(R.id.studentRollInput)
        val studentGuardianInput = findViewById<TextInputEditText>(R.id.studentGuardianInput)
        val studentNotesInput = findViewById<TextInputEditText>(R.id.studentNotesInput)

        val teacherUsernameInput = findViewById<TextInputEditText>(R.id.teacherUsernameInput)
        val teacherUsernameLayout = findViewById<TextInputLayout>(R.id.teacherUsernameLayout)
        val teacherNameInput = findViewById<TextInputEditText>(R.id.teacherNameInput)
        val teacherClassInput = findViewById<MaterialAutoCompleteTextView>(R.id.teacherClassInput)
        val teacherQualificationInput = findViewById<TextInputEditText>(R.id.teacherQualificationInput)
        val teacherExperienceInput = findViewById<TextInputEditText>(R.id.teacherExperienceInput)
        val teacherSpecializationInput = findViewById<TextInputEditText>(R.id.teacherSpecializationInput)
        val teacherBioInput = findViewById<TextInputEditText>(R.id.teacherBioInput)

        val subjectNameInput = findViewById<TextInputEditText>(R.id.subjectNameInput)
        val subjectClassInput = findViewById<MaterialAutoCompleteTextView>(R.id.subjectClassInput)
        val subjectTeacherInput = findViewById<TextInputEditText>(R.id.subjectTeacherInput)
        val studentSectionButton = findViewById<MaterialButton>(R.id.studentSectionButton)
        val teacherSectionButton = findViewById<MaterialButton>(R.id.teacherSectionButton)
        val subjectSectionButton = findViewById<MaterialButton>(R.id.subjectSectionButton)
        val recoverySectionButton = findViewById<MaterialButton>(R.id.recoverySectionButton)

        studentRecycler = findViewById(R.id.studentRecycler)
        teacherRecycler = findViewById(R.id.teacherRecycler)
        subjectRecycler = findViewById(R.id.subjectRecycler)
        recoveryRecycler = findViewById(R.id.recoveryRecycler)
        studentRecycler.layoutManager = LinearLayoutManager(this)
        teacherRecycler.layoutManager = LinearLayoutManager(this)
        subjectRecycler.layoutManager = LinearLayoutManager(this)
        recoveryRecycler.layoutManager = LinearLayoutManager(this)

        val allowedClasses = visibleClasses(currentUser)
        val classAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, allowedClasses)
        studentClassInput.setAdapter(classAdapter)
        teacherClassInput.setAdapter(classAdapter)
        subjectClassInput.setAdapter(classAdapter)
        if (allowedClasses.isNotEmpty()) {
            studentClassInput.setText(allowedClasses.first(), false)
            teacherClassInput.setText(allowedClasses.first(), false)
            subjectClassInput.setText(allowedClasses.first(), false)
        }
        UsernameAvailabilitySupport.bind(studentUsernameLayout, studentUsernameInput, studentNameInput)
        UsernameAvailabilitySupport.bind(teacherUsernameLayout, teacherUsernameInput, teacherNameInput)

        if (currentUser.role == Role.TEACHER) {
            findViewById<MaterialCardView>(R.id.addStudentCard).visibility = View.GONE
            findViewById<MaterialCardView>(R.id.addTeacherCard).visibility = View.GONE
            teacherSectionButton.visibility = View.GONE
            recoverySectionButton.visibility = View.GONE
            subjectTeacherInput.setText(currentUser.fullName)
        }

        studentSectionButton.setOnClickListener {
            setSection(Section.STUDENTS)
        }
        teacherSectionButton.setOnClickListener {
            setSection(Section.TEACHERS)
        }
        subjectSectionButton.setOnClickListener {
            setSection(Section.SUBJECTS)
        }
        recoverySectionButton.setOnClickListener {
            setSection(Section.RECOVERY)
        }

        findViewById<MaterialButton>(R.id.chooseStudentPhotoButton).setOnClickListener {
            studentPhotoPicker.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.chooseTeacherPhotoButton).setOnClickListener {
            teacherPhotoPicker.launch("image/*")
        }

        findViewById<MaterialButton>(R.id.addStudentButton).setOnClickListener {
            if (currentUser.role != Role.ADMIN) {
                Toast.makeText(this, "Only an administrator can create student records.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedClass = studentClassInput.text?.toString().orEmpty()
            val createdUsername = studentUsernameInput.text?.toString().orEmpty().trim().lowercase()
            if (!canManageClass(currentUser, selectedClass)) {
                Toast.makeText(this, "You can add students only in your assigned class", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val created = SchoolRepository.addStudentProfile(
                username = studentUsernameInput.text?.toString().orEmpty(),
                password = "",
                fullName = studentNameInput.text?.toString().orEmpty(),
                className = selectedClass,
                rollNumber = studentRollInput.text?.toString().orEmpty(),
                guardianContact = studentGuardianInput.text?.toString().orEmpty(),
                notes = studentNotesInput.text?.toString().orEmpty(),
                approved = true,
                mobileNumber = findViewById<TextInputEditText>(R.id.studentMobileInput).text?.toString().orEmpty()
            )
            if (created) {
                        Toast.makeText(this, "Student record created. Ask the student to activate it with OTP.", Toast.LENGTH_LONG).show()
                        studentUsernameInput.text = null
                        findViewById<TextInputEditText>(R.id.studentMobileInput).text = null
                        studentNameInput.text = null
                        studentClassInput.setText(allowedClasses.firstOrNull().orEmpty(), false)
                        studentRollInput.text = null
                        studentGuardianInput.text = null
                        studentNotesInput.text = null
                        uploadProfileImageIfNeeded("student_profiles/$createdUsername", selectedStudentPhotoUri) { imageUrl ->
                            if (imageUrl.isNotBlank()) SchoolRepository.updateStudentProfileImage(createdUsername, imageUrl)
                            selectedStudentPhotoUri = null
                            findViewById<TextView>(R.id.studentPhotoText).text = "No student photo selected"
                            bindLists()
                        }
            } else {
                Toast.makeText(this, "Enter a unique ID, name, class, and registered mobile number.", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<MaterialButton>(R.id.addTeacherButton).setOnClickListener {
            if (currentUser.role != Role.ADMIN) return@setOnClickListener
            val createdUsername = teacherUsernameInput.text?.toString().orEmpty().trim().lowercase()
            val qualification = teacherQualificationInput.text?.toString().orEmpty()
            val experience = teacherExperienceInput.text?.toString().orEmpty()
            val specialization = teacherSpecializationInput.text?.toString().orEmpty()
            val bio = teacherBioInput.text?.toString().orEmpty()
            val created = SchoolRepository.addTeacher(
                username = teacherUsernameInput.text?.toString().orEmpty(),
                password = "",
                fullName = teacherNameInput.text?.toString().orEmpty(),
                classNames = parseClasses(teacherClassInput.text?.toString().orEmpty()),
                subject = findViewById<TextInputEditText>(R.id.teacherSubjectInput).text?.toString().orEmpty(),
                approved = true,
                mobileNumber = findViewById<TextInputEditText>(R.id.teacherMobileInput).text?.toString().orEmpty(),
                allowBlankPassword = true
            )
            if (created) {
                        Toast.makeText(this, "Teacher record created. Ask the teacher to activate it with OTP.", Toast.LENGTH_LONG).show()
                        uploadProfileImageIfNeeded("teacher_profiles/$createdUsername", selectedTeacherPhotoUri) { imageUrl ->
                            SchoolRepository.updateTeacherProfileDetails(
                                username = createdUsername,
                                imageUrl = imageUrl.ifBlank { null },
                                qualification = qualification,
                                experience = experience,
                                specialization = specialization,
                                staffBio = bio
                            )
                            selectedTeacherPhotoUri = null
                            findViewById<TextView>(R.id.teacherPhotoText).text = "No teacher photo selected"
                            bindLists()
                        }
                        teacherUsernameInput.text = null
                        findViewById<TextInputEditText>(R.id.teacherMobileInput).text = null
                        teacherNameInput.text = null
                        teacherQualificationInput.text = null
                        teacherExperienceInput.text = null
                        teacherSpecializationInput.text = null
                        teacherBioInput.text = null
            } else {
                Toast.makeText(this, "Enter a unique teacher ID, name, and registered mobile number.", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<MaterialButton>(R.id.addSubjectButton).setOnClickListener {
            if (currentUser.role != Role.ADMIN && currentUser.role != Role.TEACHER) return@setOnClickListener
            val success = SchoolRepository.addSubject(
                name = subjectNameInput.text?.toString().orEmpty(),
                className = subjectClassInput.text?.toString().orEmpty(),
                teacherName = if (currentUser.role == Role.TEACHER) currentUser.fullName else subjectTeacherInput.text?.toString().orEmpty()
            )
            Toast.makeText(this, if (success) "Subject added" else "Fill required fields or avoid duplicates", Toast.LENGTH_SHORT).show()
            if (success) {
                subjectNameInput.text = null
                subjectClassInput.setText(allowedClasses.firstOrNull().orEmpty(), false)
                subjectTeacherInput.setText(if (currentUser.role == Role.TEACHER) currentUser.fullName else "")
                bindLists()
            }
        }

        bindLists()
    }

    private fun setSection(section: Section) {
        currentSection = section
        bindLists()
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index).orEmpty().ifBlank { "Selected image" }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected image"
    }

    private fun uploadProfileImageIfNeeded(path: String, uri: Uri?, onComplete: (String) -> Unit) {
        if (uri == null) {
            onComplete("")
            return
        }
        SessionManager.ensureFirebaseSession { authResult ->
            runOnUiThread {
                authResult.onFailure {
                    Toast.makeText(this, it.message ?: "Please log in again before uploading photo", Toast.LENGTH_SHORT).show()
                    onComplete("")
                }.onSuccess {
                    val storageRef = Firebase.storage.reference.child("$path/${System.currentTimeMillis()}_${resolveDisplayName(uri).replace("\\s+".toRegex(), "_")}")
                    val optimizedUri = prepareOptimizedUpload(uri, path)
                    storageRef.putFile(optimizedUri)
                        .continueWithTask { task ->
                            if (!task.isSuccessful) throw (task.exception ?: IllegalStateException("Upload failed"))
                            storageRef.downloadUrl
                        }
                        .addOnSuccessListener { onComplete(it.toString()) }
                        .addOnFailureListener { error ->
                            Toast.makeText(this, error.message ?: "Photo upload failed", Toast.LENGTH_SHORT).show()
                            onComplete("")
                        }
                }
            }
        }
    }

    private fun prepareOptimizedUpload(uri: Uri, cachePrefix: String): Uri {
        val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return uri
        val safePrefix = cachePrefix.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "profile" }
        val file = File(cacheDir, "${safePrefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        }
        return Uri.fromFile(file)
    }

    private fun bindLists() {
        val currentUser = SessionManager.currentUser ?: return
        val classes = visibleClasses(currentUser)
        val classAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, classes)
        findViewById<MaterialAutoCompleteTextView>(R.id.studentClassInput).setAdapter(classAdapter)
        findViewById<MaterialAutoCompleteTextView>(R.id.teacherClassInput).setAdapter(classAdapter)
        findViewById<MaterialAutoCompleteTextView>(R.id.subjectClassInput).setAdapter(classAdapter)

        val students = visibleStudents(currentUser)
        studentRecycler.adapter = SimpleListAdapter(
            students.map {
                SimpleListItem("Student: ${it.fullName}", "${it.className} | Roll ${it.rollNumber} | Username ${it.username}", "Student")
            }
        ) { position ->
            showEditStudentDialog(students[position].username)
        }
        val teachers = if (currentUser.role == Role.ADMIN) SchoolRepository.teacherUsers() else emptyList()
        teacherRecycler.adapter = SimpleListAdapter(
            teachers.map {
                SimpleListItem(
                    "Teacher: ${it.fullName}",
                    "${SchoolRepository.assignedClasses(it).joinToString(", ")} | Username ${it.username}",
                    "Teacher"
                )
            }
        ) { position ->
            showEditTeacherDialog(teachers[position].username)
        }

        val rawSubjects = if (currentUser.role == Role.ADMIN) {
            SchoolRepository.subjectItems()
        } else {
            SchoolRepository.subjectItems()
                .filter { it.className in SchoolRepository.availableClasses() }
        }
        val subjects = if (currentUser.role == Role.ADMIN) {
            rawSubjects.map {
                SimpleListItem("Subject: ${it.name}", "${it.className} | Teacher ${it.teacherName}", "Subject")
            }
        } else {
            rawSubjects.map {
                    SimpleListItem("Subject: ${it.name}", "${it.className} | Teacher ${it.teacherName}", if (it.teacherName == currentUser.fullName) "Mine" else "Subject")
                }
        }
        subjectRecycler.adapter = SimpleListAdapter(subjects) { position ->
            if (currentUser.role != Role.ADMIN) return@SimpleListAdapter
            val subject = rawSubjects.getOrNull(position) ?: return@SimpleListAdapter
            AlertDialog.Builder(this)
                .setTitle("Delete subject?")
                .setMessage("Remove ${subject.name} from ${subject.className}? This also removes matching timetable cells.")
                .setPositiveButton("Delete") { _, _ ->
                    val success = SchoolRepository.deleteSubject(subject.name, subject.className)
                    Toast.makeText(this, if (success) "Subject deleted" else "Unable to delete subject", Toast.LENGTH_SHORT).show()
                    if (success) bindLists()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val archives = SchoolRepository.deletedStudentArchives()
        recoveryRecycler.adapter = SimpleListAdapter(
            archives.map {
                val profile = it.profile
                SimpleListItem(
                    "Recover: ${profile?.fullName ?: it.user?.fullName ?: it.username}",
                    "${profile?.className ?: it.user?.className.orEmpty()} | Username ${it.username}\nAttendance ${it.attendanceRecords.size} | Marks ${it.marks.size} | Homework ${it.homeworkItems.size}",
                    "Restore"
                )
            }
        ) { position ->
            val archive = archives.getOrNull(position) ?: return@SimpleListAdapter
            AlertDialog.Builder(this)
                .setTitle("Recover student?")
                .setMessage("Restore ${archive.profile?.fullName ?: archive.user?.fullName ?: archive.username} with saved profile, login, attendance, marks, and homework submissions?")
                .setPositiveButton("Recover") { _, _ ->
                    val success = SchoolRepository.recoverDeletedStudent(archive.username)
                    Toast.makeText(this, if (success) "Student recovered" else "Unable to recover student", Toast.LENGTH_SHORT).show()
                    if (success) bindLists()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        applySectionVisibility(currentUser)
    }

    private fun applySectionVisibility(currentUser: User) {
        val showStudents = currentSection == Section.STUDENTS
        val showTeachers = currentUser.role == Role.ADMIN && currentSection == Section.TEACHERS
        val showSubjects = currentSection == Section.SUBJECTS
        val showRecovery = currentUser.role == Role.ADMIN && currentSection == Section.RECOVERY

        findViewById<TextView>(R.id.studentSectionLabel).visibility = if (showStudents) View.VISIBLE else View.GONE
        studentRecycler.visibility = if (showStudents) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.teacherSectionLabel).visibility = if (showTeachers) View.VISIBLE else View.GONE
        teacherRecycler.visibility = if (showTeachers) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.subjectSectionLabel).visibility = if (showSubjects) View.VISIBLE else View.GONE
        subjectRecycler.visibility = if (showSubjects) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.recoverySectionLabel).visibility = if (showRecovery) View.VISIBLE else View.GONE
        recoveryRecycler.visibility = if (showRecovery) View.VISIBLE else View.GONE

        updateSectionButtonState(currentUser)
    }

    private fun updateSectionButtonState(currentUser: User) {
        val studentButton = findViewById<MaterialButton>(R.id.studentSectionButton)
        val teacherButton = findViewById<MaterialButton>(R.id.teacherSectionButton)
        val subjectButton = findViewById<MaterialButton>(R.id.subjectSectionButton)
        val recoveryButton = findViewById<MaterialButton>(R.id.recoverySectionButton)

        fun setActive(button: MaterialButton, active: Boolean) {
            button.alpha = if (active) 1f else 0.72f
            button.scaleX = if (active) 1.02f else 1f
            button.scaleY = if (active) 1.02f else 1f
        }

        setActive(studentButton, currentSection == Section.STUDENTS)
        setActive(teacherButton, currentSection == Section.TEACHERS && currentUser.role == Role.ADMIN)
        setActive(subjectButton, currentSection == Section.SUBJECTS)
        setActive(recoveryButton, currentSection == Section.RECOVERY && currentUser.role == Role.ADMIN)
    }

    private fun showEditStudentDialog(username: String) {
        val currentUser = SessionManager.currentUser ?: return
        val profile = visibleStudents(currentUser).firstOrNull { it.username == username } ?: return
        val classes = visibleClasses(currentUser)
        val container = formContainer()
        val usernameInput = textField("Username", profile.username, container)
        val nameInput = textField("Student name", profile.fullName, container)
        val classInput = dropdownField("Class", classes, profile.className, container)
        val rollInput = textField("Roll number", profile.rollNumber, container)
        val guardianInput = textField("Guardian contact", profile.guardianContact, container)
        val notesInput = textField("Notes", profile.notes, container, 3)

        usernameInput.isEnabled = false

        AlertDialog.Builder(this)
            .setTitle("Edit student")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val selectedClass = classInput.text?.toString().orEmpty()
                if (!canManageClass(currentUser, selectedClass)) {
                    Toast.makeText(this, "You can manage students only in your assigned class", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val success = SchoolRepository.updateStudentProfile(
                    originalUsername = profile.username,
                    username = usernameInput.text?.toString().orEmpty(),
                    fullName = nameInput.text?.toString().orEmpty(),
                    className = selectedClass,
                    rollNumber = rollInput.text?.toString().orEmpty(),
                    guardianContact = guardianInput.text?.toString().orEmpty(),
                    notes = notesInput.text?.toString().orEmpty()
                )
                Toast.makeText(this, if (success) "Student updated" else "Unable to update student", Toast.LENGTH_SHORT).show()
                if (success) bindLists()
            }
            .setNeutralButton("Delete") { _, _ ->
                val success = if (canManageClass(currentUser, profile.className)) {
                    SchoolRepository.deleteStudent(profile.username)
                } else {
                    false
                }
                Toast.makeText(this, if (success) "Student deleted" else "Unable to delete student", Toast.LENGTH_SHORT).show()
                if (success) bindLists()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditTeacherDialog(username: String) {
        val teacher = SchoolRepository.teacherUsers().firstOrNull { it.username == username } ?: return
        val classes = SchoolRepository.availableClasses()
        val container = formContainer()
        val usernameInput = textField("Teacher username", teacher.username, container)
        val passwordInput = textField("Teacher password", teacher.password, container)
        val nameInput = textField("Teacher name", teacher.fullName, container)
        val classInput = dropdownField("Assigned class", classes, SchoolRepository.assignedClasses(teacher).joinToString(", "), container)
        val subjectInput = textField("Main subject", teacher.subject, container)
        val qualificationInput = textField("Qualification", teacher.qualification.orEmpty(), container)
        val experienceInput = textField("Teaching experience", teacher.experience.orEmpty(), container)
        val specializationInput = textField("Specialization", teacher.specialization.orEmpty(), container)
        val bioInput = textField("Staff profile note", teacher.staffBio.orEmpty(), container, 3)

        usernameInput.isEnabled = false
        passwordInput.isEnabled = false

        AlertDialog.Builder(this)
            .setTitle("Edit teacher")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.updateTeacher(
                    originalUsername = teacher.username,
                    username = usernameInput.text?.toString().orEmpty(),
                    password = passwordInput.text?.toString().orEmpty(),
                    fullName = nameInput.text?.toString().orEmpty(),
                    classNames = parseClasses(classInput.text?.toString().orEmpty()),
                    subject = subjectInput.text?.toString().orEmpty()
                )
                if (success) {
                    SchoolRepository.updateTeacherProfileDetails(
                        username = usernameInput.text?.toString().orEmpty(),
                        qualification = qualificationInput.text?.toString().orEmpty(),
                        experience = experienceInput.text?.toString().orEmpty(),
                        specialization = specializationInput.text?.toString().orEmpty(),
                        staffBio = bioInput.text?.toString().orEmpty()
                    )
                }
                Toast.makeText(this, if (success) "Teacher updated" else "Unable to update teacher", Toast.LENGTH_SHORT).show()
                if (success) bindLists()
            }
            .setNeutralButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteTeacher(teacher.username)
                Toast.makeText(this, if (success) "Teacher deleted" else "Unable to delete teacher", Toast.LENGTH_SHORT).show()
                if (success) bindLists()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun visibleClasses(user: User): List<String> {
        return when (user.role) {
            Role.ADMIN -> SchoolRepository.availableClasses()
            Role.TEACHER -> SchoolRepository.availableClasses()
            Role.STUDENT -> emptyList()
        }
    }

    private fun visibleStudents(user: User) = when (user.role) {
        Role.ADMIN -> SchoolRepository.allStudentProfiles()
        Role.TEACHER -> SchoolRepository.allStudentProfiles().filter { it.className in SchoolRepository.assignedClasses(user) }
        Role.STUDENT -> emptyList()
    }

    private fun canManageClass(user: User, className: String): Boolean {
        return when (user.role) {
            Role.ADMIN -> true
            Role.TEACHER -> SchoolRepository.assignedClasses(user).contains(className)
            Role.STUDENT -> false
        }
    }

    private fun formContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 0)
        }
    }

    private fun textField(label: String, value: String, container: LinearLayout, minLines: Int = 1): TextInputEditText {
        val inputLayout = TextInputLayout(this).apply {
            hint = label
            if (container.childCount > 0) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 14 }
            }
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            this.minLines = minLines
        }
        inputLayout.addView(input)
        container.addView(inputLayout)
        return input
    }

    private fun dropdownField(label: String, options: List<String>, value: String, container: LinearLayout): MaterialAutoCompleteTextView {
        val inputLayout = TextInputLayout(this).apply {
            hint = label
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            if (container.childCount > 0) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 14 }
            }
        }
        val input = MaterialAutoCompleteTextView(this).apply {
            inputType = 0
            setAdapter(ArrayAdapter(this@StudentManagementActivity, android.R.layout.simple_list_item_1, options))
            setText(value, false)
        }
        inputLayout.addView(input)
        container.addView(inputLayout)
        return input
    }

    private fun parseClasses(raw: String): List<String> {
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    override fun onRepositoryChanged() {
        if (::studentRecycler.isInitialized && ::teacherRecycler.isInitialized && ::subjectRecycler.isInitialized) {
            bindLists()
        }
    }
}
