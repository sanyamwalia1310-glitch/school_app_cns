package com.schoolms.mobile.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.ImageView
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ProfileActivity : BaseActivity() {
    private enum class PhotoDisplayMode {
        CROP,
        FIT
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var studentQuickActionsScroll: LinearLayout
    private lateinit var studentQuickActionsRow: LinearLayout
    private var query: String = ""

    private var selectedProfilePhotoUri: Uri? = null
    private var selectedProfilePhotoMode: PhotoDisplayMode = PhotoDisplayMode.FIT
    private var editPhotoStatusText: TextView? = null
    private var editFullNameInput: TextInputEditText? = null
    private var editClassInput: TextInputEditText? = null
    private var editRollInput: TextInputEditText? = null
    private var editGuardianInput: TextInputEditText? = null
    private var editNotesInput: TextInputEditText? = null
    private var editSubjectInput: TextInputEditText? = null
    private var editQualificationInput: TextInputEditText? = null
    private var editExperienceInput: TextInputEditText? = null
    private var editSpecializationInput: TextInputEditText? = null
    private var editBioInput: TextInputEditText? = null
    private var adminProfileRows: List<AdminProfileRow> = emptyList()
    private var pendingPhotoPickerHandler: ((Uri) -> Unit)? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val handler = pendingPhotoPickerHandler
        pendingPhotoPickerHandler = null
        if (uri == null) {
            editPhotoStatusText?.text = "No photo selected"
            return@registerForActivityResult
        }
        if (handler != null) {
            handler(uri)
            return@registerForActivityResult
        }
        selectedProfilePhotoUri = uri
        editPhotoStatusText?.text = "Selected photo: ${resolveDisplayName(uri)}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_profile)

        val user = SessionManager.refreshCurrentUser() ?: SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), profileScreenTitle(user.role))

        val addProfileButton = findViewById<MaterialButton>(R.id.addProfileButton)
        val selfProfileCard = findViewById<MaterialCardView>(R.id.selfProfileCard)
        val profileAvatar = findViewById<ImageView>(R.id.profileAvatar)
        val profileHeaderName = findViewById<TextView>(R.id.profileHeaderName)
        val profileHeaderMeta = findViewById<TextView>(R.id.profileHeaderMeta)
        val profileHeaderDetails = findViewById<TextView>(R.id.profileHeaderDetails)
        val teacherBioCard = findViewById<MaterialCardView>(R.id.teacherBioCard)
        val editTeacherAboutButton = findViewById<MaterialButton>(R.id.editTeacherAboutButton)
        val teacherBioText = findViewById<TextView>(R.id.teacherBioText)
        val profileDetailsHeading = findViewById<TextView>(R.id.profileDetailsHeading)
        val profileTilesContainer = findViewById<LinearLayout>(R.id.profileTilesContainer)
        val editMyProfileButton = findViewById<MaterialButton>(R.id.editMyProfileButton)

        addProfileButton.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        selfProfileCard.visibility = View.VISIBLE

        addProfileButton.text = "Add student profile"
        addProfileButton.setOnClickListener { showAddDialog() }

        editMyProfileButton.visibility = View.VISIBLE
        editMyProfileButton.text = if (user.role == Role.ADMIN) "Edit admin profile" else "Edit my profile"
        editMyProfileButton.setOnClickListener {
            val latestUser = SessionManager.refreshCurrentUser() ?: SessionManager.currentUser ?: user
            if (latestUser.role == Role.ADMIN) showEditAdminProfileDialog(latestUser) else showEditOwnProfileDialog(latestUser)
        }
        profileAvatar.setOnClickListener {
            val latestUser = SessionManager.refreshCurrentUser() ?: SessionManager.currentUser ?: user
            showProfilePhotoPreview(latestUser)
        }

        recyclerView = findViewById(R.id.profileRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        studentQuickActionsScroll = findViewById(R.id.studentQuickActionsScroll)
        studentQuickActionsRow = findViewById(R.id.studentQuickActionsRow)

        bindSelfProfileCard(user, profileAvatar, profileHeaderName, profileHeaderMeta, profileHeaderDetails)
        bindRoleSpecificProfileSection(user, teacherBioCard, editTeacherAboutButton, teacherBioText, profileDetailsHeading, profileTilesContainer)
        bindList(user)
    }

    override fun onResume() {
        super.onResume()
        val user = SessionManager.refreshCurrentUser() ?: return
        if (::recyclerView.isInitialized) {
            bindSelfProfileCard(
                user,
                findViewById(R.id.profileAvatar),
                findViewById(R.id.profileHeaderName),
                findViewById(R.id.profileHeaderMeta),
                findViewById(R.id.profileHeaderDetails)
            )
            bindRoleSpecificProfileSection(
                user,
                findViewById(R.id.teacherBioCard),
                findViewById(R.id.editTeacherAboutButton),
                findViewById(R.id.teacherBioText),
                findViewById(R.id.profileDetailsHeading),
                findViewById(R.id.profileTilesContainer)
            )
            bindList(user)
        }
    }

    override fun onRepositoryChanged() {
        val user = SessionManager.refreshCurrentUser() ?: return
        if (::recyclerView.isInitialized) {
            bindSelfProfileCard(
                user,
                findViewById(R.id.profileAvatar),
                findViewById(R.id.profileHeaderName),
                findViewById(R.id.profileHeaderMeta),
                findViewById(R.id.profileHeaderDetails)
            )
            bindRoleSpecificProfileSection(
                user,
                findViewById(R.id.teacherBioCard),
                findViewById(R.id.editTeacherAboutButton),
                findViewById(R.id.teacherBioText),
                findViewById(R.id.profileDetailsHeading),
                findViewById(R.id.profileTilesContainer)
            )
            bindList(user)
        }
    }

    private fun bindSelfProfileCard(
        user: User,
        avatar: ImageView,
        nameView: TextView,
        metaView: TextView,
        detailView: TextView
    ) {
        when (user.role) {
            Role.STUDENT -> {
                val profile = SchoolRepository.profileFor(user.username)
                nameView.text = profile?.fullName ?: user.fullName
                metaView.text = "${profile?.className ?: user.className} | Student"
                detailView.text = buildString {
                    append("Roll: ${profile?.rollNumber.orEmpty().ifBlank { "Not set" }}")
                    append("\nGuardian: ${profile?.guardianContact.orEmpty().ifBlank { "Not set" }}")
                    append("\nNotes: ${profile?.notes.orEmpty().ifBlank { "Not set" }}")
                    append("\nAttendance: ${SchoolRepository.attendanceSummaryText(user.username)}")
                    append("\nHomework: ${SchoolRepository.homeworkSummaryText(user.username)}")
                    append("\nMarks: ${SchoolRepository.marksSummaryText(user.username)}")
                }
                ImageLoader.loadInto(avatar, SchoolRepository.profileImageUrlFor(user), R.drawable.ic_school_crest)
            }
            Role.TEACHER -> {
                val profile = SchoolRepository.userByUsername(user.username) ?: user
                nameView.text = profile.fullName
                metaView.text = "${SchoolRepository.assignedClasses(profile).joinToString(", ").ifBlank { "Assigned later" }} | Teacher"
                detailView.text = buildTeacherProfileBullets(profile)
                detailView.setLineSpacing(8f, 1.1f)
                ImageLoader.loadInto(avatar, SchoolRepository.profileImageUrlFor(profile), R.drawable.ic_school_crest)
            }
            Role.ADMIN -> {
                nameView.text = user.fullName
                metaView.text = "Administrator"
                detailView.text = buildString {
                    append("You can manage students, teachers, classes, and school content.")
                    append("\nAccount: ${user.username}")
                }
                ImageLoader.loadInto(avatar, SchoolRepository.profileImageUrlFor(user), R.drawable.ic_school_crest)
            }
        }
    }

    private fun showProfilePhotoPreview(user: User) {
        val imageUrl = SchoolRepository.profileImageUrlFor(user).trim()
        if (imageUrl.isBlank()) {
            Toast.makeText(this, "No profile photo added yet", Toast.LENGTH_SHORT).show()
            return
        }

        val previewCard = MaterialCardView(this).apply {
            radius = 28f
            cardElevation = 8f
            setCardBackgroundColor(getColor(R.color.surface))
            setContentPadding(24, 24, 24, 24)
        }
        val previewImage = ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_school_crest)
        }
        previewCard.addView(previewImage)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(previewCard)
        }

        ImageLoader.loadInto(previewImage, imageUrl, R.drawable.ic_school_crest)

        AlertDialog.Builder(this)
            .setTitle("Profile photo")
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showPhotoModeDialog(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(240)
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val modeHint = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        val cropButton = MaterialButton(this).apply {
            text = "Crop"
            isCheckable = true
        }
        val fitButton = MaterialButton(this).apply {
            text = "Fit"
            isCheckable = true
        }

        fun applyMode(mode: PhotoDisplayMode) {
            selectedProfilePhotoMode = mode
            cropButton.isChecked = mode == PhotoDisplayMode.CROP
            fitButton.isChecked = mode == PhotoDisplayMode.FIT
            preview.scaleType = when (mode) {
                PhotoDisplayMode.CROP -> ImageView.ScaleType.CENTER_CROP
                PhotoDisplayMode.FIT -> ImageView.ScaleType.FIT_CENTER
            }
            modeHint.text = when (mode) {
                PhotoDisplayMode.CROP -> "Crop fills the frame and trims the edges."
                PhotoDisplayMode.FIT -> "Fit keeps the full image visible."
            }
        }

        cropButton.setOnClickListener { applyMode(PhotoDisplayMode.CROP) }
        fitButton.setOnClickListener { applyMode(PhotoDisplayMode.FIT) }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cropButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(fitButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 0)
            addView(TextView(this@ProfileActivity).apply {
                text = displayName
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                setPadding(0, 0, 0, 10)
            })
            addView(preview)
            addView(modeHint)
            addView(buttonRow)
        }

        applyMode(selectedProfilePhotoMode)
        ImageLoader.loadInto(preview, uri.toString(), R.drawable.ic_school_crest)

        AlertDialog.Builder(this)
            .setTitle("Edit profile photo")
            .setView(container)
            .setPositiveButton("Use photo") { _, _ ->
                selectedProfilePhotoUri = uri
                editPhotoStatusText?.text = "Selected photo: $displayName (${prettyPhotoMode(selectedProfilePhotoMode)})"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindList(user: User) {
        if (user.role == Role.STUDENT) {
            bindStudentQuickActions(user)
            recyclerView.visibility = View.GONE
            return
        }
        studentQuickActionsScroll.visibility = View.GONE

        if (user.role == Role.TEACHER) {
            recyclerView.visibility = View.GONE
            return
        }
        if (user.role == Role.ADMIN) {
            recyclerView.visibility = View.VISIBLE
            val rows = adminDirectoryRows().filter {
                query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true) || it.badge.orEmpty().contains(query, true)
            }
            recyclerView.adapter = SimpleListAdapter(
                rows
            ) { position ->
                when (rows.getOrNull(position)?.title) {
                    "Teachers" -> showAdminTeacherDirectoryDialog()
                    "Students by class" -> showAdminStudentDirectoryByClassDialog()
                }
            }
            return
        }

        recyclerView.visibility = View.VISIBLE
        recyclerView.adapter = SimpleListAdapter(buildPersonalRows(user))
    }

    private fun bindStudentQuickActions(user: User) {
        val profile = SchoolRepository.profileFor(user.username)
        val cards = listOf(
            "Attendance" to SchoolRepository.attendanceSummaryText(user.username),
            "Homework" to SchoolRepository.homeworkSummaryText(user.username),
            "Marks" to SchoolRepository.marksSummaryText(user.username),
            "Notes" to profile?.notes.orEmpty().ifBlank { "No notes added." }
        )
        studentQuickActionsRow.removeAllViews()
        cards.chunked(2).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (rowIndex == 0) dp(10) else 0
                }
            }
            rowItems.forEachIndexed { index, (title, body) ->
                val button = MaterialButton(this).apply {
                    text = title
                    textSize = 14f
                    minHeight = dp(64)
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = dp(20)
                    elevation = dp(2).toFloat()
                    setTextColor(getColor(R.color.brand_primary))
                    strokeWidth = dp(1)
                    strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#8CC6EE"))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEF8FF"))
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                    iconPadding = dp(6)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener { showStudentInfoCard(title, body) }
                }
                button.icon = when (title) {
                    "Attendance" -> getDrawable(android.R.drawable.ic_menu_my_calendar)
                    "Homework" -> getDrawable(android.R.drawable.ic_menu_edit)
                    "Marks" -> getDrawable(android.R.drawable.ic_menu_agenda)
                    else -> getDrawable(android.R.drawable.ic_menu_info_details)
                }
                val params = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (index == 0) marginEnd = dp(8) else marginStart = dp(8)
                }
                row.addView(button, params)
            }
            if (rowItems.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            }
            studentQuickActionsRow.addView(row)
        }
        studentQuickActionsScroll.visibility = View.VISIBLE
    }

    private fun showStudentInfoCard(title: String, body: String) {
        val card = MaterialCardView(this).apply {
            radius = 22f
            cardElevation = 6f
            setCardBackgroundColor(getColor(R.color.surface))
            setContentPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val text = TextView(this).apply {
            this.text = body
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setLineSpacing(6f, 1f)
        }
        card.addView(text)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(card)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildPersonalRows(user: User): List<SimpleListItem> {
        return when (user.role) {
            Role.STUDENT -> {
                val profile = SchoolRepository.profileFor(user.username)
                listOf(
                    SimpleListItem("Attendance", SchoolRepository.attendanceSummaryText(user.username), "Attendance"),
                    SimpleListItem("Homework", SchoolRepository.homeworkSummaryText(user.username), "Homework"),
                    SimpleListItem("Marks", SchoolRepository.marksSummaryText(user.username), "Marks"),
                    SimpleListItem("Notes", profile?.notes.orEmpty().ifBlank { "No notes added." }, "Profile")
                ).filter {
                    query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true)
                }
            }
            Role.TEACHER -> {
                val profile = SchoolRepository.userByUsername(user.username) ?: user
                listOf(
                    SimpleListItem("About teacher", profile.staffBio.ifBlank { "No staff note added." }, "About"),
                    SimpleListItem("Assigned classes", SchoolRepository.assignedClasses(profile).joinToString(", ").ifBlank { "Assigned later" }, "Teacher")
                ).filter {
                    query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true)
                }
            }
            Role.ADMIN -> emptyList()
        }
    }

    private fun bindRoleSpecificProfileSection(
        user: User,
        bioCard: MaterialCardView,
        editAboutButton: MaterialButton,
        bioText: TextView,
        heading: TextView,
        tilesContainer: LinearLayout
    ) {
        if (user.role != Role.TEACHER) {
            bioCard.visibility = View.GONE
            editAboutButton.visibility = View.GONE
            heading.visibility = View.GONE
            tilesContainer.visibility = View.GONE
            tilesContainer.removeAllViews()
            return
        }

        val profile = SchoolRepository.userByUsername(user.username) ?: user
        bioCard.visibility = View.VISIBLE
        editAboutButton.visibility = View.VISIBLE
        heading.visibility = View.VISIBLE
        tilesContainer.visibility = View.VISIBLE
        tilesContainer.removeAllViews()

        editAboutButton.setOnClickListener { showEditTeacherAboutDialog(user) }

        bioText.text = profile.staffBio.ifBlank { "No staff bio added yet." }

        val tiles = listOf(
            ProfileTile("Subject", profile.subject.ifBlank { "Not set" }, "#EAF2FF"),
            ProfileTile("Qualification", profile.qualification.ifBlank { "Not set" }, "#EAF8F0"),
            ProfileTile("Experience", profile.experience.ifBlank { "Not set" }, "#FFF4E8"),
            ProfileTile("Specialization", profile.specialization.ifBlank { "Not set" }, "#F6EDFF")
        )

        tiles.chunked(2).forEachIndexed { index, rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (index == tiles.chunked(2).lastIndex) 0 else dp(12)
                }
            }

            rowItems.forEachIndexed { itemIndex, tile ->
                row.addView(createProfileTile(tile), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (itemIndex == 0) {
                        marginEnd = dp(8)
                    } else {
                        marginStart = dp(8)
                    }
                })
            }

            tilesContainer.addView(row)
        }
    }

    private fun createProfileTile(tile: ProfileTile): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 22f
            cardElevation = 5f
            setCardBackgroundColor(Color.parseColor(tile.backgroundColor))
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#1A0F7C8F")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(112)
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = tile.title
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val value = TextView(this).apply {
            text = tile.value
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setPadding(0, dp(8), 0, 0)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        content.addView(title)
        content.addView(value)
        card.addView(content)
        return card
    }

    private fun showEditTeacherAboutDialog(user: User) {
        val profile = SchoolRepository.userByUsername(user.username) ?: user
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val aboutInput = addField(container, "About teacher", profile.staffBio, minLines = 4)

        AlertDialog.Builder(this)
            .setTitle("Edit about")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.updateTeacherProfileDetails(
                    username = profile.username,
                    fullName = profile.fullName,
                    qualification = profile.qualification,
                    experience = profile.experience,
                    specialization = profile.specialization,
                    staffBio = aboutInput.text?.toString().orEmpty()
                )
                if (success) {
                    finishProfileSave("About updated")
                } else {
                    Toast.makeText(this, "Unable to update about section", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun profileScreenTitle(role: Role): String {
        return when (role) {
            Role.STUDENT -> "Student profile"
            Role.TEACHER -> "Teacher profile"
            Role.ADMIN -> "Admin profile"
        }
    }

    private fun buildAdminRows(): List<AdminProfileRow> {
        val rows = mutableListOf<AdminProfileRow>()
        rows.add(AdminProfileRow.HeaderRow("Teachers", "Tap any teacher card to edit", "Admin"))
        rows.addAll(
            SchoolRepository.teacherUsers()
                .filter {
                    query.isBlank() || it.fullName.contains(query, true) || it.username.contains(query, true) || SchoolRepository.assignedClasses(it).joinToString(", ").contains(query, true)
                }
                .map { AdminProfileRow.TeacherRow(it) }
        )
        val studentsByClass = SchoolRepository.allStudentProfiles()
            .groupBy { it.className.ifBlank { "Unassigned" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        studentsByClass.forEach { (className, profiles) ->
            val filtered = profiles.filter {
                query.isBlank() || it.fullName.contains(query, true) || it.username.contains(query, true) || it.className.contains(query, true)
            }
            if (filtered.isNotEmpty()) {
                rows.add(
                    AdminProfileRow.HeaderRow(
                        "Students - $className",
                        "Tap any student card to edit",
                        "Class"
                    )
                )
                rows.addAll(filtered.sortedBy { it.fullName }.map { AdminProfileRow.StudentRow(it) })
            }
        }
        return rows
    }

    private fun adminDirectoryRows(): List<SimpleListItem> = listOf(
        SimpleListItem(
            title = "Teachers",
            subtitle = "Open teacher list and tap any teacher to edit profile details",
            badge = "Action"
        ),
        SimpleListItem(
            title = "Students by class",
            subtitle = "Choose class first, then open any student for profile editing",
            badge = "Action"
        )
    )

    private fun showAdminTeacherDirectoryDialog() {
        val teachers = SchoolRepository.teacherUsers().sortedBy { it.fullName }
        if (teachers.isEmpty()) {
            Toast.makeText(this, "No teachers found", Toast.LENGTH_SHORT).show()
            return
        }
        val rows = teachers.map {
            SimpleListItem(
                title = "Teacher: ${it.fullName}",
                subtitle = "${SchoolRepository.assignedClasses(it).joinToString(", ").ifBlank { "Assigned later" }} | ${it.username}",
                badge = it.subject.ifBlank { "Teacher" }
            )
        }
        showSimpleListDialog("Teachers", rows) { index ->
            val selected = teachers.getOrNull(index) ?: return@showSimpleListDialog
            showAdminTeacherEditDialog(selected.username)
        }
    }

    private fun showAdminStudentDirectoryByClassDialog() {
        val classes = SchoolRepository.availableClasses().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        if (classes.isEmpty()) {
            Toast.makeText(this, "No classes found", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Students by class")
            .setItems(classes.toTypedArray()) { _, which ->
                val className = classes.getOrNull(which).orEmpty()
                if (className.isBlank()) return@setItems
                val students = SchoolRepository.studentsForClass(className).sortedBy { it.fullName }
                if (students.isEmpty()) {
                    Toast.makeText(this, "No students in $className", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val rows = students.map {
                    SimpleListItem(
                        title = "Student: ${it.fullName}",
                        subtitle = "Roll ${it.rollNumber.ifBlank { "--" }} | ${it.username}",
                        badge = className
                    )
                }
                showSimpleListDialog(className, rows) { index ->
                    val selected = students.getOrNull(index) ?: return@showSimpleListDialog
                    showAdminStudentEditDialog(selected.username)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSimpleListDialog(title: String, rows: List<SimpleListItem>, onClick: (Int) -> Unit) {
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ProfileActivity)
            adapter = SimpleListAdapter(rows, onClick)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(recycler)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val usernameInput = EditText(this).apply { hint = "Username" }
        val nameInput = EditText(this).apply { hint = "Student name" }
        val classInput = EditText(this).apply { hint = "Class" }
        val rollInput = EditText(this).apply { hint = "Roll number" }
        val guardianInput = EditText(this).apply { hint = "Guardian contact" }
        val notesInput = EditText(this).apply { hint = "Notes" }
        listOf(usernameInput, nameInput, classInput, rollInput, guardianInput, notesInput).forEach(container::addView)

        AlertDialog.Builder(this)
            .setTitle("Add student profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.addStudentProfile(
                    username = usernameInput.text.toString(),
                    fullName = nameInput.text.toString(),
                    className = classInput.text.toString(),
                    rollNumber = rollInput.text.toString(),
                    guardianContact = guardianInput.text.toString(),
                    notes = notesInput.text.toString()
                )
                Toast.makeText(this, if (success) "Student profile added" else "Fill required fields", Toast.LENGTH_SHORT).show()
                bindList(SessionManager.refreshCurrentUser() ?: return@setPositiveButton)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditOwnProfileDialog(user: User) {
        selectedProfilePhotoUri = null
        editPhotoStatusText = null
        editFullNameInput = null
        editClassInput = null
        editRollInput = null
        editGuardianInput = null
        editNotesInput = null
        editQualificationInput = null
        editExperienceInput = null
        editSpecializationInput = null
        editBioInput = null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }

        val photoText = TextView(this).apply {
            text = "No photo selected"
            setPadding(0, 8, 0, 8)
            setTextColor(getColor(R.color.text_secondary))
        }
        val choosePhotoButton = MaterialButton(this).apply {
            text = "Choose photo"
            setOnClickListener {
                pendingPhotoPickerHandler = { uri -> showPhotoModeDialog(uri) }
                photoPicker.launch("image/*")
            }
        }
        editPhotoStatusText = photoText

        when (user.role) {
            Role.STUDENT -> buildStudentEditForm(user, container)
            Role.TEACHER -> buildTeacherEditForm(user, container)
            else -> Unit
        }

        container.addView(photoText)
        container.addView(choosePhotoButton)

        AlertDialog.Builder(this)
            .setTitle("Edit profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                when (user.role) {
                    Role.STUDENT -> saveStudentProfileEdit(user)
                    Role.TEACHER -> saveTeacherProfileEdit(user)
                    else -> Unit
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                selectedProfilePhotoUri = null
                editPhotoStatusText = null
            }
            .show()
    }

    private fun showEditAdminProfileDialog(user: User) {
        selectedProfilePhotoUri = null
        selectedProfilePhotoMode = PhotoDisplayMode.FIT
        editPhotoStatusText = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val nameInput = addPlainField(container, "Admin name", user.fullName)
        val photoText = TextView(this).apply {
            text = "No photo selected"
            setPadding(0, 8, 0, 8)
            setTextColor(getColor(R.color.text_secondary))
        }
        editPhotoStatusText = photoText
        container.addView(photoText)
        container.addView(MaterialButton(this).apply {
            text = "Choose photo"
            setOnClickListener {
                pendingPhotoPickerHandler = { uri -> showPhotoModeDialog(uri) }
                photoPicker.launch("image/*")
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Edit admin profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                uploadProfileImageIfNeeded("admin_profiles/${user.username}", selectedProfilePhotoUri, selectedProfilePhotoMode) { imageUrl ->
                    val success = SchoolRepository.updateAdminProfile(
                        username = user.username,
                        fullName = nameInput.text?.toString().orEmpty(),
                        imageUrl = imageUrl.ifBlank { null }
                    )
                    if (success) {
                        finishProfileSave("Admin profile updated")
                    } else {
                        Toast.makeText(this, "Unable to update admin profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildStudentEditForm(user: User, container: LinearLayout) {
        val profile = SchoolRepository.profileFor(user.username)
        editFullNameInput = addField(container, "Full name", profile?.fullName ?: user.fullName)
        editClassInput = addField(container, "Class", profile?.className ?: user.className, enabled = false)
        editRollInput = addField(container, "Roll number", profile?.rollNumber.orEmpty())
        editGuardianInput = addField(container, "Guardian contact", profile?.guardianContact.orEmpty())
        editNotesInput = addField(container, "Notes", profile?.notes.orEmpty(), minLines = 3)
    }

    private fun buildTeacherEditForm(user: User, container: LinearLayout) {
        val profile = SchoolRepository.userByUsername(user.username) ?: user
        editFullNameInput = addField(container, "Full name", profile.fullName)
        editSubjectInput = addField(container, "Subject", profile.subject)
        editQualificationInput = addField(container, "Qualification", profile.qualification)
        editExperienceInput = addField(container, "Teaching experience", profile.experience)
        editSpecializationInput = addField(container, "Specialization", profile.specialization)
        editBioInput = addField(container, "Staff profile note", profile.staffBio, minLines = 3)
    }

    private fun addField(
        container: LinearLayout,
        label: String,
        value: String,
        enabled: Boolean = true,
        minLines: Int = 1
    ): TextInputEditText {
        val inputLayout = TextInputLayout(this).apply {
            hint = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            isEnabled = enabled
            this.minLines = minLines
        }
        inputLayout.addView(input)
        container.addView(inputLayout)
        return input
    }

    private fun addPlainField(container: LinearLayout, label: String, value: String): TextInputEditText {
        val inputLayout = TextInputLayout(this).apply {
            hint = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
        }
        val input = TextInputEditText(this).apply { setText(value) }
        inputLayout.addView(input)
        container.addView(inputLayout)
        return input
    }

    private fun saveStudentProfileEdit(user: User) {
        val fullName = editFullNameInput?.text?.toString().orEmpty()
        val className = editClassInput?.text?.toString().orEmpty().ifBlank { user.className }
        val rollNumber = editRollInput?.text?.toString().orEmpty()
        val guardian = editGuardianInput?.text?.toString().orEmpty()
        val notes = editNotesInput?.text?.toString().orEmpty()

        uploadProfileImageIfNeeded("student_profiles/${user.username}", selectedProfilePhotoUri, selectedProfilePhotoMode) { imageUrl ->
            val success = SchoolRepository.updateStudentProfile(
                originalUsername = user.username,
                username = user.username,
                fullName = fullName,
                className = className,
                rollNumber = rollNumber,
                guardianContact = guardian,
                notes = notes,
                imageUrl = imageUrl.ifBlank { null }
            )
            if (!success) {
                Toast.makeText(this, "Unable to update student profile", Toast.LENGTH_SHORT).show()
                return@uploadProfileImageIfNeeded
            }
            finishProfileSave("Student profile updated")
        }
    }

    private fun saveTeacherProfileEdit(user: User) {
        val fullName = editFullNameInput?.text?.toString().orEmpty()
        val subject = editSubjectInput?.text?.toString().orEmpty()
        val qualification = editQualificationInput?.text?.toString().orEmpty()
        val experience = editExperienceInput?.text?.toString().orEmpty()
        val specialization = editSpecializationInput?.text?.toString().orEmpty()
        val bio = editBioInput?.text?.toString().orEmpty()
        uploadProfileImageIfNeeded("teacher_profiles/${user.username}", selectedProfilePhotoUri, selectedProfilePhotoMode) { imageUrl ->
            val success = SchoolRepository.updateTeacherProfileDetails(
                username = user.username,
                fullName = fullName,
                imageUrl = imageUrl.ifBlank { null },
                subject = subject,
                qualification = qualification,
                experience = experience,
                specialization = specialization,
                staffBio = bio
            )
            if (!success) {
                Toast.makeText(this, "Unable to update teacher profile", Toast.LENGTH_SHORT).show()
                return@uploadProfileImageIfNeeded
            }
            finishProfileSave("Teacher profile updated")
        }
    }

    private fun finishProfileSave(message: String) {
        selectedProfilePhotoUri = null
        editPhotoStatusText = null
        editFullNameInput = null
        editClassInput = null
        editRollInput = null
        editGuardianInput = null
        editNotesInput = null
        editSubjectInput = null
        editQualificationInput = null
        editExperienceInput = null
        editSpecializationInput = null
        editBioInput = null
        selectedProfilePhotoMode = PhotoDisplayMode.FIT

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val refreshed = SessionManager.refreshCurrentUser() ?: return
        if (::recyclerView.isInitialized) {
            bindSelfProfileCard(
                refreshed,
                findViewById(R.id.profileAvatar),
                findViewById(R.id.profileHeaderName),
                findViewById(R.id.profileHeaderMeta),
                findViewById(R.id.profileHeaderDetails)
            )
            bindRoleSpecificProfileSection(
                refreshed,
                findViewById(R.id.teacherBioCard),
                findViewById(R.id.editTeacherAboutButton),
                findViewById(R.id.teacherBioText),
                findViewById(R.id.profileDetailsHeading),
                findViewById(R.id.profileTilesContainer)
            )
            bindList(refreshed)
        }
    }

    private fun uploadProfileImageIfNeeded(path: String, uri: Uri?, mode: PhotoDisplayMode, onComplete: (String) -> Unit) {
        if (uri == null) {
            onComplete("")
            return
        }
        Thread {
            val uploadSource = runCatching { preparePhotoForUpload(uri, path, mode) }.getOrElse { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "Photo preparation failed", Toast.LENGTH_SHORT).show()
                    onComplete("")
                }
                return@Thread
            }

            SessionManager.ensureFirebaseSession { authResult ->
                runOnUiThread {
                    authResult.onFailure {
                        Toast.makeText(this, it.message ?: "Please log in again before uploading photo", Toast.LENGTH_SHORT).show()
                        onComplete("")
                    }.onSuccess {
                        val storageRef = Firebase.storage.reference.child("$path/${System.currentTimeMillis()}_${resolveDisplayName(uri).replace("\\s+".toRegex(), "_")}")
                        storageRef.putFile(uploadSource)
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
        }.start()
    }

    private fun preparePhotoForUpload(uri: Uri, path: String, mode: PhotoDisplayMode): Uri {
        if (mode == PhotoDisplayMode.FIT) return prepareOptimizedUpload(uri, "${path}_fit")
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalStateException("Unable to read the selected image")
        val size = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - size) / 2
        val top = (bitmap.height - size) / 2
        val cropped = Bitmap.createBitmap(bitmap, left, top, size, size)
        val prefix = path.substringAfterLast('/').ifBlank { "profile" }
        return prepareOptimizedUpload(cropped, prefix)
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

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun prettyPhotoMode(mode: PhotoDisplayMode): String = when (mode) {
        PhotoDisplayMode.CROP -> "Crop"
        PhotoDisplayMode.FIT -> "Fit"
    }

    private fun prepareOptimizedUpload(uri: Uri, cachePrefix: String): Uri {
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return uri
        return prepareOptimizedUpload(bitmap, cachePrefix)
    }

    private fun prepareOptimizedUpload(bitmap: Bitmap, cachePrefix: String): Uri {
        val safePrefix = cachePrefix.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "profile" }
        val file = File(cacheDir, "${safePrefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        return Uri.fromFile(file)
    }

    private fun buildTeacherProfileBullets(profile: User): String = buildString {
        appendLine("- Subject: ${profile.subject.ifBlank { "Not set" }}")
        appendLine("- Qualification: ${profile.qualification.ifBlank { "Not set" }}")
        appendLine("- Teaching experience: ${profile.experience.ifBlank { "Not set" }}")
        appendLine("- Specialization: ${profile.specialization.ifBlank { "Not set" }}")
        append("- Extra exam qualified / about: ${profile.staffBio.ifBlank { "Not set" }}")
    }

    private sealed class AdminProfileRow {
        data class HeaderRow(val title: String, val subtitle: String, val badge: String) : AdminProfileRow()
        data class StudentRow(val profile: com.schoolms.mobile.data.StudentProfile) : AdminProfileRow()
        data class TeacherRow(val teacher: User) : AdminProfileRow()
    }

    private data class ProfileTile(
        val title: String,
        val value: String,
        val backgroundColor: String
    )

    private fun showAdminStudentEditDialog(username: String) {
        selectedProfilePhotoUri = null
        selectedProfilePhotoMode = PhotoDisplayMode.FIT
        editPhotoStatusText = null
        val profile = SchoolRepository.profileFor(username) ?: return
        val previousImageUrl = profile.imageUrl
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val usernameInput = addPlainField(container, "Username", profile.username)
        usernameInput.isEnabled = false
        val nameInput = addPlainField(container, "Full name", profile.fullName)
        val classInput = addPlainField(container, "Class", profile.className)
        val rollInput = addPlainField(container, "Roll number", profile.rollNumber)
        val guardianInput = addPlainField(container, "Guardian contact", profile.guardianContact)
        val notesInput = addPlainField(container, "Notes", profile.notes)
        val photoText = TextView(this).apply {
            text = if (previousImageUrl.isBlank()) "No photo selected" else "Current photo saved"
            setPadding(0, 8, 0, 8)
            setTextColor(getColor(R.color.text_secondary))
        }
        editPhotoStatusText = photoText
        container.addView(photoText)
        container.addView(MaterialButton(this).apply {
            text = "Choose photo"
            setOnClickListener {
                pendingPhotoPickerHandler = { uri -> showPhotoModeDialog(uri) }
                photoPicker.launch("image/*")
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Edit student profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                uploadProfileImageIfNeeded("student_profiles/${profile.username}", selectedProfilePhotoUri, selectedProfilePhotoMode) { imageUrl ->
                    val success = SchoolRepository.updateStudentProfile(
                        originalUsername = profile.username,
                        username = profile.username,
                        fullName = nameInput.text?.toString().orEmpty(),
                        className = classInput.text?.toString().orEmpty(),
                        rollNumber = rollInput.text?.toString().orEmpty(),
                        guardianContact = guardianInput.text?.toString().orEmpty(),
                        notes = notesInput.text?.toString().orEmpty(),
                        imageUrl = imageUrl.ifBlank { null }
                    )
                    if (success) {
                        finishProfileSave("Student profile updated")
                    } else {
                        Toast.makeText(this, "Unable to update student profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdminTeacherEditDialog(username: String) {
        selectedProfilePhotoUri = null
        selectedProfilePhotoMode = PhotoDisplayMode.FIT
        editPhotoStatusText = null
        val teacher = SchoolRepository.userByUsername(username) ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val nameInput = addPlainField(container, "Teacher name", teacher.fullName)
        val subjectInput = addPlainField(container, "Subject", teacher.subject)
        val qualificationInput = addPlainField(container, "Qualification", teacher.qualification)
        val experienceInput = addPlainField(container, "Experience", teacher.experience)
        val specializationInput = addPlainField(container, "Specialization", teacher.specialization)
        val bioInput = addPlainField(container, "Bio", teacher.staffBio)
        val photoText = TextView(this).apply {
            text = "No photo selected"
            setPadding(0, 8, 0, 8)
            setTextColor(getColor(R.color.text_secondary))
        }
        editPhotoStatusText = photoText
        container.addView(photoText)
        container.addView(MaterialButton(this).apply {
            text = "Choose photo"
            setOnClickListener {
                pendingPhotoPickerHandler = { uri -> showPhotoModeDialog(uri) }
                photoPicker.launch("image/*")
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Edit teacher profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                uploadProfileImageIfNeeded("teacher_profiles/${teacher.username}", selectedProfilePhotoUri, selectedProfilePhotoMode) { imageUrl ->
                    val success = SchoolRepository.updateTeacherProfileDetails(
                        username = teacher.username,
                        fullName = nameInput.text?.toString().orEmpty(),
                        imageUrl = imageUrl.ifBlank { null },
                        subject = subjectInput.text?.toString().orEmpty(),
                        qualification = qualificationInput.text?.toString().orEmpty(),
                        experience = experienceInput.text?.toString().orEmpty(),
                        specialization = specializationInput.text?.toString().orEmpty(),
                        staffBio = bioInput.text?.toString().orEmpty()
                    )
                    if (success) {
                        finishProfileSave("Teacher profile updated")
                    } else {
                        Toast.makeText(this, "Unable to update teacher profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
