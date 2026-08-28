package com.schoolms.mobile.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.schoolms.mobile.data.FlaskEmailGateway
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.NotificationHelper

class SchoolMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MessagingTopics.refreshUserTopics(SessionManager.currentUser)
        registerPrivateToken(token)
    }

    /** Flask verifies the Firebase UID can use this exact selected school profile. */
    fun registerPrivateToken(token: String? = null) {
        val profileId = SessionManager.activeProfileId ?: return
        val firebaseUser = FirebaseAuth.getInstance().currentUser ?: return
        val register: (String) -> Unit = { deviceToken ->
            firebaseUser.getIdToken(false).addOnSuccessListener { idToken ->
                FlaskEmailGateway.registerFcmToken(idToken.token.orEmpty(), profileId, deviceToken) { }
            }
        }
        if (token.isNullOrBlank()) FirebaseMessaging.getInstance().token.addOnSuccessListener(register) else register(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val title = data["title"].orEmpty().ifBlank { remoteMessage.notification?.title.orEmpty() }
        val body = data["message"].orEmpty().ifBlank { remoteMessage.notification?.body.orEmpty() }
        val scope = data["delivery_scope"].orEmpty()
        val destination = data["destination"].orEmpty()
        val profileId = data["target_profile_id"]?.toIntOrNull()
        val allowed = when (scope) {
            "public" -> true
            "profile" -> profileId != null && profileId == SessionManager.activeProfileId
            else -> false
        }
        if (allowed && title.isNotBlank() && body.isNotBlank()) {
            NotificationHelper.showRealtimeUpdate(this, title, body, destination)
        }
        if (allowed) {
            SchoolRepository.refreshSharedStateOnce { }
            SchoolRepository.refreshPrivateAcademicContent { }
        }
    }
}
