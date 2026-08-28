package com.schoolms.mobile.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.data.SimpleListItem
import com.schoolms.mobile.data.TimetableSlot
import com.schoolms.mobile.data.User
import com.schoolms.mobile.ui.adapter.SimpleListAdapter

class TimetableActivity : BaseActivity() {
    private lateinit var tableLayout: TableLayout
    private lateinit var emptyStateText: TextView
    private lateinit var adminActionsContainer: View
    private lateinit var rotateTimetableButton: MaterialButton
    private lateinit var addSlotButton: MaterialButton
    private lateinit var addClassButton: MaterialButton
    private lateinit var removeClassButton: MaterialButton
    private lateinit var addSubjectButton: MaterialButton
    private lateinit var removeSubjectButton: MaterialButton
    private lateinit var addTimeButton: MaterialButton
    private lateinit var removeTimeButton: MaterialButton
    private lateinit var viewTeachersButton: MaterialButton
    private lateinit var viewStudentsButton: MaterialButton
    private var timetableScale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_timetable)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.timetable_title))

        tableLayout = findViewById(R.id.timetableTable)
        configurePinchZoom(findViewById(R.id.timetableHorizontalScroll))
        emptyStateText = findViewById(R.id.emptyStateText)
        adminActionsContainer = findViewById(R.id.adminActionsContainer)
        rotateTimetableButton = findViewById(R.id.rotateTimetableButton)
        addSlotButton = findViewById(R.id.addTimetableButton)
        addClassButton = findViewById(R.id.addClassButton)
        removeClassButton = findViewById(R.id.removeClassButton)
        addSubjectButton = findViewById(R.id.addSubjectButton)
        removeSubjectButton = findViewById(R.id.removeSubjectButton)
        addTimeButton = findViewById(R.id.addTimeButton)
        removeTimeButton = findViewById(R.id.removeTimeButton)
        viewTeachersButton = findViewById(R.id.viewTeachersButton)
        viewStudentsButton = findViewById(R.id.viewStudentsButton)

        addSlotButton.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        adminActionsContainer.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        addSlotButton.text = "Add slot"
        addSlotButton.setOnClickListener { showSlotPickerDialog() }
        rotateTimetableButton.visibility = View.GONE

        addClassButton.setOnClickListener { showAddClassDialog() }
        removeClassButton.setOnClickListener { showRemoveClassDialog() }
        addSubjectButton.setOnClickListener { showAddSubjectDialog() }
        removeSubjectButton.setOnClickListener { showRemoveSubjectDialog() }
        addTimeButton.setOnClickListener { showAddTimeDialog() }
        removeTimeButton.setOnClickListener { showRemoveTimeDialog() }
        viewTeachersButton.setOnClickListener { showTeacherDirectoryDialog() }
        viewStudentsButton.setOnClickListener { showStudentDirectoryByClassDialog() }

        renderSheet()
    }

    private fun configurePinchZoom(scrollView: HorizontalScrollView) {
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(scaleDetector: ScaleGestureDetector): Boolean {
                timetableScale = (timetableScale * scaleDetector.scaleFactor).coerceIn(0.65f, 1.65f)
                tableLayout.pivotX = 0f
                tableLayout.pivotY = 0f
                tableLayout.scaleX = timetableScale
                tableLayout.scaleY = timetableScale
                return true
            }
        })
        scrollView.setOnTouchListener { _, event: MotionEvent ->
            detector.onTouchEvent(event)
            false
        }
    }

    override fun onResume() {
        super.onResume()
        renderSheet()
    }

    override fun onRepositoryChanged() {
        renderSheet()
    }

    private fun renderSheet() {
        val user = SessionManager.currentUser ?: return
        val classes = SchoolRepository.timetableMatrixClasses(user)
        val rowLabels = timetableTimeLabels()
        val columnLabels = classes

        tableLayout.removeAllViews()
        emptyStateText.visibility = if (rowLabels.isEmpty() || columnLabels.isEmpty()) View.VISIBLE else View.GONE
        emptyStateText.text = if (rowLabels.isEmpty() || columnLabels.isEmpty()) {
            "No timetable classes available yet."
        } else {
            ""
        }
        if (rowLabels.isEmpty() || columnLabels.isEmpty()) return

        tableLayout.addView(buildHeaderRow("Time / Class", columnLabels))
        rowLabels.forEachIndexed { rowIndex, rowLabel ->
            tableLayout.addView(buildDataRow(user, rowIndex, rowLabel, columnLabels))
        }
    }

    private fun buildHeaderRow(rowLabelTitle: String, columnLabels: List<String>): TableRow {
        val row = TableRow(this).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.WRAP_CONTENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(createHeaderCell(rowLabelTitle, 156, isCorner = true))
        columnLabels.forEach { label ->
            row.addView(createHeaderCell(label, 156))
        }
        return row
    }

    private fun buildDataRow(
        user: com.schoolms.mobile.data.User,
        rowIndex: Int,
        rowLabel: String,
        columnLabels: List<String>
    ): TableRow {
        val row = TableRow(this).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.WRAP_CONTENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(createRowLabelCell(rowLabel, rowIndex))
        columnLabels.forEachIndexed { colIndex, className ->
            val slot = timetableSlotFor(user, className, rowLabel)
            row.addView(createTimetableCell(user, className, rowLabel, rowIndex, colIndex, slot))
        }
        return row
    }

    private fun createHeaderCell(text: String, widthDp: Int, isCorner: Boolean = false): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(dp(widthDp), dp(60)).apply {
                marginEnd = dp(4)
                bottomMargin = dp(4)
            }
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            includeFontPadding = false
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            this.text = text
            background = sheetCellDrawable(
                fillColor = Color.parseColor(if (isCorner) "#0B344B" else "#0E5B7A"),
                strokeColor = Color.parseColor("#0E5B7A")
            )
        }
    }

    private fun createRowLabelCell(text: String, rowIndex: Int): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(dp(156), dp(92)).apply {
                marginEnd = dp(4)
                bottomMargin = dp(4)
            }
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            includeFontPadding = false
            setTextColor(Color.parseColor("#123E57"))
            textSize = 12.5f
            maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            this.text = text
            background = sheetCellDrawable(
                fillColor = if (rowIndex % 2 == 0) Color.parseColor("#E8F5FB") else Color.parseColor("#DFF0F8"),
                strokeColor = Color.parseColor("#9CC8DA")
            )
        }
    }

    private fun createTimetableCell(
        user: com.schoolms.mobile.data.User,
        className: String,
        timeLabel: String,
        rowIndex: Int,
        colIndex: Int,
        slot: TimetableSlot?
    ): TextView {
        val editable = user.role == Role.ADMIN
        val cell = TextView(this).apply {
            layoutParams = TableRow.LayoutParams(dp(156), dp(92)).apply {
                marginEnd = dp(4)
                bottomMargin = dp(4)
            }
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1.0f)
            textSize = 12.5f
            maxLines = 4
            setTextColor(Color.parseColor("#162D3A"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            text = buildCellText(slot, editable)
            background = sheetCellDrawable(
                fillColor = premiumCellColor(slot?.subject.orEmpty(), rowIndex, colIndex),
                strokeColor = Color.parseColor("#9CC8DA")
            )
        }
        if (editable) {
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener { showSlotEditorDialog(className, timeLabel, slot) }
        }
        return cell
    }

    private fun buildCellText(slot: TimetableSlot?, editable: Boolean): String {
        if (slot == null) return if (editable) "Tap to add" else "—"
        return buildString {
            append(slot.subject.trim())
            val teacherName = displayTeacherName(slot)
            if (teacherName.isNotBlank()) {
                append("\n")
                append(teacherName)
            }
            if (slot.room.isNotBlank()) {
                append("\n")
                append(slot.room.trim())
            }
        }
    }

    private fun showSlotPickerDialog(defaultClass: String? = null, defaultTime: String? = null) {
        val user = SessionManager.currentUser ?: return
        val classes = SchoolRepository.timetableMatrixClasses(user)
        val timeSlots = timetableTimeLabels()
        if (classes.isEmpty() || timeSlots.isEmpty()) {
            Toast.makeText(this, "Add a class and subject first", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val initialClass = defaultClass?.takeIf { it.isNotBlank() } ?: classes.first()
        val classInput = buildDropdownField(container, "Class", classes, initialClass)
        val subjectInput = buildDropdownField(
            container = container,
            label = "Subject",
            items = subjectsForClass(initialClass),
            defaultValue = null,
            allowCustomValue = true
        )
        classInput.setOnItemClickListener { _, _, position, _ ->
            val selectedClass = classes.getOrNull(position).orEmpty()
            refreshDropdownItems(subjectInput, subjectsForClass(selectedClass))
        }
        val teacherInput = buildTextField(container, "Teacher name", defaultTeacherNameForClass(initialClass))
        val timeInput = buildDropdownField(container, "Time", timeSlots, defaultTime ?: timeSlots.first())
        val roomInput = buildTextField(container, "Room", "")

        AlertDialog.Builder(this)
            .setTitle("Add timetable slot")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.updateTimetableCell(
                    user = user,
                    className = classInput.text?.toString().orEmpty(),
                    subject = subjectInput.text?.toString().orEmpty(),
                    day = teacherInput.text?.toString().orEmpty(),
                    time = timeInput.text?.toString().orEmpty(),
                    room = roomInput.text?.toString().orEmpty()
                )
                Toast.makeText(this, if (success) "Timetable saved" else "Fill required fields", Toast.LENGTH_SHORT).show()
                renderSheet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSlotEditorDialog(className: String, timeLabel: String, slot: TimetableSlot?) {
        val user = SessionManager.currentUser ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val classLabel = TextView(this).apply {
            text = "Class: $className"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
        }
        val timeLabelView = TextView(this).apply {
            text = "Time: $timeLabel"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
        }
        val subjectInput = buildDropdownField(
            container = container,
            label = "Subject",
            items = subjectsForClass(className, slot?.subject),
            defaultValue = slot?.subject,
            allowCustomValue = true
        )
        val teacherInput = buildTextField(container, "Teacher name", slot?.day.orEmpty().ifBlank { defaultTeacherNameForClass(className) })
        val timeOptions = (timetableTimeLabels() + timeLabel).distinct()
        val timeInput = buildDropdownField(container, "Time", timeOptions, timeLabel)
        val roomInput = buildTextField(container, "Room", slot?.room.orEmpty())
        container.addView(classLabel, 0)
        container.addView(timeLabelView, 1)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (slot == null) "Add slot" else "Edit slot")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val editedTime = timeInput.text?.toString().orEmpty().trim()
                val success = SchoolRepository.updateTimetableCell(
                    user = user,
                    className = className,
                    subject = subjectInput.text?.toString().orEmpty(),
                    day = teacherInput.text?.toString().orEmpty(),
                    time = editedTime,
                    room = roomInput.text?.toString().orEmpty()
                )
                if (success && !editedTime.equals(timeLabel, true)) {
                    SchoolRepository.removeTimetableCell(user, className, timeLabel)
                }
                Toast.makeText(this, if (success) "Timetable updated" else "Unable to save timetable", Toast.LENGTH_SHORT).show()
                renderSheet()
            }
            .setNegativeButton("Close", null)

        if (slot != null) {
            dialog.setNeutralButton("Remove") { _, _ ->
                val success = SchoolRepository.removeTimetableCell(user, className, timeLabel)
                Toast.makeText(this, if (success) "Timetable cell removed" else "Unable to remove slot", Toast.LENGTH_SHORT).show()
                renderSheet()
            }
        }

        dialog.show()
    }

    private fun showAddClassDialog() {
        val user = SessionManager.currentUser ?: return
        val input = buildSingleFieldDialog("Class name")
        AlertDialog.Builder(this)
            .setTitle("Add class")
            .setView(input.first)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.addTimetableMatrixClass(user, input.second.text?.toString().orEmpty())
                Toast.makeText(this, if (success) "Class added" else "Unable to add class", Toast.LENGTH_SHORT).show()
                renderSheet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveClassDialog() {
        val user = SessionManager.currentUser ?: return
        val classes = SchoolRepository.timetableMatrixClasses(user)
        if (classes.isEmpty()) {
            Toast.makeText(this, "No classes to remove", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove class")
            .setItems(classes.toTypedArray()) { _, which ->
                val className = classes.getOrNull(which).orEmpty()
                AlertDialog.Builder(this)
                    .setTitle("Remove $className?")
                    .setMessage("This will remove the class row from the timetable sheet.")
                    .setPositiveButton("Remove") { _, _ ->
                        val success = SchoolRepository.removeTimetableMatrixClass(user, className)
                        Toast.makeText(this, if (success) "Class removed" else "Unable to remove class", Toast.LENGTH_SHORT).show()
                        renderSheet()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddSubjectDialog() {
        val user = SessionManager.currentUser ?: return
        val input = buildSingleFieldDialog("Subject name")
        AlertDialog.Builder(this)
            .setTitle("Add subject")
            .setView(input.first)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.addTimetableMatrixSubject(user, input.second.text?.toString().orEmpty())
                Toast.makeText(this, if (success) "Subject added" else "Unable to add subject", Toast.LENGTH_SHORT).show()
                renderSheet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveSubjectDialog() {
        val user = SessionManager.currentUser ?: return
        val subjects = SchoolRepository.timetableMatrixSubjects(user)
        if (subjects.isEmpty()) {
            Toast.makeText(this, "No subjects to remove", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove subject")
            .setItems(subjects.toTypedArray()) { _, which ->
                val subject = subjects.getOrNull(which).orEmpty()
                AlertDialog.Builder(this)
                    .setTitle("Remove $subject?")
                    .setMessage("This will remove the subject from admin timetable selections.")
                    .setPositiveButton("Remove") { _, _ ->
                        val success = SchoolRepository.removeTimetableMatrixSubject(user, subject)
                        Toast.makeText(this, if (success) "Subject removed" else "Unable to remove subject", Toast.LENGTH_SHORT).show()
                        renderSheet()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddTimeDialog() {
        val user = SessionManager.currentUser ?: return
        val input = buildSingleFieldDialog("Time range (e.g. 09:00 - 09:45)")
        AlertDialog.Builder(this)
            .setTitle("Add time row")
            .setView(input.first)
            .setPositiveButton("Save") { _, _ ->
                val success = SchoolRepository.addTimetableMatrixTime(user, input.second.text?.toString().orEmpty())
                Toast.makeText(this, if (success) "Time row added" else "Unable to add time row", Toast.LENGTH_SHORT).show()
                renderSheet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveTimeDialog() {
        val user = SessionManager.currentUser ?: return
        val times = timetableTimeLabels()
        if (times.isEmpty()) {
            Toast.makeText(this, "No time rows to remove", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove time row")
            .setItems(times.toTypedArray()) { _, which ->
                val time = times.getOrNull(which).orEmpty()
                AlertDialog.Builder(this)
                    .setTitle("Remove $time?")
                    .setMessage("This removes the full time row and all class slots in it.")
                    .setPositiveButton("Remove") { _, _ ->
                        val success = SchoolRepository.removeTimetableMatrixTime(user, time)
                        Toast.makeText(this, if (success) "Time row removed" else "Unable to remove time row", Toast.LENGTH_SHORT).show()
                        renderSheet()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun buildDropdownField(container: LinearLayout, label: String, items: List<String>): MaterialAutoCompleteTextView {
        return buildDropdownField(container, label, items, null, false)
    }

    private fun buildDropdownField(
        container: LinearLayout,
        label: String,
        items: List<String>,
        defaultValue: String?,
        allowCustomValue: Boolean = false
    ): MaterialAutoCompleteTextView {
        val layout = TextInputLayout(this).apply {
            hint = label
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val input = MaterialAutoCompleteTextView(this).apply {
            setAdapter(ArrayAdapter(this@TimetableActivity, android.R.layout.simple_list_item_1, items))
            setText(defaultValue?.takeIf { it.isNotBlank() }.orEmpty(), false)
            inputType = if (allowCustomValue) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            } else {
                InputType.TYPE_NULL
            }
            setOnClickListener { showDropDown() }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showDropDown() }
        }
        layout.addView(input)
        container.addView(layout)
        return input
    }

    private fun buildTextField(container: LinearLayout, label: String, value: String): TextInputEditText {
        val layout = TextInputLayout(this).apply {
            hint = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val input = TextInputEditText(this).apply {
            setText(value)
        }
        layout.addView(input)
        container.addView(layout)
        return input
    }

    private fun buildSingleFieldDialog(hint: String): Pair<LinearLayout, TextInputEditText> {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val layout = TextInputLayout(this).apply {
            this.hint = hint
        }
        val input = TextInputEditText(this)
        layout.addView(input)
        container.addView(layout)
        return container to input
    }

    private fun sheetCellDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun timetableSlotFor(user: com.schoolms.mobile.data.User, className: String, timeLabel: String): TimetableSlot? {
        val normalizedClass = className.trim()
        return SchoolRepository.timetableFor(user)
            .firstOrNull {
                it.className.equals(normalizedClass, true) &&
                    it.time.equals(timeLabel.trim(), true)
            }
    }

    private fun timetableTimeLabels(): List<String> {
        val user = SessionManager.currentUser ?: return emptyList()
        return SchoolRepository.timetableMatrixTimes(user)
            .sortedBy { parseStartMinutes(it) }
    }

    private fun parseStartMinutes(label: String): Int {
        val raw = label.substringBefore("-").trim()
        val parts = raw.split(":")
        if (parts.size != 2) return Int.MAX_VALUE
        val hour = parts[0].toIntOrNull() ?: return Int.MAX_VALUE
        val minute = parts[1].toIntOrNull() ?: return Int.MAX_VALUE
        val normalizedHour = when {
            hour in 1..7 -> hour + 12
            hour in 8..23 -> hour
            else -> return Int.MAX_VALUE
        }
        return normalizedHour * 60 + minute
    }

    private fun subjectColor(subject: String): Int {
        val palette = listOf(
            Color.parseColor("#1E88E5"),
            Color.parseColor("#00897B"),
            Color.parseColor("#6D4C41"),
            Color.parseColor("#8E24AA"),
            Color.parseColor("#F4511E"),
            Color.parseColor("#3949AB")
        )
        return palette[(subject.trim().lowercase().hashCode().absoluteValue) % palette.size]
    }

    private fun cellColor(subject: String): Int {
        val base = subjectColor(subject)
        val r = (Color.red(base) + 110).coerceAtMost(255)
        val g = (Color.green(base) + 105).coerceAtMost(255)
        val b = (Color.blue(base) + 95).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    private fun premiumCellColor(subject: String, rowIndex: Int, colIndex: Int): Int {
        val base = if (subject.isBlank()) Color.parseColor("#FFFFFF") else cellColor(subject)
        val shift = if ((rowIndex + colIndex) % 2 == 0) 8 else -8
        val r = (Color.red(base) + shift).coerceIn(0, 255)
        val g = (Color.green(base) + shift).coerceIn(0, 255)
        val b = (Color.blue(base) + shift).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun showTeacherDirectoryDialog() {
        val teachers = SchoolRepository.teacherUsers().sortedBy { it.fullName }
        if (teachers.isEmpty()) {
            Toast.makeText(this, "No teachers found", Toast.LENGTH_SHORT).show()
            return
        }
        showSimpleListDialog(
            title = "Teachers",
            rows = teachers.map {
                SimpleListItem(
                    "Teacher: ${it.fullName}",
                    "Classes: ${SchoolRepository.assignedClasses(it).joinToString(", ").ifBlank { "Assigned later" }}",
                    it.subject.ifBlank { "Teacher" }
                )
            }
        )
    }

    private fun showStudentDirectoryByClassDialog() {
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
                val students = SchoolRepository.studentsForClass(className)
                if (students.isEmpty()) {
                    Toast.makeText(this, "No students in $className", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                showSimpleListDialog(
                    title = className,
                    rows = students.sortedBy { it.fullName }.map {
                        SimpleListItem(
                            "Student: ${it.fullName}",
                            "Roll ${it.rollNumber.ifBlank { "--" }} | ${it.username}",
                            "Student"
                        )
                    }
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSimpleListDialog(title: String, rows: List<SimpleListItem>) {
        val recycler = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@TimetableActivity)
            adapter = SimpleListAdapter(rows)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(recycler)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun subjectsForClass(className: String, include: String? = null): List<String> {
        val user = SessionManager.currentUser ?: return emptyList()
        val normalizedClass = className.trim()
        val classSubjects = SchoolRepository.subjectsForClass(normalizedClass).map { it.name }
        val timetableSubjects = SchoolRepository.timetableMatrixSubjects(user)
        val slotSubjects = SchoolRepository.timetableFor(user)
            .filter { it.className.equals(normalizedClass, true) }
            .map { it.subject }
        return (classSubjects + timetableSubjects + slotSubjects + listOfNotNull(include))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    private fun defaultTeacherNameForClass(className: String): String {
        return SchoolRepository.teacherNameForClass(className).takeIf { it != "Assigned later" }.orEmpty()
    }

    private fun displayTeacherName(slot: TimetableSlot): String {
        val value = slot.day.trim()
        if (value.isBlank() || value.lowercase() in weekdayNames) {
            return defaultTeacherNameForClass(slot.className)
        }
        return value
    }

    private fun refreshDropdownItems(input: MaterialAutoCompleteTextView, items: List<String>) {
        input.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items))
        val current = input.text?.toString().orEmpty().trim()
        if (current.isNotBlank() && items.any { it.equals(current, true) }) {
            val preserved = items.firstOrNull { it.equals(current, true) }.orEmpty()
            input.setText(preserved, false)
        } else {
            input.setText(current, false)
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private val Int.absoluteValue: Int
        get() = if (this == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(this)

    private val weekdayNames = setOf(
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday"
    )
}
