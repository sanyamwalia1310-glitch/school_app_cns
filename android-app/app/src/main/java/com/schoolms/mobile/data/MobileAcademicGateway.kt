package com.schoolms.mobile.data

import android.content.ContentResolver
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.schoolms.mobile.BuildConfig
import java.net.URL
import java.io.DataOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * HTTPS client for profile-scoped academic content.
 *
 * Every call includes both a Firebase ID token and SessionManager.activeProfileId.
 * The Flask server validates that pair, which prevents a shared parent account
 * from reading a sibling student's private marks, attendance, homework, or files.
 */
object MobileAcademicGateway {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private val gson = Gson()

    class ApiException(message: String, val statusCode: Int = 0) : Exception(message)

    data class Attachment(val id: Int, val name: String)
    data class Homework(
        val id: Int, val title: String, val description: String, val subject: String,
        val teacher: String, val className: String, val dueDate: String,
        val instructions: String, val externalLink: String, val attachments: List<Attachment>
    )
    data class Test(
        val id: Int, val title: String, val subject: String, val teacher: String,
        val className: String, val date: String, val syllabus: String, val instructions: String,
        val maximumMarks: Int?, val attachments: List<Attachment>
    )
    data class Mark(val subject: String, val assessment: String, val score: Int, val outOf: Int, val grade: String)
    data class Attendance(val date: String, val subject: String, val className: String, val present: Boolean)
    data class Download(val url: String, val filename: String)
    data class Upload(val mediaId: Int, val filename: String)
    data class StaffSubject(val name: String)
    data class StaffClass(val id: Int, val name: String)
    data class StaffStudent(val username: String, val fullName: String, val rollNumber: String)
    private data class CachedStaffClasses(val profileId: Int, val savedAt: Long, val items: List<StaffClass>)
    private val classCacheLock = Any()
    private var staffClassCache: CachedStaffClasses? = null

    fun homework(callback: (Result<List<Homework>>) -> Unit) = authenticated("/api/mobile/homework/list", callback) { payload ->
        payload.items().map { item ->
            Homework(
                id = item.int("id"), title = item.string("title"), description = item.string("description"),
                subject = item.string("subject_name"), teacher = item.string("teacher_name"),
                className = item.string("class_name"), dueDate = item.string("due_date"),
                instructions = item.string("instructions"), externalLink = item.string("external_link"),
                attachments = item.attachments()
            )
        }
    }

    fun tests(callback: (Result<List<Test>>) -> Unit) = authenticated("/api/mobile/tests/list", callback) { payload ->
        payload.items().map { item ->
            Test(
                id = item.int("id"), title = item.string("title"), subject = item.string("subject_name"),
                teacher = item.string("teacher_name"), className = item.string("class_name"),
                date = item.string("test_date"), syllabus = item.string("syllabus"),
                instructions = item.string("instructions"),
                maximumMarks = item.optionalInt("maximum_marks"), attachments = item.attachments()
            )
        }
    }

    fun marks(callback: (Result<List<Mark>>) -> Unit) = authenticated("/api/mobile/marks/list", callback) { payload ->
        payload.items().map { item ->
            Mark(item.string("subject_name"), item.string("exam_name"), item.int("obtained_marks"), item.int("total_marks"), item.string("grade"))
        }
    }

    fun attendance(callback: (Result<List<Attendance>>) -> Unit) = authenticated("/api/mobile/attendance/list", callback) { payload ->
        payload.items().map { item ->
            Attendance(item.string("attendance_date"), item.string("subject_name"), item.string("class_name"), item.string("status").equals("present", true))
        }
    }

    fun attachmentDownload(attachmentId: Int, callback: (Result<Download>) -> Unit) =
        authenticated("/api/mobile/attachments/$attachmentId/download", callback) { payload ->
            Download(payload.string("url"), payload.string("filename"))
        }

