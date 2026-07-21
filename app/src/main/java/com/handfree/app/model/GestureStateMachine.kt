package com.handfree.app.model

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max

enum class GestureState {
    SEARCHING,
    TRACKING,
    CLICK_ACTIVE,
    DRAG_PREPARED,
    DRAG_ENGAGED
}

enum class HudStatus {
    CAMERA_STARTING,
    SEARCHING,
    TRACKING,
    CLICK_ACTIVE,
    DRAG_PREPARED,
    DRAG_ENGAGED,
    CAMERA_ERROR,
    NO_FRONT_CAMERA,
    MODEL_REQUIRED,
    MODEL_ERROR,
    VISION_ERROR;

    companion object {
        fun fromGestureState(state: GestureState): HudStatus = when (state) {
            GestureState.SEARCHING -> SEARCHING
            GestureState.TRACKING -> TRACKING
            GestureState.CLICK_ACTIVE -> CLICK_ACTIVE
            GestureState.DRAG_PREPARED -> DRAG_PREPARED
            GestureState.DRAG_ENGAGED -> DRAG_ENGAGED
        }
    }
}

sealed interface GestureAction {
    data class Click(val position: PointF) : GestureAction
    data class Swipe(
        val start: PointF,
        val end: PointF,
        val durationMillis: Long
    ) : GestureAction
}

data class GestureSettings(
    val motionSensitivity: Float = 1.0f,
    val pinchThreshold: Float = 0.22f,
    val thumbExtensionThreshold: Float = 1.20f,
    val fingerFoldThreshold: Float = 0.88f
) {
    fun normalized(): GestureSettings = copy(
        motionSensitivity = motionSensitivity.coerceIn(0.5f, 3.0f),
        pinchThreshold = pinchThreshold.coerceIn(0.08f, 0.35f),
        thumbExtensionThreshold = thumbExtensionThreshold.coerceIn(0.70f, 2.40f),
        fingerFoldThreshold = fingerFoldThreshold.coerceIn(0.35f, 1.60f)
    )
}

data class MotionVector(
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val speed: Float = 0f
)

data class GestureTelemetry(
    val state: GestureState = GestureState.SEARCHING,
    val fps: Int = 0,
    val handScale: Float = 0f,
    val pinchDistance: Float = 0f,
    val thumbExtension: Float = 0f,
    val fingerClosure: Float = 0f,
    val cursor: PointF = PointF(0.5f, 0.5f),
    val trajectoryStart: PointF = PointF(0.5f, 0.5f),
    val trajectoryEnd: PointF = PointF(0.5f, 0.5f),
    val motionVector: MotionVector = MotionVector(),
    val motionSensitivity: Float = 1.0f
)

data class GestureFrame(
    val telemetry: GestureTelemetry = GestureTelemetry(),
    val landmarks: List<PointF> = emptyList(),
    val status: HudStatus = HudStatus.SEARCHING
)

/**
 * Converts normalized hand landmarks into immediate click and swipe actions. Cursor movement is
 * based solely on direct wrist deltas, so no axis inversion or camera-mirroring correction is
 * applied in this class.
 */
