package com.handfree.app.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.content.pm.ServiceInfo
import android.graphics.PointF
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.handfree.app.R
import com.handfree.app.accessibility.HandFreeAccessibilityService
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.GestureSettings
import com.handfree.app.model.GestureStateMachine
import com.handfree.app.model.HudStatus
import com.handfree.app.vision.CameraFrameOrientation
import com.handfree.app.vision.CameraModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HandFreeOverlayService : LifecycleService() {
    companion object {
        private const val CHANNEL_ID = "handfree_control"
        private const val NOTIFICATION_ID = 2105
        private const val PREFERENCES_NAME = "handfree_settings"
        private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
        private const val KEY_PINCH_THRESHOLD = "pinch_threshold"
        private const val KEY_THUMB_EXTENSION_THRESHOLD = "thumb_extension_threshold"
        private const val KEY_FINGER_FOLD_THRESHOLD = "finger_fold_threshold"
        private const val KEY_LANGUAGE_TAG = "language_tag"
        private const val LAYOUT_UPDATE_INTERVAL_MILLIS = 16L

        fun start(context: Context) {
            val intent = Intent(context, HandFreeOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayBounds: DisplayBounds? = null
    private var cameraModule: CameraModule? = null
    private var layoutUpdateJob: Job? = null
    private var lastLayoutUpdateMillis = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService advances its Lifecycle through Service.onStart(). CameraX only binds
        // use cases while that Lifecycle is started, so this call must not be skipped.
        super.onStartCommand(intent, flags, startId)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayRoot == null) installOverlay() else requestOverlayLayoutUpdate()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestOverlayLayoutUpdate()
    }

    private fun installOverlay() {
        val root = FrameLayout(this)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            alpha = 0f
        }
        val cursorOverlay = HandOverlayCanvasView(this).apply {
            setRenderMode(HandOverlayCanvasView.RenderMode.CURSOR_ONLY)
        }
        root.addView(cursorOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))

        val displayBounds = currentDisplayBounds()
        val params = WindowManager.LayoutParams(
            displayBounds.width,
            displayBounds.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(root, params)
        overlayRoot = root
        overlayParams = params
        overlayBounds = displayBounds
        OverlayRuntime.attach(cursorOverlay)
        OverlayRuntime.bindLanguageSink(cursorOverlay::setLanguageTag)
        cursorOverlay.setLanguageTag(readLanguageTag())
        val stateMachine = GestureStateMachine(readGestureSettings()) { action ->
            val currentBounds = currentDisplayBounds()
            HandFreeAccessibilityService.dispatch(action, currentBounds.width, currentBounds.height)
        }
        OverlayRuntime.bindSettingsSink(stateMachine::updateSettings)
        OverlayRuntime.publish(GestureFrame(status = HudStatus.CAMERA_STARTING))
        cameraModule = CameraModule(
            context = this,
            lifecycleOwner = this,
            previewView = preview,
            onLandmarks = { rawLandmarks, frameOrientation ->
                processingScope.launch {
                    val frame = stateMutex.withLock {
                        val displayLandmarks = rawLandmarks.map { landmark ->
                            translateToDisplayNormalized(landmark, frameOrientation)
                        }
                        val telemetry = stateMachine.onLandmarks(displayLandmarks)
                        GestureFrame(
                            telemetry,
                            displayLandmarks,
                            HudStatus.fromGestureState(telemetry.state)
                        )
                    }
                    OverlayRuntime.publish(frame)
                }
            },
            onNoHand = {
                processingScope.launch {
                    val telemetry = stateMutex.withLock { stateMachine.onNoHand() }
                    OverlayRuntime.publish(GestureFrame(telemetry, emptyList(), HudStatus.SEARCHING))
                }
            },
            onStatus = { status ->
                processingScope.launch {
                    val telemetry = stateMutex.withLock { stateMachine.onNoHand() }
                    OverlayRuntime.publish(GestureFrame(telemetry, emptyList(), status))
                }
            }
        ).also { it.bind() }
    }

    override fun onDestroy() {
        layoutUpdateJob?.cancel()
        processingScope.cancel()
        cameraModule?.close()
        cameraModule = null
        overlayRoot?.let { root -> runCatching { windowManager.removeView(root) } }
        overlayRoot = null
        overlayParams = null
        overlayBounds = null
        OverlayRuntime.detach()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun readGestureSettings(): GestureSettings {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        return GestureSettings(
            motionSensitivity = preferences.getFloat(KEY_MOTION_SENSITIVITY, 1.0f),
            pinchThreshold = preferences.getFloat(KEY_PINCH_THRESHOLD, 0.22f),
            thumbExtensionThreshold = preferences.getFloat(KEY_THUMB_EXTENSION_THRESHOLD, 1.20f),
            fingerFoldThreshold = preferences.getFloat(KEY_FINGER_FOLD_THRESHOLD, 0.88f)
        ).normalized()
    }

    private fun readLanguageTag(): String = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        .getString(KEY_LANGUAGE_TAG, "en") ?: "en"

    private fun translateToDisplayNormalized(
        landmark: PointF,
        frameOrientation: CameraFrameOrientation
    ): PointF {
        val rotated = when (frameOrientation.analysisRotationDegrees) {
            90 -> PointF(1f - landmark.y, landmark.x)
            180 -> PointF(1f - landmark.x, 1f - landmark.y)
            270 -> PointF(landmark.y, 1f - landmark.x)
            else -> PointF(landmark.x, landmark.y)
        }
        val displayX = if (frameOrientation.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            1f - rotated.x
        } else {
            rotated.x
        }.coerceIn(0f, 1f)
        val displayY = rotated.y.coerceIn(0f, 1f)
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat().coerceAtLeast(1f)
        val screenHeight = metrics.heightPixels.toFloat().coerceAtLeast(1f)
        val pixelX = displayX * screenWidth
        val pixelY = displayY * screenHeight

        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE,
            Configuration.ORIENTATION_PORTRAIT -> PointF(pixelX / screenWidth, pixelY / screenHeight)
            else -> PointF(displayX, displayY)
        }
    }

    private fun requestOverlayLayoutUpdate() {
        layoutUpdateJob?.cancel()
        layoutUpdateJob = processingScope.launch(Dispatchers.Main.immediate) {
            val elapsed = SystemClock.uptimeMillis() - lastLayoutUpdateMillis
            val delayMillis = (LAYOUT_UPDATE_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L)
            if (delayMillis > 0L) delay(delayMillis)
            val root = overlayRoot ?: return@launch
            val params = overlayParams ?: return@launch
            val currentBounds = currentDisplayBounds()
            if (currentBounds == overlayBounds) return@launch
            params.width = currentBounds.width
            params.height = currentBounds.height
            withContext(Dispatchers.Main.immediate) {
                runCatching { windowManager.updateViewLayout(root, params) }
                    .onSuccess {
                        overlayBounds = currentBounds
                        lastLayoutUpdateMillis = SystemClock.uptimeMillis()
                    }
            }
        }
    }

    private fun currentDisplayBounds(): DisplayBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return DisplayBounds(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return DisplayBounds(metrics.widthPixels, metrics.heightPixels)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class DisplayBounds(val width: Int, val height: Int)

}
