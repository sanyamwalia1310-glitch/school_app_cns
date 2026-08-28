package com.schoolms.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout.LayoutParams
import com.schoolms.mobile.R
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.StudentDetailActivity

class RoleDashboardActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_role_dashboard)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "${user.role.name.lowercase().replaceFirstChar(Char::uppercase)} dashboard")

        val heroPanel = findViewById<LinearLayout>(R.id.roleHeroPanel)
        val heroIcon = findViewById<ImageView>(R.id.roleHeroIcon)
        val heroBadge = findViewById<TextView>(R.id.roleHeroBadge)
        val titleText = findViewById<TextView>(R.id.dashboardTitleText)
        val descriptionText = findViewById<TextView>(R.id.dashboardDescription)
        val attendanceButton = findViewById<MaterialButton>(R.id.attendanceButton)
        val homeworkButton = findViewById<MaterialButton>(R.id.homeworkButton)
        val marksButton = findViewById<MaterialButton>(R.id.marksButton)
        val profileButton = findViewById<MaterialButton>(R.id.profileButton)
        val classesButton = findViewById<MaterialButton>(R.id.classesButton)
        val studentsButton = findViewById<MaterialButton>(R.id.studentsButton)
        val timetableButton = findViewById<MaterialButton>(R.id.timetableButton)
        val studentOverviewSection = findViewById<MaterialCardView>(R.id.studentOverviewSection)
        val topAnnouncementCard = findViewById<MaterialCardView>(R.id.topAnnouncementCard)
        val topAnnouncementTitle = findViewById<TextView>(R.id.topAnnouncementTitle)
        val topAnnouncementBody = findViewById<TextView>(R.id.topAnnouncementBody)
        val openModulesLabel = findViewById<TextView>(R.id.openModulesLabel)
        val studentOverviewLabel = findViewById<TextView>(R.id.studentOverviewLabel)
        val studentOverviewCaption = findViewById<TextView>(R.id.studentOverviewCaption)
        val notificationsButton = findViewById<MaterialButton>(R.id.notificationsButton)
        val quizFeatureCard = findViewById<MaterialCardView>(R.id.quizFeatureCard)
        val quizFeatureTitle = findViewById<TextView>(R.id.quizFeatureTitle)
        val quizFeatureBody = findViewById<TextView>(R.id.quizFeatureBody)

        val classCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.studentMetricClassCard)
        val attendanceCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.studentMetricAttendanceCard)
        val homeworkCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.studentMetricHomeworkCard)
        val marksCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.studentMetricMarksCard)

        classCard.setOnClickListener {
            startActivity(
                Intent(this, StudentDetailActivity::class.java)
                    .putExtra(StudentDetailActivity.EXTRA_USERNAME, user.username)
                    .putExtra(StudentDetailActivity.EXTRA_SECTION, StudentDetailActivity.SECTION_ATTENDANCE)
            )
        }
        attendanceCard.setOnClickListener { open(AttendanceActivity::class.java) }
        homeworkCard.setOnClickListener { open(HomeworkActivity::class.java) }
        marksCard.setOnClickListener { open(MarksActivity::class.java) }

        when (user.role) {
            Role.ADMIN -> {
                heroPanel.setBackgroundResource(R.drawable.bg_hero_admin)
                heroIcon.setImageResource(android.R.drawable.ic_menu_manage)
                heroBadge.text = getString(R.string.admin_console)
                titleText.text = "School operations desk"
                descriptionText.text = "Manage users, classes, subjects, notifications, attendance overview, and academic operations."
                openModulesLabel.text = "Workspace modules"
                classesButton.text = "Class management"
                classesButton.setIconResource(android.R.drawable.ic_menu_slideshow)
                studentsButton.visibility = View.VISIBLE
                studentsButton.text = "Students and subjects"
                studentsButton.setIconResource(android.R.drawable.ic_menu_info_details)
                notificationsButton.text = "School updates"
                topAnnouncementCard.visibility = View.VISIBLE
                quizFeatureCard.visibility = View.GONE
                bindTopAnnouncement(topAnnouncementTitle, topAnnouncementBody)
            }
            Role.TEACHER -> {
                heroPanel.setBackgroundResource(R.drawable.bg_hero_teacher)
                heroIcon.setImageResource(android.R.drawable.ic_menu_edit)
                heroBadge.text = getString(R.string.teacher_hub)
                titleText.text = "Teaching workflow"
                descriptionText.text = "Record attendance, assign homework, review marks, and stay updated on class activity."
                openModulesLabel.text = "Workspace modules"
                attendanceButton.text = "Daily Attendance"
                attendanceButton.textSize = 14f
                homeworkButton.text = "Class homework"
                marksButton.text = "Marks tracker"
                marksButton.background = getDrawable(R.drawable.bg_dashboard_button_teacher_marks)
                shrinkRoleButton(marksButton)
                profileButton.visibility = View.GONE
                classesButton.text = "Class records"
                classesButton.setIconResource(android.R.drawable.ic_menu_agenda)
                studentsButton.visibility = View.VISIBLE
                studentsButton.text = "Students & subjects"
                studentsButton.setIconResource(android.R.drawable.ic_menu_info_details)
                notificationsButton.text = "School updates"
                notificationsButton.background = getDrawable(R.drawable.bg_dashboard_button_teacher_updates)
                shrinkRoleButton(notificationsButton)
                timetableButton.visibility = View.GONE
                topAnnouncementCard.visibility = View.GONE
                quizFeatureCard.visibility = View.GONE
            }
            Role.STUDENT -> {
                heroPanel.setBackgroundResource(R.drawable.bg_hero_student)
                heroIcon.setImageResource(android.R.drawable.ic_menu_myplaces)
                heroBadge.text = getString(R.string.student_portal)
                titleText.text = "Student home"
                descriptionText.text = "Everything for your class is here: attendance, homework, marks, timetable, profile, and school updates."
                openModulesLabel.text = "Quick actions"
                studentOverviewLabel.text = "At a glance"
                studentOverviewCaption.text = "Your class-based activity updates automatically from your registered account."
                attendanceButton.text = "Attendance history"
                homeworkButton.text = "Homework desk"
                marksButton.text = "Marks tracker"
                profileButton.text = "Profile card"
                classesButton.text = "Class timetable"
                classesButton.setIconResource(android.R.drawable.ic_menu_today)
                studentsButton.visibility = View.GONE
                timetableButton.visibility = View.GONE
                studentOverviewSection.visibility = View.VISIBLE
                notificationsButton.text = "School updates"
                quizFeatureCard.visibility = if (isSeniorStudent(user.className)) View.VISIBLE else View.GONE
                quizFeatureTitle.text = "Daily quiz zone"
                quizFeatureBody.text =
                    if (isSeniorStudent(user.className)) {
                        "Math and science quiz rounds for ${user.className}. The cloud set refreshes every 24 hours."
                    } else {
                        ""
                    }
                topAnnouncementCard.visibility = View.GONE
                bindStudentOverview(user)
            }
        }

        attendanceButton.setOnClickListener { open(AttendanceActivity::class.java) }
        homeworkButton.setOnClickListener { open(HomeworkActivity::class.java) }
        marksButton.setOnClickListener { open(MarksActivity::class.java) }
        profileButton.setOnClickListener { open(ProfileActivity::class.java) }
        classesButton.setOnClickListener {
            when (user.role) {
                Role.ADMIN -> open(ClassManagementActivity::class.java)
                Role.TEACHER -> open(ClassManagementActivity::class.java)
                Role.STUDENT -> open(TimetableActivity::class.java)
            }
        }
        studentsButton.setOnClickListener { open(StudentManagementActivity::class.java) }
        topAnnouncementCard.setOnClickListener { open(InfoListActivity::class.java, "notifications") }
        timetableButton.visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        timetableButton.setOnClickListener { open(TimetableActivity::class.java) }
        quizFeatureCard.setOnClickListener { open(QuizActivity::class.java) }
        notificationsButton.setOnClickListener { open(InfoListActivity::class.java, "notifications") }
        animateContentEntrance(
            heroPanel,
            topAnnouncementCard,
            studentOverviewSection,
            quizFeatureCard,
            attendanceButton,
            homeworkButton,
            marksButton,
            profileButton,
            classesButton,
            notificationsButton
        )
    }

    override fun onRepositoryChanged() {
        val user = SessionManager.currentUser ?: return
        if (user.role == Role.STUDENT) {
            bindStudentOverview(user)
            findViewById<MaterialCardView>(R.id.topAnnouncementCard).visibility = View.GONE
        }
        if (user.role != Role.STUDENT) {
            bindTopAnnouncement(
                findViewById(R.id.topAnnouncementTitle),
                findViewById(R.id.topAnnouncementBody)
            )
        }
    }

    private fun bindStudentOverview(user: com.schoolms.mobile.data.User) {
        val attendanceRecord = SchoolRepository.attendanceForStudent(user.username)
        val marks = SchoolRepository.marksForStudent(user.username)
        val homework = SchoolRepository.homeworkForStudent(user.username)
        val pendingHomework = homework.count { item -> item.submissions.none { it.studentUsername == user.username } }
        val latestGrade = marks.lastOrNull()?.grade ?: "--"
        val attendancePercent = if (attendanceRecord == null || attendanceRecord.totalDays == 0) {
            "0%"
        } else {
            "${SchoolRepository.attendancePercent(attendanceRecord)}%"
        }

        findViewById<TextView>(R.id.studentClassValue).text = user.className.ifBlank { "Not set" }
        findViewById<TextView>(R.id.studentAttendanceValue).text = attendancePercent
        findViewById<TextView>(R.id.studentHomeworkValue).text = pendingHomework.toString()
        findViewById<TextView>(R.id.studentMarksValue).text = latestGrade

        val classTeacher = SchoolRepository.teacherNameForClass(user.className)
        val latestHomework = homework.lastOrNull()
        val latestNotification = SchoolRepository.notifications().firstOrNull()

        findViewById<TextView>(R.id.studentInsightTitle).text =
            if (latestHomework != null) "Next focus: ${latestHomework.title}" else "Class teacher: $classTeacher"
        findViewById<TextView>(R.id.studentInsightBody).text =
            buildString {
                append("Teacher: ")
                append(classTeacher)
                if (latestHomework != null) {
                    append("\nDue: ${latestHomework.dueDate} | Subject: ${latestHomework.subject}")
                }
                if (latestNotification != null) {
                    append("\nLatest update: ${latestNotification.title}")
                }
            }
    }

    private fun bindTopAnnouncement(titleView: TextView, bodyView: TextView) {
        val latestAnnouncement = SchoolRepository.announcements().firstOrNull()
        titleView.text = latestAnnouncement?.title ?: "No announcement yet"
        bodyView.text = latestAnnouncement?.subtitle ?: "New school announcements will appear here for students and teachers."
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    private fun open(target: Class<*>, mode: String) {
        startActivity(Intent(this, target).putExtra(InfoListActivity.EXTRA_MODE, mode))
    }

    private fun isSeniorStudent(className: String): Boolean {
        val number = Regex("(\\d+)").find(className)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return number >= 6
    }

    private fun shrinkRoleButton(button: MaterialButton) {
        val params = button.layoutParams as? LinearLayout.LayoutParams ?: return
        params.height = dp(118)
        button.layoutParams = params
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
