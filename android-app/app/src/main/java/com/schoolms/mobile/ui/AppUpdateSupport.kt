package com.schoolms.mobile.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.schoolms.mobile.data.AppUpdateNotice
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AppUpdateSupport {
    fun activeNotice(): AppUpdateNotice? =
        SchoolRepository.appUpdateNotice()?.takeIf { it.isActive() }

    fun installedVersionInfo(context: Context): Pair<Int, String> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName.orEmpty().ifBlank { "1.0" }
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return versionCode to versionName
    }

    fun isOutdated(context: Context, notice: AppUpdateNotice? = activeNotice()): Boolean {
        val minVersion = notice?.minimumVersionCode ?: 0
        val currentVersion = installedVersionInfo(context).first
        return minVersion > 0 && currentVersion < minVersion
    }

    fun isForceUpdateRequired(context: Context, notice: AppUpdateNotice? = activeNotice()): Boolean =
        notice?.forceUpdate == true && isOutdated(context, notice)

    fun promptKey(notice: AppUpdateNotice?): String =
        notice?.let { "${it.updatedAt}_${it.minimumVersionCode}_${it.forceUpdate}_${it.downloadUrl.trim()}" }.orEmpty()

    fun shouldShowPrompt(context: Context, notice: AppUpdateNotice? = activeNotice()): Boolean =
        notice?.isActive() == true &&
            SessionManager.currentUser?.role != com.schoolms.mobile.data.Role.ADMIN &&
            isOutdated(context, notice)

    fun showUpdatePrompt(activity: Activity, notice: AppUpdateNotice, forceRequired: Boolean): AlertDialog {
        val message = buildString {
            append(
                notice.subtitle.ifBlank {
                    if (forceRequired) {
                        "A new app version is required before you continue."
                    } else {
                        "A new app version is available for your app."
                    }
                }
            )
            append("\n\nTap Update now to download the APK inside the app.")
            append("\nThe system installer will open after the download finishes.")
        }
        return AlertDialog.Builder(activity)
            .setTitle(
                notice.title.ifBlank {
                    if (forceRequired) "App update required" else "App update available"
                }
            )
            .setMessage(message)
            .setPositiveButton(notice.buttonText.ifBlank { "Update now" }) { _, _ ->
                openUpdateAction(activity, notice)
            }
            .setNegativeButton(if (forceRequired) "Exit app" else "Later") { _, _ ->
                if (forceRequired) activity.finishAffinity()
            }
            .setCancelable(!forceRequired)
            .show()
    }

    fun openUpdateAction(activity: Activity, notice: AppUpdateNotice?) {
        val url = notice?.downloadUrl.orEmpty().trim()
        if (url.isBlank()) {
            AlertDialog.Builder(activity)
                .setTitle("Update information")
                .setMessage("The admin has not added a download link yet. Ask for the latest APK or wait for the shared link to be added.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow app installs")
                .setMessage("Allow this app to install updates, then come back and tap Update now again.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        downloadAndInstallApk(activity, url, notice)
    }

    private fun downloadAndInstallApk(activity: Activity, url: String, notice: AppUpdateNotice?) {
        val updatesDir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "school-management-update.apk")
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val progressText = TextView(activity).apply {
            text = "Preparing download..."
            textSize = 14f
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        container.addView(progressText)
        container.addView(progressBar)

        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(notice?.title?.ifBlank { "Downloading update" } ?: "Downloading update")
            .setView(container)
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connect()
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Download failed with HTTP ${connection.responseCode}")
                }
                val totalBytes = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (totalBytes > 0L) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
                            activity.runOnUiThread {
                                if (progressDialog.isShowing) {
                                    if (percent >= 0) {
                                        progressBar.isIndeterminate = false
                                        progressBar.progress = percent
                                        progressText.text = "Downloading update... $percent%"
                                    } else {
                                        progressBar.isIndeterminate = true
                                        progressText.text = "Downloading update..."
                                    }
                                }
                            }
                        }
                    }
                }
                connection.disconnect()
                apkFile
            }.onSuccess { file ->
                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    installApk(activity, file)
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Update failed")
                        .setMessage(error.message ?: "Unable to download the update right now.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            activity.startActivity(intent)
        }.onFailure {
            AlertDialog.Builder(activity)
                .setTitle("Install update")
                .setMessage("The update was downloaded, but the installer could not be opened automatically.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
