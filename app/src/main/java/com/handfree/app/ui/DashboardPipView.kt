package com.handfree.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.handfree.app.model.GestureState
import com.handfree.app.model.GestureTelemetry
import kotlin.math.min

/** A privacy-safe dashboard preview: it visualizes landmarks, never camera pixels. */
class DashboardPipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 49, 77); strokeWidth = 1f }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(99, 102, 241); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118); style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(233, 242, 255); textSize = 26f; typeface = android.graphics.Typeface.MONOSPACE }
    private var telemetry = GestureTelemetry()
    private var landmarks: List<PointF> = emptyList()
    private var sandboxStage = 0

    fun render(telemetry: GestureTelemetry, landmarks: List<PointF> = emptyList()) {
        this.telemetry = telemetry
        if (landmarks.isNotEmpty()) this.landmarks = landmarks
        postInvalidateOnAnimation()
    }

    fun selectSandboxStage(stage: Int) {
        sandboxStage = stage
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val step = min(width, height) / 8f
        for (x in 0..8) canvas.drawLine(x * step, 0f, x * step, height, gridPaint)
        for (y in 0..8) canvas.drawLine(0f, y * step, width, y * step, gridPaint)

        val cx = telemetry.cursor.x * width
        val cy = telemetry.cursor.y * height
        accentPaint.color = when (telemetry.state) {
            GestureState.SEARCHING -> Color.rgb(255, 176, 32)
            GestureState.TRACKING -> Color.rgb(99, 102, 241)
            GestureState.CLICK, GestureState.DRAGGING -> Color.rgb(0, 230, 118)
        }
        canvas.drawCircle(cx, cy, 23f, accentPaint)
        canvas.drawCircle(cx, cy, 7f, pointPaint)

        landmarks.forEach { point -> canvas.drawCircle(point.x * width, point.y * height, 5f, pointPaint) }
        canvas.drawText("PIP / LANDMARK VISUALIZER", 18f, 34f, textPaint)
        canvas.drawText("SANDBOX STAGE: ${sandboxStage + 1}", 18f, height - 22f, textPaint)
    }
}

