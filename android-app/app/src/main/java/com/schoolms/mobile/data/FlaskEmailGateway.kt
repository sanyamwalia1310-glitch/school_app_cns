package com.schoolms.mobile.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.schoolms.mobile.BuildConfig
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/** HTTPS bridge for Flask's master-record validation.  It contains no Firebase Admin or SMS key. */
object FlaskEmailGateway {
    // Render can need a little time to wake on a free instance. Keep this below a minute,
    // while showing a clear in-app state instead of failing a valid registration too quickly.
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 45_000
    private val gson = Gson()

    class ApiException(message: String, val statusCode: Int) : Exception(message)

    data class Registration(val token: String, val email: String, val message: String, val verificationRequired: Boolean)
    data class LinkedProfile(val id: Int, val identifier: String, val fullName: String, val role: String)

    fun startRegistration(role: String, identifier: String, email: String, password: String, confirm: String, firebaseIdToken: String = "", callback: (Result<Registration>) -> Unit) = request(callback) {
        val body = post("/api/email-registration/start", mapOf("role" to role, "identifier" to identifier, "email" to email, "password" to password, "confirm_password" to confirm, "firebase_id_token" to firebaseIdToken))
        Registration(body["registration_token"]?.toString().orEmpty(), body["email"]?.toString().orEmpty(), body["message"]?.toString().orEmpty(), body["verification_required"] as? Boolean ?: true).also {
            if (it.token.isBlank() || it.email.isBlank()) throw ApiException("The school server did not create a verification session.", 500)
        }
    }

    fun resendRegistration(token: String, callback: (Result<Unit>) -> Unit) = request(callback) { post("/api/email-registration/resend", mapOf("registration_token" to token)); Unit }
    fun completeRegistration(token: String, firebaseIdToken: String, callback: (Result<String>) -> Unit) = request(callback) { post("/api/email-registration/complete", mapOf("registration_token" to token, "firebase_id_token" to firebaseIdToken))["message"]?.toString().orEmpty() }
    fun requestPasswordReset(role: String, identifier: String, email: String, callback: (Result<String>) -> Unit) = request(callback) { post("/api/password-reset/request", mapOf("role" to role, "identifier" to identifier, "email" to email))["email"]?.toString().orEmpty() }
    fun upsertStudentMasterRecord(
        firebaseIdToken: String,
        studentId: String,
        fullName: String,
        rollNumber: String,
        guardianName: String,
        email: String,
        callback: (Result<Unit>) -> Unit
    ) = request(callback) {
        post("/api/mobile/admin/student-master-record", mapOf(
            "firebase_id_token" to firebaseIdToken,
            "student_id" to studentId,
            "full_name" to fullName,
            "roll_no" to rollNumber,
            "guardian_name" to guardianName,
            "email" to email
        ))
        Unit
    }
    fun linkedProfiles(firebaseIdToken: String, callback: (Result<List<LinkedProfile>>) -> Unit) = request(callback) {
        val body = post("/api/firebase-session/login", mapOf("firebase_id_token" to firebaseIdToken))
        val items = body["profiles"] as? List<*> ?: emptyList<Any>()
        items.mapNotNull { item -> (item as? Map<*, *>)?.let { map ->
            val id = (map["id"] as? Number)?.toInt() ?: return@let null
            LinkedProfile(id, map["identifier"]?.toString().orEmpty(), map["full_name"]?.toString().orEmpty(), map["role"]?.toString().orEmpty())
        } }
    }
    fun selectProfile(firebaseIdToken: String, profileId: Int, callback: (Result<Unit>) -> Unit) = request(callback) { post("/api/firebase-session/select", mapOf("firebase_id_token" to firebaseIdToken, "profile_id" to profileId.toString())); Unit }
    fun registerFcmToken(firebaseIdToken: String, profileId: Int, token: String, callback: (Result<Unit>) -> Unit) = request(callback) {
        post("/api/mobile/fcm-token", mapOf("firebase_id_token" to firebaseIdToken, "profile_id" to profileId.toString(), "token" to token))
        Unit
    }

    private fun <T> request(callback: (Result<T>) -> Unit, action: () -> T) { thread { callback(runCatching(action)) } }
    private fun post(path: String, values: Map<String, String>): Map<String, Any> {
        val root = BuildConfig.FLASK_BASE_URL.trim().trimEnd('/')
        if (!root.startsWith("https://")) throw ApiException("Set SCHOOLMS_FLASK_BASE_URL to your HTTPS Flask address.", 0)
        val connection = (URL(root + path).openConnection() as? HttpsURLConnection) ?: throw ApiException("School server must use HTTPS.", 0)
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = CONNECT_TIMEOUT_MS; connection.readTimeout = READ_TIMEOUT_MS; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(gson.toJson(values).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { gson.fromJson(JsonParser.parseString(text), Map::class.java) as Map<String, Any> }.getOrDefault(emptyMap())
            if (status !in 200..299) throw ApiException(payload["error"]?.toString().orEmpty().ifBlank { "Server error (HTTP $status)." }, status)
            return payload
        } finally { connection.disconnect() }
    }
}
