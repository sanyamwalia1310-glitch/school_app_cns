package com.schoolms.mobile.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.schoolms.mobile.R
import com.schoolms.mobile.data.FacilityCard
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.ui.ImageLoader

class FacilityCardAdapter(
    private val items: List<FacilityCard>,
    private val onItemClick: ((FacilityCard) -> Unit)? = null
) : RecyclerView.Adapter<FacilityCardAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_facility_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        ImageLoader.loadInto(holder.imageView, item.imageUrl, SchoolRepository.facilityDrawableFor(item.imageResName))
        holder.badgeText.text = item.badge
        holder.titleText.text = item.title
        holder.subtitleText.text = item.subtitle
        holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.facilityImageView)
        val badgeText: TextView = view.findViewById(R.id.facilityBadgeText)
        val titleText: TextView = view.findViewById(R.id.facilityTitleText)
        val subtitleText: TextView = view.findViewById(R.id.facilitySubtitleText)
    }
}
