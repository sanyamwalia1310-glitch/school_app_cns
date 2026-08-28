package com.schoolms.mobile.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import androidx.collection.LruCache
import java.net.URL
import java.util.concurrent.Executors

object ImageLoader {
    private val cache = LruCache<String, android.graphics.Bitmap>(16)
    private val executor = Executors.newFixedThreadPool(2)

    fun loadInto(imageView: ImageView, url: String?, fallbackRes: Int) {
        val cleanUrl = url?.trim().orEmpty()
        val currentTag = imageView.tag as? String
        if (cleanUrl.isBlank()) {
            imageView.tag = null
            if (currentTag == null || imageView.drawable == null) {
                imageView.setImageResource(fallbackRes)
            }
            return
        }
        if (currentTag == cleanUrl && imageView.drawable != null) {
            return
        }
        imageView.tag = cleanUrl
        if (cleanUrl.isBlank()) return
        if (cleanUrl.startsWith("content:") || cleanUrl.startsWith("file:")) {
            imageView.setImageURI(Uri.parse(cleanUrl))
            return
        }
        val cached = cache.get(cleanUrl)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        if (imageView.drawable == null) {
            imageView.setImageResource(fallbackRes)
        }
        executor.execute {
            runCatching {
                URL(cleanUrl).openStream().use { BitmapFactory.decodeStream(it) }
            }.onSuccess { bitmap ->
                imageView.post {
                    if (bitmap != null) {
                        cache.put(cleanUrl, bitmap)
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
