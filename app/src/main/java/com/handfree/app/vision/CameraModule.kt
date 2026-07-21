package com.handfree.app.vision

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.graphics.PointF
import com.handfree.app.model.GestureStateMachine
import com.handfree.app.model.GestureTelemetry
import com.handfree.app.model.HudStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraModule(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val stateMachine: GestureStateMachine,
    private val onTelemetry: (GestureTelemetry, List<PointF>, HudStatus) -> Unit
) : AutoCloseable {
    companion object {
        private const val TAG = "HandFreeCamera"
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var pipeline: HandLandmarkerPipeline? = null

    fun bind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                check(provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true) {
                    "No front-facing camera is available"
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                pipeline = HandLandmarkerPipeline(
                    context = context,
                    onLandmarks = { points ->
                        val telemetry = stateMachine.onLandmarks(points)
                        onTelemetry(telemetry, points, HudStatus.fromGestureState(telemetry.state))
                    },
                    onNoHand = {
                        val telemetry = stateMachine.onNoHand()
                        onTelemetry(telemetry, emptyList(), HudStatus.SEARCHING)
                    },
                    onStatus = { status ->
                        onTelemetry(stateMachine.onNoHand(), emptyList(), status)
                    }
                )
                analysis.setAnalyzer(cameraExecutor, pipeline!!)
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                Log.i(TAG, "Front camera use cases are bound")
            }.onFailure { error ->
                Log.e(TAG, "Unable to bind the front camera", error)
                val status = if (error.message?.contains("No front-facing", ignoreCase = true) == true) {
                    HudStatus.NO_FRONT_CAMERA
                } else {
                    HudStatus.CAMERA_ERROR
                }
                onTelemetry(stateMachine.onNoHand(), emptyList(), status)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun close() {
        provider?.unbindAll()
        pipeline?.close()
        cameraExecutor.shutdown()
    }
}
