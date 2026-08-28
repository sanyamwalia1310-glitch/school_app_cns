package com.schoolms.mobile

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.schoolms.mobile.firebase.MessagingTopics
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.NotificationHelper

class SchoolApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionManager.init(this)
        val firebaseReady = runCatching {
            FirebaseApp.initializeApp(this)
            Firebase.firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            true
        }.getOrDefault(false)

        if (firebaseReady) {
            runCatching {
                MessagingTopics.init(this)
                MessagingTopics.subscribeBaseTopics()
            }
        }
        NotificationHelper.ensureChannel(this)

        runCatching { SchoolRepository.init(this) }
        runCatching { SessionManager.restoreSessionFromCache() }
    }
}
