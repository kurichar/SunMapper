package com.sunmapper


import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View


/**
 * A simple View that will draw a red rectangle whenever
 * updateBBox(…) is called from your Activity’s UI thread.
 */
class BBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var rect: RectF? = null

    /** Call this from the Main/UI thread to show a new box. */
    fun updateBBox(x0: Float, y0: Float, x1: Float, y1: Float) {
        rect = RectF(x0, y0, x1, y1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect?.let { canvas.drawRect(it, paint) }
    }
}
