package com.agrilens.field.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Crosshair square over the webcam preview showing where the reagent tube
 * must sit: the server averages the centre 70×70 px of the frame scaled to
 * 320×240 (same as the web colorimeter), i.e. 70/320 of the frame's width.
 */
class CaptureZoneOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = width * ZONE_FRACTION / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
    }

    companion object {
        private const val ZONE_FRACTION = 70f / 320f
    }
}
