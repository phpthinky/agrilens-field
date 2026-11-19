package com.agrilens.field.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Alignment guide over the webcam preview: a narrow vertical strip where the
 * reagent tube must stand. It matches what the server actually measures
 * (SnapshotColorService on a 320×240 frame): the middle 40 % of the tube's
 * width, or for a colorless liquid a 30/320-wide centre strip, skipping the
 * top and bottom 10 % (cap, tube base).
 *
 * Drawn over [target]'s bounds (the video), not this view's, so it stays
 * aligned when the preview is letterboxed.
 */
class CaptureZoneOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The preview view whose bounds the guide is drawn over; this view's own bounds if null. */
    var target: View? = null
        set(value) {
            field = value
            value?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 2f * density
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 255, 255, 0)
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = target
        val left = (t?.left ?: 0).toFloat()
        val top = (t?.top ?: 0).toFloat()
        val w = (t?.width ?: width).toFloat()
        val h = (t?.height ?: height).toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = left + w * (0.5f + OFFSET_FRACTION)
        val half = w * STRIP_FRACTION / 2f
        val y1 = top + h * MARGIN
        val y2 = top + h * (1f - MARGIN)

        canvas.drawRect(cx - half, y1, cx + half, y2, stripPaint)
        canvas.drawLine(cx, top, cx, y1, centerPaint)
        canvas.drawLine(cx, y2, cx, top + h, centerPaint)
    }

    companion object {
        /** Same as SnapshotColorService::STRIP. */
        private const val STRIP_FRACTION = 30f / 320f
        /** Same as SnapshotColorService::MARGIN. */
        private const val MARGIN = 0.10f
        /**
         * The colorimeter box holds the tube slightly left of the camera's centre
         * (~25 px on a 1080 px-wide preview), so the guide is shifted to sit on it.
         * Display only: the server's colorless fallback strip still uses the true centre.
         */
        private const val OFFSET_FRACTION = -25f / 1080f
    }
}
