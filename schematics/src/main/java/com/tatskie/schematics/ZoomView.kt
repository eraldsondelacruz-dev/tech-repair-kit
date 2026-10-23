package com.tatskie.schematics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val MIN_SCALE = 0.3f
        private const val MAX_SCALE = 12f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val TAP_TIMEOUT = 250L
    }

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val start = PointF()
    private val mid = PointF()
    private val values = FloatArray(9)
    private var mode = NONE
    private var lastTapTime = 0L
    private val baseMatrix = Matrix()
    private var baseScaleVal = 1f
    var onIdleZoom: ((Float) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    var onCanvasTap: (() -> Unit)? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    matrix.set(savedMatrix)
                    val cur = currentScale()
                    if (cur <= 0f) return true
                    val f = det.scaleFactor.coerceIn(MIN_SCALE / cur, MAX_SCALE / cur)
                    matrix.postScale(f, f, det.focusX, det.focusY)
                    onZoomChanged?.invoke(currentScale())
                    return true
                }
            }
        )
        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val base = baseScale()
                    val cur = currentScale()
                    if (base <= 0f) return true
                    if (cur > base * 1.2f) {
                        resetToBase()
                    } else {
                        matrix.set(savedMatrix)
                        val f = DOUBLE_TAP_ZOOM / cur
                        matrix.postScale(f, f, e.x, e.y)
                        imageMatrix = matrix
                        onZoomChanged?.invoke(currentScale())
                    }
                    return true
                }
            }
        )
    }

    fun setBitmap(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post { fitToScreen() }
    }

    fun setBitmapHd(bitmap: Bitmap, previewWidth: Int) {
        if (bitmap.width <= 0 || previewWidth <= 0 || width <= 0) return
        val k = previewWidth.toFloat() / bitmap.width
        setImageBitmap(bitmap)
        if (k in 0.001f..0.999f) {
            matrix.set(imageMatrix)
            matrix.postScale(k, k)
            savedMatrix.set(matrix)
            baseMatrix.postScale(k, k)
            baseScaleVal *= k
        }
        imageMatrix = matrix
        onZoomChanged?.invoke(currentScale())
    }

    private fun fitToScreen() {
        val d = drawable ?: return
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            post { fitToScreen() }
            return
        }
        val scale = minOf(w / bw, h / bh)
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate((w - bw * scale) / 2f, (h - bh * scale) / 2f)
        baseMatrix.set(matrix)
        baseScaleVal = scale
        savedMatrix.set(matrix)
        imageMatrix = matrix
        onZoomChanged?.invoke(currentScale())
    }

    fun currentLevel(): Float = currentScale() / baseScaleVal.coerceAtLeast(0.0001f)

    fun zoomTo(level: Float) {
        if (width <= 0 || height <= 0 || baseScaleVal <= 0f) return
        val lvl = level.coerceIn(MIN_SCALE / baseScaleVal, MAX_SCALE / baseScaleVal)
        matrix.set(baseMatrix)
        matrix.postScale(lvl, lvl, width / 2f, height / 2f)
        savedMatrix.set(matrix)
        imageMatrix = matrix
        onZoomChanged?.invoke(currentScale())
    }

    fun zoomToScale(targetScale: Float) {
        zoomTo(targetScale / baseScaleVal.coerceAtLeast(0.0001f))
    }

    private fun resetToBase() {
        savedMatrix.set(baseMatrix)
        matrix.set(baseMatrix)
        imageMatrix = matrix
        onZoomChanged?.invoke(currentScale())
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun baseScale(): Float = baseScaleVal

    private fun midPoint(out: PointF, ev: MotionEvent) {
        out.set(
            (ev.getX(0) + ev.getX(1)) / 2f,
            (ev.getY(0) + ev.getY(1)) / 2f
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                matrix.set(imageMatrix)
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
                lastTapTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                savedMatrix.set(matrix)
                midPoint(mid, event)
                mode = ZOOM
            }
            MotionEvent.ACTION_UP -> {
                mode = NONE
                val dx = event.x - start.x
                val dy = event.y - start.y
                if (dx * dx + dy * dy < 225f) onCanvasTap?.invoke()
                matrix.set(imageMatrix)
                savedMatrix.set(matrix)
                onIdleZoom?.invoke(currentScale() / baseScaleVal)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                matrix.set(imageMatrix)
                savedMatrix.set(matrix)
                onIdleZoom?.invoke(currentScale() / baseScaleVal)
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    DRAG -> {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime > TAP_TIMEOUT) {
                            matrix.set(savedMatrix)
                            matrix.postTranslate(event.x - start.x, event.y - start.y)
                            imageMatrix = matrix
                        }
                    }
                    ZOOM -> {
                        imageMatrix = matrix
                        onZoomChanged?.invoke(currentScale())
                    }
                }
            }
        }
        return true
    }
}
