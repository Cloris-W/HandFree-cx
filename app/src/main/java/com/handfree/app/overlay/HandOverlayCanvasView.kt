package com.handfree.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.handfree.app.R
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.GestureState
import com.handfree.app.model.HudStatus
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Renders cursor state, landmarks, motion direction, and calibration diagnostics. */
class HandOverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class RenderMode {
        CURSOR_ONLY,
        DIAGNOSTIC
    }

    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val cursorCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = dp(12f)
    }
    private val badgeBounds = RectF()
    private var frame = GestureFrame()
    private var renderMode = RenderMode.DIAGNOSTIC
    private var calibrationMode = false
    private var calibrationStep = 1
    private var textContext: Context = context

    fun update(frame: GestureFrame) {
        val stateChanged = this.frame.status != frame.status
        this.frame = frame
        if (stateChanged) invalidate() else postInvalidateOnAnimation()
    }

    fun setRenderMode(mode: RenderMode) {
        renderMode = mode
        postInvalidateOnAnimation()
    }

    fun setCalibrationMode(enabled: Boolean, step: Int = 1) {
        calibrationMode = enabled
        calibrationStep = step.coerceIn(1, 3)
        postInvalidateOnAnimation()
    }

    fun setLanguageTag(languageTag: String) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        textContext = context.createConfigurationContext(configuration)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stateColor = colorFor(frame.status)
        if (renderMode == RenderMode.DIAGNOSTIC) {
            drawLandmarkSkeleton(canvas, stateColor)
            drawTrajectoryArrow(canvas, stateColor)
        }
        drawCursor(canvas, stateColor)
        if (renderMode == RenderMode.DIAGNOSTIC) {
            drawStatusBadge(canvas, stateColor)
            drawMetrics(canvas, stateColor)
        }
    }

    private fun drawLandmarkSkeleton(canvas: Canvas, color: Int) {
        val landmarks = frame.landmarks
        if (landmarks.size < 21) return
        skeletonPaint.color = color
        landmarkPaint.color = color
        for ((start, end) in HAND_CONNECTIONS) {
            canvas.drawLine(pointX(landmarks[start]), pointY(landmarks[start]), pointX(landmarks[end]), pointY(landmarks[end]), skeletonPaint)
        }
        landmarks.forEach { point -> canvas.drawCircle(pointX(point), pointY(point), dp(3f), landmarkPaint) }
    }

    private fun drawTrajectoryArrow(canvas: Canvas, color: Int) {
        val telemetry = frame.telemetry
        if (telemetry.motionVector.deltaX == 0f && telemetry.motionVector.deltaY == 0f) return
        val startX = pointX(telemetry.trajectoryStart)
        val startY = pointY(telemetry.trajectoryStart)
        val endX = pointX(telemetry.trajectoryEnd)
        val endY = pointY(telemetry.trajectoryEnd)
        vectorPaint.color = color
        canvas.drawLine(startX, startY, endX, endY, vectorPaint)
        val angle = atan2(endY - startY, endX - startX)
        val arrowLength = dp(10f)
        val firstAngle = angle + Math.PI.toFloat() * 0.82f
        val secondAngle = angle - Math.PI.toFloat() * 0.82f
        canvas.drawLine(endX, endY, endX + cos(firstAngle) * arrowLength, endY + sin(firstAngle) * arrowLength, vectorPaint)
        canvas.drawLine(endX, endY, endX + cos(secondAngle) * arrowLength, endY + sin(secondAngle) * arrowLength, vectorPaint)
    }

    private fun drawCursor(canvas: Canvas, color: Int) {
        val cursorX = pointX(frame.telemetry.cursor)
        val cursorY = pointY(frame.telemetry.cursor)
        cursorRingPaint.color = color
        cursorCorePaint.color = color
        canvas.drawCircle(cursorX, cursorY, dp(20f), cursorRingPaint)
        canvas.drawCircle(cursorX, cursorY, dp(5f), cursorCorePaint)
    }

    private fun drawStatusBadge(canvas: Canvas, color: Int) {
        val label = statusLabel(frame.status)
        textPaint.textSize = dp(12f)
        val horizontalPadding = dp(10f)
        val verticalPadding = dp(8f)
        val textWidth = textPaint.measureText(label)
        badgeBounds.set(dp(14f), dp(14f), dp(14f) + textWidth + horizontalPadding * 2f, dp(14f) + textPaint.textSize + verticalPadding * 2f)
        badgePaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(badgeBounds, dp(16f), dp(16f), badgePaint)
        textPaint.color = Color.rgb(11, 15, 25)
        canvas.drawText(label, badgeBounds.left + horizontalPadding, badgeBounds.bottom - verticalPadding, textPaint)
    }

    private fun drawMetrics(canvas: Canvas, color: Int) {
        val telemetry = frame.telemetry
        textPaint.color = color
        textPaint.textSize = dp(if (height < dp(420f)) 10f else 13f)
        val baseline = height - dp(if (height < dp(420f)) 12f else 22f)
        val firstLine = textContext.getString(
            R.string.hud_metrics_primary,
            telemetry.thumbExtension,
            telemetry.fingerClosure,
            telemetry.pinchDistance
        )
        val secondLine = textContext.getString(
            R.string.hud_metrics_secondary,
            telemetry.motionVector.deltaX,
            telemetry.motionVector.deltaY,
            telemetry.motionSensitivity
        )
        canvas.drawText(firstLine, dp(14f), baseline - textPaint.textSize - dp(4f), textPaint)
        canvas.drawText(secondLine, dp(14f), baseline, textPaint)
        if (calibrationMode) {
            textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
            canvas.drawText(textContext.getString(R.string.sandbox_step, calibrationStep), dp(14f), badgeBounds.bottom + dp(22f), textPaint)
        }
    }

    private fun statusLabel(status: HudStatus): String = textContext.getString(
        when (status) {
            HudStatus.CAMERA_STARTING -> R.string.hud_status_camera_starting
            HudStatus.SEARCHING -> R.string.hud_status_searching
            HudStatus.TRACKING -> R.string.hud_status_tracking
            HudStatus.CLICK_ACTIVE -> R.string.hud_status_click_active
            HudStatus.DRAG_PREPARED -> R.string.hud_status_drag_prepared
            HudStatus.DRAG_ENGAGED -> R.string.hud_status_drag_engaged
            HudStatus.CAMERA_ERROR -> R.string.hud_status_camera_error
            HudStatus.NO_FRONT_CAMERA -> R.string.hud_status_no_front_camera
            HudStatus.MODEL_REQUIRED -> R.string.hud_status_model_required
            HudStatus.MODEL_ERROR -> R.string.hud_status_model_error
            HudStatus.VISION_ERROR -> R.string.hud_status_vision_error
        }
    )

    private fun colorFor(status: HudStatus): Int = when (status) {
        HudStatus.CLICK_ACTIVE -> COLOR_CLICK
        HudStatus.DRAG_PREPARED, HudStatus.DRAG_ENGAGED -> COLOR_DRAG
        else -> COLOR_NORMAL
    }

    private fun pointX(point: PointF): Float = point.x.coerceIn(0f, 1f) * width
    private fun pointY(point: PointF): Float = point.y.coerceIn(0f, 1f) * height
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        val COLOR_NORMAL: Int = Color.parseColor("#00E5FF")
        val COLOR_CLICK: Int = Color.parseColor("#00E676")
        val COLOR_DRAG: Int = Color.parseColor("#FFD700")
        val HAND_CONNECTIONS = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            0 to 9, 9 to 10, 10 to 11, 11 to 12,
            0 to 13, 13 to 14, 14 to 15, 15 to 16,
            0 to 17, 17 to 18, 18 to 19, 19 to 20
        )
    }
}
