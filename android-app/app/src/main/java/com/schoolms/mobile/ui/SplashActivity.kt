package com.schoolms.mobile.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.schoolms.mobile.R
import com.schoolms.mobile.data.SessionManager

class SplashActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        playSplashAnimation()
        window.decorView.postDelayed({
            if (!isFinishing) {
                SessionManager.restoreSession { restored ->
                    runOnUiThread {
                        val target = when {
                            AppUpdateSupport.isForceUpdateRequired(this) -> LoginActivity::class.java
                            restored -> MainDashboardActivity::class.java
                            else -> LoginActivity::class.java
                        }
                        startActivity(Intent(this, target))
                        finish()
                    }
                }
            }
        }, 3000)
    }

    private fun playSplashAnimation() {
        val logoCard = findViewById<View>(R.id.splashLogoCard)
        val crest = findViewById<View>(R.id.splashCrest)
        val title = findViewById<TextView>(R.id.splashTitle)
        val tagline = findViewById<View>(R.id.splashTagline)
        val progress = findViewById<View>(R.id.splashProgress)
        val chairman = findViewById<View>(R.id.splashChairmanText)
        val glowOne = findViewById<View>(R.id.splashGlowOne)
        val glowTwo = findViewById<View>(R.id.splashGlowTwo)
        val root = findViewById<View>(R.id.splashRoot)

        listOf(logoCard, title, tagline, progress, chairman).forEach { view ->
            view.alpha = 0f
            view.translationY = 28f
        }
        logoCard.scaleX = 0.82f
        logoCard.scaleY = 0.82f
        crest.rotation = -8f

        logoCard.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(760L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        crest.animate()
            .rotation(0f)
            .setDuration(850L)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        title.text = ""
        title.animate().alpha(1f).translationY(0f).setStartDelay(260L).setDuration(220L).start()
        startJumpingWordsTitleAnimation(title)
        tagline.animate().alpha(1f).translationY(0f).setStartDelay(420L).setDuration(560L).start()
        progress.animate().alpha(1f).translationY(0f).setStartDelay(620L).setDuration(420L).start()
        chairman.animate().alpha(1f).translationY(0f).setStartDelay(760L).setDuration(520L).start()

        val logoPulse = ObjectAnimator.ofPropertyValuesHolder(
            logoCard,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f, 1f)
        ).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 900L
            start()
        }

        ObjectAnimator.ofFloat(glowOne, View.TRANSLATION_Y, 0f, 34f, 0f).apply {
            duration = 2600L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(glowOne, View.SCALE_X, 1f, 1.12f, 1f).apply {
            duration = 3000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(glowOne, View.SCALE_Y, 1f, 1.12f, 1f).apply {
            duration = 3000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(glowTwo, View.TRANSLATION_Y, 0f, -42f, 0f).apply {
            duration = 3200L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(glowTwo, View.SCALE_X, 1f, 1.16f, 1f).apply {
            duration = 3600L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(glowTwo, View.SCALE_Y, 1f, 1.16f, 1f).apply {
            duration = 3600L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(root, View.ALPHA, 0.97f, 1f, 0.97f).apply {
            duration = 2300L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(crest, View.TRANSLATION_Y, 0f, -6f, 0f).apply {
            duration = 2100L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 780L
            start()
        }

        logoCard.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                logoPulse.cancel()
            }
        })
    }

    private fun startJumpingWordsTitleAnimation(title: TextView) {
        val finalTitle = getString(R.string.full_school_name)
        val words = finalTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
        var wordIndex = 0
        val revealRunnable = object : Runnable {
            override fun run() {
                if (!title.isAttachedToWindow) return
                wordIndex += 1
                title.text = words.take(wordIndex).joinToString(" ")
                title.translationY = -18f
                title.scaleX = 1.05f
                title.scaleY = 1.05f
                title.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(170L)
                    .setInterpolator(OvershootInterpolator(1.7f))
                    .start()
                if (wordIndex < words.size) {
                    title.postDelayed(this, 190L)
                }
            }
        }
        title.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                title.removeCallbacks(revealRunnable)
            }
        })
        title.postDelayed(revealRunnable, 260L)
    }
}
