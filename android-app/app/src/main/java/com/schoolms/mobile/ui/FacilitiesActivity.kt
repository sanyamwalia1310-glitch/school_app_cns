package com.schoolms.mobile.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.schoolms.mobile.R
import com.schoolms.mobile.data.FacilityCard
import com.schoolms.mobile.data.Role
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.SessionManager
import com.schoolms.mobile.ui.adapter.FacilityCardAdapter
import java.io.File
import java.io.FileOutputStream

class FacilitiesActivity : BaseActivity() {
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
        setContentView(R.layout.activity_facilities)

        val user = SessionManager.currentUser ?: return
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), getString(R.string.facilities_title))
        findViewById<MaterialButton>(R.id.addFacilityButton).apply {
            visibility = if (user.role == Role.ADMIN) View.VISIBLE else View.GONE
            setOnClickListener { showFacilityDialog() }
        }
        recyclerView = findViewById(R.id.facilitiesRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        bindFacilities()
        animateContentEntrance(
            findViewById(R.id.facilitiesHeroCard),
            findViewById(R.id.addFacilityButton),
            findViewById(R.id.facilitiesAdminHint),
            recyclerView
        )
    }

    override fun onResume() {
        super.onResume()
        if (::recyclerView.isInitialized) bindFacilities()
    }

    override fun onRepositoryChanged() {
        if (::recyclerView.isInitialized) bindFacilities()
    }

    private fun bindFacilities() {
        val isAdmin = SessionManager.currentUser?.role == Role.ADMIN
        recyclerView.adapter = FacilityCardAdapter(SchoolRepository.facilityCards()) { item ->
            if (isAdmin) {
                showFacilityActions(item)
            } else {
                showFacilityPreview(item)
            }
        }
    }

    private fun showFacilityActions(item: FacilityCard) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("View facility", "Edit facility", "Delete facility")) { _, which ->
                when (which) {
                    0 -> showFacilityPreview(item)
                    1 -> showFacilityDialog(item)
                    2 -> confirmDeleteFacility(item)
                }
            }
            .show()
    }

    private fun showFacilityPreview(item: FacilityCard) {
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels / 3
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        ImageLoader.loadInto(imageView, item.imageUrl, SchoolRepository.facilityDrawableFor(item.imageResName))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 0)
            addView(imageView)
            addView(TextView(this@FacilitiesActivity).apply {
                text = item.badge
                setTextColor(getColor(R.color.dashboard_orange))
                textSize = 13f
                setPadding(0, 14, 0, 0)
            })
            addView(TextView(this@FacilitiesActivity).apply {
                text = item.subtitle
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setPadding(0, 8, 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmDeleteFacility(item: FacilityCard) {
        AlertDialog.Builder(this)
            .setTitle("Delete facility?")
            .setMessage("This will remove ${item.title} from the facilities screen for all users.")
            .setPositiveButton("Delete") { _, _ ->
                val success = SchoolRepository.deleteFacilityCard(item.id)
                if (success && item.imageUrl.isNotBlank()) {
                    deleteStorageUrlIfPossible(item.imageUrl)
                }
                Toast.makeText(this, if (success) "Facility deleted" else "Unable to delete facility", Toast.LENGTH_SHORT).show()
                bindFacilities()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFacilityDialog(existingItem: FacilityCard? = null) {
        selectedImageUri = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val stepTitle = TextView(this).apply {
            text = "Step 1 of 3: Select image"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, 10)
        }
        container.addView(stepTitle)

        val stepOne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stepTwo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val stepThree = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val imageButton = MaterialButton(this).apply {
            text = getString(R.string.choose_image)
            setOnClickListener { imagePicker.launch("image/*") }
        }
        val imageInfo = TextView(this).apply {
            text = if (existingItem?.imageUrl.isNullOrBlank()) getString(R.string.no_image_selected) else "Current image saved"
            setPadding(0, 8, 0, 12)
        }
        selectedImageText = imageInfo
        stepOne.addView(imageButton)
        stepOne.addView(imageInfo)

        val titleInput = EditText(this).apply {
            hint = "Facility title"
            setText(existingItem?.title.orEmpty())
        }
        val badgeInput = EditText(this).apply {
            hint = "Premium badge"
            setText(existingItem?.badge.orEmpty())
        }
        stepTwo.addView(titleInput)
        stepTwo.addView(badgeInput)

        val subtitleInput = EditText(this).apply {
            hint = "Facility description"
            minLines = 4
            setText(existingItem?.subtitle.orEmpty())
        }
        stepThree.addView(subtitleInput)

        container.addView(stepOne)
        container.addView(stepTwo)
        container.addView(stepThree)

        var currentStep = 0
        fun updateSteps(dialog: AlertDialog) {
            stepOne.visibility = if (currentStep == 0) View.VISIBLE else View.GONE
            stepTwo.visibility = if (currentStep == 1) View.VISIBLE else View.GONE
            stepThree.visibility = if (currentStep == 2) View.VISIBLE else View.GONE
            stepTitle.text = when (currentStep) {
                0 -> "Step 1 of 3: Select image"
                1 -> "Step 2 of 3: Title and badge"
                else -> "Step 3 of 3: Description"
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = if (currentStep == 0) "Cancel" else "Back"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = if (currentStep == 2) "Save" else "Next"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingItem == null) "Add facility" else "Edit facility")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        activeDialog = dialog
        dialog.setOnShowListener {
            updateSteps(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (currentStep < 2) {
                    if (currentStep == 1 && (titleInput.text.isNullOrBlank() || badgeInput.text.isNullOrBlank())) {
                        Toast.makeText(this, "Title and badge are required", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    currentStep += 1
                    updateSteps(dialog)
                } else {
                    saveFacility(existingItem, titleInput.text.toString(), subtitleInput.text.toString(), badgeInput.text.toString())
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (currentStep == 0) {
                    dialog.dismiss()
                } else {
                    currentStep -= 1
                    updateSteps(dialog)
                }
            }
        }
        dialog.show()
    }

    private fun saveFacility(existingItem: FacilityCard?, title: String, subtitle: String, badge: String) {
        if (title.isBlank() || subtitle.isBlank() || badge.isBlank()) {
            Toast.makeText(this, "Fill all required fields", Toast.LENGTH_SHORT).show()
            return
        }

        val chosenUri = selectedImageUri
        val previousImageUrl = existingItem?.imageUrl.orEmpty()
        if (chosenUri == null) {
            val success = SchoolRepository.addOrUpdateFacilityCard(
                id = existingItem?.id,
                imageUrl = previousImageUrl,
                title = title,
                subtitle = subtitle,
                badge = badge,
                imageResName = existingItem?.imageResName.orEmpty().ifBlank { "library" }
            )
            Toast.makeText(this, if (success) "Facilities updated" else "Unable to update facility", Toast.LENGTH_SHORT).show()
            if (success) {
                activeDialog?.dismiss()
                bindFacilities()
            }
            return
        }

        val safeName = title.trim().replace("\\s+".toRegex(), "_")
        val storageRef = Firebase.storage.reference.child("facilities/${System.currentTimeMillis()}_$safeName.jpg")
        val optimizedUri = prepareOptimizedUpload(chosenUri, "facilities")
        storageRef.putFile(optimizedUri)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    throw task.exception ?: IllegalStateException("Upload failed")
                }
                storageRef.downloadUrl
            }
            .addOnSuccessListener { downloadUri ->
                val success = SchoolRepository.addOrUpdateFacilityCard(
                    id = existingItem?.id,
                    imageUrl = downloadUri.toString(),
                    title = title,
                    subtitle = subtitle,
                    badge = badge,
                    imageResName = existingItem?.imageResName.orEmpty().ifBlank { "library" }
                )
                if (success && previousImageUrl.isNotBlank() && previousImageUrl != downloadUri.toString()) {
                    deleteStorageUrlIfPossible(previousImageUrl)
                }
                Toast.makeText(this, if (success) "Facilities updated" else "Unable to update facility", Toast.LENGTH_SHORT).show()
                if (success) {
                    activeDialog?.dismiss()
                    bindFacilities()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Image upload failed", Toast.LENGTH_SHORT).show()
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
