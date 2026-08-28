package com.schoolms.mobile.ui

import android.os.Bundle
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.schoolms.mobile.R
import com.schoolms.mobile.data.SchoolRepository

class OurScholarsActivity : BaseActivity() {
    private lateinit var badgeText: TextView
    private lateinit var initialText: TextView
    private lateinit var titleText: TextView
    private lateinit var quoteText: TextView
    private lateinit var bodyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireLogin()) return
        setContentView(R.layout.activity_our_scholars)
        setupToolbar(findViewById<MaterialToolbar>(R.id.toolbar), "Our Scholars")
        badgeText = findViewById(R.id.scholarsBadgeText)
        initialText = findViewById(R.id.scholarsInitialText)
        titleText = findViewById(R.id.scholarsTitleText)
        quoteText = findViewById(R.id.scholarsQuoteText)
        bodyText = findViewById(R.id.scholarsBodyText)
        bind()
    }

    override fun onRepositoryChanged() {
        if (::titleText.isInitialized) bind()
    }

    private fun bind() {
        val item = SchoolRepository.ourScholars()
        badgeText.text = item.badge.orEmpty().ifBlank { "Scholar spotlight" }
        initialText.text = scholarMonogram(item.title)
        titleText.text = item.title
        quoteText.text = "Celebrating discipline, consistency, and academic excellence."
        bodyText.text = item.subtitle
    }

    private fun scholarMonogram(title: String): String {
        val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.any { it.contains("achiever", true) || it.contains("topper", true) } -> "A+"
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.isNotEmpty() -> words.first().take(2).uppercase()
            else -> "A+"
        }
    }
}
