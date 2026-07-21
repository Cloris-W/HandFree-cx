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
import com.handfree.app.model.GestureEngine
import com.handfree.app.model.GestureTelemetry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraModule(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val gestureEngine: GestureEngine,
    private val onTelemetry: (GestureTelemetry, String) -> Unit
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
                    "No front-facing camera is available on this device"
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                pipeline = HandLandmarkerPipeline(
                    context = context,
                    onLandmarks = { points -> onTelemetry(gestureEngine.onLandmarks(points), "TRACKING") },
                    onNoHand = { onTelemetry(gestureEngine.onNoHand(), "SEARCHING") },
                    onStatus = { status -> onTelemetry(gestureEngine.onNoHand(), status) }
                )
                analysis.setAnalyzer(cameraExecutor, pipeline!!)
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                Log.i(TAG, "Front camera use cases are bound")
            }.onFailure { error ->
                Log.e(TAG, "Unable to bind the front camera", error)
                onTelemetry(gestureEngine.onNoHand(), "CAMERA ERROR")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun close() {
        provider?.unbindAll()
        pipeline?.close()
        cameraExecutor.shutdown()
    }
}