    /** Uploads a private attachment through Flask; Cloudinary credentials never enter Android. */
    fun uploadHomeworkAttachment(
        resolver: ContentResolver,
        fileUri: Uri,
        filename: String,
        callback: (Result<Upload>) -> Unit
    ) {
        withAuthentication(callback) { token, profileId ->
            resolver.openInputStream(fileUri)?.use { input ->
                postMultipart(
                    path = "/api/mobile/media/upload",
                    token = token,
                    profileId = profileId,
                    purpose = "homework_attachment",
                    filename = filename,
                    mimeType = resolver.getType(fileUri).orEmpty(),
                    input = input
                ).let { payload -> Upload(payload.int("media_id"), payload.string("filename")) }
            } ?: throw ApiException("Unable to open the selected attachment.")
        }
    }

    /** Creates homework only after Flask has authorized the selected teacher/admin profile. */
    fun createHomework(
        className: String,
        subjectName: String,
        title: String,
        description: String,
        dueDate: String,
        attachmentMediaIds: List<Int>,
        callback: (Result<Unit>) -> Unit
    ) = authenticated("/api/mobile/homework", callback, mapOf(
            "class_name" to className,
            "subject_name" to subjectName,
            "target_mode" to "class",
            "title" to title,
            "description" to description,
            "due_date" to dueDate,
            "attachment_media_ids" to attachmentMediaIds
        )) { Unit }

    fun staffSubjects(className: String, callback: (Result<List<StaffSubject>>) -> Unit) =
        authenticated("/api/mobile/staff/subjects", callback, mapOf("class_name" to className)) { payload ->
            payload.items().map {
                StaffSubject(it.string("name"))
            }.filter { it.name.isNotBlank() }
        }

    /** The school server, not an older local cache, owns staff class assignments. */
    fun staffClasses(callback: (Result<List<StaffClass>>) -> Unit) {
        val profileId = SessionManager.activeProfileId ?: -1
        val now = System.currentTimeMillis()
        synchronized(classCacheLock) {
            staffClassCache?.takeIf { it.profileId == profileId && now - it.savedAt < 60_000L }?.let {
                callback(Result.success(it.items))
                return
            }
        }
        authenticated("/api/mobile/staff/classes", { result ->
            result.onSuccess { items ->
                synchronized(classCacheLock) {
                    staffClassCache = CachedStaffClasses(profileId, System.currentTimeMillis(), items)
                }
            }
            callback(result)
        }) { payload ->
            payload.items().map {
                StaffClass(it.int("id"), it.string("class_name"))
            }.filter { it.id > 0 && it.name.isNotBlank() }
        }
    }

    /** Enrolled students are returned only after Flask verifies staff/class access. */
    fun staffClassStudents(className: String, callback: (Result<List<StaffStudent>>) -> Unit) =
        authenticated("/api/mobile/staff/class-students", callback, mapOf("class_name" to className)) { payload ->
            payload.items().map {
                StaffStudent(it.string("username"), it.string("full_name"), it.string("roll_no"))
            }.filter { it.username.isNotBlank() }
        }

    fun saveMark(
        studentUsername: String,
        className: String,
        subjectName: String,
        assessment: String,
        score: Int,
        outOf: Int,
        callback: (Result<Unit>) -> Unit
    ) = authenticated("/api/mobile/marks", callback, mapOf(
            "student_username" to studentUsername,
            "class_name" to className,
            "subject_name" to subjectName,
            "assessment" to assessment,
            "score" to score,
            "out_of" to outOf
        )) { Unit }

    fun saveAttendance(
        className: String,
        marks: Map<String, Boolean>,
        attendanceDate: String = "",
        callback: (Result<Int>) -> Unit
    ) = authenticated("/api/mobile/attendance", callback, mapOf(
        "class_name" to className,
        "marks" to marks,
        "attendance_date" to attendanceDate
    )) { payload ->
        payload.int("saved_count")
    }

    private fun <T> authenticated(
        path: String,
        callback: (Result<T>) -> Unit,
        values: Map<String, Any> = emptyMap(),
        parse: (JsonObject) -> T
    ) {
        withAuthentication(callback) { token, profileId ->
            parse(post(path, values + mapOf("firebase_id_token" to token, "profile_id" to profileId)))
        }
    }

