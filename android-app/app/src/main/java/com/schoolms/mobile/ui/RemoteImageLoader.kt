package com.schoolms.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.collection.LruCache
import java.net.URL
import java.util.concurrent.Executors

object RemoteImageLoader {
    private val cache = LruCache<String, Bitmap>(20)
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun loadInto(imageView: ImageView, imageUrl: String, placeholderRes: Int) {
        if (!imageUrl.startsWith("https://", ignoreCase = true)) {
            imageView.tag = null
            imageView.setImageResource(placeholderRes)
            return
        }
        val currentTag = imageView.tag as? String
        if (currentTag == imageUrl && imageView.drawable != null) {
            return
        }
        imageView.tag = imageUrl
        imageView.setImageResource(placeholderRes)
        val cached = cache.get(imageUrl)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        executor.execute {
            val bitmap = runCatching {
                URL(imageUrl).openStream().use(BitmapFactory::decodeStream)
            }.getOrNull()
            if (bitmap == null) {
                mainHandler.post {
                    if (imageView.tag == imageUrl) imageView.setImageResource(placeholderRes)
                }
                return@execute
            }
            cache.put(imageUrl, bitmap)
            mainHandler.post {
                if (imageView.tag == imageUrl) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }
}
