package com.schoolms.mobile.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.schoolms.mobile.R
import com.schoolms.mobile.data.SimpleListItem

class SimpleListAdapter(
    private var items: List<SimpleListItem>,
    private val animateEntries: Boolean = true,
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<SimpleListAdapter.ViewHolder>() {
    private var lastAnimatedPosition = -1

    constructor(
        items: List<SimpleListItem>,
        onClick: ((Int) -> Unit)?
    ) : this(
        items = items,
        animateEntries = true,
        onClick = onClick
    )

    init {
        setHasStableIds(true)
    }

    fun updateItems(newItems: List<SimpleListItem>) {
        val hadItems = items.isNotEmpty()
        items = newItems
        if (!hadItems) {
            lastAnimatedPosition = -1
        }
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        val item = items[position]
        return "${item.title}|${item.subtitle}|${item.badge.orEmpty()}".hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_simple_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleText.text = item.title
        holder.subtitleText.text = item.subtitle
        holder.badgeText.text = item.badge
        holder.monogramText.text = monogramFor(item.title)
        holder.badgeText.visibility = if (item.badge.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick?.invoke(position) }
        if (animateEntries) {
            animateRow(holder.itemView, position)
        } else {
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
        }
    }

    override fun getItemCount(): Int = items.size

    private fun monogramFor(title: String): String {
        val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.isNotEmpty() -> words.first().take(2).uppercase()
            else -> "•"
        }
    }

    private fun animateRow(view: View, position: Int) {
        if (position <= lastAnimatedPosition) return
        lastAnimatedPosition = position
        view.alpha = 0f
        view.translationY = 28f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setStartDelay((position.coerceAtMost(6) * 22L))
            .start()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val monogramText: TextView = view.findViewById(R.id.monogramText)
        val titleText: TextView = view.findViewById(R.id.titleText)
        val subtitleText: TextView = view.findViewById(R.id.subtitleText)
        val badgeText: TextView = view.findViewById(R.id.badgeText)
    }
}
