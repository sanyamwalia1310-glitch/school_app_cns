package com.schoolms.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.schoolms.mobile.BuildConfig
import com.schoolms.mobile.R
import com.schoolms.mobile.firebase.MessagingTopics
import com.schoolms.mobile.ui.NotificationHelper
import com.schoolms.mobile.util.PhoneNumberSupport
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object SchoolRepository {
    private const val PREFS = "schoolhub_prefs"
    private const val SHARED_STATE_COLLECTION = "shared_state"
    private const val SHARED_STATE_DOCUMENT = "schoolhub"
    // Public school-wide content is deliberately separated from account, marks, and
    // enquiry records.  Do not add private/profile keys to this document.
    private const val PUBLIC_CONTENT_COLLECTION = "public_content"
    private const val PUBLIC_CONTENT_DOCUMENT = "schoolhub"
    private const val SHARED_STATE_FETCH_TIMEOUT_MS = 15_000L
    private const val PERSONAL_EVENTS_COLLECTION = "personal_events"
    private const val PERSONAL_NOTIFICATIONS_COLLECTION = "personal_notifications"
    private const val REGISTRATION_REQUESTS_COLLECTION = "registration_requests"
    private const val PASSWORD_RESET_REQUESTS_COLLECTION = "password_reset_requests"
    private const val KEY_REGISTRATION_REQUESTS = "registration_requests_cache"
    private const val KEY_PASSWORD_RESET_REQUESTS = "password_reset_requests_cache"
    private const val KEY_LAST_ADMIN_ACCESS_SYNC = "last_admin_access_sync"
    private const val KEY_LAST_AUTH_IMPORT_SYNC = "last_auth_import_sync"
    private const val KEY_LAST_EVENT_ID = "last_event_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DAILY_ATTENDANCE = "daily_attendance_marks"
    private const val KEY_ANNOUNCEMENTS = "announcements"
    private const val KEY_OUR_SCHOLARS = "our_scholars"
    private const val KEY_SCHOOL_CONTACT = "school_contact"
    private const val KEY_SCHOOL_CONTENT = "school_content"
    private const val KEY_APP_UPDATE = "app_update"
    private const val KEY_FACILITY_CARDS = "facility_cards"
    private const val KEY_TIMETABLE_CLASSES = "timetable_classes"
    private const val KEY_TIMETABLE_SUBJECTS = "timetable_subjects"
    private const val KEY_TIMETABLE_TIMES = "timetable_times"
    private const val KEY_LAST_ANNOUNCEMENT_SIGNATURE = "last_announcement_signature"
    private const val KEY_TARGETED_NOTIFICATIONS_PREFIX = "targeted_notifications_"
    private const val KEY_QUIZ_STATES = "quiz_states"
    private const val KEY_QUIZ_LEADERBOARD = "quiz_leaderboard"
    private const val KEY_DELETED_STUDENTS = "deleted_students"
    private const val KEY_DELETED_ACCOUNTS = "deleted_accounts"
    private const val KEY_APPROVED_ACCOUNTS = "approved_accounts"
    private const val KEY_STUDENT_RECOVERY_ARCHIVES = "student_recovery_archives"
    private const val KEY_BACKUP_PREFIX = "backup_"
    private const val QUIZ_ROTATION_WINDOW_MS = 24 * 60 * 60 * 1000L
    private val gson = Gson()
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var sharedStateListener: ListenerRegistration? = null
    private var publicContentListener: ListenerRegistration? = null
    private var hasDedicatedPublicContent = false
    private var personalNotificationsListener: ListenerRegistration? = null
    private var isApplyingRemoteState = false
    private var isInitializing = false
    private var batchDepth = 0
    private var pendingSyncEvent: SyncEvent? = null
    private var activeTargetedNotificationsUsername = ""
    private val dirtySharedKeys = linkedSetOf<String>()
    private val changeListeners = linkedSetOf<() -> Unit>()

    private val users = mutableListOf<User>()
    private val attendanceRecords = mutableListOf<AttendanceRecord>()
    private val homeworkItems = mutableListOf<HomeworkItem>()
    private val marksStore = mutableListOf<MarkItem>()
    // These values come from Flask and are scoped to the selected Firebase profile.
    // They are deliberately not written back to the shared Firestore state.
    private var privateAcademicProfileId: Int? = null
    private var privateHomeworkItems: List<HomeworkItem> = emptyList()
    private var privateTestItems: List<MobileAcademicGateway.Test> = emptyList()
    private var privateMarksItems: List<MarkItem> = emptyList()
    private var privateAttendanceItems: List<DailyAttendanceMark> = emptyList()
    private val facilityItems = mutableListOf<SimpleListItem>()
    private val eventItems = mutableListOf<SimpleListItem>()
    private val announcementItems = mutableListOf<SimpleListItem>()
    private val ourScholarsItems = mutableListOf<SimpleListItem>()
    private val schoolContactItems = mutableListOf<SimpleListItem>()
    private val schoolContentItems = mutableListOf<SimpleListItem>()
    private val appUpdateItems = mutableListOf<AppUpdateNotice>()
    private val notificationItems = mutableListOf<SimpleListItem>()
    private val targetedNotificationItems = mutableListOf<AppNotification>()
    private val adminModuleItems = mutableListOf<SimpleListItem>()
    private val adminClassItems = mutableListOf<SimpleListItem>()
    private val subjectItems = mutableListOf<SubjectItem>()
    private val galleryCards = mutableListOf<GalleryItem>()
    private val facilityCardItems = mutableListOf<FacilityCard>()
    private val timetableItems = mutableListOf<TimetableSlot>()
    private val timetableClassItems = mutableListOf<String>()
    private val timetableSubjectItems = mutableListOf<String>()
    private val timetableTimeItems = mutableListOf<String>()
    private val studentProfiles = mutableListOf<StudentProfile>()
    private val feedbackStore = mutableListOf<FeedbackEntry>()
    private val admissionStore = mutableListOf<AdmissionEntry>()
    private val dailyAttendanceMarks = mutableListOf<DailyAttendanceMark>()
    private val quizStates = mutableListOf<QuizState>()
    private val quizLeaderboard = mutableListOf<QuizLeaderboardEntry>()
    private val studentRecoveryArchives = mutableListOf<StudentRecoveryArchive>()
    private val registrationRequests = mutableListOf<RegistrationRequest>()
    private val passwordResetRequests = mutableListOf<PasswordResetRequest>()
    private val deletedStudentUsernames = linkedSetOf<String>()
    private val deletedAccountUsernames = linkedSetOf<String>()
    private val approvedAccountUsernames = linkedSetOf<String>()
    private val protectedContentKeys = setOf(
        KEY_OUR_SCHOLARS,
        KEY_SCHOOL_CONTACT,
        KEY_SCHOOL_CONTENT
    )
    /** Keys which every signed-in school user may read.  This list is the Firestore privacy boundary. */
    private val publicContentKeys = setOf(
        "facilities",
        "events",
        KEY_ANNOUNCEMENTS,
        KEY_OUR_SCHOLARS,
        KEY_SCHOOL_CONTACT,
        KEY_SCHOOL_CONTENT,
        KEY_APP_UPDATE,
        "gallery",
        KEY_FACILITY_CARDS,
        "timetable",
        KEY_TIMETABLE_CLASSES,
        KEY_TIMETABLE_SUBJECTS,
        KEY_TIMETABLE_TIMES
    )
    private val legacyCodeImageResNames = setOf("library", "lab", "sports")
    private val legacyFacilityTitles = setOf("smart classrooms", "science labs", "sports complex")

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isInitializing = true
        if (prefs.getString(KEY_DEVICE_ID, null).isNullOrBlank()) {
            prefs.edit().putString(KEY_DEVICE_ID, "device_${System.currentTimeMillis()}").apply()
        }
        runCatching {
            loadAll()
        }.onFailure {
            // Recover from corrupted local cache instead of crashing the whole app at launch.
            prefs.edit().clear().apply()
            prefs.edit().putString(KEY_DEVICE_ID, "device_${System.currentTimeMillis()}").apply()
            loadAll()
        }
        ensureBaselineData()
        startSharedSync()
        isInitializing = false
        // Account ledgers are shared state.  They are saved at the moment an
        // administrator changes an account, never replayed from an arbitrary
        // device's old local cache at startup.  Replaying them here let a
        // stale installation hide students that had already been restored on
        // another device, and caused unnecessary Firestore writes on launch.
    }

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    private fun classesFor(user: User): List<String> {
        val raw = if (user.classNames.isNotEmpty()) user.classNames else listOfNotNull(user.className.takeIf { it.isNotBlank() })
        return raw.map { normalizeClassName(it) }.distinct()
    }

    fun assignedClasses(user: User): List<String> = classesFor(user)

    private fun loadAll() {
        val deletedStudents = mutableListOf<String>()
        loadMutableList(KEY_DELETED_STUDENTS, deletedStudents, mutableListOf())
        deletedStudentUsernames.clear()
        deletedStudentUsernames.addAll(
            deletedStudents.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        )
        val deletedAccounts = mutableListOf<String>()
        loadMutableList(KEY_DELETED_ACCOUNTS, deletedAccounts, mutableListOf())
        deletedAccountUsernames.clear()
        deletedAccountUsernames.addAll(
            deletedAccounts.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        )
        deletedAccountUsernames.addAll(deletedStudentUsernames)
        val approvedAccounts = mutableListOf<String>()
        loadMutableList(KEY_APPROVED_ACCOUNTS, approvedAccounts, mutableListOf())
        approvedAccountUsernames.clear()
        approvedAccountUsernames.addAll(
            approvedAccounts.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        )
        loadMutableList("users", users, seedUsers())
        loadMutableList("attendance", attendanceRecords, seedAttendance())
        loadMutableList("homework", homeworkItems, seedHomework())
        loadMutableList("marks", marksStore, seedMarks())
        loadMutableList("facilities", facilityItems, seedFacilities())
        loadMutableList("events", eventItems, seedEvents())
        loadMutableList(KEY_ANNOUNCEMENTS, announcementItems, seedAnnouncements())
        loadMutableList(KEY_OUR_SCHOLARS, ourScholarsItems, seedOurScholars())
        loadMutableList(KEY_SCHOOL_CONTACT, schoolContactItems, seedSchoolContact())
        loadMutableList(KEY_SCHOOL_CONTENT, schoolContentItems, seedSchoolContent())
        loadMutableList(KEY_APP_UPDATE, appUpdateItems, seedAppUpdate())
        loadMutableList("notifications", notificationItems, mutableListOf())
        loadMutableList("admin_modules", adminModuleItems, seedAdminModules())
        loadMutableList("admin_classes", adminClassItems, seedAdminClasses())
        loadMutableList("subjects", subjectItems, seedSubjects())
        loadMutableList("gallery", galleryCards, seedGallery())
        loadMutableList(KEY_FACILITY_CARDS, facilityCardItems, seedFacilityCards())
        loadMutableList("timetable", timetableItems, seedTimetable())
        loadMutableList(KEY_TIMETABLE_CLASSES, timetableClassItems, seedTimetableClasses())
        loadMutableList(KEY_TIMETABLE_SUBJECTS, timetableSubjectItems, seedTimetableSubjects())
        loadMutableList(KEY_TIMETABLE_TIMES, timetableTimeItems, seedTimetableTimes())
        loadMutableList("profiles", studentProfiles, seedProfiles())
        loadMutableList("feedback", feedbackStore, mutableListOf())
        loadMutableList("admissions", admissionStore, mutableListOf())
        loadMutableList(KEY_DAILY_ATTENDANCE, dailyAttendanceMarks, mutableListOf())
        loadMutableList(KEY_QUIZ_STATES, quizStates, seedQuizStates())
        loadMutableList(KEY_QUIZ_LEADERBOARD, quizLeaderboard, mutableListOf())
        loadMutableList(KEY_STUDENT_RECOVERY_ARCHIVES, studentRecoveryArchives, mutableListOf())
        loadMutableList(KEY_REGISTRATION_REQUESTS, registrationRequests, mutableListOf())
        loadMutableList(KEY_PASSWORD_RESET_REQUESTS, passwordResetRequests, mutableListOf())
        sanitizeUsers()
        sanitizeHomeworkItems()
        sanitizeGalleryItems()
        sanitizeFacilityCards()
        sanitizeQuizStates()
        reconcileAccountLedgersWithUsers()
        purgeDeletedStudentsFromAllStores()
    }

    private inline fun <reified T> loadMutableList(key: String, target: MutableList<T>, fallback: MutableList<T>) {
        val saved = prefs.getString(key, null)
        val loaded = parseMutableListJson(saved) { fallback.toMutableList() }
        target.clear()
        target.addAll(loaded)
    }

    private inline fun <reified T> parseMutableListJson(
        rawJson: String?,
        fallback: () -> MutableList<T> = { mutableListOf() }
    ): MutableList<T> {
        if (rawJson.isNullOrBlank()) return fallback()
        return runCatching {
            gson.fromJson(rawJson, object : TypeToken<MutableList<T>>() {}.type) ?: fallback()
        }.getOrElse { fallback() }
    }

    private fun save(key: String, value: Any, event: SyncEvent? = null) {
        persistValue(key, value)
        if (event != null) {
            pendingSyncEvent = event
        }
        if (!isApplyingRemoteState && !isInitializing) {
            dirtySharedKeys.add(key)
        }
        if (!isApplyingRemoteState && !isInitializing && batchDepth == 0) {
            flushSharedState()
        }
    }

    private fun persistValue(key: String, value: Any) {
        val json = gson.toJson(value)
        val editor = prefs.edit().putString(key, json)
        if (key in protectedContentKeys) {
            editor.putString("$KEY_BACKUP_PREFIX$key", json)
        }
        editor.apply()
    }

    private fun httpErrorMessage(responseCode: Int, body: String, fallback: String): String {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return fallback
        runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(trimmed, Map::class.java) as? Map<String, Any?>
        }.getOrNull()?.get("error")?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return fallback
        }
        return trimmed.takeIf { !it.equals("null", ignoreCase = true) } ?: fallback
    }

    private fun flushSharedState() {
        val changedKeys = dirtySharedKeys.toSet()
        dirtySharedKeys.clear()
        pushPublicContent(changedKeys.filterTo(linkedSetOf()) { it in publicContentKeys })
        pushSharedState(pendingSyncEvent, changedKeys.filterNot { it in publicContentKeys }.toSet())
        pendingSyncEvent = null
        notifyDataChanged()
    }

    private fun resolvedUsers(): List<User> {
        val merged = linkedMapOf<String, User>()
        users.filterNot { it.username.trim().lowercase() in deletedAccountUsernames }.forEach { user ->
            val key = user.username.trim().lowercase()
            val current = merged[key]
            merged[key] = if (current == null) {
                user.copy(username = key, approved = user.approved || key in approvedAccountUsernames)
            } else {
                mergeUserRecord(current, user)
            }
        }
        return merged.values.toList()
    }

    private fun mergeUserRecord(current: User, incoming: User): User {
        val normalizedIncoming = incoming.copy(username = incoming.username.trim().lowercase())
        val preferredIsCurrent = when {
            current.role == Role.ADMIN -> true
            normalizedIncoming.role == Role.ADMIN -> false
            current.approved && !normalizedIncoming.approved -> true
            !current.approved && normalizedIncoming.approved -> false
            else -> false
        }
        val preferred = if (preferredIsCurrent) current else normalizedIncoming
        val fallback = if (preferredIsCurrent) normalizedIncoming else current
        val accountApproved = normalizedIncoming.username in approvedAccountUsernames
        return preferred.copy(
            username = normalizedIncoming.username,
            password = preferred.password.ifBlank { fallback.password },
            role = preferred.role,
            fullName = preferred.fullName.ifBlank { fallback.fullName },
            className = preferred.className.ifBlank { fallback.className },
            classNames = if (preferred.classNames.isNotEmpty()) preferred.classNames else fallback.classNames,
            subject = preferred.subject.ifBlank { fallback.subject },
            approved = preferred.approved || fallback.approved || accountApproved,
            mobileNumber = preferred.mobileNumber.ifBlank { fallback.mobileNumber },
            profileImageUrl = preferred.profileImageUrl.ifBlank { fallback.profileImageUrl },
            forcePasswordChange = preferred.forcePasswordChange || fallback.forcePasswordChange,
            qualification = preferred.qualification.ifBlank { fallback.qualification },
            experience = preferred.experience.ifBlank { fallback.experience },
            specialization = preferred.specialization.ifBlank { fallback.specialization },
            staffBio = preferred.staffBio.ifBlank { fallback.staffBio }
        )
    }

    private fun runSharedUpdate(
        type: String,
        title: String,
        message: String,
        badge: String = "Update",
        role: String = "",
        className: String = "",
        targetUsername: String = "",
        addToGlobalNotifications: Boolean = false,
        block: () -> Unit
    ) {
        val event = buildSyncEvent(type, title, message, role, className, targetUsername)
        batchDepth += 1
        try {
            block()
            if (addToGlobalNotifications) {
                addLiveNotification(title, message, badge)
                persistValue("notifications", notificationItems)
            }
            pendingSyncEvent = event
        } finally {
            batchDepth -= 1
        }
        if (!isApplyingRemoteState && batchDepth == 0) {
            flushSharedState()
        }
    }

    private fun addLiveNotification(title: String, message: String, badge: String) {
        notificationItems.add(0, SimpleListItem(title.trim(), message.trim(), badge))
        if (notificationItems.size > 40) {
            notificationItems.subList(40, notificationItems.size).clear()
        }
    }

    private fun buildSyncEvent(
        type: String,
        title: String,
        message: String,
        role: String = "",
        className: String = "",
        targetUsername: String = ""
    ): SyncEvent {
        return SyncEvent(
            id = "event_${System.currentTimeMillis()}_${deviceId()}",
            type = type,
            title = title.trim(),
            message = message.trim(),
            actor = SessionManager.currentUser?.fullName ?: "System",
            role = role.trim().lowercase(),
            className = normalizeClassName(className),
            targetUsername = targetUsername.trim().lowercase(),
            sourceDeviceId = deviceId(),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun deviceId(): String = prefs.getString(KEY_DEVICE_ID, "device_local").orEmpty()

    fun refreshPersonalNotificationsForCurrentUser(forceRemoteRefresh: Boolean = false) {
        ensurePersonalNotificationsSession(forceRemoteRefresh)
    }

    private fun ensurePersonalNotificationsSession(forceRemoteRefresh: Boolean = false) {
        if (!::prefs.isInitialized) return
        val username = SessionManager.currentUser?.username.orEmpty().trim().lowercase()
        if (username.isBlank()) {
            personalNotificationsListener?.remove()
            personalNotificationsListener = null
            activeTargetedNotificationsUsername = ""
            if (targetedNotificationItems.isNotEmpty()) {
                targetedNotificationItems.clear()
                notifyDataChanged()
            }
            return
        }

        if (username != activeTargetedNotificationsUsername) {
            personalNotificationsListener?.remove()
            personalNotificationsListener = null
            activeTargetedNotificationsUsername = username
            val cached = loadCachedPersonalNotifications(username)
            targetedNotificationItems.clear()
            targetedNotificationItems.addAll(cached)
            notifyDataChanged()
            personalNotificationsListener = runCatching {
                Firebase.firestore
                    .collection(PERSONAL_NOTIFICATIONS_COLLECTION)
                    .document(username)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) return@addSnapshotListener
                        applyPersonalNotifications(username, snapshot?.getString("items_json"))
                    }
            }.getOrNull()
        }

        if (forceRemoteRefresh) {
            runCatching {
                Firebase.firestore
                    .collection(PERSONAL_NOTIFICATIONS_COLLECTION)
                    .document(username)
                    .get(Source.SERVER)
                    .addOnSuccessListener { snapshot ->
                        applyPersonalNotifications(username, snapshot.getString("items_json"))
                    }
            }
        }
    }

    private fun applyPersonalNotifications(username: String, rawJson: String?) {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) return
        val parsed = parsePersonalNotifications(rawJson, normalizedUsername)
        persistValue(targetedNotificationsCacheKey(normalizedUsername), parsed)
        if (activeTargetedNotificationsUsername != normalizedUsername) return
        if (targetedNotificationItems == parsed) return
        targetedNotificationItems.clear()
        targetedNotificationItems.addAll(parsed)
        notifyDataChanged()
    }

    private fun loadCachedPersonalNotifications(username: String): MutableList<AppNotification> {
        val cached = prefs.getString(targetedNotificationsCacheKey(username), null)
        return parsePersonalNotifications(cached, username)
    }

    private fun parsePersonalNotifications(rawJson: String?, username: String): MutableList<AppNotification> {
        val parsed = parseMutableListJson<AppNotification>(rawJson)
        return parsed
            .map {
                it.copy(
                    title = it.title.trim(),
                    subtitle = it.subtitle.trim(),
                    targetUsername = username.trim().lowercase(),
                    timestamp = it.timestamp.takeIf { value -> value > 0L } ?: System.currentTimeMillis()
                )
            }
            .filter { it.title.isNotBlank() || it.subtitle.isNotBlank() }
            .sortedByDescending { it.timestamp }
            .take(80)
            .toMutableList()
    }

    private fun targetedNotificationsCacheKey(username: String): String =
        "$KEY_TARGETED_NOTIFICATIONS_PREFIX${username.trim().lowercase()}"

    private fun readRemoteEvent(data: Map<String, Any>): SyncEvent? {
        val json = data["last_event"] as? String ?: return null
        return runCatching { gson.fromJson(json, SyncEvent::class.java) }.getOrNull()
    }

    private fun handleRemoteEvent(event: SyncEvent?) {
        if (event == null || event.id.isBlank()) return
        val lastEventId = prefs.getString(KEY_LAST_EVENT_ID, "").orEmpty()
        if (event.id == lastEventId) return
        prefs.edit().putString(KEY_LAST_EVENT_ID, event.id).apply()
        if (!shouldShowRealtimeEvent(event)) return
        val currentUser = SessionManager.currentUser
        val currentUsername = currentUser?.username?.trim()?.lowercase().orEmpty()
        val watchedPendingUsers = MessagingTopics.pendingApprovalUsers()
        val targetMatchesCurrentUser = event.targetUsername.isBlank() || event.targetUsername == currentUsername
        val targetMatchesPendingApproval =
            event.targetUsername.isNotBlank() &&
                watchedPendingUsers.contains(event.targetUsername) &&
                event.type == "account_approval" &&
                event.role.isBlank() &&
                event.className.isBlank()
        if (!targetMatchesCurrentUser && !targetMatchesPendingApproval) return
        if (event.role.isNotBlank() && currentUser?.role?.name?.lowercase() != event.role) return
        if (event.className.isNotBlank() && (currentUser == null || event.className !in classesFor(currentUser))) return
        if (event.sourceDeviceId != deviceId()) {
            NotificationHelper.showRealtimeUpdate(appContext, event.title, event.message)
            if (targetMatchesPendingApproval) {
                MessagingTopics.clearPendingApproval(event.targetUsername)
            }
        }
    }

    private fun shouldShowRealtimeEvent(event: SyncEvent): Boolean {
        val notifiableTypes = setOf(
            "registration_request",
            "account_approval",
            "announcement_publish",
            "announcement_update",
            "events",
            "attendance",
            "homework_publish",
            "homework_submission",
            "marks",
            "feedback",
            "feedback_reply",
            "admission",
            "admission_reply",
            "password_reset"
        )
        if (event.type !in notifiableTypes) return false
        val personalTypes = setOf(
            "account_approval",
            "feedback_reply",
            "admission_reply",
            "marks",
            "homework_submission",
            "password_reset"
        )
        if (event.type in personalTypes && event.targetUsername.isBlank()) return false
        return event.targetUsername.isNotBlank() || event.role.isNotBlank() || event.className.isNotBlank()
    }

    private fun shouldStoreTargetedNotification(event: SyncEvent): Boolean {
        val targetedTypes = setOf(
            "account_approval",
            "password_reset",
            "announcement_publish",
            "announcement_update",
            "events",
            "attendance",
            "homework_publish",
            "homework_submission",
            "marks",
            "feedback",
            "feedback_reply",
            "admission",
            "admission_reply"
        )
        return event.type in targetedTypes
    }

    private fun shouldUsePersonalChannel(event: SyncEvent): Boolean =
        event.targetUsername.isNotBlank() && shouldStoreTargetedNotification(event)

    private fun announcementSignature(item: SimpleListItem?): String {
        if (item == null) return ""
        return "${item.title.trim()}|${item.subtitle.trim()}|${item.badge.orEmpty().trim()}"
    }

    private fun syncAnnouncementNotification() {
        val latestAnnouncement = announcementItems.firstOrNull()
        val latestSignature = announcementSignature(latestAnnouncement)
        val previousSignature = prefs.getString(KEY_LAST_ANNOUNCEMENT_SIGNATURE, "").orEmpty()
        if (latestSignature.isNotBlank() && latestSignature != previousSignature) {
            prefs.edit().putString(KEY_LAST_ANNOUNCEMENT_SIGNATURE, latestSignature).apply()
        } else if (latestSignature.isBlank() && previousSignature.isNotBlank()) {
            prefs.edit().remove(KEY_LAST_ANNOUNCEMENT_SIGNATURE).apply()
        }
    }

    private fun notifyDataChanged() {
        changeListeners.toList().forEach { it.invoke() }
    }

    private fun startSharedSync() {
        sharedStateListener?.remove()
        publicContentListener?.remove()
        // Keep the legacy listener during the rolling upgrade.  Once a dedicated
        // public document exists it is never allowed to overwrite public content.
        sharedStateListener = runCatching {
            Firebase.firestore
                .collection(SHARED_STATE_COLLECTION)
                .document(SHARED_STATE_DOCUMENT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    val data = snapshot?.data
                    if (!data.isNullOrEmpty()) {
                        runCatching { applyRemoteState(data) }
                    }
                }
        }.getOrNull()
        publicContentListener = runCatching {
            Firebase.firestore
                .collection(PUBLIC_CONTENT_COLLECTION)
                .document(PUBLIC_CONTENT_DOCUMENT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    val data = snapshot?.data
                    hasDedicatedPublicContent = !data.isNullOrEmpty()
                    if (!data.isNullOrEmpty()) {
                        runCatching { applyRemotePublicContent(data) }
                    }
                }
        }.getOrNull()
    }

    fun refreshSharedStateOnce(onComplete: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeout = Runnable { completeRefresh(completed, timeoutHandler, onComplete, false) }
        timeoutHandler.postDelayed(timeout, SHARED_STATE_FETCH_TIMEOUT_MS)

        fun completeAfterLegacySnapshot(snapshot: DocumentSnapshot) {
            val data = snapshot.data
            val success = if (!data.isNullOrEmpty()) runCatching { applyRemoteState(data) }.isSuccess else false
            Firebase.firestore
                .collection(PUBLIC_CONTENT_COLLECTION)
                .document(PUBLIC_CONTENT_DOCUMENT)
                .get(Source.SERVER)
                .addOnSuccessListener { publicSnapshot ->
                    publicSnapshot.data?.takeIf { it.isNotEmpty() }?.let {
                        hasDedicatedPublicContent = true
                        runCatching { applyRemotePublicContent(it) }
                    }
                    completeRefresh(completed, timeoutHandler, onComplete, success)
                }
                .addOnFailureListener { completeRefresh(completed, timeoutHandler, onComplete, success) }
        }

        runCatching {
            val document = Firebase.firestore
                .collection(SHARED_STATE_COLLECTION)
                .document(SHARED_STATE_DOCUMENT)

            document.get(Source.SERVER)
                .addOnSuccessListener(::completeAfterLegacySnapshot)
                .addOnFailureListener {
                    document.get()
                        .addOnSuccessListener(::completeAfterLegacySnapshot)
                        .addOnFailureListener { completeRefresh(completed, timeoutHandler, onComplete, false) }
                }
        }.onFailure {
            completeRefresh(completed, timeoutHandler, onComplete, false)
        }
    }

    private fun completeRefresh(
        completed: AtomicBoolean,
        timeoutHandler: Handler,
        onComplete: (Boolean) -> Unit,
        success: Boolean
    ) {
        if (completed.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacksAndMessages(null)
            onComplete(success)
        }
    }

    fun refreshRegistrationRequestsOnce(onComplete: (Boolean) -> Unit = {}) {
        runCatching {
            Firebase.firestore
                .collection(REGISTRATION_REQUESTS_COLLECTION)
                .get(Source.SERVER)
                .addOnSuccessListener { snapshot ->
                    registrationRequests.clear()
                    registrationRequests.addAll(
                        snapshot.documents.mapNotNull { it.toObject(RegistrationRequest::class.java) }
                            .map(::normalizeRegistrationRequest)
                    )
                    persistRegistrationRequests()
                    notifyDataChanged()
                    onComplete(true)
                }
                .addOnFailureListener {
                    Firebase.firestore
                        .collection(REGISTRATION_REQUESTS_COLLECTION)
                        .get()
                        .addOnSuccessListener { snapshot ->
                            registrationRequests.clear()
                            registrationRequests.addAll(
                                snapshot.documents.mapNotNull { it.toObject(RegistrationRequest::class.java) }
                                    .map(::normalizeRegistrationRequest)
                            )
                            persistRegistrationRequests()
                            notifyDataChanged()
                            onComplete(true)
                        }
                        .addOnFailureListener { onComplete(false) }
                }
        }.onFailure {
            onComplete(false)
        }
    }

    fun registrationRequestByUsername(username: String): RegistrationRequest? =
        registrationRequests.firstOrNull { it.username == username.trim().lowercase() }

    fun registrationRequestByUsernameOnce(username: String, onComplete: (RegistrationRequest?) -> Unit) {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) {
            onComplete(null)
            return
        }
        Firebase.firestore
            .collection(REGISTRATION_REQUESTS_COLLECTION)
            .document(normalizedUsername)
            .get(Source.SERVER)
            .addOnSuccessListener { snapshot ->
                val request = snapshot.toObject(RegistrationRequest::class.java)?.let(::normalizeRegistrationRequest)
                if (request != null) {
                    registrationRequests.removeAll { it.username == normalizedUsername }
                    registrationRequests.add(request)
                    notifyDataChanged()
                }
                onComplete(request)
            }
            .addOnFailureListener {
                Firebase.firestore
                    .collection(REGISTRATION_REQUESTS_COLLECTION)
                    .document(normalizedUsername)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val request = snapshot.toObject(RegistrationRequest::class.java)?.let(::normalizeRegistrationRequest)
                        if (request != null) {
                            registrationRequests.removeAll { it.username == normalizedUsername }
                            registrationRequests.add(request)
                            notifyDataChanged()
                        }
                        onComplete(request)
                    }
                    .addOnFailureListener { onComplete(null) }
            }
    }

    fun submitRegistrationRequest(request: RegistrationRequest, onComplete: (Boolean) -> Unit) {
        val normalized = normalizeRegistrationRequest(request)
        Firebase.firestore
            .collection(REGISTRATION_REQUESTS_COLLECTION)
            .document(normalized.username)
            .set(normalized)
            .addOnSuccessListener {
                releaseDeletedUsernameReservation(normalized.username)
                registrationRequests.removeAll { it.username == normalized.username }
                registrationRequests.add(normalized)
                persistRegistrationRequests()
                notifyDataChanged()
                onComplete(true)
                broadcastRegistrationRequestEvent(normalized)
            }
            .addOnFailureListener { onComplete(false) }
    }

    fun ensureAdminSessionAccessIfNeeded(
        force: Boolean = false,
        maxAgeMs: Long = 10 * 60 * 1000L,
        onComplete: (Result<Boolean>) -> Unit = {}
    ) {
        if (!::prefs.isInitialized || SessionManager.currentUser?.role != Role.ADMIN) {
            onComplete(Result.success(false))
            return
        }
        val lastSyncAt = prefs.getLong(KEY_LAST_ADMIN_ACCESS_SYNC, 0L)
        if (!force && lastSyncAt > 0L && System.currentTimeMillis() - lastSyncAt < maxAgeMs) {
            onComplete(Result.success(false))
            return
        }
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)
            ?.addOnSuccessListener { tokenResult ->
                if ((tokenResult.claims["admin"] as? Boolean) == true) {
                    prefs.edit().putLong(KEY_LAST_ADMIN_ACCESS_SYNC, System.currentTimeMillis()).apply()
                    onComplete(Result.success(false))
                } else {
                    ensureAdminSessionAccess { result ->
                        result.onSuccess {
                            prefs.edit().putLong(KEY_LAST_ADMIN_ACCESS_SYNC, System.currentTimeMillis()).apply()
                        }
                        onComplete(result)
                    }
                }
            }
            ?.addOnFailureListener {
                ensureAdminSessionAccess { result ->
                    result.onSuccess {
                        prefs.edit().putLong(KEY_LAST_ADMIN_ACCESS_SYNC, System.currentTimeMillis()).apply()
                    }
                    onComplete(result)
                }
            }
            ?: ensureAdminSessionAccess { result ->
                result.onSuccess {
                    prefs.edit().putLong(KEY_LAST_ADMIN_ACCESS_SYNC, System.currentTimeMillis()).apply()
                }
                onComplete(result)
            }
    }

    fun ensureAdminSessionAccess(onComplete: (Result<Boolean>) -> Unit) {
        SessionManager.ensureFirebaseSession { authResult ->
            authResult.onFailure {
                onComplete(Result.failure(IllegalStateException("Admin session expired. Log in again.")))
                return@ensureFirebaseSession
            }
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser == null) {
                onComplete(Result.failure(IllegalStateException("Admin session expired. Log in again.")))
                return@ensureFirebaseSession
            }
            firebaseUser.getIdToken(true)
                .addOnSuccessListener { tokenResult ->
                    val projectId = FirebaseAuth.getInstance().app.options.projectId.orEmpty()
                    if (projectId.isBlank()) {
                        onComplete(Result.failure(IllegalStateException("Missing project setup.")))
                        return@addOnSuccessListener
                    }
                    val idToken = tokenResult.token.orEmpty()
                    if (idToken.isBlank()) {
                        onComplete(Result.failure(IllegalStateException("Unable to refresh login session.")))
                        return@addOnSuccessListener
                    }
                    val endpoint = "https://us-central1-$projectId.cloudfunctions.net/ensureAdminSessionAccess"
                    thread {
                        runCatching {
                            val connection = URL(endpoint).openConnection() as HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 15000
                            connection.readTimeout = 20000
                            connection.doOutput = true
                            connection.setRequestProperty("Authorization", "Bearer $idToken")
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.outputStream.use { it.write("{}".toByteArray()) }
                            val responseCode = connection.responseCode
                            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            if (responseCode !in 200..299) {
                                throw IllegalStateException(body.ifBlank { "Admin access check failed with HTTP $responseCode" })
                            }
                            val payload = gson.fromJson(body, Map::class.java) ?: emptyMap<String, Any>()
                            ((payload["updated"] as? Boolean) == true)
                        }.onSuccess { updated ->
                            firebaseUser.getIdToken(true)
                                .addOnSuccessListener { onComplete(Result.success(updated)) }
                                .addOnFailureListener { onComplete(Result.failure(it)) }
                        }.onFailure {
                            onComplete(Result.failure(it))
                        }
                    }
                }
                .addOnFailureListener { onComplete(Result.failure(it)) }
        }
    }

    fun updateRegistrationRequest(request: RegistrationRequest, onComplete: (Boolean) -> Unit = {}) {
        val normalized = normalizeRegistrationRequest(request)
        Firebase.firestore
            .collection(REGISTRATION_REQUESTS_COLLECTION)
            .document(normalized.username)
            .set(normalized, SetOptions.merge())
            .addOnSuccessListener {
                registrationRequests.removeAll { it.username == normalized.username }
                registrationRequests.add(normalized)
                persistRegistrationRequests()
                notifyDataChanged()
                onComplete(true)
            }
            .addOnFailureListener { onComplete(false) }
    }

    fun removeRegistrationRequest(username: String, onComplete: (Boolean) -> Unit = {}) {
        val normalizedUsername = username.trim().lowercase()
        Firebase.firestore
            .collection(REGISTRATION_REQUESTS_COLLECTION)
            .document(normalizedUsername)
            .delete()
            .addOnSuccessListener {
                clearRegistrationRequestLocally(normalizedUsername)
                onComplete(true)
            }
            .addOnFailureListener { onComplete(false) }
    }

    fun syncFirebaseAuthUsersForAdminIfNeeded(
        force: Boolean = false,
        maxAgeMs: Long = 5 * 60 * 1000L,
        onComplete: (Result<Int>) -> Unit = {}
    ) {
        if (!::prefs.isInitialized || SessionManager.currentUser?.role != Role.ADMIN) {
            onComplete(Result.success(0))
            return
        }
        val lastSyncAt = prefs.getLong(KEY_LAST_AUTH_IMPORT_SYNC, 0L)
        if (!force && lastSyncAt > 0L && System.currentTimeMillis() - lastSyncAt < maxAgeMs) {
            onComplete(Result.success(0))
            return
        }
        syncFirebaseAuthUsersForAdmin { result ->
            result.onSuccess {
                prefs.edit().putLong(KEY_LAST_AUTH_IMPORT_SYNC, System.currentTimeMillis()).apply()
            }
            onComplete(result)
        }
    }

    fun syncFirebaseAuthUsersForAdmin(onComplete: (Result<Int>) -> Unit) {
        SessionManager.ensureFirebaseSession { authResult ->
            authResult.onFailure {
                onComplete(Result.failure(IllegalStateException("Admin session expired. Log in again.")))
                return@ensureFirebaseSession
            }
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser == null) {
                onComplete(Result.failure(IllegalStateException("Admin session expired. Log in again.")))
                return@ensureFirebaseSession
            }
            firebaseUser.getIdToken(true)
                .addOnSuccessListener { tokenResult ->
                    val projectId = FirebaseAuth.getInstance().app.options.projectId.orEmpty()
                    if (projectId.isBlank()) {
                        onComplete(Result.failure(IllegalStateException("Missing project setup.")))
                        return@addOnSuccessListener
                    }
                    val idToken = tokenResult.token.orEmpty()
                    if (idToken.isBlank()) {
                        onComplete(Result.failure(IllegalStateException("Unable to refresh login session.")))
                        return@addOnSuccessListener
                    }
                    val endpoint = "https://us-central1-$projectId.cloudfunctions.net/syncAuthUsersToRegistrationRequests"
                    thread {
                        runCatching {
                            val connection = URL(endpoint).openConnection() as HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 15000
                            connection.readTimeout = 20000
                            connection.doOutput = true
                            connection.setRequestProperty("Authorization", "Bearer $idToken")
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.outputStream.use { it.write("{}".toByteArray()) }
                            val responseCode = connection.responseCode
                            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            if (responseCode !in 200..299) {
                                throw IllegalStateException(body.ifBlank { "Sync failed with HTTP $responseCode" })
                            }
                            val payload = gson.fromJson(body, Map::class.java) ?: emptyMap<String, Any>()
                            ((payload["imported"] as? Number)?.toInt() ?: 0)
                        }.onSuccess { imported ->
                            refreshRegistrationRequestsOnce {
                                onComplete(Result.success(imported))
                            }
                        }.onFailure {
                            onComplete(Result.failure(it))
                        }
                    }
                }
                .addOnFailureListener { onComplete(Result.failure(it)) }
        }
    }

    private fun normalizeRegistrationRequest(request: RegistrationRequest): RegistrationRequest =
        request.copy(
            username = request.username.trim().lowercase(),
            authUid = request.authUid.trim(),
            fullName = request.fullName.trim().ifBlank { request.username.trim() },
            className = normalizeClassName(request.className),
            subject = request.subject.trim(),
            rollNumber = request.rollNumber.trim(),
            guardianContact = request.guardianContact.trim(),
            notes = request.notes.trim(),
            mobileNumber = request.mobileNumber.trim(),
            source = request.source.trim().ifBlank { "app" },
            createdAt = request.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )

    private fun persistRegistrationRequests() {
        if (!::prefs.isInitialized) return
        persistValue(KEY_REGISTRATION_REQUESTS, registrationRequests)
    }

    private fun clearRegistrationRequestLocally(username: String): Boolean {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) return false
        val removed = registrationRequests.removeAll { it.username == normalizedUsername }
        if (removed) {
            persistRegistrationRequests()
            notifyDataChanged()
        }
        return removed
    }

    private fun registrationRequestToUser(request: RegistrationRequest): User =
        User(
            username = request.username,
            password = "",
            role = request.role,
            fullName = request.fullName,
            className = request.className,
            classNames = request.className.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList(),
            subject = request.subject,
            approved = false,
            mobileNumber = request.mobileNumber
        )

    private fun broadcastRegistrationRequestEvent(request: RegistrationRequest, onComplete: (Boolean) -> Unit = {}) {
        val roleLabel = request.role.name.lowercase()
        val classSuffix = request.className.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()
        broadcastSharedEvent(
            buildSyncEvent(
                type = "registration_request",
                title = "New registration",
                message = "${request.fullName} registered for a $roleLabel account$classSuffix. Waiting for approval.",
                role = Role.ADMIN.name.lowercase()
            ),
            onComplete
        )
    }

    private fun broadcastSharedEvent(event: SyncEvent, onComplete: (Boolean) -> Unit = {}) {
        if (shouldUsePersonalChannel(event)) {
            pushPersonalEvent(event, onComplete)
            return
        }
        Firebase.firestore
            .collection(SHARED_STATE_COLLECTION)
            .document(SHARED_STATE_DOCUMENT)
            .set(
                mapOf(
                    "last_event" to gson.toJson(event),
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    private fun pushPersonalEvent(event: SyncEvent, onComplete: (Boolean) -> Unit = {}) {
        Firebase.firestore
            .collection(PERSONAL_EVENTS_COLLECTION)
            .document(event.id)
            .set(event)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    private fun pushSharedState(syncEvent: SyncEvent? = null, changedKeys: Set<String> = emptySet()) {
        val payload = mutableMapOf<String, Any>()
        changedKeys.forEach { key ->
            sharedStateValueForKey(key)?.let { payload[key] = it }
        }
        if (syncEvent != null && !shouldUsePersonalChannel(syncEvent)) {
            payload["last_event"] = gson.toJson(syncEvent)
        }
        if (payload.isNotEmpty()) {
            payload["updatedAt"] = System.currentTimeMillis()
            runCatching {
                Firebase.firestore
                    .collection(SHARED_STATE_COLLECTION)
                    .document(SHARED_STATE_DOCUMENT)
                    .set(payload, SetOptions.merge())
            }
        }
        syncEvent?.takeIf(::shouldUsePersonalChannel)?.let { pushPersonalEvent(it) }
    }

    /**
     * Writes a complete public snapshot on the first public change.  A complete
     * document avoids a partial migration where a new document would otherwise
     * hide legacy public fields that have not yet been edited.
     */
    private fun pushPublicContent(changedKeys: Set<String>) {
        if (changedKeys.isEmpty()) return
        val payload = mutableMapOf<String, Any>(
            "schemaVersion" to 1,
            "updatedAt" to System.currentTimeMillis()
        )
        publicContentKeys.forEach { key ->
            sharedStateValueForKey(key)?.let { payload[key] = it }
        }
        runCatching {
            Firebase.firestore
                .collection(PUBLIC_CONTENT_COLLECTION)
                .document(PUBLIC_CONTENT_DOCUMENT)
                .set(payload, SetOptions.merge())
        }
    }

    private fun sharedStateValueForKey(key: String): String? = when (key) {
        "users" -> gson.toJson(usersForSharedState())
        "attendance" -> gson.toJson(attendanceRecords)
        "homework" -> gson.toJson(homeworkItems)
        "marks" -> gson.toJson(marksStore)
        "facilities" -> gson.toJson(facilityItems)
        "events" -> gson.toJson(eventItems)
        KEY_ANNOUNCEMENTS -> gson.toJson(announcementItems)
        KEY_OUR_SCHOLARS -> gson.toJson(ourScholarsItems)
        KEY_SCHOOL_CONTACT -> gson.toJson(schoolContactItems)
        KEY_SCHOOL_CONTENT -> gson.toJson(schoolContentItems)
        KEY_APP_UPDATE -> gson.toJson(appUpdateItems)
        "notifications" -> gson.toJson(notificationItems)
        "admin_modules" -> gson.toJson(adminModuleItems)
        "admin_classes" -> gson.toJson(adminClassItems)
        "subjects" -> gson.toJson(subjectItems)
        "gallery" -> gson.toJson(galleryCards)
        KEY_FACILITY_CARDS -> gson.toJson(facilityCardItems)
        "timetable" -> gson.toJson(timetableItems)
        KEY_TIMETABLE_CLASSES -> gson.toJson(timetableClassItems)
        KEY_TIMETABLE_SUBJECTS -> gson.toJson(timetableSubjectItems)
        KEY_TIMETABLE_TIMES -> gson.toJson(timetableTimeItems)
        "profiles" -> gson.toJson(studentProfiles)
        "feedback" -> gson.toJson(feedbackStore)
        "admissions" -> gson.toJson(admissionStore)
        KEY_DAILY_ATTENDANCE -> gson.toJson(dailyAttendanceMarks)
        KEY_QUIZ_STATES -> gson.toJson(quizStates)
        KEY_QUIZ_LEADERBOARD -> gson.toJson(quizLeaderboard)
        KEY_STUDENT_RECOVERY_ARCHIVES -> gson.toJson(studentRecoveryArchives)
        KEY_PASSWORD_RESET_REQUESTS -> gson.toJson(passwordResetRequests)
        KEY_DELETED_STUDENTS -> gson.toJson(deletedStudentUsernames.toList())
        KEY_DELETED_ACCOUNTS -> gson.toJson(deletedAccountUsernames.toList())
        KEY_APPROVED_ACCOUNTS -> gson.toJson(approvedAccountUsernames.toList())
        else -> null
    }

    private fun usersForSharedState(): List<User> =
        users.map { it.copy(password = "") }

    private fun applyRemoteState(data: Map<String, Any>) {
        val remoteEvent = readRemoteEvent(data)
        isApplyingRemoteState = true
        try {
            readRemoteAccountLedgers(data)
            readRemoteDeletedStudents(data)
            readRemoteUsers(data)
            readRemoteList(data, "attendance", attendanceRecords)
            readRemoteList(data, "homework", homeworkItems)
            readRemoteList(data, "marks", marksStore)
            if (!hasDedicatedPublicContent) {
                applyRemotePublicContent(data, notify = false)
            }
            readRemoteList(data, "notifications", notificationItems)
            readRemoteList(data, "admin_modules", adminModuleItems)
            readRemoteList(data, "admin_classes", adminClassItems)
            readRemoteList(data, "subjects", subjectItems)
            readRemoteList(data, "profiles", studentProfiles)
            readRemoteList(data, "feedback", feedbackStore)
            readRemoteList(data, "admissions", admissionStore)
            readRemoteList(data, KEY_DAILY_ATTENDANCE, dailyAttendanceMarks)
            readRemoteList(data, KEY_QUIZ_STATES, quizStates)
            readRemoteList(data, KEY_QUIZ_LEADERBOARD, quizLeaderboard)
            readRemoteList(data, KEY_STUDENT_RECOVERY_ARCHIVES, studentRecoveryArchives)
            readRemoteList(data, KEY_PASSWORD_RESET_REQUESTS, passwordResetRequests)
            reconcileAccountLedgersWithUsers(markSharedDirty = true)
            ensureBaselineData()
        } finally {
            isApplyingRemoteState = false
        }
        syncAnnouncementNotification()
        handleRemoteEvent(remoteEvent)
        notifyDataChanged()
        if (!isApplyingRemoteState && dirtySharedKeys.isNotEmpty() && batchDepth == 0) {
            flushSharedState()
        }
    }

    /** Applies only fields that are safe for every signed-in user to read. */
    private fun applyRemotePublicContent(data: Map<String, Any>, notify: Boolean = true) {
        val wasApplyingRemoteState = isApplyingRemoteState
        isApplyingRemoteState = true
        try {
            readRemoteList(data, "facilities", facilityItems)
            readRemoteList(data, "events", eventItems)
            readRemoteList(data, KEY_ANNOUNCEMENTS, announcementItems)
            readRemoteList(data, KEY_OUR_SCHOLARS, ourScholarsItems)
            readRemoteList(data, KEY_SCHOOL_CONTACT, schoolContactItems)
            readRemoteList(data, KEY_SCHOOL_CONTENT, schoolContentItems)
            readRemoteList(data, KEY_APP_UPDATE, appUpdateItems)
            readRemoteList(data, "gallery", galleryCards)
            readRemoteList(data, KEY_FACILITY_CARDS, facilityCardItems)
            readRemoteList(data, "timetable", timetableItems)
            readRemoteList(data, KEY_TIMETABLE_CLASSES, timetableClassItems)
            readRemoteList(data, KEY_TIMETABLE_SUBJECTS, timetableSubjectItems)
            readRemoteList(data, KEY_TIMETABLE_TIMES, timetableTimeItems)
            ensureBaselineData()
        } finally {
            isApplyingRemoteState = wasApplyingRemoteState
        }
        syncAnnouncementNotification()
        if (notify) notifyDataChanged()
    }

    private fun normalizedUsernameSet(items: Collection<String>): LinkedHashSet<String> =
        linkedSetOf<String>().apply {
            items.map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach(::add)
        }

    private fun readRemoteAccountLedgers(data: Map<String, Any>) {
        val deletedJson = data[KEY_DELETED_ACCOUNTS] as? String
        if (!deletedJson.isNullOrBlank()) {
            val remoteDeleted = normalizedUsernameSet(parseMutableListJson<String>(deletedJson))
            if (deletedAccountUsernames != remoteDeleted) {
                deletedAccountUsernames.clear()
                deletedAccountUsernames.addAll(remoteDeleted)
                persistValue(KEY_DELETED_ACCOUNTS, deletedAccountUsernames.toList())
                purgeDeletedAccountsFromAllStores()
            }
        }

        val approvedJson = data[KEY_APPROVED_ACCOUNTS] as? String
        if (!approvedJson.isNullOrBlank()) {
            val remoteApproved = normalizedUsernameSet(parseMutableListJson<String>(approvedJson))
            if (approvedAccountUsernames != remoteApproved) {
                approvedAccountUsernames.clear()
                approvedAccountUsernames.addAll(remoteApproved)
                persistValue(KEY_APPROVED_ACCOUNTS, approvedAccountUsernames.toList())
            }
        }
    }

    private fun readRemoteDeletedStudents(data: Map<String, Any>) {
        val json = data[KEY_DELETED_STUDENTS] as? String ?: return
        val remoteDeleted = normalizedUsernameSet(parseMutableListJson<String>(json))
        val changed = deletedStudentUsernames != remoteDeleted
        val accountTarget = linkedSetOf<String>().apply {
            addAll(deletedAccountUsernames.filter { it !in deletedStudentUsernames })
            addAll(remoteDeleted)
        }
        val accountChanged = deletedAccountUsernames != accountTarget
        if (changed) {
            deletedStudentUsernames.clear()
            deletedStudentUsernames.addAll(remoteDeleted)
            persistValue(KEY_DELETED_STUDENTS, deletedStudentUsernames.toList())
        }
        if (accountChanged) {
            deletedAccountUsernames.clear()
            deletedAccountUsernames.addAll(accountTarget)
            persistValue(KEY_DELETED_ACCOUNTS, deletedAccountUsernames.toList())
        }
        if (changed || accountChanged) {
            purgeDeletedStudentsFromAllStores()
            purgeDeletedAccountsFromAllStores()
        }
    }

    private fun reconcileAccountLedgersWithUsers(markSharedDirty: Boolean = false) {
        val approvedFromUsers = users
            .filter { it.approved && it.username.isNotBlank() && it.role != Role.ADMIN }
            .map { it.username.trim().lowercase() }
            .toSet()
        var approvedChanged = false
        if (!approvedAccountUsernames.containsAll(approvedFromUsers)) {
            approvedAccountUsernames.addAll(approvedFromUsers)
            persistValue(KEY_APPROVED_ACCOUNTS, approvedAccountUsernames.toList())
            approvedChanged = true
        }

        val shouldNotBeDeleted = approvedAccountUsernames.toSet()
        val deletedStudentsChanged = deletedStudentUsernames.removeAll(shouldNotBeDeleted)
        if (deletedStudentsChanged) {
            persistValue(KEY_DELETED_STUDENTS, deletedStudentUsernames.toList())
        }
        val deletedAccountsChanged = deletedAccountUsernames.removeAll(shouldNotBeDeleted)
        if (deletedAccountsChanged) {
            persistValue(KEY_DELETED_ACCOUNTS, deletedAccountUsernames.toList())
        }

        if (markSharedDirty) {
            if (approvedChanged) dirtySharedKeys.add(KEY_APPROVED_ACCOUNTS)
            if (deletedStudentsChanged) dirtySharedKeys.add(KEY_DELETED_STUDENTS)
            if (deletedAccountsChanged) dirtySharedKeys.add(KEY_DELETED_ACCOUNTS)
        }
    }

    private fun readRemoteUsers(data: Map<String, Any>) {
        val json = data["users"] as? String ?: return
        val parsed = parseMutableListJson<User>(json)
        val existingByUsername = users.associateBy { it.username.trim().lowercase() }
        var repairedMissingLocalUsers = false
        val merged = parsed.mapNotNull { incoming ->
            val key = incoming.username.trim().lowercase()
            if (key in deletedAccountUsernames || (incoming.role == Role.STUDENT && key in deletedStudentUsernames)) return@mapNotNull null
            val approvedIncoming = if (key in approvedAccountUsernames) incoming.copy(approved = true) else incoming
            val current = existingByUsername[key]
            if (current == null) {
                approvedIncoming.copy(username = key)
            } else {
                mergeUserRecord(current, approvedIncoming)
            }
        }.toMutableList()
        val mergedUsernames = merged.map { it.username.trim().lowercase() }.toSet()
        existingByUsername.forEach { (key, localUser) ->
            if (key !in mergedUsernames &&
                key !in deletedAccountUsernames &&
                !(localUser.role == Role.STUDENT && key in deletedStudentUsernames) &&
                (localUser.approved || key in approvedAccountUsernames)
            ) {
                merged.add(localUser.copy(username = key, approved = localUser.approved || key in approvedAccountUsernames))
                repairedMissingLocalUsers = true
            }
        }
        users.clear()
        users.addAll(merged)
        sanitizeUsers()
        prefs.edit().putString("users", gson.toJson(users)).apply()
        if (repairedMissingLocalUsers) {
            dirtySharedKeys.add("users")
        }
    }

    private inline fun <reified T> readRemoteList(data: Map<String, Any>, key: String, target: MutableList<T>) {
        val json = data[key] as? String ?: return
        val parsed = parseMutableListJson<T>(json)
        if (key in protectedContentKeys && parsed.isEmpty() && target.isNotEmpty()) {
            // Ignore destructive empty snapshots for critical school content unless local is also empty.
            return
        }
        if (key in protectedContentKeys && parsed.isEmpty() && target.isEmpty()) {
            val backupJson = prefs.getString("$KEY_BACKUP_PREFIX$key", null)
            if (!backupJson.isNullOrBlank()) {
                val backupParsed = parseMutableListJson<T>(backupJson)
                if (backupParsed.isNotEmpty()) {
                    target.clear()
                    target.addAll(backupParsed)
                    persistValue(key, target)
                    return
                }
            }
        }
        val existingStudentProfilesByUsername = if (key == "profiles") {
            studentProfiles.associateBy { it.username.trim().lowercase() }
        } else {
            emptyMap()
        }
        target.clear()
        target.addAll(parsed)
        if (key == "profiles") {
            @Suppress("UNCHECKED_CAST")
            (target as MutableList<StudentProfile>).replaceAll { incoming ->
                val existing = existingStudentProfilesByUsername[incoming.username.trim().lowercase()]
                if (incoming.imageUrl.isBlank() && existing?.imageUrl?.isNotBlank() == true) {
                    incoming.copy(imageUrl = existing.imageUrl)
                } else {
                    incoming
                }
            }
            target.removeAll {
                val username = it.username.trim().lowercase()
                username in deletedStudentUsernames || username in deletedAccountUsernames
            }
        }
        if (key == "attendance") {
            @Suppress("UNCHECKED_CAST")
            (target as MutableList<AttendanceRecord>).removeAll {
                val username = it.studentUsername.trim().lowercase()
                username in deletedStudentUsernames || username in deletedAccountUsernames
            }
        }
        if (key == "marks") {
            @Suppress("UNCHECKED_CAST")
            (target as MutableList<MarkItem>).removeAll {
                val username = it.studentUsername.trim().lowercase()
                username in deletedStudentUsernames || username in deletedAccountUsernames
            }
        }
        if (key == "homework") {
            @Suppress("UNCHECKED_CAST")
            (target as MutableList<HomeworkItem>).replaceAll { item ->
                item.copy(
                    teacherUsername = if (item.teacherUsername.trim().lowercase() in deletedAccountUsernames) "admin" else item.teacherUsername,
                    submissions = item.submissions.filterNot {
                        val username = it.studentUsername.trim().lowercase()
                        username in deletedStudentUsernames || username in deletedAccountUsernames
                    }
                )
            }
        }
        if (key == "users") {
            @Suppress("UNCHECKED_CAST")
            sanitizeUsers(target as MutableList<User>)
        }
        if (key == "gallery") {
            @Suppress("UNCHECKED_CAST")
            sanitizeGalleryItems(target as MutableList<GalleryItem>)
        }
        if (key == "facilities") {
            @Suppress("UNCHECKED_CAST")
            sanitizeFacilityItems(target as MutableList<SimpleListItem>)
        }
        if (key == KEY_FACILITY_CARDS) {
            @Suppress("UNCHECKED_CAST")
            sanitizeFacilityCards(target as MutableList<FacilityCard>)
        }
        if (key == "homework") {
            @Suppress("UNCHECKED_CAST")
            sanitizeHomeworkItems(target as MutableList<HomeworkItem>)
        }
        if (key == "timetable") {
            @Suppress("UNCHECKED_CAST")
            sanitizeTimetableItems(target as MutableList<TimetableSlot>)
        }
        if (key == KEY_QUIZ_STATES) {
            @Suppress("UNCHECKED_CAST")
            sanitizeQuizStates(target as MutableList<QuizState>)
        }
        prefs.edit().putString(key, gson.toJson(target)).apply()
    }

    private fun saveDeletedStudents() {
        save(KEY_DELETED_STUDENTS, deletedStudentUsernames.toList())
    }

    private fun saveDeletedAccounts() {
        save(KEY_DELETED_ACCOUNTS, deletedAccountUsernames.toList())
    }

    private fun saveApprovedAccounts() {
        save(KEY_APPROVED_ACCOUNTS, approvedAccountUsernames.toList())
    }

    private fun purgeDeletedAccountsFromAllStores() {
        if (deletedAccountUsernames.isEmpty()) return
        val deletedTeachers = users
            .filter { it.role == Role.TEACHER && it.username.trim().lowercase() in deletedAccountUsernames }
            .map { it.fullName }
            .toSet()
        var changed = false
        changed = users.removeAll { it.username.trim().lowercase() in deletedAccountUsernames } || changed
        changed = studentProfiles.removeAll { it.username.trim().lowercase() in deletedAccountUsernames } || changed
        changed = attendanceRecords.removeAll { it.studentUsername.trim().lowercase() in deletedAccountUsernames } || changed
        changed = marksStore.removeAll { it.studentUsername.trim().lowercase() in deletedAccountUsernames } || changed
        val previousHomework = homeworkItems.toList()
        homeworkItems.replaceAll { item ->
            item.copy(
                teacherUsername = if (item.teacherUsername.trim().lowercase() in deletedAccountUsernames) "admin" else item.teacherUsername,
                submissions = item.submissions.filterNot { it.studentUsername.trim().lowercase() in deletedAccountUsernames }
            )
        }
        changed = changed || previousHomework != homeworkItems
        if (deletedTeachers.isNotEmpty()) {
            subjectItems.replaceAll {
                if (it.teacherName in deletedTeachers) it.copy(teacherName = "Assigned later") else it
            }
            changed = true
        }
        if (changed) {
            persistValue("users", users)
            persistValue("profiles", studentProfiles)
            persistValue("attendance", attendanceRecords)
            persistValue("marks", marksStore)
            persistValue("homework", homeworkItems)
            persistValue("subjects", subjectItems)
        }
    }

    private fun purgeDeletedStudentsFromAllStores() {
        if (deletedStudentUsernames.isEmpty()) return
        var changed = false
        changed = users.removeAll { it.role == Role.STUDENT && it.username.trim().lowercase() in deletedStudentUsernames } || changed
        changed = studentProfiles.removeAll { it.username.trim().lowercase() in deletedStudentUsernames } || changed
        changed = attendanceRecords.removeAll { it.studentUsername.trim().lowercase() in deletedStudentUsernames } || changed
        changed = marksStore.removeAll { it.studentUsername.trim().lowercase() in deletedStudentUsernames } || changed
        val previousHomework = homeworkItems.toList()
        homeworkItems.replaceAll { item ->
            item.copy(submissions = item.submissions.filterNot { it.studentUsername.trim().lowercase() in deletedStudentUsernames })
        }
        changed = changed || previousHomework != homeworkItems
        if (changed) {
            persistValue("users", users)
            persistValue("profiles", studentProfiles)
            persistValue("attendance", attendanceRecords)
            persistValue("marks", marksStore)
            persistValue("homework", homeworkItems)
        }
    }

    private fun sanitizeUsers(target: MutableList<User> = users) {
        target.removeAll { it.username.trim().lowercase() in deletedAccountUsernames }
        target.replaceAll { user ->
            val normalizedUsername = (user.username as String?).orEmpty().trim().lowercase()
            User(
                username = normalizedUsername,
                password = (user.password as String?).orEmpty(),
                role = user.role,
                fullName = (user.fullName as String?).orEmpty(),
                className = (user.className as String?).orEmpty(),
                classNames = (user.classNames as List<String>?).orEmpty().map { (it as String?).orEmpty() }.filter { it.isNotBlank() },
                subject = (user.subject as String?).orEmpty(),
                approved = user.approved || normalizedUsername in approvedAccountUsernames,
                mobileNumber = (user.mobileNumber as String?).orEmpty(),
                profileImageUrl = (user.profileImageUrl as String?).orEmpty(),
                forcePasswordChange = user.forcePasswordChange,
                qualification = (user.qualification as String?).orEmpty(),
                experience = (user.experience as String?).orEmpty(),
                specialization = (user.specialization as String?).orEmpty(),
                staffBio = (user.staffBio as String?).orEmpty()
            )
        }
        deduplicateUsers(target)
    }

    private fun deduplicateUsers(target: MutableList<User> = users) {
        if (target.isEmpty()) return
        val merged = linkedMapOf<String, User>()
        target.forEach { user ->
            val key = user.username.trim().lowercase()
            if (key in deletedAccountUsernames) return@forEach
            val normalizedUser = user.copy(username = key, approved = user.approved || key in approvedAccountUsernames)
            val current = merged[key]
            merged[key] = when {
                current == null -> normalizedUser
                normalizedUser.role == Role.ADMIN -> normalizedUser
                current.role == Role.ADMIN -> current
                normalizedUser.approved && !current.approved -> normalizedUser
                !normalizedUser.approved && current.approved -> current
                normalizedUser.fullName.length > current.fullName.length -> normalizedUser
                else -> current
            }
        }
        target.clear()
        target.addAll(merged.values)
    }

    private fun sanitizeGalleryItems(target: MutableList<GalleryItem> = galleryCards) {
        val defaults = seedGallery()
        target.removeAll {
            it.imageUrl.trim().isBlank() &&
                it.imageResName.trim().lowercase() in legacyCodeImageResNames
        }
        if (target.isEmpty()) return
        target.indices.forEach { index ->
            val item = target[index]
            val fallback = defaults.getOrNull(index)
            target[index] = item.copy(
                id = item.id.takeIf { it != 0L } ?: (index + 1).toLong(),
                imageResName = item.imageResName.ifBlank { fallback?.imageResName.orEmpty() }
            )
        }
    }

    private fun sanitizeFacilityItems(target: MutableList<SimpleListItem> = facilityItems) {
        target.removeAll { it.title.trim().lowercase() in legacyFacilityTitles }
    }

    private fun sanitizeHomeworkItems(target: MutableList<HomeworkItem> = homeworkItems) {
        if (target.isEmpty()) return
        target.replaceAll { item ->
            val attachmentNames = item.attachmentNames.orEmpty()
                .ifEmpty { listOfNotNull(item.attachmentName?.takeIf { it.isNotBlank() }) }
            val attachmentUrls = item.attachmentUrls.orEmpty()
                .ifEmpty { listOfNotNull(item.attachmentUrl?.takeIf { it.isNotBlank() }) }
            item.copy(
                attachmentName = attachmentNames.firstOrNull(),
                attachmentUrl = attachmentUrls.firstOrNull(),
                attachmentNames = attachmentNames,
                attachmentUrls = attachmentUrls,
                submissions = item.submissions.orEmpty().filter {
                    it.studentUsername.isNotBlank() && (it.fileName.isNotBlank() || it.fileNames.orEmpty().isNotEmpty())
                }.map {
                    val fileNames = it.fileNames.orEmpty().ifEmpty { listOfNotNull(it.fileName.takeIf { name -> name.isNotBlank() }) }
                    val fileUrls = it.fileUrls.orEmpty().ifEmpty { listOfNotNull(it.fileUrl?.takeIf { url -> url.isNotBlank() }) }
                    it.copy(
                        fileName = fileNames.firstOrNull().orEmpty(),
                        fileUrl = fileUrls.firstOrNull(),
                        fileNames = fileNames,
                        fileUrls = fileUrls
                    )
                }
            )
        }
    }

    private fun sanitizeQuizStates(target: MutableList<QuizState> = quizStates) {
        val required = seedQuizStates()
        val merged = linkedMapOf<String, QuizState>()
        required.forEach { merged[it.mode] = it }
        target.forEach { state ->
            if (state.mode.isNotBlank()) {
                merged[state.mode.trim().lowercase()] = state.copy(mode = state.mode.trim().lowercase())
            }
        }
        target.clear()
        target.addAll(merged.values)
    }

    private fun sanitizeTimetableItems(target: MutableList<TimetableSlot> = timetableItems) {
        if (target.isEmpty()) return
        target.replaceAll { item ->
            val rawClass = (item.className as String?).orEmpty()
            val rawRoom = (item.room as String?).orEmpty()
            val rawDay = (item.day as String?).orEmpty()
            val rawTime = (item.time as String?).orEmpty()
            val rawSubject = (item.subject as String?).orEmpty()
            val normalizedClass = rawClass.trim().ifBlank { classNameFromRoom(rawRoom).orEmpty() }.let { normalizeClassName(it) }
            item.copy(
                className = normalizedClass,
                day = rawDay.trim(),
                time = rawTime.trim(),
                subject = rawSubject.trim(),
                room = rawRoom.trim().ifBlank { normalizedClass.ifBlank { "Shared" } }
            )
        }
    }

    private fun classNameFromRoom(room: String): String? {
        val normalized = room.trim()
        if (normalized.isBlank()) return null
        val known = Regex("(Class\\s+\\d+|LKG|UKG)", RegexOption.IGNORE_CASE).find(normalized)?.value
        return known?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun defaultAdminUser(): User? {
        val username = BuildConfig.BOOTSTRAP_ADMIN_USERNAME.trim().lowercase()
        val password = BuildConfig.BOOTSTRAP_ADMIN_PASSWORD.trim()
        val fullName = BuildConfig.BOOTSTRAP_ADMIN_NAME.trim()
        if (username.isBlank() || password.isBlank() || fullName.isBlank()) return null
        return User(
            username = username,
            password = password,
            role = Role.ADMIN,
            fullName = fullName,
            approved = true
        )
    }

    private fun defaultClasses() = listOf(
        "LKG", "UKG", "Class 1", "Class 2", "Class 3", "Class 4", "Class 5",
        "Class 6", "Class 7", "Class 8", "Class 9", "Class 10"
    )

    private fun seedProfilesTemplate() = mutableListOf(
        StudentProfile("lkg_aarav", "Aarav Kumar", "LKG", "01", "9876500001", "Enjoys classroom activities and storytelling."),
        StudentProfile("ukg_diya", "Diya Sharma", "UKG", "02", "9876500002", "Strong communication and drawing skills."),
        StudentProfile("c1_vihaan", "Vihaan Singh", "Class 1", "03", "9876500003", "Good reading progress and regular attendance."),
        StudentProfile("c2_anaya", "Anaya Patel", "Class 2", "04", "9876500004", "Participates actively in class discussions."),
        StudentProfile("c3_isha", "Isha Gupta", "Class 3", "05", "9876500005", "Shows interest in science models."),
        StudentProfile("c4_kabir", "Kabir Nair", "Class 4", "06", "9876500006", "Performs well in mathematics drills."),
        StudentProfile("c5_meera", "Meera Das", "Class 5", "07", "9876500007", "Consistent performance and discipline."),
        StudentProfile("c6_advik", "Advik Rao", "Class 6", "08", "9876500008", "Active in quiz and reading club."),
        StudentProfile("c7_sana", "Sana Khan", "Class 7", "09", "9876500009", "Balanced academic and sports performance."),
        StudentProfile("c8_reyansh", "Reyansh Joshi", "Class 8", "10", "9876500010", "Strong in computer and project work."),
        StudentProfile("c9_kiara", "Kiara Menon", "Class 9", "11", "9876500011", "Good attendance and leadership skills."),
        StudentProfile("student", "Neha Singh", "Class 10", "14", "9876543210", "Strong in mathematics, active in debate and quiz.")
    )

    private fun seedUsers() = mutableListOf<User>().apply {
        defaultAdminUser()?.let(::add)
        add(
            User(
                "teacher",
                "",
                Role.TEACHER,
                "Rahul Verma",
                className = "Class 10",
                classNames = listOf("Class 10"),
                subject = "Mathematics"
            )
        )
        add(
            User(
                "teacher9",
                "",
                Role.TEACHER,
                "Priya Nanda",
                className = "Class 9",
                classNames = listOf("Class 9"),
                subject = "English"
            )
        )
        addAll(
            seedProfilesTemplate().map {
                User(
                    it.username,
                    "",
                    Role.STUDENT,
                    it.fullName,
                    it.className,
                    classNames = listOf(it.className)
                )
            }
        )
    }

    private fun seedAttendance() = seedProfilesTemplate().mapIndexed { index, profile ->
        AttendanceRecord(
            studentUsername = profile.username,
            studentName = profile.fullName,
            className = profile.className,
            teacherUsername = "teacher",
            presentDays = 150 + index,
            totalDays = 180 + index
        )
    }.toMutableList()

    private fun seedQuizStates() = mutableListOf(
        QuizState(mode = "math_junior", rotation = 0, updatedAt = System.currentTimeMillis()),
        QuizState(mode = "science_junior", rotation = 0, updatedAt = System.currentTimeMillis()),
        QuizState(mode = "math_senior", rotation = 0, updatedAt = System.currentTimeMillis()),
        QuizState(mode = "science_senior", rotation = 0, updatedAt = System.currentTimeMillis())
    )

    private fun seedHomework() = mutableListOf(
        HomeworkItem(
            id = 1,
            className = "Class 10",
            subject = "Mathematics",
            teacherUsername = "teacher",
            title = "Algebra worksheet",
            description = "Solve linear equations set 5 and upload your notebook PDF.",
            dueDate = "12 Apr 2026",
            attachmentName = "algebra-sheet.pdf"
        ),
        HomeworkItem(
            id = 2,
            className = "Class 10",
            subject = "Science",
            teacherUsername = "teacher",
            title = "Physics notes",
            description = "Prepare short notes on electricity and resistance.",
            dueDate = "15 Apr 2026",
            attachmentName = "physics-notes.docx"
        )
    )

    private fun seedMarks() = seedProfilesTemplate().flatMapIndexed { index, profile ->
        listOf(
            MarkItem(profile.username, profile.fullName, "Mathematics", 70 + (index % 25), 100, "Class Test 1"),
            MarkItem(profile.username, profile.fullName, "Science", 68 + (index % 27), 100, "Class Test 2")
        )
    }.toMutableList()

    private fun seedFacilities() = mutableListOf<SimpleListItem>()

    private fun seedFacilityCards() = mutableListOf<FacilityCard>()

    private fun seedEvents() = mutableListOf(
        SimpleListItem("Science exhibition", "Student innovation fair scheduled for 20 April.", "Upcoming"),
        SimpleListItem("Cultural week", "Dance, theatre, music, and art performances.", "Festival"),
        SimpleListItem("PTM", "Parent teacher meeting for Class 9 and 10 on Saturday.", "Parents")
    )

    private fun seedAnnouncements() = mutableListOf(
        SimpleListItem("Fee reminder", "Last date for quarter one fee payment is 14 April.", "Finance"),
        SimpleListItem("Transport update", "Route 5 bus timing shifted by 10 minutes.", "Transport"),
        SimpleListItem("Library drive", "Return issued books before annual stock verification.", "Library")
    )

    private fun seedOurScholars() = mutableListOf(
        SimpleListItem(
            "Proud CNS Achievers",
            "A premium space to celebrate students who show discipline, consistency, leadership, creativity, and strong academic growth. Admin can update this spotlight anytime so every parent, teacher, and student sees the latest achievements.",
            "Scholar spotlight"
        )
    )

    private fun seedSchoolContact() = mutableListOf(
        SimpleListItem(
            "Contact: +91 98765 43210",
            "Email: info@cnspaunta.edu",
            "Office: 9:00 AM - 3:00 PM"
        )
    )

    private fun seedSchoolContent() = mutableListOf(
        SimpleListItem(
            "School content",
            defaultSchoolContent(),
            "CNS"
        )
    )

    private fun seedAppUpdate() = mutableListOf(
        AppUpdateNotice(
            title = "",
            subtitle = "",
            buttonText = "Update",
            downloadUrl = "",
            minimumVersionCode = 0,
            forceUpdate = false,
            updatedAt = 0L
        )
    )

    private fun defaultSchoolContent(): String = """
        Welcome to Cambridge National School Paunta

        Cambridge National School Paunta brings daily school communication, academics, administration, and student progress into one connected app experience.

        Digital school management

        Admin users can manage shared school information, facilities, events, student profiles, attendance records, timetable entries, gallery photos, feedback, and admission enquiries from one place.

        For teachers and students

        Teachers can manage attendance, homework, class updates, and academic records. Students can track attendance percentage, assigned homework, marks, timetable, profile details, and important school updates.

        Shared updates

        School content, gallery, facilities, contact details, and scholar highlights are shared across connected devices so updated information remains available for everyone.
    """.trimIndent()

    private fun seedAdminModules() = mutableListOf(
        SimpleListItem("Users", "Manage admin, teacher, and student accounts.", "${seedUsers().size} users"),
        SimpleListItem("Classes", "Create sections and assign class teachers.", "${defaultClasses().size} classes"),
        SimpleListItem("Subjects", "Track syllabus ownership and subject allocations.", "2 subjects"),
        SimpleListItem("Password reset requests", "Review forgot-password requests and issue temporary passwords.", "0 open"),
        SimpleListItem("Announcements", "Publish school-wide notices and alerts.", "3 live"),
        SimpleListItem("Facilities", "Manage campus facilities shown to all users.", "Campus"),
        SimpleListItem("Events", "Manage school events shown to all users.", "Calendar"),
        SimpleListItem("Notifications", "Manage notices shown to every user.", "Broadcast"),
        SimpleListItem("Admissions", "Review all admission enquiries in one place.", "Inbox")
    )

    private fun seedAdminClasses() = defaultClasses().mapIndexed { index, className ->
        SimpleListItem(className, "Class teacher: ${if (className == "Class 10") "Rahul Verma" else "Coordinator ${index + 1}"} | Students: ${seedProfilesTemplate().count { it.className == className }}", "Section")
    }.toMutableList()

    private fun seedGallery() = mutableListOf<GalleryItem>()

    private fun seedSubjects() = mutableListOf(
        SubjectItem("Mathematics", "Class 10", "Rahul Verma"),
        SubjectItem("Science", "Class 10", "Rahul Verma"),
        SubjectItem("English", "Class 9", "Coordinator 10"),
        SubjectItem("EVS", "Class 4", "Coordinator 4")
    )

    private fun seedTimetable() = mutableListOf(
        TimetableSlot("Class 10", "Monday", "08:30 - 09:15", "Mathematics", "Room 204"),
        TimetableSlot("Class 10", "Monday", "09:20 - 10:05", "Science", "Lab 2"),
        TimetableSlot("Class 9", "Tuesday", "11:20 - 12:05", "English", "Room 105"),
        TimetableSlot("Class 8", "Wednesday", "12:10 - 12:55", "Social Science", "Room 110")
    )

    private fun seedTimetableClasses() = mutableListOf(
        "Class 10",
        "Class 9",
        "Class 8"
    )

    private fun seedTimetableSubjects() = mutableListOf(
        "Mathematics",
        "Science",
        "English",
        "Social Science"
    )

    private fun seedTimetableTimes() = mutableListOf(
        "09:30 - 10:15",
        "10:15 - 11:00",
        "11:00 - 11:45",
        "11:45 - 12:30",
        "12:30 - 01:15",
        "01:15 - 02:00",
        "02:00 - 02:45"
    )

    private fun seedProfiles() = seedProfilesTemplate()

    private fun ensureBaselineData() {
        var changed = false

        val configuredAdmin = defaultAdminUser()
        val adminUsers = users.filter { it.role == Role.ADMIN }
        val preferredAdmin = adminUsers.firstOrNull {
            configuredAdmin != null && it.username.equals(configuredAdmin.username, true) || it.username.equals("admin", true)
        }
        if (configuredAdmin != null) {
            val normalizedAdmin = configuredAdmin.copy(mobileNumber = preferredAdmin?.mobileNumber.orEmpty())
            if (preferredAdmin == null) {
                users.add(0, normalizedAdmin)
                changed = true
            } else {
                val updatedAdmins = users.map {
                    when {
                        it.role != Role.ADMIN -> it
                        it.username.equals(preferredAdmin.username, true) -> normalizedAdmin
                        else -> null
                    }
                }.filterNotNull()
                if (updatedAdmins != users) {
                    users.clear()
                    users.addAll(updatedAdmins)
                    changed = true
                }
            }
        }

        if (users.isNotEmpty() && users.none { it.approved }) {
            users.replaceAll { it.copy(approved = true) }
            changed = true
        }

        studentProfiles.forEach { profile ->
            if (attendanceRecords.none { it.studentUsername == profile.username }) {
                attendanceRecords.add(
                    AttendanceRecord(
                        profile.username,
                        profile.fullName,
                        profile.className,
                        teacherUsernameForClass(profile.className) ?: "admin",
                        0,
                        0
                    )
                )
                changed = true
            }
        }

        defaultClasses().forEach { className ->
            if (adminClassItems.none { it.title == className }) {
                changed = true
            }
        }

        if (adminModuleItems.none { it.title == "Student profiles" }) {
            adminModuleItems.add(SimpleListItem("Student profiles", "Open a profile and review complete student details by class.", "${studentProfiles.size} records"))
            changed = true
        }
        if (adminModuleItems.none { it.title == "Students & subjects" }) {
            adminModuleItems.add(SimpleListItem("Students & subjects", "Create students once and manage class-wise subject mappings.", "Manage"))
            changed = true
        }

        if (adminModuleItems.none { it.title == "Teachers" }) {
            adminModuleItems.add(SimpleListItem("Teachers", "Create teacher accounts and assign one class to each teacher.", "Manage"))
            changed = true
        }
        if (adminModuleItems.none { it.title == "Password reset requests" }) {
            adminModuleItems.add(SimpleListItem("Password reset requests", "Review forgot-password requests and issue temporary passwords.", "0 open"))
            changed = true
        }
        if (adminModuleItems.none { it.title == "Facilities" }) {
            adminModuleItems.add(SimpleListItem("Facilities", "Add and update shared facility details.", "Manage"))
            changed = true
        }
        if (adminModuleItems.none { it.title == "Events" }) {
            adminModuleItems.add(SimpleListItem("Events", "Add and update shared school event details.", "Manage"))
            changed = true
        }
        if (adminModuleItems.none { it.title == "Notifications" }) {
            adminModuleItems.add(SimpleListItem("Notifications", "Broadcast shared notifications to every user.", "Manage"))
            changed = true
        }
        if (adminModuleItems.none { it.title == "Admissions" }) {
            adminModuleItems.add(SimpleListItem("Admissions", "Review all admission enquiries submitted in the app.", "Manage"))
            changed = true
        }
        if (ourScholarsItems.isEmpty()) {
            ourScholarsItems.addAll(seedOurScholars())
            changed = true
        }
        if (schoolContactItems.isEmpty()) {
            schoolContactItems.addAll(seedSchoolContact())
            changed = true
        }
        if (schoolContentItems.isEmpty()) {
            schoolContentItems.addAll(seedSchoolContent())
            changed = true
        }
        val galleryBefore = gson.toJson(galleryCards)
        sanitizeGalleryItems()
        if (gson.toJson(galleryCards) != galleryBefore) {
            save("gallery", galleryCards)
        }
        val facilityItemsBefore = gson.toJson(facilityItems)
        sanitizeFacilityItems()
        if (gson.toJson(facilityItems) != facilityItemsBefore) {
            save("facilities", facilityItems)
        }
        val facilityCardsBefore = gson.toJson(facilityCardItems)
        sanitizeFacilityCards()
        if (gson.toJson(facilityCardItems) != facilityCardsBefore) {
            save(KEY_FACILITY_CARDS, facilityCardItems)
        }
        if (ensureTimetableLayout()) {
            changed = true
        }

        refreshAdminClassItems()

        if (changed) {
            save("profiles", studentProfiles)
            save("users", users)
            save("attendance", attendanceRecords)
            save("admin_classes", adminClassItems)
            save("admin_modules", adminModuleItems)
            save("subjects", subjectItems)
            save(KEY_TIMETABLE_CLASSES, timetableClassItems)
            save(KEY_TIMETABLE_SUBJECTS, timetableSubjectItems)
            save(KEY_TIMETABLE_TIMES, timetableTimeItems)
            save("timetable", timetableItems)
            save(KEY_OUR_SCHOLARS, ourScholarsItems)
            save(KEY_SCHOOL_CONTACT, schoolContactItems)
            save(KEY_SCHOOL_CONTENT, schoolContentItems)
        }
    }

    fun authenticate(role: Role, username: String, password: String): User? {
        return resolvedUsers().firstOrNull {
            it.role == role &&
                it.approved &&
                it.username.equals(username.trim(), ignoreCase = true) &&
                it.password == password
        }
    }

    fun authenticateByMobile(role: Role, mobileNumber: String, password: String): User? {
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        return resolvedUsers().firstOrNull {
            it.role == role &&
                it.approved &&
                it.mobileNumber == normalizedMobile &&
                it.password == password
        }
    }

    fun userByUsername(username: String): User? =
        resolvedUsers().firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }

    fun isUsernameUnavailable(username: String, originalUsername: String = ""): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val normalizedOriginal = originalUsername.trim().lowercase()
        if (normalizedUsername.isBlank() || normalizedUsername == normalizedOriginal) return false
        if (resolvedUsers().any { it.username.trim().lowercase() == normalizedUsername && it.username.trim().lowercase() != normalizedOriginal }) {
            return true
        }
        return registrationRequests.any { it.username == normalizedUsername && it.username != normalizedOriginal }
    }

    fun suggestAvailableUsernames(rawValue: String, count: Int = 3, originalUsername: String = ""): List<String> {
        val base = rawValue
            .trim()
            .lowercase()
            .replace("\\s+".toRegex(), "")
            .replace("[^a-z0-9._-]".toRegex(), "")
            .ifBlank { rawValue.trim().lowercase().replace("\\s+".toRegex(), "") }
        if (base.isBlank()) return emptyList()

        val suggestions = linkedSetOf<String>()
        if (!isUsernameUnavailable(base, originalUsername)) {
            suggestions.add(base)
        }

        var suffix = 1
        while (suggestions.size < count && suffix <= 50) {
            val candidate = "$base$suffix"
            if (!isUsernameUnavailable(candidate, originalUsername)) {
                suggestions.add(candidate)
            }
            suffix += 1
        }
        return suggestions.toList()
    }

    fun userByMobile(mobileNumber: String): User? =
        resolvedUsers().firstOrNull { it.mobileNumber == PhoneNumberSupport.normalize(mobileNumber) }

    fun recoverableUser(username: String, role: Role): User? {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank() || role == Role.ADMIN) return null
        return resolvedUsers().firstOrNull {
            it.username == normalizedUsername &&
                it.role == role &&
                it.approved
        }
    }

    fun recoverableUser(username: String, role: Role, mobileNumber: String): User? {
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        if (normalizedMobile.isBlank()) return null
        return recoverableUser(username, role)?.takeIf { it.mobileNumber == normalizedMobile }
    }

    fun activatableUser(identifier: String): User? {
        val username = identifier.trim().lowercase()
        if (username.isBlank()) return null
        return resolvedUsers().firstOrNull {
            it.username == username &&
                it.role != Role.ADMIN &&
                it.approved &&
                it.password.isBlank() &&
                PhoneNumberSupport.normalize(it.mobileNumber).isNotBlank()
        }
    }

    fun isAccountDeleted(username: String): Boolean {
        val normalizedUsername = username.trim().lowercase()
        return normalizedUsername in deletedAccountUsernames || normalizedUsername in deletedStudentUsernames
    }

    private fun releaseDeletedUsernameReservation(username: String) {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) return
        var changed = false
        if (deletedStudentUsernames.remove(normalizedUsername)) {
            saveDeletedStudents()
            dirtySharedKeys.add(KEY_DELETED_STUDENTS)
            changed = true
        }
        if (deletedAccountUsernames.remove(normalizedUsername)) {
            saveDeletedAccounts()
            dirtySharedKeys.add(KEY_DELETED_ACCOUNTS)
            changed = true
        }
        if (approvedAccountUsernames.remove(normalizedUsername)) {
            saveApprovedAccounts()
            dirtySharedKeys.add(KEY_APPROVED_ACCOUNTS)
            changed = true
        }
        if (changed && !isApplyingRemoteState && batchDepth == 0) {
            flushSharedState()
        }
    }

    fun announcements(): List<SimpleListItem> = announcementItems.toList()

    fun ourScholars(): SimpleListItem = ourScholarsItems.firstOrNull()
        ?: seedOurScholars().first()

    fun updateOurScholars(title: String, subtitle: String): Boolean {
        if (title.isBlank() || subtitle.isBlank()) return false
        val item = SimpleListItem(title.trim(), subtitle.trim(), "Scholars")
        runSharedUpdate(
            type = "our_scholars",
            title = "Our Scholars updated",
            message = item.title,
            badge = "Scholars"
        ) {
            if (ourScholarsItems.isEmpty()) {
                ourScholarsItems.add(item)
            } else {
                ourScholarsItems[0] = item
            }
            save(KEY_OUR_SCHOLARS, ourScholarsItems)
        }
        return true
    }

    fun schoolContact(): SimpleListItem = schoolContactItems.firstOrNull()
        ?: seedSchoolContact().first()

    fun updateSchoolContact(phone: String, email: String, details: String): Boolean {
        if (phone.isBlank() || email.isBlank()) return false
        val item = SimpleListItem(
            "Contact: ${phone.trim()}",
            "Email: ${email.trim()}",
            details.trim().ifBlank { "Office details" }
        )
        runSharedUpdate(
            type = "school_contact",
            title = "School contact updated",
            message = "${item.title} | ${item.subtitle}",
            badge = "Contact"
        ) {
            if (schoolContactItems.isEmpty()) {
                schoolContactItems.add(item)
            } else {
                schoolContactItems[0] = item
            }
            save(KEY_SCHOOL_CONTACT, schoolContactItems)
        }
        return true
    }

    private data class QuizPrompt(
        val question: String,
        val options: List<String>,
        val correctIndex: Int
    )

    private fun classQuizTier(className: String): String {
        val number = Regex("(\\d+)").find(className.trim().lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: return "junior"
        return if (number >= 8) "senior" else "junior"
    }

    private fun quizStateKey(mode: String, className: String): String =
        "${mode.trim().lowercase()}_${classQuizTier(className)}"

    private fun quizQuestionBank(stateKey: String): List<QuizPrompt> = when (stateKey.trim().lowercase()) {
        "science_senior" -> listOf(
            QuizPrompt("Which part of the atom has a negative charge?", listOf("Proton", "Electron", "Neutron", "Nucleus"), 1),
            QuizPrompt("Acids turn blue litmus paper to?", listOf("Green", "Red", "Yellow", "White"), 1),
            QuizPrompt("Which law explains action and reaction?", listOf("Newton's first law", "Newton's second law", "Newton's third law", "Ohm's law"), 2),
            QuizPrompt("What is the chemical formula of water?", listOf("CO2", "O2", "H2O", "NaCl"), 2),
            QuizPrompt("Which blood cells help fight infection?", listOf("RBC", "Platelets", "WBC", "Plasma"), 2),
            QuizPrompt("The speed of light is highest in?", listOf("Glass", "Water", "Vacuum", "Air"), 2),
            QuizPrompt("Which organelle is called the powerhouse of the cell?", listOf("Nucleus", "Mitochondria", "Ribosome", "Golgi body"), 1),
            QuizPrompt("A solution with pH 7 is?", listOf("Acidic", "Basic", "Neutral", "Salty"), 2)
        )
        "science_junior" -> listOf(
            QuizPrompt("Which gas do plants mainly use for photosynthesis?", listOf("Oxygen", "Carbon dioxide", "Nitrogen", "Helium"), 1),
            QuizPrompt("The basic unit of life is the?", listOf("Atom", "Tissue", "Cell", "Organ"), 2),
            QuizPrompt("Which planet is known as the Red Planet?", listOf("Venus", "Mars", "Jupiter", "Mercury"), 1),
            QuizPrompt("Water boils at what temperature at sea level?", listOf("90 C", "95 C", "100 C", "110 C"), 2),
            QuizPrompt("Which organ pumps blood through the body?", listOf("Lungs", "Heart", "Brain", "Liver"), 1),
            QuizPrompt("What force pulls objects toward Earth?", listOf("Magnetism", "Friction", "Gravity", "Pressure"), 2),
            QuizPrompt("Which part of the plant absorbs water from soil?", listOf("Leaf", "Stem", "Root", "Flower"), 2),
            QuizPrompt("The Sun is a?", listOf("Planet", "Star", "Asteroid", "Satellite"), 1)
        )
        "math_senior" -> listOf(
            QuizPrompt("What is 25% of 480?", listOf("100", "120", "140", "160"), 1),
            QuizPrompt("Simplify: 3x + 2x - x", listOf("4x", "5x", "6x", "3x"), 0),
            QuizPrompt("If the angles in a triangle are 90, 35, and ?, the missing angle is?", listOf("45", "55", "65", "75"), 1),
            QuizPrompt("What is the value of 7^2 - 5^2?", listOf("14", "20", "24", "28"), 2),
            QuizPrompt("A car travels 180 km in 3 hours. Its speed is?", listOf("50 km/h", "55 km/h", "60 km/h", "65 km/h"), 2),
            QuizPrompt("What is the median of 4, 7, 9, 10, 12?", listOf("7", "8", "9", "10"), 2),
            QuizPrompt("Factorize: x^2 - 9", listOf("(x-9)(x+1)", "(x-3)(x+3)", "(x-1)(x-9)", "(x+9)(x+1)"), 1),
            QuizPrompt("What is the value of pi rounded to two decimals?", listOf("3.12", "3.14", "3.16", "3.18"), 1)
        )
        else -> listOf(
            QuizPrompt("What is 18 x 6?", listOf("96", "108", "124", "88"), 1),
            QuizPrompt("Solve: 3/4 + 1/4", listOf("1/2", "1", "3/8", "2"), 1),
            QuizPrompt("What is the square root of 144?", listOf("11", "13", "12", "14"), 2),
            QuizPrompt("If a triangle has angles 60, 60, 60, it is called?", listOf("Scalene", "Right", "Equilateral", "Obtuse"), 2),
            QuizPrompt("A shop gives 10% off on Rs 500. What is the discount?", listOf("Rs 40", "Rs 60", "Rs 55", "Rs 50"), 3),
            QuizPrompt("What is 15% of 200?", listOf("20", "30", "35", "25"), 1),
            QuizPrompt("Which number is prime?", listOf("21", "27", "29", "33"), 2),
            QuizPrompt("What is the value of 2^5?", listOf("8", "16", "32", "64"), 2)
        )
    }

    fun quizQuestionsFor(mode: String, className: String): List<Triple<String, List<String>, Int>> {
        val stateKey = quizStateKey(mode, className)
        val bank = quizQuestionBank(stateKey)
        if (bank.isEmpty()) return emptyList()
        val rotation = quizStates.firstOrNull { it.mode == stateKey }?.rotation ?: 0
        val start = ((rotation % bank.size) + bank.size) % bank.size
        return List(minOf(5, bank.size)) { offset ->
            val question = bank[(start + offset) % bank.size]
            Triple(question.question, question.options, question.correctIndex)
        }
    }

    fun quizRotation(mode: String, className: String): Int =
        quizStates.firstOrNull { it.mode == quizStateKey(mode, className) }?.rotation ?: 0

    fun completeQuizRound(user: User, mode: String, className: String, score: Int, total: Int): Boolean {
        val stateKey = quizStateKey(mode, className)
        if (stateKey.isBlank()) return false
        val currentIndex = quizStates.indexOfFirst { it.mode == stateKey }
        if (currentIndex < 0) return false
        val bankSize = quizQuestionBank(stateKey).size
        if (bankSize == 0) return false
        val now = System.currentTimeMillis()
        val current = quizStates[currentIndex]
        val shouldRotate = now - current.updatedAt >= QUIZ_ROTATION_WINDOW_MS
        val tierLabel = if (classQuizTier(className) == "senior") "Class 8-10" else "Class 6-7"
        runSharedUpdate(
            type = "quiz_rotation",
            title = "${user.fullName.trim()} finished the ${mode.trim().replaceFirstChar(Char::uppercase)} quiz",
            message = if (shouldRotate) {
                "Leaderboard updated and a new cloud quiz set is ready for $tierLabel students."
            } else {
                "Leaderboard updated. The same cloud quiz set stays active for 24 hours."
            },
            role = Role.STUDENT.name.lowercase(),
            addToGlobalNotifications = false
        ) {
            quizStates[currentIndex] = current.copy(
                rotation = if (shouldRotate) (current.rotation + 1) % bankSize else current.rotation,
                updatedAt = if (shouldRotate) now else current.updatedAt
            )
            val existingIndex = quizLeaderboard.indexOfFirst {
                it.modeKey == stateKey && it.username == user.username
            }
            val newEntry = QuizLeaderboardEntry(
                modeKey = stateKey,
                username = user.username,
                fullName = user.fullName,
                score = score,
                total = total,
                timestamp = System.currentTimeMillis()
            )
            if (existingIndex >= 0) {
                val existing = quizLeaderboard[existingIndex]
                val existingPercent = if (existing.total == 0) 0 else (existing.score * 100) / existing.total
                val newPercent = if (total == 0) 0 else (score * 100) / total
                if (newPercent > existingPercent || (newPercent == existingPercent && score >= existing.score)) {
                    quizLeaderboard[existingIndex] = newEntry
                }
            } else {
                quizLeaderboard.add(newEntry)
            }
            save(KEY_QUIZ_STATES, quizStates)
            save(KEY_QUIZ_LEADERBOARD, quizLeaderboard)
        }
        return shouldRotate
    }

    fun quizLeaderboardRows(mode: String, className: String, currentUsername: String): List<SimpleListItem> {
        val stateKey = quizStateKey(mode, className)
        return quizLeaderboard
            .filter { it.modeKey == stateKey }
            .sortedWith(
                compareByDescending<QuizLeaderboardEntry> {
                    if (it.total == 0) 0 else (it.score * 100) / it.total
                }.thenByDescending { it.score }
                    .thenByDescending { it.timestamp }
            )
            .take(5)
            .mapIndexed { index, entry ->
                val percent = if (entry.total == 0) 0 else (entry.score * 100) / entry.total
                SimpleListItem(
                    title = "${index + 1}. ${entry.fullName}",
                    subtitle = "Score ${entry.score}/${entry.total} | $percent%",
                    badge = if (entry.username == currentUsername) "You" else "${index + 1}"
                )
            }
    }

    private fun homeworkAnnouncement(): List<SimpleListItem> = listOf(
        SimpleListItem("Homework portal active", "Teachers can assign work and students can submit files in-app.", "Update")
    )

    fun facilities(): List<SimpleListItem> = facilityItems.toList()
    fun facilityCards(): List<FacilityCard> = facilityCardItems.toList()
    fun events(): List<SimpleListItem> = eventItems.toList()
    fun notifications(): List<SimpleListItem> {
        ensurePersonalNotificationsSession()
        val currentUsername = SessionManager.currentUser?.username.orEmpty().trim().lowercase()
        val targeted = targetedNotificationItems
            .filter { it.targetUsername == currentUsername }
            .sortedByDescending { it.timestamp }
            .map { SimpleListItem(it.title, it.subtitle, it.badge) }
        return targeted + announcementItems
    }
    fun adminPanelItems(): List<SimpleListItem> =
        adminModuleItems.map { item ->
            when (item.title.lowercase()) {
                "password reset requests" -> item.copy(
                    subtitle = "Review forgot-password requests and issue temporary passwords.",
                    badge = "${passwordResetRequests.size} open"
                )
                else -> item
            }
        }

    fun addManagedItem(mode: String, title: String, subtitle: String, badge: String): Boolean {
        if (title.isBlank() || subtitle.isBlank()) return false
        val normalizedBadge = when {
            mode == "notifications" -> null
            else -> badge.trim().ifBlank { null }
        }
        val item = SimpleListItem(title.trim(), subtitle.trim(), normalizedBadge)
        when (val target = managedItemsForMode(mode)) {
            null -> return false
            else -> {
                runSharedUpdate(
                    type = if (mode == "notifications") "announcement_publish" else mode,
                    title = if (mode == "notifications") title.trim() else "${mode.replaceFirstChar(Char::uppercase)} updated",
                    message = if (mode == "notifications") subtitle.trim() else "${title.trim()} was added.",
                    badge = if (mode == "notifications") "" else "Update"
                ) {
                    target.add(item)
                    save(managedKeyForMode(mode), target)
                }
            }
        }
        return true
    }

    fun updateManagedItem(mode: String, index: Int, title: String, subtitle: String, badge: String): Boolean {
        if (title.isBlank() || subtitle.isBlank()) return false
        val target = managedItemsForMode(mode) ?: return false
        if (index !in target.indices) return false
        runSharedUpdate(
            type = if (mode == "notifications") "announcement_update" else mode,
            title = if (mode == "notifications") title.trim() else "${mode.replaceFirstChar(Char::uppercase)} updated",
            message = if (mode == "notifications") subtitle.trim() else "${title.trim()} was updated.",
            badge = if (mode == "notifications") "" else "Update"
        ) {
            target[index] = SimpleListItem(
                title.trim(),
                subtitle.trim(),
                if (mode == "notifications") null else badge.trim().ifBlank { null }
            )
            save(managedKeyForMode(mode), target)
        }
        return true
    }

    fun deleteManagedItem(mode: String, index: Int): Boolean {
        val target = managedItemsForMode(mode) ?: return false
        if (index !in target.indices) return false
        val removed = target[index]
        runSharedUpdate(
            type = mode,
            title = "${mode.replaceFirstChar(Char::uppercase)} removed",
            message = "${removed.title} was removed from the shared app."
        ) {
            target.removeAt(index)
            save(managedKeyForMode(mode), target)
        }
        return true
    }

    private fun managedItemsForMode(mode: String): MutableList<SimpleListItem>? = when (mode) {
        "facilities" -> facilityItems
        "events" -> eventItems
        "notifications" -> announcementItems
        else -> null
    }

    private fun managedKeyForMode(mode: String): String = when (mode) {
        "facilities" -> "facilities"
        "events" -> "events"
        "notifications" -> KEY_ANNOUNCEMENTS
        else -> error("Unsupported managed mode: $mode")
    }

    fun classItems(user: User): List<SimpleListItem> = when (user.role) {
        Role.ADMIN -> adminClassItems.sortedBy { classOrder(it.title) }
        Role.TEACHER -> availableClasses().sortedBy { classOrder(it) }.map { className ->
            SimpleListItem(
                className,
                "Class teacher: ${teacherNameForClass(className)} | Students: ${studentProfiles.count { it.className == className }}",
                if (className in classesFor(user)) "Assigned" else "View"
            )
        }
        Role.STUDENT -> listOf(
            SimpleListItem(user.className, "Homeroom teacher: Rahul Verma | Section strength: ${studentProfiles.count { it.className == user.className }}", "Current"),
            SimpleListItem("Subject set", "Mathematics, Science, English, Social Science", "Academic")
        )
    }

    fun classOverviewRows(user: User): List<SimpleListItem> = when (user.role) {
        Role.ADMIN -> availableClasses().map { classOverviewItem(it) }
        Role.TEACHER -> classesFor(user).sortedBy { classOrder(it) }.map { classOverviewItem(it) }
        Role.STUDENT -> listOf(
            SimpleListItem(user.className, "Your class records", "Open")
        )
    }

    fun allClassOverviewRows(): List<SimpleListItem> = availableClasses().map { classOverviewItem(it) }

    private fun classOverviewItem(className: String): SimpleListItem {
        val normalizedClass = normalizeClassName(className)
        val subjects = subjectsForClass(normalizedClass).map { it.name }.distinct()
        val subjectSummary = when {
            subjects.isEmpty() -> "Subjects pending"
            subjects.size <= 3 -> subjects.joinToString(", ")
            else -> subjects.take(3).joinToString(", ") + " +${subjects.size - 3} more"
        }
        return SimpleListItem(
            normalizedClass,
            "Class teacher: ${teacherNameForClass(normalizedClass)} | Subjects: $subjectSummary | Students: ${studentProfiles.count { it.className == normalizedClass }}",
            "Open"
        )
    }

    fun addClassItem(title: String, subtitle: String, badge: String): Boolean {
        if (title.isBlank() || subtitle.isBlank()) return false
        val normalizedClass = normalizeClassName(title)
        runSharedUpdate(
            type = "class",
            title = "Class list updated",
            message = "$normalizedClass is now available."
        ) {
            if (adminClassItems.none { it.title == normalizedClass }) {
                adminClassItems.add(SimpleListItem(normalizedClass, subtitle.trim(), badge.trim().ifBlank { "Section" }))
            }
            refreshAdminClassItems()
        }
        return true
    }

    fun profiles(user: User): List<SimpleListItem> = when (user.role) {
        Role.STUDENT -> {
            val profile = studentProfiles.firstOrNull { it.username == user.username }
            if (profile == null) {
                listOf(SimpleListItem(user.fullName, "${user.className} | Profile not added by admin yet.", "Student"))
            } else {
                listOf(
                    SimpleListItem(profile.fullName, "Roll no. ${profile.rollNumber} | ${profile.className} | Parent contact: ${profile.guardianContact}", "Student"),
                    SimpleListItem("Academic focus", profile.notes, "Notes"),
                    SimpleListItem("Attendance summary", attendanceSummaryText(profile.username), "Attendance")
                )
            }
        }
        else -> studentProfiles.map {
            SimpleListItem(it.fullName, "${it.className} | Roll ${it.rollNumber} | Guardian ${it.guardianContact}", "Profile")
        }
    }

    fun addStudentProfile(username: String, password: String = "", fullName: String, className: String, rollNumber: String, guardianContact: String, notes: String, approved: Boolean = true, mobileNumber: String = "", email: String = ""): Boolean {
        if (SessionManager.currentUser?.role != Role.ADMIN || username.isBlank() || fullName.isBlank() || className.isBlank()) return false
        val normalizedUsername = username.trim().lowercase()
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        val normalizedEmail = email.trim().lowercase()
        if (normalizedMobile.isBlank()) return false
        if (normalizedEmail.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) return false
        val normalizedClass = normalizeClassName(className)
        if (normalizedUsername in deletedStudentUsernames || normalizedUsername in deletedAccountUsernames) return false
        if (studentProfiles.any { it.username == normalizedUsername } || users.any { it.username == normalizedUsername }) return false
        runSharedUpdate(
            type = if (approved) "student" else "registration_request",
            title = if (approved) "Student added" else "Student registration",
            message = if (approved) {
                "${fullName.trim()} was added in $normalizedClass."
            } else {
                "${fullName.trim()} registered for a student account in $normalizedClass. Waiting for approval."
            },
            role = if (approved) "" else Role.ADMIN.name.lowercase()
        ) {
            if (approved) {
                approvedAccountUsernames.add(normalizedUsername)
                saveApprovedAccounts()
            }
            studentProfiles.add(
                StudentProfile(
                    username = normalizedUsername,
                    fullName = fullName.trim(),
                    className = normalizedClass,
                    rollNumber = rollNumber.trim(),
                    guardianContact = guardianContact.trim(),
                    notes = notes.trim(),
                    email = normalizedEmail
                )
            )
            if (users.none { it.username == normalizedUsername }) {
                users.add(
                    User(
                        normalizedUsername,
                        password.trim(),
                        Role.STUDENT,
                        fullName.trim(),
                        normalizedClass,
                        classNames = listOf(normalizedClass),
                        approved = approved,
                        mobileNumber = normalizedMobile
                    )
                )
                save("users", users)
            }
            if (attendanceRecords.none { it.studentUsername == normalizedUsername }) {
                attendanceRecords.add(
                    AttendanceRecord(
                        normalizedUsername,
                        fullName.trim(),
                        normalizedClass,
                        teacherUsernameForClass(normalizedClass) ?: "admin",
                        0,
                        0
                    )
                )
                save("attendance", attendanceRecords)
            }
            save("profiles", studentProfiles)
            refreshAdminClassItems()
        }
        return true
    }

    fun updateStudentProfile(
        originalUsername: String,
        username: String,
        fullName: String,
        className: String,
        rollNumber: String,
        guardianContact: String,
        notes: String,
        imageUrl: String? = null,
        email: String? = null
    ): Boolean {
        if (originalUsername.isBlank() || username.isBlank() || fullName.isBlank() || className.isBlank()) return false
        val original = originalUsername.trim().lowercase()
        val normalizedUsername = username.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        val normalizedEmail = email?.trim()?.lowercase()
        val profileIndex = studentProfiles.indexOfFirst { it.username == original }
        if (profileIndex < 0) return false
        if (normalizedUsername != original && users.any { it.username == normalizedUsername }) return false
        if (!normalizedEmail.isNullOrBlank() && !Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) return false

        // replace profile with normalized values
        studentProfiles[profileIndex] = studentProfiles[profileIndex].copy(
            username = normalizedUsername,
            fullName = fullName.trim(),
            className = normalizedClass,
            rollNumber = rollNumber.trim(),
            guardianContact = guardianContact.trim(),
            notes = notes.trim(),
            imageUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: studentProfiles[profileIndex].imageUrl,
            email = normalizedEmail ?: studentProfiles[profileIndex].email.orEmpty()
        )

        users.replaceAll {
            when {
                it.username == original && it.role == Role.STUDENT ->
                User(
                    normalizedUsername,
                    it.password,
                    it.role,
                    fullName.trim(),
                    normalizedClass,
                    classNames = listOf(normalizedClass),
                    it.subject,
                    it.approved,
                    it.mobileNumber,
                    it.profileImageUrl,
                    it.forcePasswordChange,
                    it.qualification,
                    it.experience,
                    it.specialization,
                    it.staffBio
                )
                else -> it
            }
        }
        attendanceRecords.replaceAll {
            when {
                it.studentUsername == original -> it.copy(
                    studentUsername = normalizedUsername,
                    studentName = fullName.trim(),
                    className = normalizedClass,
                    teacherUsername = teacherUsernameForClass(normalizedClass) ?: "admin"
                )
                else -> it
            }
        }
        marksStore.replaceAll {
            when {
                it.studentUsername == original -> it.copy(
                    studentUsername = normalizedUsername,
                    studentName = fullName.trim()
                )
                else -> it
            }
        }
        homeworkItems.replaceAll { item ->
            val updatedSubmissions = item.submissions.map {
                if (it.studentUsername == original) it.copy(studentUsername = normalizedUsername) else it
            }
            item.copy(submissions = updatedSubmissions)
        }

        runSharedUpdate(
            type = "profile",
            title = "Student profile updated",
            message = "${fullName.trim()} profile was updated."
        ) {
            save("profiles", studentProfiles)
            save("users", users)
            save("attendance", attendanceRecords)
            save("marks", marksStore)
            save("homework", homeworkItems)
            refreshAdminClassItems()
        }
        return true
    }

    fun updateStudentProfileImage(username: String, imageUrl: String): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val index = studentProfiles.indexOfFirst { it.username == normalizedUsername }
        if (index < 0 || imageUrl.isBlank()) return false
        runSharedUpdate(
            type = "profile",
            title = "Student photo updated",
            message = "${studentProfiles[index].fullName} profile photo was updated."
        ) {
            studentProfiles[index] = studentProfiles[index].copy(imageUrl = imageUrl.trim())
            save("profiles", studentProfiles)
        }
        return true
    }

    fun deletedStudentArchives(): List<StudentRecoveryArchive> =
        studentRecoveryArchives
            .distinctBy { it.username.trim().lowercase() }
            .sortedByDescending { it.deletedAt }

    private fun archiveStudentForRecovery(username: String) {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) return
        val user = users.firstOrNull { it.username == normalizedUsername && it.role == Role.STUDENT }
        val profile = studentProfiles.firstOrNull { it.username == normalizedUsername }
        if (user == null && profile == null) return
        val archive = StudentRecoveryArchive(
            username = normalizedUsername,
            deletedAt = System.currentTimeMillis(),
            deletedBy = SessionManager.currentUser?.username.orEmpty(),
            user = user,
            profile = profile,
            attendanceRecords = attendanceRecords.filter { it.studentUsername.trim().lowercase() == normalizedUsername },
            dailyAttendanceMarks = dailyAttendanceMarks.filter { it.studentUsername.trim().lowercase() == normalizedUsername },
            marks = marksStore.filter { it.studentUsername.trim().lowercase() == normalizedUsername },
            homeworkItems = homeworkItems.mapNotNull { item ->
                val submissions = item.submissions.filter { it.studentUsername.trim().lowercase() == normalizedUsername }
                if (submissions.isEmpty()) null else item.copy(submissions = submissions)
            }
        )
        studentRecoveryArchives.removeAll { it.username.trim().lowercase() == normalizedUsername }
        studentRecoveryArchives.add(archive)
        save(KEY_STUDENT_RECOVERY_ARCHIVES, studentRecoveryArchives)
    }

    fun recoverDeletedStudent(username: String): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val archiveIndex = studentRecoveryArchives.indexOfFirst { it.username.trim().lowercase() == normalizedUsername }
        if (archiveIndex < 0) return false
        val archive = studentRecoveryArchives[archiveIndex]
        val user = archive.user
        val profile = archive.profile
        if (user == null && profile == null) return false

        runSharedUpdate(
            type = "student_recovery",
            title = "Student recovered",
            message = "${profile?.fullName ?: user?.fullName ?: normalizedUsername} was recovered by admin."
        ) {
            deletedStudentUsernames.remove(normalizedUsername)
            deletedAccountUsernames.remove(normalizedUsername)
            user?.let {
                users.removeAll { existing -> existing.username.trim().lowercase() == normalizedUsername }
                users.add(it.copy(username = normalizedUsername, approved = true))
                approvedAccountUsernames.add(normalizedUsername)
            }
            profile?.let {
                studentProfiles.removeAll { existing -> existing.username.trim().lowercase() == normalizedUsername }
                studentProfiles.add(it.copy(username = normalizedUsername))
            }
            archive.attendanceRecords.forEach { record ->
                attendanceRecords.removeAll {
                    it.studentUsername.trim().lowercase() == normalizedUsername &&
                        it.className == record.className
                }
                attendanceRecords.add(record.copy(studentUsername = normalizedUsername))
            }
            archive.dailyAttendanceMarks.forEach { mark ->
                dailyAttendanceMarks.removeAll {
                    it.studentUsername.trim().lowercase() == normalizedUsername &&
                        it.className == mark.className &&
                        it.date == mark.date
                }
                dailyAttendanceMarks.add(mark.copy(studentUsername = normalizedUsername))
            }
            archive.marks.forEach { mark ->
                marksStore.removeAll {
                    it.studentUsername.trim().lowercase() == normalizedUsername &&
                        it.subject.equals(mark.subject, true) &&
                        it.assessment.equals(mark.assessment, true)
                }
                marksStore.add(mark.copy(studentUsername = normalizedUsername))
            }
            archive.homeworkItems.forEach { archivedHomework ->
                val existingIndex = homeworkItems.indexOfFirst { it.id == archivedHomework.id }
                if (existingIndex >= 0) {
                    val current = homeworkItems[existingIndex]
                    val restoredSubmissions = current.submissions
                        .filterNot { it.studentUsername.trim().lowercase() == normalizedUsername } +
                        archivedHomework.submissions.map { it.copy(studentUsername = normalizedUsername) }
                    homeworkItems[existingIndex] = current.copy(submissions = restoredSubmissions)
                } else {
                    homeworkItems.add(archivedHomework)
                }
            }
            studentRecoveryArchives.removeAt(archiveIndex)
            saveDeletedStudents()
            saveDeletedAccounts()
            saveApprovedAccounts()
            save("users", users)
            save("profiles", studentProfiles)
            save("attendance", attendanceRecords)
            save(KEY_DAILY_ATTENDANCE, dailyAttendanceMarks)
            save("marks", marksStore)
            save("homework", homeworkItems)
            save(KEY_STUDENT_RECOVERY_ARCHIVES, studentRecoveryArchives)
            refreshAdminClassItems()
        }
        return true
    }

    fun deleteStudent(username: String): Boolean {
        val normalizedUsername = username.trim().lowercase()
        fun sameStudent(value: String): Boolean = value.trim().lowercase() == normalizedUsername

        val removedProfile = studentProfiles.firstOrNull { sameStudent(it.username) }
        val removedUser = users.firstOrNull { sameStudent(it.username) && it.role == Role.STUDENT }
        if (removedProfile == null && removedUser == null) return false
        archiveStudentForRecovery(normalizedUsername)
        deletedStudentUsernames.add(normalizedUsername)
        deletedAccountUsernames.add(normalizedUsername)
        approvedAccountUsernames.remove(normalizedUsername)
        studentProfiles.removeAll { sameStudent(it.username) }
        users.removeAll { sameStudent(it.username) && it.role == Role.STUDENT }
        attendanceRecords.removeAll { sameStudent(it.studentUsername) }
        marksStore.removeAll { sameStudent(it.studentUsername) }
        homeworkItems.replaceAll { item ->
            item.copy(submissions = item.submissions.filterNot { submission -> sameStudent(submission.studentUsername) })
        }
        runSharedUpdate(
            type = "student",
            title = "Student removed",
            message = "${removedProfile?.fullName ?: removedUser?.fullName ?: normalizedUsername} was removed from the school app.",
            targetUsername = normalizedUsername,
            addToGlobalNotifications = false
        ) {
            saveDeletedStudents()
            saveDeletedAccounts()
            saveApprovedAccounts()
            save("profiles", studentProfiles)
            save("users", users)
            save("attendance", attendanceRecords)
            save("marks", marksStore)
            save("homework", homeworkItems)
            refreshAdminClassItems()
        }
        return true
    }

    fun subjectItems(): List<SubjectItem> = subjectItems.sortedWith(compareBy({ classOrder(it.className) }, { it.name }))

    /** Turns legacy spellings and combined subject strings into one clean list. */
    fun subjectDisplayNames(raw: String): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val known = Regex("(?i)social\\s+science|mathematics|sanskrit|english|hindi|science|math|evs|s\\.?st|sst|skt")
        val matches = known.findAll(text).map { it.value }.toList()
        val values = if (matches.size >= 2) matches else text.split(',', ';', '/').map { it.trim() }
        return values.mapNotNull { value ->
            when (value.trim().lowercase().replace(".", "")) {
                "math", "mathematics" -> "Mathematics"
                "sst", "social science" -> "Social Science"
                "skt", "sanskrit" -> "Sanskrit"
                "evs" -> "EVS"
                "english" -> "English"
                "hindi" -> "Hindi"
                "science" -> "Science"
                else -> value.trim().takeIf { it.isNotBlank() }
            }
        }.distinctBy { it.lowercase() }
    }

    fun subjectsForClass(className: String): List<SubjectItem> {
        val assigned = subjectItems
            .filter { it.className == className }
            .flatMap { item ->
                subjectDisplayNames(item.name).map { item.copy(name = it) }
            }
        val allSchoolSubjects = subjectItems.flatMap { item ->
            subjectDisplayNames(item.name)
                .map { SubjectItem(it, normalizeClassName(className), item.teacherName) }
        }
        val standardSubjects = listOf("English", "Hindi", "Mathematics", "Science", "Social Science", "EVS", "S.st", "Sanskrit")
            .map { SubjectItem(it, normalizeClassName(className), "Assigned later") }
        return (assigned + allSchoolSubjects + standardSubjects)
            .distinctBy { it.name.lowercase() }
    }

    fun availableClasses(): List<String> {
        val supportedClasses = defaultClasses().toSet()
        val combined = (defaultClasses() + adminClassItems.map { it.title } + studentProfiles.map { it.className })
            .map { normalizeClassName(it) }
            .filter { it in supportedClasses }
            .distinct()
        return combined.sortedBy { classOrder(it) }
    }

    fun availableTeacherClasses(): List<String> {
        return availableClasses().filter { teacherNameForClass(it) == "Assigned later" }
    }

    fun addSubject(name: String, className: String, teacherName: String): Boolean {
        if (name.isBlank() || className.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        val exists = subjectItems.any { it.name.equals(name.trim(), true) && it.className == normalizedClass }
        if (exists) return false
        runSharedUpdate(
            type = "subject",
            title = "Subject added",
            message = "${name.trim()} was assigned for $normalizedClass."
        ) {
            subjectItems.add(SubjectItem(name.trim(), normalizedClass, teacherName.trim().ifBlank { "Assigned later" }))
            save("subjects", subjectItems)
            refreshAdminClassItems()
        }
        return true
    }

    fun deleteSubject(name: String, className: String): Boolean {
        if (name.isBlank() || className.isBlank()) return false
        val normalizedName = name.trim()
        val normalizedClass = normalizeClassName(className)
        val removedAny = subjectItems.removeAll {
            it.className == normalizedClass && it.name.equals(normalizedName, true)
        }
        if (!removedAny) return false

        timetableItems.removeAll {
            it.className == normalizedClass && it.subject.equals(normalizedName, true)
        }

        val subjectStillUsed = subjectItems.any { it.name.equals(normalizedName, true) } ||
            timetableItems.any { it.subject.equals(normalizedName, true) }
        if (!subjectStillUsed) {
            timetableSubjectItems.removeAll { it.equals(normalizedName, true) }
        }

        runSharedUpdate(
            type = "subject",
            title = "Subject removed",
            message = "$normalizedName was removed from $normalizedClass."
        ) {
            save("subjects", subjectItems)
            save("timetable", timetableItems)
            save(KEY_TIMETABLE_SUBJECTS, timetableSubjectItems)
            refreshAdminClassItems()
        }
        return true
    }

    fun attendanceFor(user: User): List<AttendanceRecord> = when (user.role) {
        Role.ADMIN -> attendanceRecords.toList()
        Role.TEACHER -> attendanceRecords.filter { it.className in classesFor(user) }
        Role.STUDENT -> attendanceRecords.filter { it.studentUsername == user.username }
    }

    fun recordAttendance(@Suppress("UNUSED_PARAMETER") user: User): Boolean {
        return false
    }

    fun addAttendanceRecord(studentUsername: String, studentName: String, className: String, presentDays: Int, totalDays: Int): Boolean {
        if (studentUsername.isBlank() || studentName.isBlank() || className.isBlank()) return false
        val normalizedUsername = studentUsername.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        runSharedUpdate(
            type = "attendance",
            title = "Attendance updated",
            message = "Attendance for ${studentName.trim()} was updated in $normalizedClass.",
            className = normalizedClass
        ) {
            attendanceRecords.removeAll { it.studentUsername == normalizedUsername }
            attendanceRecords.add(AttendanceRecord(normalizedUsername, studentName.trim(), normalizedClass, teacherUsernameForClass(normalizedClass) ?: "admin", presentDays, totalDays))
            ensureStudentShell(normalizedUsername, studentName.trim(), normalizedClass, "Added from attendance by admin.")
            save("attendance", attendanceRecords)
            refreshAdminClassItems()
        }
        return true
    }

    fun markDailyAttendance(user: User, studentUsername: String, className: String, present: Boolean): Boolean {
        if (user.role == Role.STUDENT || studentUsername.isBlank() || className.isBlank()) return false
        val normalizedUsername = studentUsername.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        if (!canMarkAttendanceForClass(user, normalizedClass)) return false

        val record = attendanceRecords.firstOrNull {
            it.studentUsername == normalizedUsername && it.className == normalizedClass
        } ?: return false

        val today = todayStamp()
        if (dailyAttendanceMarks.any {
                it.studentUsername == normalizedUsername &&
                    it.className == normalizedClass &&
                    it.date == today
            }) return false

        runSharedUpdate(
            type = "attendance",
            title = "Daily attendance marked",
            message = "${record.studentName} was marked ${if (present) "present" else "absent"} for today.",
            className = normalizedClass
        ) {
            record.totalDays += 1
            if (present) {
                record.presentDays += 1
            }
            dailyAttendanceMarks.add(
                DailyAttendanceMark(
                    studentUsername = normalizedUsername,
                    className = normalizedClass,
                    date = today,
                    present = present,
                    markedBy = user.username,
                    updatedAt = System.currentTimeMillis()
                )
            )
            save("attendance", attendanceRecords)
            save(KEY_DAILY_ATTENDANCE, dailyAttendanceMarks)
        }
        return true
    }

    fun markDailyAttendanceBatch(user: User, className: String, marks: Map<String, Boolean>): Int {
        if (user.role == Role.STUDENT || className.isBlank() || marks.isEmpty()) return 0
        val normalizedClass = normalizeClassName(className)
        if (!canMarkAttendanceForClass(user, normalizedClass)) return 0

        val today = todayStamp()
        val allowedRecords = attendanceRecords
            .filter { it.className == normalizedClass }
            .associateBy { it.studentUsername }

        val pendingMarks = marks
            .mapKeys { it.key.trim().lowercase() }
            .filterKeys { username ->
                allowedRecords.containsKey(username) &&
                    dailyAttendanceMarks.none {
                        it.studentUsername == username &&
                            it.className == normalizedClass &&
                            it.date == today
                    }
            }

        if (pendingMarks.isEmpty()) return 0

        runSharedUpdate(
            type = "attendance",
            title = "Daily attendance marked",
            message = "${pendingMarks.size} students were marked for $normalizedClass.",
            className = normalizedClass
        ) {
            pendingMarks.forEach { (username, present) ->
                val record = allowedRecords[username] ?: return@forEach
                record.totalDays += 1
                if (present) {
                    record.presentDays += 1
                }
                dailyAttendanceMarks.add(
                    DailyAttendanceMark(
                        studentUsername = username,
                        className = normalizedClass,
                        date = today,
                        present = present,
                        markedBy = user.username,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            save("attendance", attendanceRecords)
            save(KEY_DAILY_ATTENDANCE, dailyAttendanceMarks)
        }
        return pendingMarks.size
    }

    /**
     * Mirrors a successful Flask attendance write in this device's private cache.
     * This deliberately does not use the old shared Firestore write path: attendance
     * remains profile-private and the Render API is the source of truth.
     */
    fun cacheServerAttendanceBatch(className: String, marks: Map<String, Boolean>, date: String = todayStamp()) {
        val normalizedClass = normalizeClassName(className)
        val normalizedMarks = marks.mapKeys { it.key.trim().lowercase() }.filterKeys { it.isNotBlank() }
        if (normalizedClass.isBlank() || normalizedMarks.isEmpty()) return
        normalizedMarks.forEach { (username, present) ->
            dailyAttendanceMarks.removeAll {
                it.studentUsername == username && it.className == normalizedClass && it.date == date
            }
            dailyAttendanceMarks.add(
                DailyAttendanceMark(
                    studentUsername = username,
                    className = normalizedClass,
                    date = date,
                    present = present,
                    markedBy = "server",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        persistValue(KEY_DAILY_ATTENDANCE, dailyAttendanceMarks)
        notifyDataChanged()
    }

    fun wasAttendanceMarkedToday(studentUsername: String, className: String): Boolean {
        val normalizedUsername = studentUsername.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        val today = todayStamp()
        return dailyAttendanceMarks.any {
            it.studentUsername == normalizedUsername &&
                it.className == normalizedClass &&
                it.date == today
        }
    }

    fun attendanceStatusLabel(studentUsername: String, className: String): String {
        val normalizedUsername = studentUsername.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        val today = todayStamp()
        val mark = dailyAttendanceMarks.firstOrNull {
            it.studentUsername == normalizedUsername &&
                it.className == normalizedClass &&
                it.date == today
        } ?: return "Pending today"
        return if (mark.present) "Present today" else "Absent today"
    }

    fun attendanceHistoryForStudent(username: String): List<DailyAttendanceMark> {
        if (hasPrivateAcademicData() && username.equals(SessionManager.currentUser?.username, true)) {
            return privateAttendanceItems.sortedByDescending { it.date }
        }
        val normalizedUsername = username.trim().lowercase()
        return dailyAttendanceMarks
            .filter { it.studentUsername == normalizedUsername }
            .sortedByDescending { it.date }
    }

    fun attendanceHistoryForStudent(username: String, className: String): List<DailyAttendanceMark> {
        if (hasPrivateAcademicData() && username.equals(SessionManager.currentUser?.username, true)) {
            return privateAttendanceItems
                .filter { it.className.equals(className, true) }
                .sortedByDescending { it.date }
        }
        val normalizedUsername = username.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        return dailyAttendanceMarks
            .filter { it.studentUsername == normalizedUsername && it.className == normalizedClass }
            .sortedByDescending { it.date }
    }

    fun attendanceDatesForClass(className: String): List<String> {
        val normalizedClass = normalizeClassName(className)
        return dailyAttendanceMarks
            .filter { it.className == normalizedClass }
            .map { it.date }
            .distinct()
            .sortedDescending()
    }

    fun attendanceMarkForDate(studentUsername: String, className: String, date: String): DailyAttendanceMark? {
        val normalizedUsername = studentUsername.trim().lowercase()
        val normalizedClass = normalizeClassName(className)
        return dailyAttendanceMarks.firstOrNull {
            it.studentUsername == normalizedUsername &&
                it.className == normalizedClass &&
                it.date == date
        }
    }

    fun updateDailyAttendanceBatch(user: User, className: String, date: String, marks: Map<String, Boolean>): Int {
        if (user.role == Role.STUDENT || className.isBlank() || date.isBlank() || marks.isEmpty()) return 0
        val normalizedClass = normalizeClassName(className)
        if (!canMarkAttendanceForClass(user, normalizedClass)) return 0

        val allowedRecords = attendanceRecords
            .filter { it.className == normalizedClass }
            .associateBy { it.studentUsername }
        val normalizedMarks = marks.mapKeys { it.key.trim().lowercase() }
            .filterKeys { allowedRecords.containsKey(it) }
        if (normalizedMarks.isEmpty()) return 0

        var changed = 0
        runSharedUpdate(
            type = "attendance",
            title = "Attendance corrected",
            message = "$normalizedClass attendance was edited for $date.",
            className = normalizedClass
        ) {
            normalizedMarks.forEach { (username, present) ->
                val index = dailyAttendanceMarks.indexOfFirst {
                    it.studentUsername == username &&
                        it.className == normalizedClass &&
                        it.date == date
                }
                if (index < 0) return@forEach
                val old = dailyAttendanceMarks[index]
                if (old.present == present) return@forEach
                val record = allowedRecords[username] ?: return@forEach
                record.presentDays = (record.presentDays + if (present) 1 else -1).coerceAtLeast(0)
                dailyAttendanceMarks[index] = old.copy(
                    present = present,
                    markedBy = user.username,
                    updatedAt = System.currentTimeMillis()
                )
                changed += 1
            }
            if (changed > 0) {
                save("attendance", attendanceRecords)
                save(KEY_DAILY_ATTENDANCE, dailyAttendanceMarks)
            }
        }
        return changed
    }

    private fun attendanceSummaryNumbers(username: String, className: String? = null): Pair<Int, Int> {
        val normalizedUsername = username.trim().lowercase()
        val filteredHistory = dailyAttendanceMarks.filter {
            it.studentUsername == normalizedUsername &&
                (className == null || it.className == normalizeClassName(className))
        }
        if (filteredHistory.isNotEmpty()) {
            val totalDays = filteredHistory.size
            val presentDays = filteredHistory.count { it.present }
            return presentDays to totalDays
        }
        val record = attendanceForStudent(normalizedUsername) ?: return 0 to 0
        return record.presentDays to record.totalDays
    }

    fun attendanceSummaryText(username: String): String {
        val (presentDays, totalDays) = attendanceSummaryNumbers(username)
        return if (totalDays == 0) {
            "Attendance not added yet"
        } else {
            val percent = ((presentDays.toDouble() / totalDays.toDouble()) * 100).toInt()
            "Total classes held: $totalDays\nPresent on: $presentDays days\nAttendance: $percent%"
        }
    }

    fun latestAttendanceStatusLabel(username: String, className: String): String {
        val latest = attendanceHistoryForStudent(username, className).firstOrNull() ?: return "No saved record"
        return "${latest.date} - ${if (latest.present) "Present" else "Absent"}"
    }

    private fun todayStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Refresh private academic data from Flask for the active school profile. */
    fun refreshPrivateAcademicContent(onComplete: (Boolean) -> Unit = {}) {
        val profileId = SessionManager.activeProfileId ?: return onComplete(false)
        val user = SessionManager.currentUser ?: return onComplete(false)
        val pending = AtomicInteger(if (user.role == Role.STUDENT) 4 else 2)
        var success = true
        fun finish(result: Boolean) {
            success = success && result
            if (pending.decrementAndGet() == 0) {
                Handler(Looper.getMainLooper()).post {
                    if (privateAcademicProfileId == profileId) notifyDataChanged()
                    onComplete(success)
                }
            }
        }
        privateAcademicProfileId = profileId
        MobileAcademicGateway.homework { result ->
            result.onSuccess { rows ->
                if (privateAcademicProfileId == profileId) {
                    privateHomeworkItems = rows.map { row ->
                        HomeworkItem(
                            id = row.id, className = row.className, subject = row.subject,
                            teacherUsername = if (user.role == Role.TEACHER) user.username else row.teacher, title = row.title,
                            description = listOf(row.description, row.instructions.takeIf { it.isNotBlank() })
                                .filterNotNull().joinToString("\n"), dueDate = row.dueDate,
                            attachmentName = row.attachments.firstOrNull()?.name,
                            attachmentNames = row.attachments.map { it.name },
                            attachmentIds = row.attachments.map { it.id }
                        )
                    }
                }
            }
            finish(result.isSuccess)
        }
        MobileAcademicGateway.tests { result ->
            result.onSuccess { if (privateAcademicProfileId == profileId) privateTestItems = it }
            finish(result.isSuccess)
        }
        if (user.role != Role.STUDENT) return
        MobileAcademicGateway.marks { result ->
            result.onSuccess { rows ->
                if (privateAcademicProfileId == profileId) {
                    privateMarksItems = rows.map { row ->
                        MarkItem(user.username, user.fullName, row.subject, row.score, row.outOf, row.assessment)
                    }
                }
            }
            finish(result.isSuccess)
        }
        MobileAcademicGateway.attendance { result ->
            result.onSuccess { rows ->
                if (privateAcademicProfileId == profileId) {
                    privateAttendanceItems = rows.map { row ->
                        DailyAttendanceMark(user.username, row.className, row.date, row.present, row.subject)
                    }
                }
            }
            finish(result.isSuccess)
        }
    }

    fun privateTestsForActiveProfile(): List<MobileAcademicGateway.Test> =
        if (privateAcademicProfileId == SessionManager.activeProfileId) privateTestItems else emptyList()

    private fun hasPrivateAcademicData(): Boolean = privateAcademicProfileId == SessionManager.activeProfileId

    fun homeworkFor(user: User): List<HomeworkItem> = when (user.role) {
        Role.ADMIN, Role.TEACHER -> if (hasPrivateAcademicData()) privateHomeworkItems else homeworkItems.sortedBy { it.id }
        Role.STUDENT -> if (hasPrivateAcademicData()) privateHomeworkItems else homeworkItems.filter { it.className == user.className }
    }

    fun homeworkForStudent(username: String): List<HomeworkItem> {
        if (hasPrivateAcademicData() && username.equals(SessionManager.currentUser?.username, true)) {
            return privateHomeworkItems.sortedBy { it.dueDate }
        }
        val profile = profileFor(username) ?: return emptyList()
        return homeworkItems
            .filter { it.className == profile.className }
            .sortedBy { it.dueDate }
    }

    fun homeworkSummaryText(username: String): String {
        val items = homeworkForStudent(username)
        if (items.isEmpty()) return "No homework assigned yet"
        val normalizedUsername = username.trim().lowercase()
        val submitted = items.count { item -> item.submissions.any { it.studentUsername == normalizedUsername } }
        val pending = items.size - submitted
        return "Homework assigned: ${items.size}\nSubmitted: $submitted\nPending: $pending"
    }

    fun addHomework(user: User, className: String, subject: String, title: String, description: String, dueDate: String, attachmentName: String?, attachmentUrl: String?): Boolean =
        addHomework(user, className, subject, title, description, dueDate, listOfNotNull(attachmentName), listOfNotNull(attachmentUrl))

    fun addHomework(user: User, className: String, subject: String, title: String, description: String, dueDate: String, attachmentNames: List<String>, attachmentUrls: List<String>): Boolean {
        if ((user.role != Role.TEACHER && user.role != Role.ADMIN) || subject.isBlank() || title.isBlank() || description.isBlank() || dueDate.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        if (user.role == Role.TEACHER && !classExists(normalizedClass)) return false
        val nextId = (homeworkItems.maxOfOrNull { it.id } ?: 0) + 1
        val cleanNames = attachmentNames.map { it.trim() }.filter { it.isNotBlank() }
        val cleanUrls = attachmentUrls.map { it.trim() }.filter { it.isNotBlank() }
        runSharedUpdate(
            type = "homework_publish",
            title = title.trim(),
            message = "$normalizedClass - ${subject.trim()} - Due ${dueDate.trim()}",
            role = Role.STUDENT.name.lowercase(),
            className = normalizedClass
        ) {
            homeworkItems.add(
                HomeworkItem(
                    id = nextId,
                    className = normalizedClass,
                    subject = subject.trim(),
                    teacherUsername = user.username,
                    title = title.trim(),
                    description = description.trim(),
                    dueDate = dueDate.trim(),
                    attachmentName = cleanNames.firstOrNull(),
                    attachmentUrl = cleanUrls.firstOrNull(),
                    attachmentNames = cleanNames,
                    attachmentUrls = cleanUrls
                )
            )
            save("homework", homeworkItems)
        }
        return true
    }

    fun updateHomework(
        user: User,
        homeworkId: Int,
        className: String,
        subject: String,
        title: String,
        description: String,
        dueDate: String,
        attachmentName: String?,
        attachmentUrl: String?
    ): Boolean = updateHomework(user, homeworkId, className, subject, title, description, dueDate, listOfNotNull(attachmentName), listOfNotNull(attachmentUrl))

    fun updateHomework(
        user: User,
        homeworkId: Int,
        className: String,
        subject: String,
        title: String,
        description: String,
        dueDate: String,
        attachmentNames: List<String>,
        attachmentUrls: List<String>
    ): Boolean {
        if ((user.role != Role.TEACHER && user.role != Role.ADMIN) || subject.isBlank() || title.isBlank() || description.isBlank() || dueDate.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        if (user.role == Role.TEACHER && !classExists(normalizedClass)) return false
        val index = homeworkItems.indexOfFirst { it.id == homeworkId && it.className == normalizedClass }
        if (index < 0) return false
        if (user.role == Role.TEACHER && homeworkItems[index].teacherUsername != user.username) return false
        val cleanNames = attachmentNames.map { it.trim() }.filter { it.isNotBlank() }
        val cleanUrls = attachmentUrls.map { it.trim() }.filter { it.isNotBlank() }
        runSharedUpdate(
            type = "homework_publish",
            title = title.trim(),
            message = "$normalizedClass - ${subject.trim()} - Due ${dueDate.trim()}",
            role = Role.STUDENT.name.lowercase(),
            className = normalizedClass
        ) {
            val current = homeworkItems[index]
            homeworkItems[index] = current.copy(
                subject = subject.trim(),
                title = title.trim(),
                description = description.trim(),
                dueDate = dueDate.trim(),
                attachmentName = cleanNames.firstOrNull(),
                attachmentUrl = cleanUrls.firstOrNull(),
                attachmentNames = cleanNames,
                attachmentUrls = cleanUrls
            )
            save("homework", homeworkItems)
        }
        return true
    }

    fun deleteHomework(user: User, homeworkId: Int): Boolean {
        val index = homeworkItems.indexOfFirst { it.id == homeworkId }
        if (index < 0) return false
        val item = homeworkItems[index]
        if (user.role == Role.TEACHER && !classExists(item.className)) return false
        if (user.role == Role.TEACHER && item.teacherUsername != user.username) return false
        runSharedUpdate(
            type = "homework",
            title = "Homework removed",
            message = "${item.title} was removed from ${item.className}.",
            role = Role.STUDENT.name.lowercase(),
            className = item.className
        ) {
            homeworkItems.removeAt(index)
            save("homework", homeworkItems)
        }
        return true
    }

    fun classExists(className: String): Boolean {
        val normalizedClass = normalizeClassName(className)
        return availableClasses().any { it == normalizedClass }
    }

    fun canAccessClass(user: User, className: String): Boolean {
        val normalizedClass = normalizeClassName(className)
        return when (user.role) {
            Role.ADMIN -> classExists(normalizedClass)
            Role.TEACHER -> normalizedClass in classesFor(user)
            Role.STUDENT -> normalizeClassName(user.className) == normalizedClass
        }
    }

    fun canMarkAttendanceForClass(user: User, className: String): Boolean {
        val normalizedClass = normalizeClassName(className)
        return when (user.role) {
            Role.ADMIN -> classExists(normalizedClass)
            Role.TEACHER -> normalizedClass in classesFor(user)
            Role.STUDENT -> false
        }
    }

    fun submitHomework(user: User, homeworkId: Int, fileName: String?, fileUrl: String? = null): Boolean =
        submitHomework(user, homeworkId, listOfNotNull(fileName), listOfNotNull(fileUrl))

    fun submitHomework(user: User, homeworkId: Int, fileNames: List<String>, fileUrls: List<String>): Boolean {
        val cleanNames = fileNames.map { it.trim() }.filter { it.isNotBlank() }
        val cleanUrls = fileUrls.map { it.trim() }.filter { it.isNotBlank() }
        if (user.role != Role.STUDENT || cleanNames.isEmpty()) return false
        val itemIndex = homeworkItems.indexOfFirst { it.id == homeworkId && it.className == user.className }
        if (itemIndex < 0) return false
        val item = homeworkItems[itemIndex]
        val teacherTarget = item.teacherUsername.ifBlank { teacherUsernameForClass(item.className).orEmpty() }
        runSharedUpdate(
            type = "homework_submission",
            title = "Homework submitted",
            message = "${user.fullName} submitted ${item.title}.",
            role = Role.TEACHER.name.lowercase(),
            className = item.className,
            targetUsername = teacherTarget,
            addToGlobalNotifications = false
        ) {
            val updatedSubmissions = item.submissions
                .filterNot { it.studentUsername == user.username }
                .plus(
                    com.schoolms.mobile.data.HomeworkSubmission(
                        studentUsername = user.username,
                        fileName = cleanNames.firstOrNull().orEmpty(),
                        fileUrl = cleanUrls.firstOrNull(),
                        fileNames = cleanNames,
                        fileUrls = cleanUrls,
                        submittedAt = System.currentTimeMillis()
                    )
                )
            homeworkItems[itemIndex] = item.copy(submissions = updatedSubmissions)
            save("homework", homeworkItems)
        }
        return true
    }

    fun marksFor(user: User): List<MarkItem> = when (user.role) {
        Role.ADMIN -> marksStore.toList()
        Role.TEACHER -> marksStore.toList()
        Role.STUDENT -> if (hasPrivateAcademicData()) privateMarksItems else marksStore.filter { it.studentUsername == user.username }
    }

    fun addMark(user: User, studentUsername: String, studentName: String, subject: String, score: Int, outOf: Int, assessment: String = "Class Test 1"): Boolean {
        if (studentUsername.isBlank() || studentName.isBlank() || subject.isBlank() || outOf <= 0) return false
        val normalizedUsername = studentUsername.trim().lowercase()
        val profile = studentProfiles.firstOrNull { it.username == normalizedUsername } ?: return false
        if (user.role == Role.TEACHER && !classExists(profile.className)) return false
        val normalizedSubject = subject.trim()
        val normalizedAssessment = assessment.trim().ifBlank { "Class Test 1" }
        if (subjectsForClass(profile.className).none { it.name.equals(normalizedSubject, true) }) return false
        runSharedUpdate(
            type = "marks",
            title = "Marks updated",
            message = "$normalizedSubject ($normalizedAssessment) marks for ${studentName.trim()} were updated.",
            className = profile.className,
            targetUsername = normalizedUsername,
            addToGlobalNotifications = false
        ) {
            marksStore.removeAll {
                it.studentUsername == normalizedUsername &&
                    it.subject.equals(normalizedSubject, true) &&
                    it.assessment.equals(normalizedAssessment, true)
            }
            marksStore.add(MarkItem(normalizedUsername, studentName.trim(), normalizedSubject, score, outOf, normalizedAssessment))
            save("marks", marksStore)
        }
        return true
    }

    fun deleteMark(user: User, studentUsername: String, subject: String, assessment: String): Boolean {
        if (user.role == Role.STUDENT || studentUsername.isBlank() || subject.isBlank() || assessment.isBlank()) return false
        val normalizedUsername = studentUsername.trim().lowercase()
        val profile = studentProfiles.firstOrNull { it.username == normalizedUsername } ?: return false
        if (user.role == Role.TEACHER && !classExists(profile.className)) return false
        val normalizedSubject = subject.trim()
        val normalizedAssessment = assessment.trim()
        val exists = marksStore.any {
            it.studentUsername == normalizedUsername &&
                it.subject.equals(normalizedSubject, true) &&
                it.assessment.equals(normalizedAssessment, true)
        }
        if (!exists) return false
        runSharedUpdate(
            type = "marks",
            title = "Marks removed",
            message = "$normalizedSubject ($normalizedAssessment) marks for ${profile.fullName} were removed.",
            className = profile.className,
            targetUsername = normalizedUsername,
            addToGlobalNotifications = false
        ) {
            marksStore.removeAll {
                it.studentUsername == normalizedUsername &&
                    it.subject.equals(normalizedSubject, true) &&
                    it.assessment.equals(normalizedAssessment, true)
            }
            save("marks", marksStore)
        }
        return true
    }

    fun timetableFor(user: User): List<TimetableSlot> {
        return timetableItems.toList()
    }

    fun addTimetableSlot(user: User, day: String, time: String, subject: String, className: String, room: String): Boolean {
        if (user.role != Role.ADMIN || day.isBlank() || time.isBlank() || subject.isBlank() || className.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        val roomLabel = room.trim().ifBlank { normalizedClass }
        val scopedRoom = if (roomLabel.contains(normalizedClass, true)) roomLabel else "$normalizedClass - $roomLabel"
        runSharedUpdate(
            type = "timetable",
            title = "Timetable updated",
            message = "${subject.trim()} was added to the $normalizedClass timetable."
        ) {
            ensureTimetableMatrixClass(normalizedClass)
            ensureTimetableMatrixSubject(subject.trim())
            ensureTimetableMatrixTime(time.trim())
            timetableItems.removeAll {
                it.className == normalizedClass && it.time.equals(time.trim(), true)
            }
            timetableItems.add(TimetableSlot(normalizedClass, day.trim(), time.trim(), subject.trim(), scopedRoom))
            save("timetable", timetableItems)
        }
        return true
    }

    fun timetableMatrixClasses(user: User): List<String> =
        (timetableClassItems.ifEmpty { seedTimetableClasses() })
            .map { className: String -> normalizeClassName(className) }
            .distinct()

    fun timetableMatrixSubjects(user: User): List<String> =
        (timetableSubjectItems.ifEmpty { seedTimetableSubjects() })
            .map { subject: String -> subject.trim() }
            .distinct()

    fun timetableMatrixTimes(user: User): List<String> =
        (timetableTimeItems.ifEmpty { seedTimetableTimes() })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    fun timetableCellFor(className: String, time: String): TimetableSlot? {
        val normalizedClass = normalizeClassName(className)
        return timetableItems.firstOrNull {
            it.className == normalizedClass && it.time.equals(time.trim(), true)
        }
    }

    fun updateTimetableCell(user: User, className: String, subject: String, day: String, time: String, room: String): Boolean {
        if (user.role != Role.ADMIN || className.isBlank() || subject.isBlank() || day.isBlank() || time.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        val normalizedSubject = subject.trim()
        val normalizedTime = time.trim()
        runSharedUpdate(
            type = "timetable",
            title = "Timetable updated",
            message = "$normalizedClass - $normalizedTime timetable entry was updated."
        ) {
            ensureTimetableMatrixClass(normalizedClass)
            ensureTimetableMatrixSubject(normalizedSubject)
            ensureTimetableMatrixTime(normalizedTime)
            timetableItems.removeAll {
                it.className == normalizedClass && it.time.equals(normalizedTime, true)
            }
            timetableItems.add(
                TimetableSlot(
                    className = normalizedClass,
                    day = day.trim(),
                    time = normalizedTime,
                    subject = normalizedSubject,
                    room = room.trim().ifBlank { normalizedClass }
                )
            )
            save("timetable", timetableItems)
        }
        return true
    }

    fun removeTimetableCell(user: User, className: String, time: String): Boolean {
        if (user.role != Role.ADMIN || className.isBlank() || time.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        val normalizedTime = time.trim()
        val removed = timetableItems.removeAll {
            it.className == normalizedClass && it.time.equals(normalizedTime, true)
        }
        if (!removed) return false
        runSharedUpdate(
            type = "timetable",
            title = "Timetable updated",
            message = "$normalizedClass - $normalizedTime was removed from the timetable."
        ) {
            save("timetable", timetableItems)
        }
        return true
    }

    fun addTimetableMatrixClass(user: User, className: String): Boolean {
        if (user.role != Role.ADMIN || className.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        if (timetableClassItems.any { normalizeClassName(it) == normalizedClass }) return false
        runSharedUpdate(
            type = "timetable",
            title = "Timetable class added",
            message = "$normalizedClass was added to the timetable sheet."
        ) {
            timetableClassItems.add(normalizedClass)
            save(KEY_TIMETABLE_CLASSES, timetableClassItems)
        }
        return true
    }

    fun removeTimetableMatrixClass(user: User, className: String): Boolean {
        if (user.role != Role.ADMIN || className.isBlank()) return false
        val normalizedClass = normalizeClassName(className)
        val removed = timetableClassItems.removeAll { normalizeClassName(it) == normalizedClass }
        if (!removed) return false
        timetableItems.removeAll { it.className == normalizedClass }
        runSharedUpdate(
            type = "timetable",
            title = "Timetable class removed",
            message = "$normalizedClass was removed from the timetable sheet."
        ) {
            save(KEY_TIMETABLE_CLASSES, timetableClassItems)
            save("timetable", timetableItems)
        }
        return true
    }

    fun addTimetableMatrixSubject(user: User, subject: String): Boolean {
        if (user.role != Role.ADMIN || subject.isBlank()) return false
        val normalizedSubject = subject.trim()
        if (timetableSubjectItems.any { it.equals(normalizedSubject, true) }) return false
        runSharedUpdate(
            type = "timetable",
            title = "Timetable subject added",
            message = "$normalizedSubject was added to the timetable sheet."
        ) {
            timetableSubjectItems.add(normalizedSubject)
            save(KEY_TIMETABLE_SUBJECTS, timetableSubjectItems)
        }
        return true
    }

    fun removeTimetableMatrixSubject(user: User, subject: String): Boolean {
        if (user.role != Role.ADMIN || subject.isBlank()) return false
        val normalizedSubject = subject.trim()
        val removed = timetableSubjectItems.removeAll { it.equals(normalizedSubject, true) }
        if (!removed) return false
        timetableItems.removeAll { it.subject.equals(normalizedSubject, true) }
        runSharedUpdate(
            type = "timetable",
            title = "Timetable subject removed",
            message = "$normalizedSubject was removed from the timetable sheet."
        ) {
            save(KEY_TIMETABLE_SUBJECTS, timetableSubjectItems)
            save("timetable", timetableItems)
        }
        return true
    }

    fun addTimetableMatrixTime(user: User, time: String): Boolean {
        if (user.role != Role.ADMIN || time.isBlank()) return false
        val normalizedTime = time.trim()
        if (timetableTimeItems.any { it.equals(normalizedTime, true) }) return false
        runSharedUpdate(
            type = "timetable",
            title = "Timetable time added",
            message = "$normalizedTime was added to the timetable sheet."
        ) {
            timetableTimeItems.add(normalizedTime)
            save(KEY_TIMETABLE_TIMES, timetableTimeItems)
        }
        return true
    }

    fun removeTimetableMatrixTime(user: User, time: String): Boolean {
        if (user.role != Role.ADMIN || time.isBlank()) return false
        val normalizedTime = time.trim()
        val removed = timetableTimeItems.removeAll { it.equals(normalizedTime, true) }
        if (!removed) return false
        timetableItems.removeAll { it.time.equals(normalizedTime, true) }
        runSharedUpdate(
            type = "timetable",
            title = "Timetable time removed",
            message = "$normalizedTime was removed from the timetable sheet."
        ) {
            save(KEY_TIMETABLE_TIMES, timetableTimeItems)
            save("timetable", timetableItems)
        }
        return true
    }

    private fun ensureTimetableMatrixClass(className: String) {
        val normalizedClass = normalizeClassName(className)
        if (timetableClassItems.none { normalizeClassName(it) == normalizedClass }) {
            timetableClassItems.add(normalizedClass)
            save(KEY_TIMETABLE_CLASSES, timetableClassItems)
        }
    }

    private fun ensureTimetableMatrixSubject(subject: String) {
        val normalizedSubject = subject.trim()
        if (timetableSubjectItems.none { it.equals(normalizedSubject, true) }) {
            timetableSubjectItems.add(normalizedSubject)
            save(KEY_TIMETABLE_SUBJECTS, timetableSubjectItems)
        }
    }

    private fun ensureTimetableMatrixTime(time: String) {
        val normalizedTime = time.trim()
        if (normalizedTime.isBlank()) return
        if (timetableTimeItems.none { it.equals(normalizedTime, true) }) {
            timetableTimeItems.add(normalizedTime)
            save(KEY_TIMETABLE_TIMES, timetableTimeItems)
        }
    }

    fun gallery(): List<GalleryItem> = galleryCards.toList()

    fun addOrUpdateFacilityCard(
        id: Long?,
        imageUrl: String,
        title: String,
        subtitle: String,
        badge: String,
        imageResName: String = ""
    ): Boolean {
        if (title.isBlank() || subtitle.isBlank() || badge.isBlank()) return false
        val item = FacilityCard(
            id = id ?: System.currentTimeMillis(),
            title = title.trim(),
            subtitle = subtitle.trim(),
            badge = badge.trim(),
            imageUrl = imageUrl.trim(),
            imageResName = imageResName.trim()
        )
        val existingIndex = id?.let { targetId -> facilityCardItems.indexOfFirst { card -> card.id == targetId } } ?: -1
        runSharedUpdate(
            type = "facilities",
            title = if (existingIndex >= 0) "Facilities updated" else "New facility added",
            message = "${title.trim()} is now visible in the premium facilities section."
        ) {
            if (existingIndex >= 0) {
                facilityCardItems[existingIndex] = item
            } else {
                facilityCardItems.add(item)
            }
            save(KEY_FACILITY_CARDS, facilityCardItems)
        }
        return true
    }

    fun deleteFacilityCard(id: Long): Boolean {
        val existingIndex = facilityCardItems.indexOfFirst { card -> card.id == id }
        if (existingIndex < 0) return false
        val removed = facilityCardItems[existingIndex]
        runSharedUpdate(
            type = "facilities",
            title = "Facility removed",
            message = "${removed.title} was removed from the facilities section.",
            addToGlobalNotifications = false
        ) {
            facilityCardItems.removeAt(existingIndex)
            save(KEY_FACILITY_CARDS, facilityCardItems)
        }
        return true
    }

    fun addOrUpdateGalleryItem(id: Long?, imageUrl: String, title: String, subtitle: String, imageResName: String = ""): Boolean {
        val normalizedTitle = title.trim()
        val normalizedSubtitle = subtitle.trim()
        val item = GalleryItem(
            id = id ?: System.currentTimeMillis(),
            title = normalizedTitle,
            subtitle = normalizedSubtitle,
            imageUrl = imageUrl.trim(),
            imageResName = imageResName.trim()
        )
        val existingIndex = id?.let { galleryCards.indexOfFirst { card -> card.id == it } } ?: -1
        runSharedUpdate(
            type = "gallery",
            title = if (existingIndex >= 0) "Gallery updated" else "New gallery image",
            message = "${item.title.ifBlank { "Gallery image" }} is now available in the school gallery."
        ) {
            if (existingIndex >= 0) {
                galleryCards[existingIndex] = item
            } else {
                galleryCards.add(item)
            }
            save("gallery", galleryCards)
        }
        return true
    }

    fun deleteGalleryItem(id: Long): Boolean {
        val existingIndex = galleryCards.indexOfFirst { card -> card.id == id }
        if (existingIndex < 0) return false
        val removed = galleryCards[existingIndex]
        runSharedUpdate(
            type = "gallery",
            title = "Gallery image removed",
            message = "${removed.title} was removed from the shared gallery.",
            addToGlobalNotifications = false
        ) {
            galleryCards.removeAt(existingIndex)
            save("gallery", galleryCards)
        }
        return true
    }

    fun galleryDrawableFor(imageResName: String): Int = when (imageResName.lowercase()) {
        "library" -> R.drawable.gallery_library
        "lab" -> R.drawable.gallery_lab
        "sports" -> R.drawable.gallery_sports
        else -> R.drawable.gallery_library
    }

    fun facilityDrawableFor(imageResName: String): Int = galleryDrawableFor(imageResName)

    private fun sanitizeFacilityCards(target: MutableList<FacilityCard> = facilityCardItems) {
        target.removeAll {
            it.imageUrl.trim().isBlank() &&
                it.imageResName.trim().lowercase() in legacyCodeImageResNames
        }
        if (target.isEmpty()) return
        target.replaceAll { card ->
            val fallbackRes = card.imageResName.trim()
            val fallbackBadge = card.badge.trim().ifBlank { "Campus Feature" }
            card.copy(
                id = card.id.takeIf { it > 0 } ?: System.currentTimeMillis(),
                title = card.title.trim(),
                subtitle = card.subtitle.trim(),
                badge = fallbackBadge,
                imageUrl = card.imageUrl.trim(),
                imageResName = fallbackRes
            )
        }
    }

    fun content(): String = schoolContentItems.firstOrNull()?.subtitle?.takeIf { it.isNotBlank() }
        ?: defaultSchoolContent()

    fun appUpdateNotice(): AppUpdateNotice? = appUpdateItems.firstOrNull()

    fun updateAppUpdateNotice(
        title: String,
        subtitle: String,
        buttonText: String,
        downloadUrl: String,
        minimumVersionCode: Int,
        forceUpdate: Boolean,
        onSyncComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        val normalizedTitle = title.trim()
        val normalizedSubtitle = subtitle.trim()
        val normalizedButton = buttonText.trim().ifBlank { "Update" }
        val normalizedUrl = downloadUrl.trim()
        if (normalizedTitle.isBlank() && normalizedSubtitle.isBlank() && normalizedUrl.isBlank() && !forceUpdate && minimumVersionCode <= 0) {
            appUpdateItems.clear()
            runSharedUpdate(
                type = "app_update",
                title = "App update notice cleared",
                message = "The update card was removed from all screens."
            ) {
                save(KEY_APP_UPDATE, appUpdateItems)
            }
            confirmAppUpdateSync(onSyncComplete)
            return true
        }
        val notice = AppUpdateNotice(
            title = normalizedTitle,
            subtitle = normalizedSubtitle,
            buttonText = normalizedButton,
            downloadUrl = normalizedUrl,
            minimumVersionCode = minimumVersionCode.coerceAtLeast(0),
            forceUpdate = forceUpdate,
            updatedAt = System.currentTimeMillis()
        )
        runSharedUpdate(
            type = "app_update",
            title = "App update notice updated",
            message = normalizedTitle.ifBlank { "App update notice changed." }
        ) {
            if (appUpdateItems.isEmpty()) {
                appUpdateItems.add(notice)
            } else {
                appUpdateItems[0] = notice
            }
            save(KEY_APP_UPDATE, appUpdateItems)
        }
        confirmAppUpdateSync(onSyncComplete)
        return true
    }

    private fun confirmAppUpdateSync(onSyncComplete: ((Boolean) -> Unit)?) {
        if (onSyncComplete == null) return
        val payload = mutableMapOf<String, Any>("schemaVersion" to 1, "updatedAt" to System.currentTimeMillis())
        publicContentKeys.forEach { key ->
            sharedStateValueForKey(key)?.let { payload[key] = it }
        }
        runCatching {
            Firebase.firestore
                .collection(PUBLIC_CONTENT_COLLECTION)
                .document(PUBLIC_CONTENT_DOCUMENT)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener { onSyncComplete(true) }
                .addOnFailureListener { onSyncComplete(false) }
        }.onFailure {
            onSyncComplete(false)
        }
    }

    fun updateSchoolContent(content: String): Boolean {
        if (SessionManager.currentUser?.role != Role.ADMIN) return false
        if (content.isBlank()) return false
        val item = SimpleListItem("School content", content.trim(), "CNS")
        runSharedUpdate(
            type = "school_content",
            title = "School content updated",
            message = "School content was updated.",
            badge = "CNS"
        ) {
            if (schoolContentItems.isEmpty()) {
                schoolContentItems.add(item)
            } else {
                schoolContentItems[0] = item
            }
            save(KEY_SCHOOL_CONTENT, schoolContentItems)
        }
        return true
    }

    fun submitFeedback(name: String, roleOrClass: String, message: String): Boolean {
        if (name.isBlank() || roleOrClass.isBlank() || message.isBlank()) return false
        val submitter = SessionManager.currentUser?.username.orEmpty().trim().lowercase()
        runSharedUpdate(
            type = "feedback",
            title = "New feedback received",
            message = "Feedback from ${name.trim()} is now visible to admins.",
            role = Role.ADMIN.name.lowercase()
        ) {
            feedbackStore.add(FeedbackEntry(name.trim(), roleOrClass.trim(), message.trim(), submitterUsername = submitter))
            save("feedback", feedbackStore)
        }
        return true
    }

    fun feedbackEntries(): List<FeedbackEntry> =
        feedbackStore.map { it.copy(adminReply = it.adminReply.orEmpty()) }

    fun replyToFeedback(index: Int, reply: String): Boolean {
        if (reply.isBlank() || index !in feedbackStore.indices) return false
        val entry = feedbackStore[index]
        val target = targetUsernameForFeedback(entry)
        runSharedUpdate(
            type = "feedback_reply",
            title = "Feedback replied",
            message = "Admin replied to your feedback.",
            targetUsername = target,
            addToGlobalNotifications = false
        ) {
            feedbackStore[index] = entry.copy(adminReply = reply.trim())
            save("feedback", feedbackStore)
        }
        return true
    }

    fun editFeedback(index: Int, name: String, roleOrClass: String, message: String): Boolean {
        if (index !in feedbackStore.indices || name.isBlank() || roleOrClass.isBlank() || message.isBlank()) return false
        val entry = feedbackStore[index]
        runSharedUpdate(
            type = "feedback_edit",
            title = "Feedback edited",
            message = "Admin edited a feedback entry."
        ) {
            feedbackStore[index] = entry.copy(
                name = name.trim(),
                roleOrClass = roleOrClass.trim(),
                message = message.trim()
            )
            save("feedback", feedbackStore)
        }
        return true
    }

    fun deleteFeedback(index: Int): Boolean {
        if (index !in feedbackStore.indices) return false
        runSharedUpdate(
            type = "feedback_delete",
            title = "Feedback deleted",
            message = "Admin deleted a feedback entry."
        ) {
            feedbackStore.removeAt(index)
            save("feedback", feedbackStore)
        }
        return true
    }

    fun submitAdmission(studentName: String, contact: String, grade: String, message: String): Boolean {
        if (studentName.isBlank() || contact.isBlank() || grade.isBlank()) return false
        val submitter = SessionManager.currentUser?.username.orEmpty().trim().lowercase()
        runSharedUpdate(
            type = "admission",
            title = "New admission enquiry",
            message = "${studentName.trim()} submitted an enquiry for $grade.",
            role = Role.ADMIN.name.lowercase()
        ) {
            admissionStore.add(AdmissionEntry(studentName.trim(), contact.trim(), grade.trim(), message.trim(), submitterUsername = submitter))
            save("admissions", admissionStore)
        }
        return true
    }

    fun admissionEntries(): List<AdmissionEntry> =
        admissionStore.map { it.copy(adminReply = it.adminReply.orEmpty()) }

    fun replyToAdmission(index: Int, reply: String): Boolean {
        if (reply.isBlank() || index !in admissionStore.indices) return false
        val entry = admissionStore[index]
        val target = targetUsernameForAdmission(entry)
        runSharedUpdate(
            type = "admission_reply",
            title = "Admission enquiry replied",
            message = "Admin replied to your admission enquiry.",
            targetUsername = target,
            addToGlobalNotifications = false
        ) {
            admissionStore[index] = entry.copy(adminReply = reply.trim())
            save("admissions", admissionStore)
        }
        return true
    }

    fun editAdmission(index: Int, studentName: String, contact: String, grade: String, message: String): Boolean {
        if (index !in admissionStore.indices || studentName.isBlank() || contact.isBlank() || grade.isBlank() || message.isBlank()) return false
        val entry = admissionStore[index]
        runSharedUpdate(
            type = "admission_edit",
            title = "Admission enquiry edited",
            message = "Admin edited an admission enquiry."
        ) {
            admissionStore[index] = entry.copy(
                studentName = studentName.trim(),
                contact = contact.trim(),
                grade = grade.trim(),
                message = message.trim()
            )
            save("admissions", admissionStore)
        }
        return true
    }

    fun deleteAdmission(index: Int): Boolean {
        if (index !in admissionStore.indices) return false
        runSharedUpdate(
            type = "admission_delete",
            title = "Admission enquiry deleted",
            message = "Admin deleted an admission enquiry."
        ) {
            admissionStore.removeAt(index)
            save("admissions", admissionStore)
        }
        return true
    }

    private fun targetUsernameForFeedback(entry: FeedbackEntry): String {
        entry.submitterUsername.orEmpty().trim().lowercase().takeIf { it.isNotBlank() }?.let { return it }
        return users.firstOrNull { user ->
            user.fullName.equals(entry.name.trim(), ignoreCase = true) ||
                user.username.equals(entry.name.trim(), ignoreCase = true)
        }?.username.orEmpty()
    }

    private fun targetUsernameForAdmission(entry: AdmissionEntry): String {
        entry.submitterUsername.orEmpty().trim().lowercase().takeIf { it.isNotBlank() }?.let { return it }
        return users.firstOrNull { user ->
            user.fullName.equals(entry.studentName.trim(), ignoreCase = true) ||
                user.username.equals(entry.studentName.trim(), ignoreCase = true) ||
                (entry.contact.isNotBlank() && user.mobileNumber == entry.contact.trim())
        }?.username
            ?: studentProfiles.firstOrNull { profile ->
                profile.fullName.equals(entry.studentName.trim(), ignoreCase = true) ||
                    (entry.contact.isNotBlank() && profile.guardianContact == entry.contact.trim())
            }?.username
            ?: ""
    }

    fun pendingUsers(): List<User> =
        (
            resolvedUsers().filter { it.role != Role.ADMIN && !it.approved } +
                registrationRequests
                    .filter { request ->
                        request.role != Role.ADMIN &&
                            request.username !in deletedAccountUsernames &&
                            resolvedUsers().none { user -> user.username == request.username && user.approved }
                    }
                    .map(::registrationRequestToUser)
        )
            .distinctBy { it.username.trim().lowercase() }
            .sortedWith(compareBy({ it.role.name }, { it.fullName }))

    fun pendingPasswordResetRequests(): List<PasswordResetRequest> =
        passwordResetRequests.sortedByDescending { it.requestedAt }

    fun passwordResetRequestItems(): List<SimpleListItem> =
        pendingPasswordResetRequests().map { request ->
            val contactText = request.verificationContact.ifBlank {
                request.mobileNumber.ifBlank { "Not available" }
            }
            val subtitle = buildString {
                append(request.fullName.ifBlank { request.username })
                append("\nRole: ")
                append(request.role.name.lowercase().replaceFirstChar(Char::uppercase))
                append("\nContact: ")
                append(contactText)
            }
            val ageMinutes = ((System.currentTimeMillis() - request.requestedAt).coerceAtLeast(0L) / 60000L).toInt()
            val badge = if (ageMinutes <= 0) "New" else "${ageMinutes}m"
            SimpleListItem(request.username, subtitle, badge)
        }

    fun findPasswordResetRequest(username: String): PasswordResetRequest? =
        passwordResetRequests.firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }

    fun submitPasswordResetRequest(
        username: String,
        role: Role,
        verificationContact: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val normalizedUsername = username.trim().lowercase()
        val rawContact = verificationContact.trim()
        val normalizedMobile = PhoneNumberSupport.normalize(rawContact)
        if (normalizedUsername.isBlank() || role == Role.ADMIN) {
            onComplete(Result.failure(IllegalArgumentException("Enter username and role.")))
            return
        }

        val candidate = userByUsername(normalizedUsername)
        if (candidate == null || candidate.role != role || !candidate.approved) {
            onComplete(Result.failure(IllegalArgumentException("No approved account matches this username and role.")))
            return
        }
        if (rawContact.isNotBlank() && !matchesVerificationContact(candidate, rawContact, normalizedMobile)) {
            onComplete(Result.failure(IllegalArgumentException("The entered contact does not match this account.")))
            return
        }

        val request = PasswordResetRequest(
            username = normalizedUsername,
            role = role,
            fullName = candidate.fullName,
            verificationContact = rawContact.ifBlank { preferredVerificationContact(candidate) },
            mobileNumber = normalizedMobile.ifBlank { PhoneNumberSupport.normalize(candidate.mobileNumber) },
            requestedAt = System.currentTimeMillis(),
            source = "app"
        )
        val projectId = FirebaseAuth.getInstance().app.options.projectId.orEmpty()
        if (projectId.isBlank()) {
            onComplete(Result.failure(IllegalStateException("Missing project setup.")))
            return
        }
        val endpoint = "https://us-central1-$projectId.cloudfunctions.net/createPasswordResetRequest"
        thread {
            runCatching {
                val payload = gson.toJson(
                    mapOf(
                        "username" to request.username,
                        "role" to request.role.name,
                        "verificationContact" to request.verificationContact
                    )
                )
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray()) }
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode !in 200..299) {
                    throw IllegalStateException(
                        httpErrorMessage(
                            responseCode = responseCode,
                            body = body,
                            fallback = "Unable to send reset request right now."
                        )
                    )
                }
            }.onSuccess {
                passwordResetRequests.removeAll { it.username == normalizedUsername }
                passwordResetRequests.add(request)
                persistValue(KEY_PASSWORD_RESET_REQUESTS, passwordResetRequests)
                notifyDataChanged()
                onComplete(Result.success(Unit))
            }.onFailure {
                onComplete(Result.failure(it))
            }
        }
    }

    fun refreshPasswordResetRequests(onComplete: (Boolean) -> Unit = {}) {
        Firebase.firestore
            .collection(PASSWORD_RESET_REQUESTS_COLLECTION)
            .get(Source.SERVER)
            .addOnSuccessListener { snapshot ->
                passwordResetRequests.clear()
                passwordResetRequests.addAll(
                    snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        val username = (data["username"] as? String).orEmpty().trim().lowercase()
                        if (username.isBlank()) return@mapNotNull null
                        PasswordResetRequest(
                            username = username,
                            role = Role.fromLabel((data["role"] as? String).orEmpty()),
                            fullName = (data["fullName"] as? String).orEmpty(),
                            verificationContact = (data["verificationContact"] as? String).orEmpty(),
                            mobileNumber = (data["mobileNumber"] as? String).orEmpty(),
                            requestedAt = (data["requestedAt"] as? Number)?.toLong() ?: 0L,
                            source = (data["source"] as? String).orEmpty().ifBlank { "app" }
                        )
                    }
                        .sortedByDescending { it.requestedAt }
                )
                persistValue(KEY_PASSWORD_RESET_REQUESTS, passwordResetRequests)
                notifyDataChanged()
                onComplete(true)
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    fun approveUser(username: String): Boolean = approveUserDetailed(username).success

    fun approveUserDetailed(username: String): ApprovalResult {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername.isBlank()) {
            return ApprovalResult(false, "Missing username for this approval request.")
        }

        val request = registrationRequestByUsername(normalizedUsername)
        if (request != null) {
            releaseDeletedUsernameReservation(normalizedUsername)
        }
        if (normalizedUsername in deletedAccountUsernames) {
            return ApprovalResult(false, "This username is reserved as deleted. Remove the stale request and ask the user to register again.")
        }

        val resolved = resolvedUsers().firstOrNull { it.username == normalizedUsername }
        if (resolved?.approved == true) {
            if (request != null) {
                clearRegistrationRequestLocally(normalizedUsername)
                removeRegistrationRequest(normalizedUsername)
                return ApprovalResult(true, "Account was already approved. The stale pending request was cleared.")
            }
            return ApprovalResult(false, "This account is already approved.")
        }

        val matchingUsers = users.filter { it.username == normalizedUsername }
        val pendingResolved = resolved?.takeIf { !it.approved }
        val fallbackRequest = request
        val candidate = matchingUsers.firstOrNull { !it.approved } ?: pendingResolved
        if (candidate == null && fallbackRequest == null) {
            return ApprovalResult(false, "Pending request data is missing. Refresh the list and try again.")
        }

        val pendingRole = fallbackRequest?.role ?: candidate?.role
            ?: return ApprovalResult(false, "The pending account does not include a valid role.")
        val fallbackUser = candidate ?: registrationRequestToUser(
            fallbackRequest ?: return ApprovalResult(false, "Pending request data is missing. Refresh the list and try again.")
        )
        val pendingFullName = fallbackRequest?.fullName?.ifBlank { fallbackUser.fullName }
            ?: fallbackUser.fullName.ifBlank { normalizedUsername }
        val pendingMobile = fallbackRequest?.mobileNumber?.ifBlank { fallbackUser.mobileNumber }
            ?: fallbackUser.mobileNumber

        return when (pendingRole) {
            Role.STUDENT -> {
                val normalizedClass = normalizeClassName(
                    fallbackRequest?.className?.ifBlank { fallbackUser.className } ?: fallbackUser.className
                )
                if (normalizedClass.isBlank()) {
                    return ApprovalResult(false, "Student approval is missing a class. Open review and fill in the class first.")
                }

                val existingProfileIndex = studentProfiles.indexOfFirst { it.username == normalizedUsername }
                val existingProfile = studentProfiles.getOrNull(existingProfileIndex)
                val approvedUser = fallbackUser.copy(
                    username = normalizedUsername,
                    role = Role.STUDENT,
                    fullName = pendingFullName,
                    className = normalizedClass,
                    classNames = listOf(normalizedClass),
                    subject = "",
                    approved = true,
                    mobileNumber = pendingMobile.trim()
                )
                val rollNumber = fallbackRequest?.rollNumber?.ifBlank { existingProfile?.rollNumber.orEmpty() }
                    ?: existingProfile?.rollNumber.orEmpty()
                val guardianContact = fallbackRequest?.guardianContact?.ifBlank { existingProfile?.guardianContact.orEmpty() }
                    ?: existingProfile?.guardianContact.orEmpty()
                val notes = fallbackRequest?.notes?.ifBlank { existingProfile?.notes.orEmpty() }
                    ?: existingProfile?.notes.orEmpty().ifBlank { "Approved from registration request." }

                runSharedUpdate(
                    type = "account_approval",
                    title = "Account approved",
                    message = "Your student account has been approved. You can now log in.",
                    targetUsername = normalizedUsername,
                    addToGlobalNotifications = false
                ) {
                    approvedAccountUsernames.add(normalizedUsername)
                    users.removeAll { it.username == normalizedUsername }
                    users.add(approvedUser)
                    deduplicateUsers()

                    val mergedProfile = StudentProfile(
                        username = normalizedUsername,
                        fullName = approvedUser.fullName,
                        className = normalizedClass,
                        rollNumber = rollNumber,
                        guardianContact = guardianContact,
                        notes = notes,
                        email = existingProfile?.email.orEmpty()
                    )
                    if (existingProfileIndex >= 0) {
                        studentProfiles[existingProfileIndex] = mergedProfile
                    } else {
                        studentProfiles.add(mergedProfile)
                    }

                    val attendanceIndex = attendanceRecords.indexOfFirst { it.studentUsername == normalizedUsername }
                    if (attendanceIndex >= 0) {
                        val currentAttendance = attendanceRecords[attendanceIndex]
                        attendanceRecords[attendanceIndex] = currentAttendance.copy(
                            studentName = approvedUser.fullName,
                            className = normalizedClass,
                            teacherUsername = teacherUsernameForClass(normalizedClass) ?: currentAttendance.teacherUsername
                        )
                    } else {
                        attendanceRecords.add(
                            AttendanceRecord(
                                studentUsername = normalizedUsername,
                                studentName = approvedUser.fullName,
                                className = normalizedClass,
                                teacherUsername = teacherUsernameForClass(normalizedClass) ?: "admin",
                                presentDays = 0,
                                totalDays = 0
                            )
                        )
                    }

                    saveApprovedAccounts()
                    save("users", users)
                    save("profiles", studentProfiles)
                    save("attendance", attendanceRecords)
                    refreshAdminClassItems()
                    clearRegistrationRequestLocally(normalizedUsername)
                }
                removeRegistrationRequest(normalizedUsername)
                ApprovalResult(true, "Account approved")
            }
            Role.TEACHER -> {
                val normalizedClasses = (
                    fallbackRequest?.className?.takeIf { it.isNotBlank() }?.let(::listOf)
                        ?: fallbackUser.classNames.takeIf { it.isNotEmpty() }
                        ?: fallbackUser.className.takeIf { it.isNotBlank() }?.let(::listOf)
                        ?: emptyList()
                    )
                    .map(::normalizeClassName)
                    .filter { it.isNotBlank() }
                    .distinct()
                val approvedUser = fallbackUser.copy(
                    username = normalizedUsername,
                    role = Role.TEACHER,
                    fullName = pendingFullName,
                    className = normalizedClasses.firstOrNull().orEmpty(),
                    classNames = normalizedClasses,
                    subject = fallbackRequest?.subject?.ifBlank { fallbackUser.subject } ?: fallbackUser.subject,
                    approved = true,
                    mobileNumber = pendingMobile.trim()
                )

                runSharedUpdate(
                    type = "account_approval",
                    title = "Account approved",
                    message = "Your teacher account has been approved. You can now log in.",
                    targetUsername = normalizedUsername,
                    addToGlobalNotifications = false
                ) {
                    approvedAccountUsernames.add(normalizedUsername)
                    users.removeAll { it.username == normalizedUsername }
                    users.add(approvedUser)
                    deduplicateUsers()
                    normalizedClasses.forEach { className ->
                        attendanceRecords.replaceAll { record ->
                            if (record.className == className) record.copy(teacherUsername = normalizedUsername) else record
                        }
                    }
                    saveApprovedAccounts()
                    save("users", users)
                    save("attendance", attendanceRecords)
                    refreshAdminClassItems()
                    clearRegistrationRequestLocally(normalizedUsername)
                }
                removeRegistrationRequest(normalizedUsername)
                ApprovalResult(true, "Account approved")
            }
            Role.ADMIN -> ApprovalResult(false, "Admin requests cannot be approved from this screen.")
        }
    }

    fun markUserApproved(username: String): User? {
        val normalizedUsername = username.trim().lowercase()
        if (normalizedUsername in deletedAccountUsernames) return null
        val user = users.firstOrNull { it.username == normalizedUsername } ?: return null
        if (!user.approved) {
            approvedAccountUsernames.add(normalizedUsername)
            users.replaceAll {
                if (it.username == normalizedUsername) it.copy(approved = true) else it
            }
            deduplicateUsers()
            runSharedUpdate(
                type = "account_approval",
                title = "Account approved",
                message = "Your ${user.role.name.lowercase()} account has been approved. You can now log in.",
                targetUsername = user.username,
                addToGlobalNotifications = false
            ) {
                saveApprovedAccounts()
                save("users", users)
                refreshAdminClassItems()
            }
        }
        return users.firstOrNull { it.username == normalizedUsername }
    }

    fun allStudentProfiles(): List<StudentProfile> = studentProfiles
        .filter { approvedStudentUsernames().contains(it.username) }
        .sortedWith(compareBy({ classOrder(it.className) }, { it.fullName }))

    fun profileFor(username: String): StudentProfile? = studentProfiles.firstOrNull { it.username == username }

    fun profileImageUrlFor(user: User): String {
        return when (user.role) {
            Role.STUDENT -> profileFor(user.username)?.imageUrl.orEmpty().ifBlank { user.profileImageUrl }
            else -> user.profileImageUrl
        }
    }

    fun marksForStudent(username: String): List<MarkItem> =
        if (hasPrivateAcademicData() && username.equals(SessionManager.currentUser?.username, true)) privateMarksItems
        else marksStore.filter { it.studentUsername == username }

    fun markForStudentAssessment(username: String, assessment: String): MarkItem? {
        val normalizedUsername = username.trim().lowercase()
        val normalizedAssessment = assessment.trim()
        if (normalizedUsername.isBlank() || normalizedAssessment.isBlank()) return null
        return marksStore.firstOrNull {
            it.studentUsername == normalizedUsername && it.assessment.equals(normalizedAssessment, true)
        }
    }

    fun attendanceForStudent(username: String): AttendanceRecord? {
        if (hasPrivateAcademicData() && username.equals(SessionManager.currentUser?.username, true)) {
            val rows = privateAttendanceItems
            if (rows.isEmpty()) return AttendanceRecord(username, SessionManager.currentUser?.fullName.orEmpty(), "", "", 0, 0)
            return AttendanceRecord(
                username, SessionManager.currentUser?.fullName.orEmpty(), rows.first().className, "",
                rows.count { it.present }, rows.size
            )
        }
        return attendanceRecords.firstOrNull { it.studentUsername == username }
    }

    fun marksSummaryText(username: String): String {
        val marks = marksForStudent(username)
        if (marks.isEmpty()) return "No marks added yet"
        val totalScore = marks.sumOf { it.score }
        val totalOutOf = marks.sumOf { it.outOf }
        val average = if (totalOutOf == 0) 0 else ((totalScore.toDouble() / totalOutOf.toDouble()) * 100).toInt()
        return "Subjects recorded: ${marks.size}\nOverall average: $average%\nLatest grade: ${marks.last().grade}"
    }

    fun profileRowsForClass(className: String, query: String): List<Pair<String, SimpleListItem>> {
        return studentProfiles.filter {
            it.className == className && (
                it.fullName.contains(query, true) ||
                    it.username.contains(query, true) ||
                    it.rollNumber.contains(query, true)
                )
        }.sortedBy { it.fullName }.map {
            it.username to SimpleListItem(it.fullName, "Roll ${it.rollNumber} | ${it.className} | ${it.guardianContact}", "Open")
        }
    }

    fun attendanceRowsForClass(className: String, query: String): List<SimpleListItem> {
        return attendanceRecords.filter {
            it.className == className &&
                (it.studentName.contains(query, true) || it.studentUsername.contains(query, true))
        }.sortedBy { it.studentName }.map {
            val (presentDays, totalDays) = attendanceSummaryNumbers(it.studentUsername, it.className)
            val percent = if (totalDays == 0) 0 else ((presentDays.toDouble() / totalDays.toDouble()) * 100).toInt()
            SimpleListItem(
                it.studentName,
                "Present $presentDays/$totalDays | Last: ${latestAttendanceStatusLabel(it.studentUsername, it.className)}",
                "$percent%"
            )
        }
    }

    fun attendanceRowsWithUsernamesForClass(className: String, query: String): List<Pair<String, SimpleListItem>> {
        return attendanceRecords.filter {
            it.className == className &&
                (it.studentName.contains(query, true) || it.studentUsername.contains(query, true))
        }.sortedBy { it.studentName }.map {
            val (presentDays, totalDays) = attendanceSummaryNumbers(it.studentUsername, it.className)
            val percent = if (totalDays == 0) 0 else ((presentDays.toDouble() / totalDays.toDouble()) * 100).toInt()
            it.studentUsername to SimpleListItem(
                it.studentName,
                "Present $presentDays/$totalDays | Last: ${latestAttendanceStatusLabel(it.studentUsername, it.className)}",
                "$percent%"
            )
        }
    }

    fun marksRowsForClass(className: String, query: String): List<SimpleListItem> {
        return marksRowsWithUsernamesForClass(className, query).map { it.second }
    }

    fun marksRowsWithUsernamesForClass(className: String, query: String): List<Pair<String, SimpleListItem>> {
        return studentProfiles.filter {
            it.className == className &&
                (it.fullName.contains(query, true) || it.username.contains(query, true) || it.rollNumber.contains(query, true))
        }.sortedBy { it.fullName }.map { profile ->
            profile.username to SimpleListItem(
                profile.fullName,
                "Tap to open marks",
                "Marks"
            )
        }
    }

    fun homeworkRowsForClass(className: String, query: String): List<SimpleListItem> {
        return homeworkItems.filter {
            it.className == className &&
                (it.title.contains(query, true) || it.subject.contains(query, true) || it.description.contains(query, true))
        }.sortedBy { it.title }.map {
            val submissionSummary = when (it.submissions.size) {
                0 -> "No submissions yet"
                1 -> "1 submission: ${it.submissions.first().fileNames.ifEmpty { listOf(it.submissions.first().fileName) }.size} file(s)"
                else -> "${it.submissions.size} submissions"
            }
            val attachments = it.attachmentNames.ifEmpty { listOfNotNull(it.attachmentName) }
            SimpleListItem(
                "${it.title} (${it.subject})",
                "${it.description}\nDue: ${it.dueDate}\nAttachments: ${attachments.ifEmpty { listOf("No file") }.joinToString()}\n$submissionSummary",
                className
            )
        }
    }

    fun homeworkItemsForClass(className: String, query: String): List<HomeworkItem> {
        return homeworkItems.filter {
            it.className == className &&
                (it.title.contains(query, true) || it.subject.contains(query, true) || it.description.contains(query, true))
        }.sortedBy { it.title }
    }

    private fun classOrder(className: String): Int = defaultClasses().indexOf(className).takeIf { it >= 0 } ?: Int.MAX_VALUE

    private fun approvedStudentUsernames(): Set<String> =
        resolvedUsers().filter { it.role == Role.STUDENT && it.approved }.map { it.username }.toSet()

    fun studentsForClass(className: String): List<StudentProfile> =
        studentProfiles.filter { it.className == className && approvedStudentUsernames().contains(it.username) }.sortedBy { it.fullName }

    fun teacherUsers(): List<User> =
        resolvedUsers().filter { it.role == Role.TEACHER && it.approved }.sortedWith(compareBy({ classOrder(classesFor(it).firstOrNull().orEmpty()) }, { it.fullName }))

    fun addTeacher(
        username: String,
        password: String,
        fullName: String,
        classNames: List<String>,
        subject: String,
        approved: Boolean = true,
        mobileNumber: String = "",
        allowBlankPassword: Boolean = false
    ): Boolean {
        val normalizedClasses = classNames.map { normalizeClassName(it) }.filter { it.isNotBlank() }.distinct()
        if (SessionManager.currentUser?.role != Role.ADMIN || username.isBlank() || fullName.isBlank()) return false
        if (!allowBlankPassword && password.isBlank()) return false
        val normalizedUsername = username.trim().lowercase()
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        if (normalizedMobile.isBlank()) return false
        if (normalizedUsername in deletedAccountUsernames) return false
        if (users.any { it.username == normalizedUsername }) return false
        if (normalizedMobile.isNotBlank() && users.any { it.mobileNumber == normalizedMobile }) return false
        runSharedUpdate(
            type = if (approved) "teacher" else "registration_request",
            title = if (approved) "Teacher added" else "Teacher registration",
            message = if (approved) {
                "${fullName.trim()} was added."
            } else {
                "${fullName.trim()} registered for a teacher account. Waiting for approval."
            },
            role = if (approved) "" else Role.ADMIN.name.lowercase()
        ) {
            if (approved) {
                approvedAccountUsernames.add(normalizedUsername)
                saveApprovedAccounts()
            }
            users.add(
                User(
                    normalizedUsername,
                    password.trim(),
                    Role.TEACHER,
                    fullName.trim(),
                    normalizedClasses.firstOrNull().orEmpty(),
                    normalizedClasses,
                    subject.trim(),
                    approved = approved,
                    mobileNumber = normalizedMobile
                )
            )
            save("users", users)
            if (approved) {
                normalizedClasses.forEach { className ->
                    attendanceRecords.filter { it.className == className }.forEach { it.teacherUsername = normalizedUsername }
                }
                save("attendance", attendanceRecords)
            }
            refreshAdminClassItems()
        }
        return true
    }

    fun updateTeacher(
        originalUsername: String,
        username: String,
        password: String,
        fullName: String,
        classNames: List<String>,
        subject: String
    ): Boolean {
        val normalizedClasses = classNames.map { normalizeClassName(it) }.filter { it.isNotBlank() }.distinct()
        if (originalUsername.isBlank() || username.isBlank() || password.isBlank() || fullName.isBlank() || normalizedClasses.isEmpty()) return false
        val original = originalUsername.trim().lowercase()
        val normalizedUsername = username.trim().lowercase()
        val teacherIndex = users.indexOfFirst { it.username == original && it.role == Role.TEACHER }
        if (teacherIndex < 0) return false
        if (normalizedUsername != original && users.any { it.username == normalizedUsername }) return false

        val oldTeacher = users[teacherIndex]
        users[teacherIndex] = User(
            normalizedUsername,
            password.trim(),
            Role.TEACHER,
            fullName.trim(),
            normalizedClasses.first(),
            normalizedClasses,
            subject.trim(),
            oldTeacher.approved,
            oldTeacher.mobileNumber,
            oldTeacher.profileImageUrl,
            oldTeacher.forcePasswordChange,
            oldTeacher.qualification,
            oldTeacher.experience,
            oldTeacher.specialization,
            oldTeacher.staffBio
        )

        attendanceRecords.replaceAll {
            when {
                it.teacherUsername == original && it.className !in normalizedClasses -> it.copy(teacherUsername = "admin")
                it.className in normalizedClasses -> it.copy(teacherUsername = normalizedUsername)
                else -> it
            }
        }
        homeworkItems.replaceAll {
            when {
                it.teacherUsername == original && it.className in normalizedClasses -> it.copy(teacherUsername = normalizedUsername)
                it.teacherUsername == original -> it.copy(teacherUsername = "admin")
                else -> it
            }
        }
        subjectItems.replaceAll {
            if (it.teacherName == oldTeacher.fullName) it.copy(teacherName = fullName.trim()) else it
        }

        runSharedUpdate(
            type = "teacher",
            title = "Teacher updated",
            message = "${fullName.trim()} was updated."
        ) {
            save("users", users)
            save("attendance", attendanceRecords)
            save("homework", homeworkItems)
            save("subjects", subjectItems)
            refreshAdminClassItems()
        }
        return true
    }

    fun updateTeacherProfileDetails(
        username: String,
        fullName: String? = null,
        imageUrl: String? = null,
        subject: String? = null,
        qualification: String,
        experience: String,
        specialization: String,
        staffBio: String
    ): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val index = users.indexOfFirst { it.username == normalizedUsername && it.role == Role.TEACHER }
        if (index < 0) return false
        val teacher = users[index]
        val updatedName = fullName?.trim().takeUnless { it.isNullOrBlank() } ?: teacher.fullName
        val updatedSubject = subject?.trim()?.takeIf { it.isNotBlank() } ?: teacher.subject
        runSharedUpdate(
            type = "teacher",
            title = "Teacher profile updated",
            message = "${updatedName} staff profile was updated."
        ) {
            users[index] = teacher.copy(
                fullName = updatedName,
                profileImageUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: teacher.profileImageUrl,
                subject = updatedSubject,
                qualification = qualification.trim(),
                experience = experience.trim(),
                specialization = specialization.trim(),
                staffBio = staffBio.trim()
            )
            subjectItems.replaceAll {
                if (it.teacherName == teacher.fullName) it.copy(name = updatedSubject.ifBlank { it.name }, teacherName = updatedName) else it
            }
            if (updatedSubject.isNotBlank()) {
                classesFor(teacher).forEach { className ->
                    if (subjectItems.none { it.className == className && it.teacherName == updatedName && it.name.equals(updatedSubject, true) }) {
                        subjectItems.add(SubjectItem(updatedSubject, className, updatedName))
                    }
                }
            }
            save("users", users)
            save("subjects", subjectItems)
        }
        return true
    }

    fun updateAdminProfile(
        username: String,
        fullName: String,
        imageUrl: String? = null
    ): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val index = users.indexOfFirst { it.username == normalizedUsername && it.role == Role.ADMIN }
        if (index < 0) return false
        val admin = users[index]
        runSharedUpdate(
            type = "admin",
            title = "Admin profile updated",
            message = "${fullName.trim()} profile changes were updated."
        ) {
            users[index] = admin.copy(
                fullName = fullName.trim(),
                profileImageUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: admin.profileImageUrl
            )
            save("users", users)
        }
        return true
    }

    fun resetPasswordForAccount(
        username: String,
        role: Role,
        verificationContact: String,
        newPassword: String
    ): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val normalizedContact = verificationContact.trim()
        val normalizedPassword = newPassword.trim()
        if (normalizedUsername.isBlank() || normalizedContact.isBlank() || normalizedPassword.length < 6) return false
        if (role == Role.ADMIN) return false
        val index = users.indexOfFirst { it.username == normalizedUsername && it.role == role }
        if (index < 0) return false
        val user = users[index]
        val verified = matchesVerificationContact(user, normalizedContact, PhoneNumberSupport.normalize(normalizedContact))
        if (!verified) return false

        runSharedUpdate(
            type = "password_reset",
            title = "Password reset",
            message = "${user.fullName} password was updated after verification.",
            targetUsername = normalizedUsername,
            addToGlobalNotifications = false
        ) {
            users[index] = user.copy(password = "__activated__")
            save("users", users)
        }
        return true
    }

    private fun matchesVerificationContact(user: User, rawContact: String, normalizedMobile: String): Boolean {
        val normalizedUsername = user.username.trim().lowercase()
        val profile = studentProfiles.firstOrNull { it.username == normalizedUsername }
        val candidateUserMobile = PhoneNumberSupport.normalize(user.mobileNumber)
        val candidateGuardian = profile?.guardianContact?.trim().orEmpty()
        val normalizedGuardian = PhoneNumberSupport.normalize(candidateGuardian)
        return when (user.role) {
            Role.STUDENT -> {
                (normalizedMobile.isNotBlank() && normalizedMobile == candidateUserMobile) ||
                    rawContact.equals(candidateGuardian, ignoreCase = true) ||
                    (normalizedMobile.isNotBlank() && normalizedMobile == normalizedGuardian)
            }
            Role.TEACHER -> normalizedMobile.isNotBlank() && normalizedMobile == candidateUserMobile
            Role.ADMIN -> false
        }
    }

    private fun preferredVerificationContact(user: User): String {
        val normalizedUsername = user.username.trim().lowercase()
        val profile = studentProfiles.firstOrNull { it.username == normalizedUsername }
        return when (user.role) {
            Role.STUDENT -> profile?.guardianContact?.trim().takeIf { !it.isNullOrBlank() }
                ?: user.mobileNumber.trim()
            Role.TEACHER -> user.mobileNumber.trim()
            Role.ADMIN -> ""
        }
    }

    fun approvePasswordResetRequest(
        username: String,
        temporaryPassword: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val request = findPasswordResetRequest(username)
        if (request == null) {
            onComplete(Result.failure(IllegalArgumentException("Reset request not found.")))
            return
        }
        val normalizedPassword = temporaryPassword.trim()
        if (normalizedPassword.length < 6) {
            onComplete(Result.failure(IllegalArgumentException("Temporary password must be at least 6 characters.")))
            return
        }

        SessionManager.ensureFirebaseSession { authResult ->
            authResult.onFailure {
                onComplete(Result.failure(IllegalStateException("Admin session expired. Log in again.")))
                return@ensureFirebaseSession
            }
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser == null) {
                onComplete(Result.failure(IllegalStateException("Admin session expired. Log in again.")))
                return@ensureFirebaseSession
            }

            firebaseUser.getIdToken(true)
                .addOnSuccessListener { tokenResult ->
                    val projectId = FirebaseAuth.getInstance().app.options.projectId.orEmpty()
                    val idToken = tokenResult.token.orEmpty()
                    if (projectId.isBlank() || idToken.isBlank()) {
                        onComplete(Result.failure(IllegalStateException("Unable to verify the admin session.")))
                        return@addOnSuccessListener
                    }
                    val endpoint = "https://us-central1-$projectId.cloudfunctions.net/adminResetUserPassword"
                    thread {
                        runCatching {
                            val payload = gson.toJson(
                                mapOf(
                                    "username" to request.username,
                                    "role" to request.role.name,
                                    "temporaryPassword" to normalizedPassword
                                )
                            )
                            val connection = URL(endpoint).openConnection() as HttpURLConnection
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 15000
                            connection.readTimeout = 20000
                            connection.doOutput = true
                            connection.setRequestProperty("Authorization", "Bearer $idToken")
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.outputStream.use { it.write(payload.toByteArray()) }
                            val responseCode = connection.responseCode
                            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            if (responseCode !in 200..299) {
                                throw IllegalStateException(body.ifBlank { "Password reset failed with HTTP $responseCode" })
                            }
                        }.onSuccess {
                            refreshSharedStateOnce {
                                refreshPasswordResetRequests {
                                    onComplete(Result.success(Unit))
                                }
                            }
                        }.onFailure {
                            onComplete(Result.failure(it))
                        }
                    }
                }
                .addOnFailureListener { onComplete(Result.failure(it)) }
        }
    }

    fun changeOwnPassword(newPassword: String, onComplete: (Result<Unit>) -> Unit) {
        val sessionUser = SessionManager.currentUser
        if (sessionUser == null) {
            onComplete(Result.failure(IllegalStateException("Login again to continue.")))
            return
        }
        val normalizedPassword = newPassword.trim()
        if (normalizedPassword.length < 6) {
            onComplete(Result.failure(IllegalArgumentException("Password must be at least 6 characters.")))
            return
        }
        SessionManager.ensureFirebaseSession { authResult ->
            authResult.onFailure {
                onComplete(Result.failure(IllegalStateException("Session expired. Log in again.")))
                return@ensureFirebaseSession
            }
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser == null) {
                onComplete(Result.failure(IllegalStateException("Session expired. Log in again.")))
                return@ensureFirebaseSession
            }

            firebaseUser.updatePassword(normalizedPassword)
                .addOnSuccessListener {
                    val index = users.indexOfFirst { it.username == sessionUser.username }
                    if (index >= 0) {
                        runSharedUpdate(
                            type = "password_change",
                            title = "Password updated",
                            message = "Your password was changed successfully.",
                            targetUsername = sessionUser.username,
                            addToGlobalNotifications = false
                        ) {
                            users[index] = users[index].copy(
                                password = "__activated__",
                                forcePasswordChange = false
                            )
                            save("users", users)
                        }
                    }
                    SessionManager.updateSessionPassword(normalizedPassword)
                    SessionManager.refreshCurrentUser()
                    onComplete(Result.success(Unit))
                }
                .addOnFailureListener { onComplete(Result.failure(it)) }
        }
    }

    fun resetPasswordWithVerifiedPhone(
        username: String,
        role: Role,
        mobileNumber: String,
        newPassword: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val normalizedUsername = username.trim().lowercase()
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        val normalizedPassword = newPassword.trim()
        val matchedUser = recoverableUser(normalizedUsername, role, normalizedMobile)
        if (matchedUser == null) {
            onComplete(Result.failure(IllegalArgumentException("This username and mobile number do not match an approved account.")))
            return
        }
        if (normalizedPassword.length < 6) {
            onComplete(Result.failure(IllegalArgumentException("Password must be at least 6 characters.")))
            return
        }

        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser == null) {
            onComplete(Result.failure(IllegalStateException("OTP verification expired. Send OTP again.")))
            return
        }

        firebaseUser.getIdToken(true)
            .addOnSuccessListener { tokenResult ->
                val projectId = FirebaseAuth.getInstance().app.options.projectId.orEmpty()
                val idToken = tokenResult.token.orEmpty()
                if (projectId.isBlank() || idToken.isBlank()) {
                    onComplete(Result.failure(IllegalStateException("Unable to verify the recovery session.")))
                    return@addOnSuccessListener
                }
                val endpoint = "https://us-central1-$projectId.cloudfunctions.net/resetPasswordWithPhoneOtp"
                thread {
                    runCatching {
                        val payload = gson.toJson(
                            mapOf(
                                "username" to normalizedUsername,
                                "role" to role.name,
                                "mobileNumber" to normalizedMobile,
                                "newPassword" to normalizedPassword
                            )
                        )
                        val connection = URL(endpoint).openConnection() as HttpURLConnection
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 20000
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer $idToken")
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(payload.toByteArray()) }
                        val responseCode = connection.responseCode
                        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (responseCode !in 200..299) {
                            throw IllegalStateException(body.ifBlank { "Password reset failed with HTTP $responseCode" })
                        }
                    }.onSuccess {
                        refreshSharedStateOnce {
                            onComplete(Result.success(Unit))
                        }
                    }.onFailure {
                        onComplete(Result.failure(it))
                    }
                }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun activateAccountWithVerifiedPhone(
        username: String,
        role: Role,
        mobileNumber: String,
        newPassword: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val matchedUser = activatableUser(username)
        val normalizedMobile = PhoneNumberSupport.normalize(mobileNumber)
        val normalizedPassword = newPassword.trim()
        if (matchedUser == null || matchedUser.role != role || PhoneNumberSupport.normalize(matchedUser.mobileNumber) != normalizedMobile) {
            onComplete(Result.failure(IllegalArgumentException("This ID is not awaiting activation for the verified mobile number.")))
            return
        }
        if (normalizedPassword.length < 6) {
            onComplete(Result.failure(IllegalArgumentException("Password must be at least 6 characters.")))
            return
        }
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser == null) {
            onComplete(Result.failure(IllegalStateException("OTP verification expired. Send OTP again.")))
            return
        }
        firebaseUser.getIdToken(true)
            .addOnSuccessListener { tokenResult ->
                val projectId = FirebaseAuth.getInstance().app.options.projectId.orEmpty()
                val idToken = tokenResult.token.orEmpty()
                if (projectId.isBlank() || idToken.isBlank()) {
                    onComplete(Result.failure(IllegalStateException("Unable to verify the activation session.")))
                    return@addOnSuccessListener
                }
                thread {
                    runCatching {
                        val payload = gson.toJson(mapOf(
                            "username" to matchedUser.username,
                            "role" to matchedUser.role.name,
                            "mobileNumber" to normalizedMobile,
                            "newPassword" to normalizedPassword
                        ))
                        val connection = URL("https://us-central1-$projectId.cloudfunctions.net/activateAccountWithPhoneOtp").openConnection() as HttpURLConnection
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 20000
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer $idToken")
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(payload.toByteArray()) }
                        val responseCode = connection.responseCode
                        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (responseCode !in 200..299) throw IllegalStateException(body.ifBlank { "Account activation failed" })
                    }.onSuccess {
                        refreshSharedStateOnce { onComplete(Result.success(Unit)) }
                    }.onFailure { onComplete(Result.failure(it)) }
                }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun deleteTeacher(username: String): Boolean {
        val normalizedUsername = username.trim().lowercase()
        val teacher = users.firstOrNull { it.username == normalizedUsername && it.role == Role.TEACHER } ?: return false
        deletedAccountUsernames.add(normalizedUsername)
        approvedAccountUsernames.remove(normalizedUsername)
        users.removeAll { it.username == normalizedUsername && it.role == Role.TEACHER }
        classesFor(teacher).forEach { className ->
            attendanceRecords.filter { it.className == className }.forEach { it.teacherUsername = "admin" }
        }
        homeworkItems.replaceAll {
            if (it.teacherUsername == normalizedUsername) it.copy(teacherUsername = "admin") else it
        }
        subjectItems.replaceAll {
            if (it.teacherName == teacher.fullName) it.copy(teacherName = "Assigned later") else it
        }
        runSharedUpdate(
            type = "teacher",
            title = "Teacher removed",
            message = "${teacher.fullName} was removed from the school app.",
            targetUsername = normalizedUsername,
            addToGlobalNotifications = false
        ) {
            saveDeletedAccounts()
            saveApprovedAccounts()
            save("users", users)
            save("attendance", attendanceRecords)
            save("homework", homeworkItems)
            save("subjects", subjectItems)
            refreshAdminClassItems()
        }
        return true
    }

    private fun normalizeClassName(raw: String): String {
        val value = raw.trim()
        if (value.equals("lkg", true)) return "LKG"
        if (value.equals("ukg", true)) return "UKG"
        val digits = Regex("(\\d+)").find(value)?.groupValues?.get(1)
        return when {
            digits != null -> "Class ${digits.toInt()}"
            value.startsWith("class ", true) -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            else -> value
        }
    }

    fun attendancePercent(record: AttendanceRecord): Int {
        if (record.totalDays == 0) return 0
        return ((record.presentDays.toDouble() / record.totalDays.toDouble()) * 100).toInt()
    }

    private fun teacherUsernameForClass(className: String): String? {
        val normalizedClass = normalizeClassName(className)
        return users.firstOrNull { it.role == Role.TEACHER && it.approved && classesFor(it).contains(normalizedClass) }?.username
    }

    fun teacherNameForClass(className: String): String {
        val normalizedClass = normalizeClassName(className)
        return users.firstOrNull { it.role == Role.TEACHER && it.approved && classesFor(it).contains(normalizedClass) }?.fullName
            ?: "Assigned later"
    }

    private fun ensureStudentShell(username: String, fullName: String, className: String, notes: String) {
        if (studentProfiles.none { it.username == username }) {
            studentProfiles.add(
                StudentProfile(
                    username = username,
                    fullName = fullName,
                    className = className,
                    rollNumber = "",
                    guardianContact = "",
                    notes = notes
                )
            )
            save("profiles", studentProfiles)
        }
        if (users.none { it.username == username }) {
            users.add(
                User(
                    username,
                    "",
                    Role.STUDENT,
                    fullName,
                    className,
                    classNames = listOf(className),
                    mobileNumber = ""
                )
            )
            save("users", users)
        }
    }

    private fun refreshAdminClassItems() {
        val classes = availableClasses()
        adminClassItems.clear()
        adminClassItems.addAll(
            classes.map { classOverviewItem(it).copy(badge = "Section") }
        )
        save("admin_classes", adminClassItems)
    }

    private fun ensureTimetableLayout(): Boolean {
        var changed = false
        val classSeed = seedTimetableClasses().map { normalizeClassName(it) }.distinct()
        val subjectSeed = seedTimetableSubjects().distinct()
        val timeSeed = seedTimetableTimes().distinct()

        if (timetableClassItems.isEmpty()) {
            timetableClassItems.addAll(classSeed)
            changed = true
        } else {
            val normalizedClasses = timetableClassItems.map { normalizeClassName(it) }.distinct()
            if (normalizedClasses != timetableClassItems) {
                timetableClassItems.clear()
                timetableClassItems.addAll(normalizedClasses)
                changed = true
            }
        }

        if (timetableSubjectItems.isEmpty()) {
            timetableSubjectItems.addAll(subjectSeed)
            changed = true
        } else {
            val normalizedSubjects = timetableSubjectItems.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (normalizedSubjects != timetableSubjectItems) {
                timetableSubjectItems.clear()
                timetableSubjectItems.addAll(normalizedSubjects)
                changed = true
            }
        }

        if (timetableTimeItems.isEmpty()) {
            timetableTimeItems.addAll(timeSeed)
            changed = true
        } else {
            val normalizedTimes = timetableTimeItems.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (normalizedTimes != timetableTimeItems) {
                timetableTimeItems.clear()
                timetableTimeItems.addAll(normalizedTimes)
                changed = true
            }
        }

        sanitizeTimetableItems()
        return changed
    }

}
