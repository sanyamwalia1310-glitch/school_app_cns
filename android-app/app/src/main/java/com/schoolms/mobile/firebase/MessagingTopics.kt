package com.schoolms.mobile.firebase

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.User

object MessagingTopics {
    private const val PREFS_NAME = "messaging_topics"
    private const val KEY_ACTIVE_TOPICS = "active_topics"
    private const val KEY_PENDING_APPROVAL_USERS = "pending_approval_users"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun subscribeBaseTopics() {
        updateTopics(null)
    }

    fun refreshUserTopics(user: User?) {
        updateTopics(user)
    }

    fun watchPendingApproval(username: String) {
        if (!::appContext.isInitialized) return
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) return
        val pendingUsers = pendingApprovalUsers().toMutableSet()
        if (pendingUsers.add(normalizedUsername)) {
            prefs().edit().putStringSet(KEY_PENDING_APPROVAL_USERS, pendingUsers).apply()
            updateTopics(null)
        }
    }

    fun clearPendingApproval(username: String) {
        if (!::appContext.isInitialized) return
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) return
        val pendingUsers = pendingApprovalUsers().toMutableSet()
        if (pendingUsers.remove(normalizedUsername)) {
            prefs().edit().putStringSet(KEY_PENDING_APPROVAL_USERS, pendingUsers).apply()
            updateTopics(null)
        }
    }

    fun pendingApprovalUsers(): Set<String> {
        if (!::appContext.isInitialized) return emptySet()
        return prefs().getStringSet(KEY_PENDING_APPROVAL_USERS, emptySet())
            .orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun updateTopics(user: User?) {
        if (!::appContext.isInitialized) return

        val desiredTopics = buildSet {
            // Topics are public/broadcast only.  Private marks, attendance, and
            // profile messages are delivered server-side to exact device tokens.
            add("school_public")
        }

        val currentTopics = prefs().getStringSet(KEY_ACTIVE_TOPICS, emptySet()).orEmpty().toSet()
        val messaging = FirebaseMessaging.getInstance()

        currentTopics.subtract(desiredTopics).forEach { topic ->
            messaging.unsubscribeFromTopic(topic)
        }
        desiredTopics.subtract(currentTopics).forEach { topic ->
            messaging.subscribeToTopic(topic)
        }

        prefs().edit().putStringSet(KEY_ACTIVE_TOPICS, desiredTopics).apply()
    }

    private fun prefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun classTopic(className: String): String =
        "class_" + sanitizeTopicPart(className)

    private fun userTopic(username: String): String =
        "user_" + sanitizeTopicPart(username)

    private fun roleTopic(role: Role): String =
        "role_" + sanitizeTopicPart(role.name)

    private fun audienceTopic(role: Role, className: String): String =
        "audience_${sanitizeTopicPart(role.name)}_${sanitizeTopicPart(className)}"

    private fun sanitizeTopicPart(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
