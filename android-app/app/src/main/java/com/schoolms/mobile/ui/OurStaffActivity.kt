package com.schoolms.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.appbar.MaterialToolbar
import com.schoolms.mobile.R
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.data.User

class OurStaffActivity : BaseActivity() {
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_our_staff)
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Our Staff")
        recyclerView = findViewById(R.id.staffRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        bind()
    }

    override fun onRepositoryChanged() {
        if (::recyclerView.isInitialized) bind()
    }

    private fun bind() {
        recyclerView.adapter = StaffAdapter(SchoolRepository.teacherUsers()) { teacher ->
            showStaffProfileDialog(teacher)
        }
    }

    private fun showStaffProfileDialog(teacher: User) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_staff_profile, null, false)
        val imageView = view.findViewById<ImageView>(R.id.staffDialogImage)
        val nameView = view.findViewById<TextView>(R.id.staffDialogName)
        val qualificationView = view.findViewById<TextView>(R.id.staffDialogQualification)
        val metaView = view.findViewById<TextView>(R.id.staffDialogMeta)
        val detailList = view.findViewById<LinearLayout>(R.id.staffDialogDetailList)
        val bioView = view.findViewById<TextView>(R.id.staffDialogBio)

        nameView.text = teacher.fullName
        qualificationView.text = teacher.qualification.ifBlank { "Qualification pending" }
        metaView.text = "${SchoolRepository.assignedClasses(teacher).joinToString(", ").ifBlank { "Assigned later" }} | ${teacher.subject.ifBlank { "Subject pending" }}"
        bioView.text = teacher.staffBio.ifBlank { "Committed to disciplined, personal, and future-ready learning." }
        ImageLoader.loadInto(imageView, teacher.profileImageUrl, R.drawable.ic_school_crest)

        detailList.removeAllViews()
        addStaffDetailRow(detailList, "Experience", teacher.experience.ifBlank { "To be updated" }, "#FFF7ED")
        addStaffDetailRow(detailList, "Specialization", teacher.specialization.ifBlank { "Teaching and mentoring" }, "#F5F3FF")
        addStaffDetailRow(detailList, "Classes", SchoolRepository.assignedClasses(teacher).joinToString(", ").ifBlank { "Assigned later" }, "#ECFEFF")
        addStaffDetailRow(detailList, "Subject", teacher.subject.ifBlank { "Subject pending" }, "#EFF6FF")

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun addStaffDetailRow(container: LinearLayout, title: String, value: String, background: String) {
        val card = MaterialCardView(this).apply {
            radius = 20f
            cardElevation = 2f
            setCardBackgroundColor(android.graphics.Color.parseColor(background))
            strokeWidth = dp(1)
            strokeColor = android.graphics.Color.parseColor("#1A0F7C8F")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setPadding(0, dp(6), 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(titleView)
        row.addView(valueView)
        card.addView(row)
        container.addView(card)
    }

    private class StaffAdapter(
        private val teachers: List<User>,
        private val onClick: (User) -> Unit
    ) : RecyclerView.Adapter<StaffAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_staff_profile, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val teacher = teachers[position]
            holder.nameText.text = teacher.fullName
            holder.metaText.text = "${teacher.qualification.ifBlank { "Qualification pending" }}\n${teacher.experience.ifBlank { "Experience to be updated" }}"
            holder.badgeText.text = teacher.specialization.ifBlank { teacher.subject.ifBlank { "Teacher" } }
            ImageLoader.loadInto(holder.imageView, teacher.profileImageUrl, R.drawable.ic_school_crest)
            holder.itemView.setOnClickListener { onClick(teacher) }
        }

        override fun getItemCount(): Int = teachers.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.staffImage)
            val nameText: TextView = view.findViewById(R.id.staffNameText)
            val metaText: TextView = view.findViewById(R.id.staffMetaText)
            val badgeText: TextView = view.findViewById(R.id.staffBadgeText)
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
