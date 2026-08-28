package com.schoolms.mobile.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class UploadProgressDialog(context: Context, title: String) {
    private val statusText = TextView(context).apply {
        text = "Preparing upload..."
        textSize = 15f
    }
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 18 }
    }
    private val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 26, 44, 8)
                addView(statusText)
                addView(progressBar)
            }
        )
        .setCancelable(false)
        .create()

    fun show() {
        if (!dialog.isShowing) dialog.show()
    }

    fun update(currentFile: Int, totalFiles: Int, percent: Int) {
        val safeTotal = totalFiles.coerceAtLeast(1)
        val safeCurrent = currentFile.coerceIn(1, safeTotal)
        val safePercent = percent.coerceIn(0, 100)
        statusText.text = "Uploading file $safeCurrent of $safeTotal - $safePercent%"
        progressBar.progress = safePercent
    }

    fun saving() {
        statusText.text = "Upload complete. Saving homework..."
        progressBar.progress = 100
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}
