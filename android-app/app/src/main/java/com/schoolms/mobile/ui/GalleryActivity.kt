package com.schoolms.mobile.ui

import android.net.Uri
import android.os.Bundle
import android.graphics.Rect
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.schoolms.mobile.R
import com.schoolms.mobile.data.GalleryItem
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.adapter.GalleryAdapter
import java.io.File
import java.io.FileOutputStream

class GalleryActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView
    private var selectedImageUri: Uri? = null
    private var selectedImageText: TextView? = null
    private var activeDialog: AlertDialog? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
        selectedImageText?.text = uri?.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.no_image_selected)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_gallery)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.gallery_title))
        findViewById<TextView>(R.id.galleryAdminHint).visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.addGalleryButton).apply {
            visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
            setOnClickListener { showGalleryDialog() }
        }
        recyclerView = findViewById(R.id.galleryRecycler)
        recyclerView.layoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
        if (recyclerView.itemDecorationCount == 0) {
            val spacing = (resources.displayMetrics.density * 8).toInt()
            recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    outRect.set(spacing / 2, spacing / 2, spacing / 2, spacing / 2)
                }
            })
        }
        bindGallery()
    }

    override fun onResume() {
        super.onResume()
        if (::recyclerView.isInitialized) {
            bindGallery()
        }
    }

    override fun onRepositoryChanged() {
        if (::recyclerView.isInitialized) {
            bindGallery()
        }
    }

    private fun bindGallery() {
        val isAdmin = SessionManager.currentUser?.role == Role.ADMIN
        recyclerView.adapter = GalleryAdapter(
            items = SchoolRepository.gallery(),
            onItemClick = { item -> showImagePreview(item) },
            onItemLongClick = if (isAdmin) ({ item -> showGalleryActions(item) }) else null
        )
    }

    private fun showGalleryActions(item: GalleryItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title.ifBlank { "Gallery image" })
            .setItems(arrayOf("View image", "Edit details", "Delete image")) { _, which ->
                when (which) {
                    0 -> showImagePreview(item)
                    1 -> showGalleryDialog(item)
                    2 -> confirmDeleteGalleryItem(item)
                }
            }
            .show()
    }

    private fun showImagePreview(item: GalleryItem) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val imageView = ZoomableImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val close = TextView(this).apply {
            text = "Close"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(28, 22, 28, 22)
            setOnClickListener { dialog.dismiss() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        }
        val caption = TextView(this).apply {
            text = listOf(item.title.trim(), item.subtitle.trim()).filter { it.isNotBlank() }.joinToString("\n")
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setPadding(24, 16, 24, 24)
            background = android.graphics.drawable.ColorDrawable(0x66000000)
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val placeholder = SchoolRepository.galleryDrawableFor(item.imageResName)
        if (item.imageUrl.isNotBlank()) {
            RemoteImageLoader.loadInto(imageView, item.imageUrl, placeholder)
        } else {
            imageView.setImageResource(placeholder)
        }
        root.addView(imageView)
        root.addView(caption)
        root.addView(close)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun confirmDeleteGalleryItem(item: GalleryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete image?")
            .setMessage("This will remove ${item.title} from the gallery for all users.")
            .setPositiveButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteGalleryItem(item.id)
                if (success) {
                    deleteStorageUrlIfPossible(item.imageUrl)
                }
                Toast.makeText(this, if (success) "Gallery image deleted" else "Unable to delete image", Toast.LENGTH_SHORT).show()
                bindGallery()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGalleryDialog(existingItem: GalleryItem? = null) {
        selectedImageUri = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val imageButton = MaterialButton(this).apply {
            text = getString(R.string.choose_image)
            setOnClickListener { imagePicker.launch("image/*") }
        }
        val imageInfo = TextView(this).apply {
            text = getString(R.string.no_image_selected)
            setPadding(0, 8, 0, 12)
        }
        selectedImageText = imageInfo
        val titleInput = EditText(this).apply {
            hint = "Title"
            setText(existingItem?.title.orEmpty())
        }
        val subtitleInput = EditText(this).apply {
            hint = "Description"
            setText(existingItem?.subtitle.orEmpty())
        }
        container.addView(imageButton)
        container.addView(imageInfo)
        container.addView(titleInput)
        container.addView(subtitleInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingItem == null) getString(R.string.add_gallery_image) else getString(R.string.edit_gallery_image))
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        activeDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveGalleryItem(existingItem, titleInput.text.toString(), subtitleInput.text.toString())
            }
        }
        dialog.show()
    }

    private fun saveGalleryItem(existingItem: GalleryItem?, title: String, subtitle: String) {
        val normalizedTitle = title.trim()
        val normalizedSubtitle = subtitle.trim()

        val chosenUri = selectedImageUri
        val previousImageUrl = existingItem?.imageUrl.orEmpty()
        if (chosenUri == null) {
            val success = SchoolRepository.addOrUpdateGalleryItem(
                id = existingItem?.id,
                imageUrl = existingItem?.imageUrl.orEmpty(),
                title = normalizedTitle,
                subtitle = normalizedSubtitle,
                imageResName = existingItem?.imageResName.orEmpty()
            )
            Toast.makeText(this, if (success) "Gallery updated" else "Unable to update gallery", Toast.LENGTH_SHORT).show()
            if (success) {
                activeDialog?.dismiss()
                bindGallery()
            }
            return
        }

        val safeName = normalizedTitle.ifBlank { "gallery" }.replace("\\s+".toRegex(), "_")
        val storageRef = Firebase.storage.reference.child("gallery/${System.currentTimeMillis()}_$safeName.jpg")
        val optimizedUri = prepareOptimizedUpload(chosenUri, "gallery")
        SessionManager.ensureFirebaseSession { authResult ->
            runOnUiThread {
                authResult.onFailure {
                    Toast.makeText(this, it.message ?: "Please log in again before uploading image", Toast.LENGTH_SHORT).show()
                }.onSuccess {
                    storageRef.putFile(optimizedUri)
                        .continueWithTask { task ->
                            if (!task.isSuccessful) {
                                throw task.exception ?: IllegalStateException("Upload failed")
                            }
                            storageRef.downloadUrl
                        }
                        .addOnSuccessListener { downloadUri ->
                            val success = SchoolRepository.addOrUpdateGalleryItem(
                                id = existingItem?.id,
                                imageUrl = downloadUri.toString(),
                                title = normalizedTitle,
                                subtitle = normalizedSubtitle,
                                imageResName = existingItem?.imageResName.orEmpty()
                            )
                            if (success && previousImageUrl.isNotBlank() && previousImageUrl != downloadUri.toString()) {
                                deleteStorageUrlIfPossible(previousImageUrl)
                            }
                            Toast.makeText(this, if (success) "Gallery updated" else "Unable to update gallery", Toast.LENGTH_SHORT).show()
                            if (success) {
                                activeDialog?.dismiss()
                                bindGallery()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Image upload failed", Toast.LENGTH_SHORT).show()
                        }
                }
            }
        }
    }

    private fun prepareOptimizedUpload(uri: Uri, cachePrefix: String): Uri {
        val bitmap = runCatching {
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return uri
        val file = File(cacheDir, "${cachePrefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        }
        return Uri.fromFile(file)
    }

    private fun deleteStorageUrlIfPossible(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        runCatching { Firebase.storage.getReferenceFromUrl(trimmed).delete() }
    }
}
