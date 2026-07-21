package com.handfree.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.HudStatus

class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = COLOR_NORMAL
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_NORMAL
    }
    private var cursorColor = COLOR_NORMAL

    fun update(frame: GestureFrame, screenWidth: Int, screenHeight: Int) {
        val nextColor = colorFor(frame.status)
        val colorChanged = cursorColor != nextColor
        cursorColor = nextColor
        ringPaint.color = nextColor
        corePaint.color = nextColor
        val centerX = frame.telemetry.cursor.x.coerceIn(0f, 1f) * screenWidth
        val centerY = frame.telemetry.cursor.y.coerceIn(0f, 1f) * screenHeight
        translationX = centerX - width / 2f
        translationY = centerY - height / 2f
        if (colorChanged) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawCircle(centerX, centerY, dp(20f), ringPaint)
        canvas.drawCircle(centerX, centerY, dp(5f), corePaint)
    }

    private fun colorFor(status: HudStatus): Int = when (status) {
        HudStatus.CLICK_ACTIVE -> COLOR_CLICK
        HudStatus.DRAG_PREPARED, HudStatus.DRAG_ENGAGED -> COLOR_DRAG
        else -> COLOR_NORMAL
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        val COLOR_NORMAL = Color.parseColor("#00E5FF")
        val COLOR_CLICK = Color.parseColor("#00E676")
        val COLOR_DRAG = Color.parseColor("#FFD700")
    }
}