class GestureStateMachine(
    initialSettings: GestureSettings = GestureSettings(),
    private val onAction: (GestureAction) -> Unit
) {
    companion object {
        private const val MIN_HAND_SCALE = 0.055f
        private const val DRAG_MOTION_THRESHOLD = 0.006f
        private const val MIN_SWIPE_DISTANCE = 0.018f
        private const val WRIST_INDEX = 0
        private const val THUMB_TIP_INDEX = 4
        private const val INDEX_MCP_INDEX = 5
        private const val INDEX_TIP_INDEX = 8
        private const val MIDDLE_MCP_INDEX = 9
        private const val MIDDLE_TIP_INDEX = 12
        private const val RING_MCP_INDEX = 13
        private const val RING_TIP_INDEX = 16
        private const val PINKY_MCP_INDEX = 17
        private const val PINKY_TIP_INDEX = 20
    }

    @Volatile
    private var settings: GestureSettings = initialSettings.normalized()
    private var state = GestureState.SEARCHING
    private var cursor = PointF(0.5f, 0.5f)
    private var previousWrist: PointF? = null
    private var previousFrameNanos = 0L
    private var clickLatched = false
    private var dragStartCursor: PointF? = null

    fun updateSettings(updatedSettings: GestureSettings) {
        settings = updatedSettings.normalized()
    }

    fun onLandmarks(landmarks: List<PointF>, frameNanos: Long = System.nanoTime()): GestureTelemetry {
        if (landmarks.size < 21) return onNoHand(frameNanos)

        val activeSettings = settings
        val wrist = landmarks[WRIST_INDEX]
        val handScale = distance(landmarks[WRIST_INDEX], landmarks[MIDDLE_MCP_INDEX])
        if (handScale < MIN_HAND_SCALE) return onNoHand(frameNanos)

        val priorWrist = previousWrist
        val elapsedNanos = (frameNanos - previousFrameNanos).coerceAtLeast(0L)
        val rawDeltaX = priorWrist?.let { wrist.x - it.x } ?: 0f
        val rawDeltaY = priorWrist?.let { wrist.y - it.y } ?: 0f
        val cursorBeforeMove = cursor.copyPoint()

        // Positive landmark deltas are applied directly to cursor deltas to lock directions 1:1.
        cursor = PointF(
            (cursor.x + rawDeltaX * activeSettings.motionSensitivity).coerceIn(0f, 1f),
            (cursor.y + rawDeltaY * activeSettings.motionSensitivity).coerceIn(0f, 1f)
        )
        previousWrist = wrist.copyPoint()
        previousFrameNanos = frameNanos

        val palmCenter = calculatePalmCenter(landmarks)
        val pinchDistance = distance(landmarks[THUMB_TIP_INDEX], landmarks[INDEX_TIP_INDEX]) / handScale
        val thumbExtension = distance(landmarks[THUMB_TIP_INDEX], palmCenter) / handScale
        val fingerClosure = listOf(INDEX_TIP_INDEX, MIDDLE_TIP_INDEX, RING_TIP_INDEX, PINKY_TIP_INDEX)
            .maxOf { distance(landmarks[it], palmCenter) / handScale }
        val frameSpeed = if (elapsedNanos > 0L) {
            hypot(rawDeltaX, rawDeltaY) * 1_000_000_000f / elapsedNanos
        } else {
            0f
        }
        val motionVector = MotionVector(rawDeltaX, rawDeltaY, frameSpeed)
        val dragPose = thumbExtension >= activeSettings.thumbExtensionThreshold &&
            fingerClosure <= activeSettings.fingerFoldThreshold
        val motionMagnitude = hypot(rawDeltaX, rawDeltaY)

        when {
            dragPose && state != GestureState.DRAG_ENGAGED && motionMagnitude >= DRAG_MOTION_THRESHOLD -> {
                state = GestureState.DRAG_ENGAGED
                dragStartCursor = cursorBeforeMove
                clickLatched = false
            }
            dragPose && state != GestureState.DRAG_ENGAGED -> {
                state = GestureState.DRAG_PREPARED
                clickLatched = false
            }
            !dragPose && state == GestureState.DRAG_ENGAGED -> {
                dispatchSwipeIfMeaningful(cursor, frameSpeed)
                state = GestureState.TRACKING
            }
            !dragPose && state == GestureState.DRAG_PREPARED -> {
                state = GestureState.TRACKING
            }
            !dragPose && pinchDistance <= activeSettings.pinchThreshold -> {
                if (!clickLatched) {
                    onAction(GestureAction.Click(cursor.copyPoint()))
                    clickLatched = true
                }
                state = GestureState.CLICK_ACTIVE
            }
            else -> {
                clickLatched = false
                state = GestureState.TRACKING
            }
        }

        return GestureTelemetry(
            state = state,
            fps = estimateFps(elapsedNanos),
            handScale = handScale,
            pinchDistance = pinchDistance,
            thumbExtension = thumbExtension,
            fingerClosure = fingerClosure,
            cursor = cursor.copyPoint(),
            trajectoryStart = priorWrist ?: wrist.copyPoint(),
            trajectoryEnd = wrist.copyPoint(),
            motionVector = motionVector,
            motionSensitivity = activeSettings.motionSensitivity
        )
    }

    fun onNoHand(frameNanos: Long = System.nanoTime()): GestureTelemetry {
        if (state == GestureState.DRAG_ENGAGED) dispatchSwipeIfMeaningful(cursor, 0f)
        state = GestureState.SEARCHING
        previousWrist = null
        previousFrameNanos = frameNanos
        clickLatched = false
        return GestureTelemetry(cursor = cursor.copyPoint(), motionSensitivity = settings.motionSensitivity)
    }

    private fun dispatchSwipeIfMeaningful(endCursor: PointF, releaseSpeed: Float) {
        val startCursor = dragStartCursor ?: endCursor
        dragStartCursor = null
        val distance = distance(startCursor, endCursor)
        if (distance < MIN_SWIPE_DISTANCE) return
        val duration = swipeDurationForSpeed(releaseSpeed)
        onAction(GestureAction.Swipe(startCursor.copyPoint(), endCursor.copyPoint(), duration))
    }

    private fun calculatePalmCenter(landmarks: List<PointF>): PointF {
        val indices = intArrayOf(WRIST_INDEX, INDEX_MCP_INDEX, MIDDLE_MCP_INDEX, RING_MCP_INDEX, PINKY_MCP_INDEX)
        val x = indices.sumOf { landmarks[it].x.toDouble() }.toFloat() / indices.size
        val y = indices.sumOf { landmarks[it].y.toDouble() }.toFloat() / indices.size
        return PointF(x, y)
    }

    private fun swipeDurationForSpeed(speed: Float): Long {
        val normalizedSpeed = (speed / 0.75f).coerceIn(0f, 1f)
        return (620f - normalizedSpeed * 420f).toLong().coerceIn(140L, 620L)
    }

    private fun estimateFps(elapsedNanos: Long): Int {
        if (elapsedNanos <= 0L) return 0
        return (1_000_000_000L / elapsedNanos).toInt().coerceIn(0, 60)
    }

    private fun distance(first: PointF, second: PointF): Float = hypot(first.x - second.x, first.y - second.y)

    private fun PointF.copyPoint(): PointF = PointF(x, y)

}
