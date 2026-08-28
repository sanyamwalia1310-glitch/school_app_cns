package com.schoolms.mobile.data

enum class Role {
    ADMIN, TEACHER, STUDENT;

    companion object {
        fun fromLabel(value: String): Role = when (value.lowercase()) {
            "admin" -> ADMIN
            "teacher" -> TEACHER
            else -> STUDENT
        }
    }
}

data class User(
    val username: String,
    val password: String,
    val role: Role,
    val fullName: String,
    val className: String = "",
    val classNames: List<String> = emptyList(),
    val subject: String = "",
    val approved: Boolean = true,
    val mobileNumber: String = "",
    val profileImageUrl: String = "",
    val forcePasswordChange: Boolean = false,
    val qualification: String = "",
    val experience: String = "",
    val specialization: String = "",
    val staffBio: String = ""
)

data class SimpleListItem(
    val title: String,
    val subtitle: String,
    val badge: String? = null
)

data class AppUpdateNotice(
    val title: String = "",
    val subtitle: String = "",
    val buttonText: String = "Update",
    val downloadUrl: String = "",
    val minimumVersionCode: Int = 0,
    val forceUpdate: Boolean = false,
    val updatedAt: Long = 0L
) {
    fun isActive(): Boolean =
        title.isNotBlank() || subtitle.isNotBlank() || downloadUrl.isNotBlank() || forceUpdate || minimumVersionCode > 0
}

data class AppNotification(
    val title: String = "",
    val subtitle: String = "",
    val badge: String? = null,
    val targetUsername: String = "",
    val timestamp: Long = 0L
)

data class RegistrationRequest(
    val username: String = "",
    val authUid: String = "",
    val role: Role = Role.STUDENT,
    val fullName: String = "",
    val className: String = "",
    val subject: String = "",
    val rollNumber: String = "",
    val guardianContact: String = "",
    val notes: String = "",
    val mobileNumber: String = "",
    val source: String = "app",
    val needsReview: Boolean = false,
    val createdAt: Long = 0L
)

data class PasswordResetRequest(
    val username: String = "",
    val role: Role = Role.STUDENT,
    val fullName: String = "",
    val verificationContact: String = "",
    val mobileNumber: String = "",
    val requestedAt: Long = 0L,
    val source: String = "app"
)

data class ApprovalResult(
    val success: Boolean,
    val message: String
)

data class GalleryItem(
    val id: Long = 0L,
    val title: String = "",
    val subtitle: String = "",
    val imageUrl: String = "",
    val imageResName: String = ""
)

data class FacilityCard(
    val id: Long = 0L,
    val title: String = "",
    val subtitle: String = "",
    val badge: String = "",
    val imageUrl: String = "",
    val imageResName: String = ""
)

data class AttendanceRecord(
    val studentUsername: String,
    val studentName: String,
    val className: String,
    var teacherUsername: String,
    var presentDays: Int,
    var totalDays: Int
)

data class DailyAttendanceMark(
    val studentUsername: String,
    val className: String,
    val date: String,
    val present: Boolean,
    val markedBy: String = "",
    val updatedAt: Long = 0L
)

data class HomeworkItem(
    val id: Int,
    val className: String,
    val subject: String,
    val teacherUsername: String,
    val title: String,
    val description: String,
    val dueDate: String,
    var attachmentName: String? = null,
    var attachmentUrl: String? = null,
    val attachmentNames: List<String> = emptyList(),
    val attachmentUrls: List<String> = emptyList(),
    // A private attachment gets a short-lived URL only after API authorization.
    val attachmentIds: List<Int> = emptyList(),
    val submissions: List<HomeworkSubmission> = emptyList()
)

data class HomeworkSubmission(
    val studentUsername: String = "",
    val fileName: String = "",
    val fileUrl: String? = null,
    val fileNames: List<String> = emptyList(),
    val fileUrls: List<String> = emptyList(),
    val submittedAt: Long = 0L
)

data class MarkItem(
    val studentUsername: String,
    val studentName: String,
    val subject: String,
    val score: Int,
    val outOf: Int,
    val assessment: String = "Class Test 1"
) {
    val grade: String
        get() = when {
            percentage >= 80 -> "A+"
            percentage >= 65 -> "A"
            percentage >= 50 -> "B"
            percentage >= 35 -> "C"
            else -> "D"
        }

    val percentage: Int
        get() = if (outOf <= 0) 0 else ((score.toDouble() / outOf.toDouble()) * 100).toInt()
}

data class TimetableSlot(
    val className: String = "",
    val day: String,
    val time: String,
    val subject: String,
    val room: String
)

data class StudentProfile(
    val username: String,
    val fullName: String,
    val className: String,
    val rollNumber: String,
    val guardianContact: String,
    val notes: String,
    val imageUrl: String = ""
)

data class StudentRecoveryArchive(
    val username: String = "",
    val deletedAt: Long = 0L,
    val deletedBy: String = "",
    val user: User? = null,
    val profile: StudentProfile? = null,
    val attendanceRecords: List<AttendanceRecord> = emptyList(),
    val dailyAttendanceMarks: List<DailyAttendanceMark> = emptyList(),
    val marks: List<MarkItem> = emptyList(),
    val homeworkItems: List<HomeworkItem> = emptyList()
)

data class FeedbackEntry(
    val name: String,
    val roleOrClass: String,
    val message: String,
    val adminReply: String? = null,
    val submitterUsername: String? = null
)

data class AdmissionEntry(
    val studentName: String,
    val contact: String,
    val grade: String,
    val message: String,
    val adminReply: String? = null,
    val submitterUsername: String? = null
)

data class SubjectItem(
    val name: String,
    val className: String,
    val teacherName: String
)

data class QuizState(
    val mode: String = "",
    val rotation: Int = 0,
    val updatedAt: Long = 0L
)

data class QuizLeaderboardEntry(
    val modeKey: String = "",
    val username: String = "",
    val fullName: String = "",
    val score: Int = 0,
    val total: Int = 0,
    val timestamp: Long = 0L
)

data class SyncEvent(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val message: String = "",
    val actor: String = "",
    val role: String = "",
    val className: String = "",
    val targetUsername: String = "",
    val sourceDeviceId: String = "",
    val timestamp: Long = 0L
)
