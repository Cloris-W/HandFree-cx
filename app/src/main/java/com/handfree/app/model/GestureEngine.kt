package com.handfree.app.model

import android.graphics.PointF
import kotlin.math.hypot

enum class GestureState { SEARCHING, TRACKING, CLICK, DRAGGING }

sealed interface GestureAction {
    data class Click(val x: Float, val y: Float) : GestureAction
    data class DragStart(val x: Float, val y: Float) : GestureAction
    data class DragMove(val x: Float, val y: Float) : GestureAction
    data object DragEnd : GestureAction
}

data class GestureTelemetry(
    val state: GestureState = GestureState.SEARCHING,
    val fps: Int = 0,
    val handScale: Float = 0f,
    val pinchDistance: Float = 0f,
    val cursor: PointF = PointF(0.5f, 0.5f)
)

/**
 * Converts MediaPipe's normalized 21 hand points into debounced actions. It deliberately keeps
 * camera and Android-service concerns out of the math pipeline, making the thresholds testable.
 */
class GestureEngine(
    initialPinchThreshold: Float = DEFAULT_PINCH_THRESHOLD,
    private val onAction: (GestureAction) -> Unit
) {
    companion object {
        const val DEFAULT_PINCH_THRESHOLD = 0.20f
        private const val MIN_HAND_SCALE = 0.06f
        private const val CLICK_FRAMES = 3
        private const val DRAG_FRAMES = 5
        private const val EMA_ALPHA = 0.27f
    }

    var pinchThreshold: Float = initialPinchThreshold.coerceIn(0.10f, 0.35f)
    private var clickFrames = 0
    private var openPalmFrames = 0
    private var isClickLatched = false
    private var isDragging = false
    private var smoothedCursor = PointF(0.5f, 0.5f)
    private var previousFrameNs = 0L

    fun onLandmarks(landmarks: List<PointF>, frameTimeNs: Long = System.nanoTime()): GestureTelemetry {
        if (landmarks.size < 21) return onNoHand()

        val handScale = distance(landmarks[0], landmarks[5])
        if (handScale < MIN_HAND_SCALE) return onNoHand()

        val cursor = landmarks[8]
        smoothedCursor = PointF(
            lerp(smoothedCursor.x, cursor.x, EMA_ALPHA),
            lerp(smoothedCursor.y, cursor.y, EMA_ALPHA)
        )
        val pinch = distance(landmarks[4], landmarks[8]) / handScale
        val isOpenPalm = isOpenPalm(landmarks)
        val fps = estimateFps(frameTimeNs)

        if (isOpenPalm) openPalmFrames++ else openPalmFrames = 0
        if (isDragging) {
            if (!isOpenPalm) {
                isDragging = false
                onAction(GestureAction.DragEnd)
            } else {
                onAction(GestureAction.DragMove(smoothedCursor.x, smoothedCursor.y))
            }
            return telemetry(if (isDragging) GestureState.DRAGGING else GestureState.TRACKING, fps, handScale, pinch)
        }

        // Drag has priority over click. This mutual exclusion avoids a click while the user ends a drag.
        if (openPalmFrames >= DRAG_FRAMES) {
            isDragging = true
            clickFrames = 0
            isClickLatched = false
            onAction(GestureAction.DragStart(smoothedCursor.x, smoothedCursor.y))
            return telemetry(GestureState.DRAGGING, fps, handScale, pinch)
        }

        if (pinch < pinchThreshold) {
            clickFrames++
            if (clickFrames >= CLICK_FRAMES && !isClickLatched) {
                isClickLatched = true
                onAction(GestureAction.Click(smoothedCursor.x, smoothedCursor.y))
                return telemetry(GestureState.CLICK, fps, handScale, pinch)
            }
        } else {
            clickFrames = 0
            isClickLatched = false
        }
        return telemetry(GestureState.TRACKING, fps, handScale, pinch)
    }

    fun onNoHand(): GestureTelemetry {
        if (isDragging) onAction(GestureAction.DragEnd)
        isDragging = false
        clickFrames = 0
        openPalmFrames = 0
        isClickLatched = false
        return GestureTelemetry(cursor = smoothedCursor)
    }

    private fun telemetry(
        state: GestureState,
        fps: Int,
        handScale: Float,
        pinch: Float
    ) = GestureTelemetry(state, fps, handScale, pinch, smoothedCursor)

    private fun isOpenPalm(points: List<PointF>): Boolean {
        val fingersExtended = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
            .all { (tip, pip) -> points[tip].y < points[pip].y }
        // The thumb check rejects the common "four fingers together" pose.
        val thumbOpen = distance(points[4], points[17]) > distance(points[5], points[17]) * 0.85f
        return fingersExtended && thumbOpen
    }

    private fun estimateFps(nowNs: Long): Int {
        val previous = previousFrameNs
        previousFrameNs = nowNs
        if (previous == 0L || nowNs <= previous) return 0
        return (1_000_000_000L / (nowNs - previous)).toInt().coerceAtMost(60)
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
    private fun lerp(from: Float, to: Float, alpha: Float) = from + (to - from) * alpha
}

