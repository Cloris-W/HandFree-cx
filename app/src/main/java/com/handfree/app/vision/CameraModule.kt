package com.handfree.app.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.handfree.app.model.HudStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class CameraFrameOrientation(
    val displayRotation: Int,
    val sensorOrientationDegrees: Int,
    val lensFacing: Int,
    val sensorRelativeRotationDegrees: Int,
    val analysisRotationDegrees: Int
)

class CameraModule(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onLandmarks: (List<android.graphics.PointF>, CameraFrameOrientation) -> Unit,
    private val onNoHand: () -> Unit,
    private val onStatus: (HudStatus) -> Unit
) : AutoCloseable {
    companion object {
        private const val TAG = "HandFreeCamera"
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val frontCamera = findFrontCamera()
    private var provider: ProcessCameraProvider? = null
    private var pipeline: HandLandmarkerPipeline? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var displayListenerRegistered = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val activeDisplayId = previewView.display?.displayId
            if (activeDisplayId == null || activeDisplayId == displayId) syncTargetRotation()
        }
    }

    fun bind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                check(provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true) {
                    "No front-facing camera is available"
                }
                val initialRotation = currentDisplayRotation()
                val preview = Preview.Builder()
                    .setTargetRotation(initialRotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(initialRotation)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                pipeline = HandLandmarkerPipeline(
                    context = context,
                    frameOrientationProvider = ::currentFrameOrientation,
                    onLandmarks = onLandmarks,
                    onNoHand = onNoHand,
                    onStatus = onStatus
                )
                analysis.setAnalyzer(cameraExecutor, pipeline!!)
                previewUseCase = preview
                analysisUseCase = analysis
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                registerDisplayListener()
                Log.i(TAG, "Front camera use cases are bound")
            }.onFailure { error ->
                Log.e(TAG, "Unable to bind the front camera", error)
                val status = if (error.message?.contains("No front-facing", ignoreCase = true) == true) {
                    HudStatus.NO_FRONT_CAMERA
                } else {
                    HudStatus.CAMERA_ERROR
                }
                onStatus(status)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun close() {
        if (displayListenerRegistered) displayManager.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
        provider?.unbindAll()
        pipeline?.close()
        cameraExecutor.shutdown()
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        displayListenerRegistered = true
    }

    private fun syncTargetRotation() {
        val rotation = currentDisplayRotation()
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
    }

    private fun currentFrameOrientation(analysisRotationDegrees: Int): CameraFrameOrientation {
        val displayRotation = currentDisplayRotation()
        val sensorOrientation = frontCamera.sensorOrientationDegrees
        val sensorRelativeRotation = relativeRotationDegrees(sensorOrientation, displayRotation, frontCamera.lensFacing)
        return CameraFrameOrientation(
            displayRotation = displayRotation,
            sensorOrientationDegrees = sensorOrientation,
            lensFacing = frontCamera.lensFacing,
            sensorRelativeRotationDegrees = sensorRelativeRotation,
            analysisRotationDegrees = analysisRotationDegrees
        )
    }

    private fun currentDisplayRotation(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return previewView.display?.rotation
                ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: Surface.ROTATION_0
        }
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.rotation
    }

    private fun findFrontCamera(): FrontCameraMetadata {
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: throw IllegalStateException("No front-facing camera is available")
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return FrontCameraMetadata(
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_FRONT
        )
    }

    private fun relativeRotationDegrees(sensorOrientation: Int, displayRotation: Int, lensFacing: Int): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
    }

    private data class FrontCameraMetadata(
        val sensorOrientationDegrees: Int,
        val lensFacing: Int
    )
}
