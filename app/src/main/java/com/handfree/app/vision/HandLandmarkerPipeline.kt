package com.handfree.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer

/**
 * MediaPipe live-stream adapter. The hand_landmarker.task model is intentionally an external
 * asset so it can be updated without changing app code; setup shows a precise status if absent.
 */
class HandLandmarkerPipeline(
    private val context: Context,
    private val onLandmarks: (List<PointF>) -> Unit,
    private val onNoHand: () -> Unit,
    private val onStatus: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    companion object { const val MODEL_FILE = "hand_landmarker.task" }

    private val landmarker: HandLandmarker? = createLandmarker()

    override fun analyze(image: ImageProxy) {
        val detector = landmarker ?: run {
            image.close()
            return
        }
        try {
            val mpImage = BitmapImageBuilder(image.toRgbaBitmap()).build()
            detector.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (error: Exception) {
            onStatus("VISION ERROR")
        } finally {
            image.close()
        }
    }

    override fun close() {
        landmarker?.close()
    }

    private fun createLandmarker(): HandLandmarker? {
        val modelPresent = runCatching { context.assets.list("")?.contains(MODEL_FILE) == true }.getOrDefault(false)
        if (!modelPresent) {
            onStatus("MODEL REQUIRED")
            return null
        }
        return runCatching {
            val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_FILE).build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setResultListener { result, _ ->
                    val points = result.landmarks().firstOrNull()?.map { PointF(it.x(), it.y()) }.orEmpty()
                    if (points.isEmpty()) onNoHand() else onLandmarks(points)
                }
                .setErrorListener { onStatus("VISION ERROR") }
                .build()
            HandLandmarker.createFromOptions(context, options)
        }.onFailure { onStatus("MODEL ERROR") }.getOrNull()
    }

    /** CameraX supplies RGBA_8888 frames; copy rows to account for device-specific padding. */
    private fun ImageProxy.toRgbaBitmap(): Bitmap {
        val plane = planes.first()
        val bytesPerPixel = 4
        val bytes = ByteArray(width * height * bytesPerPixel)
        val row = ByteArray(width * bytesPerPixel)
        val buffer = plane.buffer
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row, 0, row.size)
            System.arraycopy(row, 0, bytes, y * row.size, row.size)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }
    }
}
