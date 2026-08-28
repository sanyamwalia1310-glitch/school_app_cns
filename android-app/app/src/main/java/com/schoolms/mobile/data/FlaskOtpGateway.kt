package com.schoolms.mobile.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.schoolms.mobile.BuildConfig
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * HTTPS client for the Flask-owned 2Factor OTP gateway.
 *
 * This contains no OTP provider credentials. A mobile number is sent only for initial account
 * activation; Flask validates the school ID and owns all normalization and duplicate checks.
 */
object FlaskOtpGateway {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private val gson = Gson()

    data class OtpSession(
        val token: String,
        val cooldownSeconds: Int,
        val expiresInSeconds: Int,
        val message: String
    )

    class ApiException(message: String, val statusCode: Int, val retryAfterSeconds: Int? = null) : Exception(message)

    fun sendOtp(
        purpose: String,
        identifier: String,
        role: String? = null,
        activationPhone: String? = null,
        onComplete: (Result<OtpSession>) -> Unit
    ) {
        thread {
            onComplete(runCatching {
                val request = linkedMapOf<String, Any>(
                    "purpose" to purpose,
                    "identifier" to identifier.trim()
                )
                role?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { request["role"] = it }
                activationPhone?.trim()?.takeIf { it.isNotBlank() }?.let { request["phone"] = it }
                val response = post("/api/mobile/otp/send", gson.toJson(request))
                val token = response["otp_session_token"]?.toString().orEmpty()
                if (token.isBlank()) throw ApiException("The server did not create an OTP session.", 500)
                OtpSession(
                    token = token,
                    cooldownSeconds = (response["cooldown"] as? Number)?.toInt() ?: 60,
                    expiresInSeconds = (response["expires_in"] as? Number)?.toInt() ?: 600,
                    message = response["message"]?.toString().orEmpty()
                )
            })
        }
    }

    fun verifyOtpAndComplete(
        purpose: String,
        otpSessionToken: String,
        otp: String,
        newPassword: String,
        confirmPassword: String,
        onComplete: (Result<String>) -> Unit
    ) {
        thread {
            onComplete(runCatching {
                val response = post(
                    "/api/mobile/otp/verify",
                    gson.toJson(
                        mapOf(
                            "purpose" to purpose,
                            "otp_session_token" to otpSessionToken,
                            "otp" to otp.trim(),
                            "new_password" to newPassword,
                            "confirm_password" to confirmPassword
                        )
                    )
                )
                response["message"]?.toString().orEmpty().ifBlank { "OTP verified successfully." }
            })
        }
    }

    private fun post(path: String, json: String): Map<String, Any> {
        val root = BuildConfig.FLASK_BASE_URL.trim().trimEnd('/')
        if (!root.startsWith("https://")) {
            throw ApiException("School server is not configured. Set SCHOOLMS_FLASK_BASE_URL to its HTTPS address.", 0)
        }
        val connection = (URL(root + path).openConnection() as? HttpsURLConnection)
            ?: throw ApiException("School server must use HTTPS.", 0)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = parsePayload(body)
            if (status !in 200..299) {
                val retryAfter = (payload["retry_after"] as? Number)?.toInt()
                throw ApiException(payload["error"]?.toString().orEmpty().ifBlank { "Server error (HTTP $status)." }, status, retryAfter)
            }
            return payload
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePayload(body: String): Map<String, Any> = runCatching {
        val element = JsonParser.parseString(body)
        if (!element.isJsonObject) emptyMap() else (gson.fromJson(element, Map::class.java) as? Map<String, Any> ?: emptyMap())
    }.getOrDefault(emptyMap())
}
