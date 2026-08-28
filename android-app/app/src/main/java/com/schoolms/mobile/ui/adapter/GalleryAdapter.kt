package com.schoolms.mobile.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.schoolms.mobile.R
import com.schoolms.mobile.data.GalleryItem
import com.schoolms.mobile.data.SchoolRepository
import com.schoolms.mobile.ui.RemoteImageLoader

class GalleryAdapter(
    private val items: List<GalleryItem>,
    private val onItemClick: ((GalleryItem) -> Unit)? = null,
    private val onItemLongClick: ((GalleryItem) -> Unit)? = null
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val placeholder = SchoolRepository.galleryDrawableFor(item.imageResName)
        if (item.imageUrl.isNotBlank()) {
            RemoteImageLoader.loadInto(holder.imageView, item.imageUrl, placeholder)
        } else {
            holder.imageView.setImageResource(placeholder)
        }
        holder.titleText.text = item.title
        holder.subtitleText.text = item.subtitle
        holder.titleText.visibility = if (item.title.isBlank()) View.GONE else View.VISIBLE
        holder.subtitleText.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(item)
            onItemLongClick != null
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageView)
        val titleText: TextView = view.findViewById(R.id.titleText)
        val subtitleText: TextView = view.findViewById(R.id.subtitleText)
    }
}