    private fun <T> withAuthentication(
        callback: (Result<T>) -> Unit,
        action: (token: String, profileId: Int) -> T
    ) {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
            ?: return callback(Result.failure(ApiException("Firebase session expired. Please sign in again.")))
        firebaseUser.getIdToken(false)
            .addOnSuccessListener { tokenResult ->
                val token = tokenResult.token.orEmpty()
                if (token.isBlank()) {
                    callback(Result.failure(ApiException("Firebase session token is unavailable.")))
                    return@addOnSuccessListener
                }
                val profileId = SessionManager.activeProfileId
                if (profileId != null) {
                    thread { callback(runCatching { action(token, profileId) }) }
                } else {
                    restoreSingleAuthorizedProfile(token, callback, action)
                }
            }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    /**
     * A cached legacy login can lose its in-memory profile ID after an app update.
     * Restore it only when Firebase/Flask confirm there is exactly one authorized
     * school profile.  Shared-parent accounts must still explicitly select a profile.
     */
    private fun <T> restoreSingleAuthorizedProfile(
        token: String,
        callback: (Result<T>) -> Unit,
        action: (token: String, profileId: Int) -> T
    ) {
        FlaskEmailGateway.linkedProfiles(token, SessionManager.currentUser?.username.orEmpty()) { profilesResult ->
            profilesResult.onFailure { callback(Result.failure(it)) }.onSuccess { profiles ->
                val profile = profiles.singleOrNull()
                    ?: return@onSuccess callback(Result.failure(ApiException(
                        "Select your school profile from the sign-in screen, then try again."
                    )))
                FlaskEmailGateway.selectProfile(token, profile.id) { selectedResult ->
                    selectedResult.onFailure { callback(Result.failure(it)) }.onSuccess {
                        SessionManager.selectAuthorizedProfile(profile.id)
                        thread { callback(runCatching { action(token, profile.id) }) }
                    }
                }
            }
        }
    }

    private fun post(path: String, values: Map<String, Any>): JsonObject {
        val root = BuildConfig.FLASK_BASE_URL.trim().trimEnd('/')
        if (!root.startsWith("https://")) throw ApiException("Set SCHOOLMS_FLASK_BASE_URL to the HTTPS school server.")
        val connection = (URL(root + path).openConnection() as? HttpsURLConnection)
            ?: throw ApiException("The school server must use HTTPS.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(gson.toJson(values).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrDefault(JsonObject())
            if (status !in 200..299) throw ApiException(payload.string("error").ifBlank { "Server error (HTTP $status)." }, status)
            return payload
        } finally {
            connection.disconnect()
        }
    }

    private fun postMultipart(
        path: String,
        token: String,
        profileId: Int,
        purpose: String,
        filename: String,
        mimeType: String,
        input: java.io.InputStream
    ): JsonObject {
        val root = BuildConfig.FLASK_BASE_URL.trim().trimEnd('/')
        if (!root.startsWith("https://")) throw ApiException("Set SCHOOLMS_FLASK_BASE_URL to the HTTPS school server.")
        val boundary = "SchoolMsBoundary${System.currentTimeMillis()}"
        val connection = (URL(root + path).openConnection() as? HttpsURLConnection)
            ?: throw ApiException("The school server must use HTTPS.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS * 3
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            DataOutputStream(connection.outputStream).use { output ->
                fun field(name: String, value: String) {
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.writeBytes("\r\n")
                }
                field("firebase_id_token", token)
                field("profile_id", profileId.toString())
                field("purpose", purpose)
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${filename.replace("\"", "_")}\"\r\n")
                output.writeBytes("Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n")
                input.copyTo(output)
                output.writeBytes("\r\n--$boundary--\r\n")
                output.flush()
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrDefault(JsonObject())
            if (status !in 200..299) throw ApiException(payload.string("error").ifBlank { "Upload failed (HTTP $status)." }, status)
            return payload
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.items(): List<JsonObject> =
        getAsJsonArray("items")?.mapNotNull { it.asObjectOrNull() }.orEmpty()

    private fun JsonObject.attachments(): List<Attachment> =
        getAsJsonArray("attachments")?.mapNotNull { element ->
            element.asObjectOrNull()?.let { attachment ->
                Attachment(attachment.int("id"), attachment.string("display_name"))
            }
        }.orEmpty()

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        if (!isJsonNull && isJsonObject) asJsonObject else null

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.int(name: String): Int = optionalInt(name) ?: 0

    private fun JsonObject.optionalInt(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
}
