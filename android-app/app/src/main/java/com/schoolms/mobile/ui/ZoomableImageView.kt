package com.schoolms.mobile.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val matrixValues = FloatArray(9)
    private val imageMatrixInternal = Matrix()
    private val last = PointF()
    private var mode = MODE_NONE
    private var minScale = 1f
    private var maxScale = 5f
    private var saveScale = 1f

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (saveScale > minScale + 0.05f) minScale else 2.2f.coerceAtMost(maxScale)
            val factor = target / saveScale
            imageMatrixInternal.postScale(factor, factor, e.x, e.y)
            saveScale = target
            fixTranslation()
            imageMatrix = imageMatrixInternal
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = imageMatrixInternal
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        post { fitToCenter() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fitToCenter() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last.set(curr)
                mode = MODE_DRAG
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_DRAG && saveScale > minScale) {
                    val dx = curr.x - last.x
                    val dy = curr.y - last.y
                    imageMatrixInternal.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = imageMatrixInternal
                    last.set(curr.x, curr.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = MODE_NONE
        }
        return true
    }

    private fun fitToCenter() {
        val d = drawable ?: return
        val viewW = width.toFloat().coerceAtLeast(1f)
        val viewH = height.toFloat().coerceAtLeast(1f)
        val bmW = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val bmH = d.intrinsicHeight.toFloat().coerceAtLeast(1f)

        imageMatrixInternal.reset()
        val scale = minOf(viewW / bmW, viewH / bmH)
        minScale = scale
        saveScale = scale
        val redundantX = (viewW - bmW * scale) / 2f
        val redundantY = (viewH - bmH * scale) / 2f
        imageMatrixInternal.postScale(scale, scale)
        imageMatrixInternal.postTranslate(redundantX, redundantY)
        imageMatrix = imageMatrixInternal
    }

    private fun fixTranslation() {
        imageMatrixInternal.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]

        val d = drawable ?: return
        val contentW = d.intrinsicWidth * scaleX
        val contentH = d.intrinsicHeight * scaleY
        val fixX = getFix(transX, width.toFloat(), contentW)
        val fixY = getFix(transY, height.toFloat(), contentH)
        if (fixX != 0f || fixY != 0f) imageMatrixInternal.postTranslate(fixX, fixY)
    }

    private fun getFix(trans: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            (viewSize - contentSize) / 2f - trans
        } else {
            when {
                trans > 0 -> -trans
                trans < viewSize - contentSize -> (viewSize - contentSize) - trans
                else -> 0f
            }
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val prevScale = saveScale
            saveScale = (saveScale * factor).coerceIn(minScale, maxScale)
            val applied = saveScale / prevScale
            imageMatrixInternal.postScale(applied, applied, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = imageMatrixInternal
            return true
        }
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_DRAG = 1
    }
}
