package com.schoolms.mobile.data

import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.schoolms.mobile.BuildConfig
import java.net.URL
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

    private fun <T> authenticated(
        path: String,
        callback: (Result<T>) -> Unit,
        parse: (JsonObject) -> T
    ) {
        val profileId = SessionManager.activeProfileId
            ?: return callback(Result.failure(ApiException("Select a school profile and sign in again.")))
        val firebaseUser = FirebaseAuth.getInstance().currentUser
            ?: return callback(Result.failure(ApiException("Firebase session expired. Please sign in again.")))
        firebaseUser.getIdToken(false)
            .addOnSuccessListener { tokenResult ->
                val token = tokenResult.token.orEmpty()
                if (token.isBlank()) {
                    callback(Result.failure(ApiException("Firebase session token is unavailable.")))
                    return@addOnSuccessListener
                }
                thread {
                    callback(runCatching {
                        parse(post(path, mapOf("firebase_id_token" to token, "profile_id" to profileId)))
                    })
                }
            }
            .addOnFailureListener { callback(Result.failure(it)) }
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
