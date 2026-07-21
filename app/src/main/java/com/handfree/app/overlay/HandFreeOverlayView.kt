package com.handfree.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import com.handfree.app.model.GestureState
import com.handfree.app.model.GestureTelemetry
import java.util.Locale

class HandFreeOverlayView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private var telemetry = GestureTelemetry()
    private var status = "ENGINE INITIALIZING"

    fun update(telemetry: GestureTelemetry, status: String) {
        this.telemetry = telemetry
        this.status = status
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val color = when (telemetry.state) {
            GestureState.SEARCHING -> Color.rgb(255, 176, 32)
            GestureState.TRACKING -> Color.rgb(99, 102, 241)
            GestureState.CLICK, GestureState.DRAGGING -> Color.rgb(0, 230, 118)
        }
        ringPaint.color = color
        corePaint.color = color
        val x = telemetry.cursor.x * width
        val y = telemetry.cursor.y * height
        canvas.drawCircle(x, y, 26f, ringPaint)
        canvas.drawCircle(x, y, 7f, corePaint)

        textPaint.color = color
        canvas.drawText(
            String.format(Locale.US, "%s | FPS:%02d | SCALE:%.2f | PINCH:%.2f", status, telemetry.fps, telemetry.handScale, telemetry.pinchDistance),
            22f,
            height - 36f,
            textPaint
        )
    }
}

