package com.schoolms.mobile.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.recyclerview.widget.RecyclerView
import com.schoolms.mobile.R

data class AttendanceMarkEntry(
    val username: String,
    val fullName: String,
    val className: String,
    var present: Boolean,
    val alreadyMarked: Boolean,
    val statusLabel: String,
    val locked: Boolean = alreadyMarked
)

class AttendanceMarkAdapter(
    private val onStateChange: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AttendanceMarkAdapter.AttendanceViewHolder>() {

    private val entries = mutableListOf<AttendanceMarkEntry>()

    fun update(newEntries: List<AttendanceMarkEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_attendance_mark, parent, false)
        return AttendanceViewHolder(view)
    }

    override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class AttendanceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.attendanceName)
        private val classView: TextView = itemView.findViewById(R.id.attendanceClass)
        private val presentButton: MaterialButton = itemView.findViewById(R.id.attendancePresentButton)
        private val absentButton: MaterialButton = itemView.findViewById(R.id.attendanceAbsentButton)
        private val statusView: TextView = itemView.findViewById(R.id.attendanceStatusLabel)

        fun bind(entry: AttendanceMarkEntry) {
            nameView.text = entry.fullName
            classView.text = "${entry.className} | ${entry.username}"
            statusView.text = if (entry.alreadyMarked) {
                "Already marked: ${entry.statusLabel}"
            } else {
                entry.statusLabel
            }
            presentButton.isChecked = entry.present
            absentButton.isChecked = !entry.present
            presentButton.text = if (entry.present) "✓ Present" else "Present"
            absentButton.text = if (!entry.present) "✓ Absent" else "Absent"
            presentButton.isEnabled = !entry.locked
            absentButton.isEnabled = !entry.locked
            presentButton.setOnClickListener {
                if (entry.locked) return@setOnClickListener
                updateState(entry, true)
            }
            absentButton.setOnClickListener {
                if (entry.locked) return@setOnClickListener
                updateState(entry, false)
            }
        }

        private fun updateState(entry: AttendanceMarkEntry, present: Boolean) {
            entry.present = present
            presentButton.isChecked = present
            absentButton.isChecked = !present
            presentButton.text = if (present) "✓ Present" else "Present"
            absentButton.text = if (!present) "✓ Absent" else "Absent"
            onStateChange(entry.username, present)
        }
    }
}
