package com.handfree.app.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.handfree.app.R
import com.handfree.app.accessibility.HandFreeAccessibilityService
import com.handfree.app.model.GestureEngine
import com.handfree.app.vision.CameraModule

class HandFreeOverlayService : LifecycleService() {
    companion object {
        private const val CHANNEL_ID = "handfree_control"
        private const val NOTIFICATION_ID = 2105

        fun start(context: Context) {
            val intent = Intent(context, HandFreeOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var cameraModule: CameraModule? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("HandFree 控制已啟動")
            .setContentText("前鏡頭正在本機辨識手勢")
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
        if (overlayRoot == null) installOverlay()
        return START_STICKY
    }

    private fun installOverlay() {
        val root = FrameLayout(this)
        val preview = PreviewView(this).apply {
            setBackgroundColor(Color.argb(230, 11, 15, 25))
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val cursorOverlay = HandFreeOverlayView(this)
        root.addView(cursorOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(preview, FrameLayout.LayoutParams(dp(156), dp(208), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(32)
            marginEnd = dp(16)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
        OverlayRuntime.attach(cursorOverlay)
        val screen = resources.displayMetrics
        val threshold = getSharedPreferences("handfree", MODE_PRIVATE)
            .getFloat("pinch_threshold", GestureEngine.DEFAULT_PINCH_THRESHOLD)
        val engine = GestureEngine(threshold) { action ->
            HandFreeAccessibilityService.dispatch(action, screen.widthPixels, screen.heightPixels)
        }
        cameraModule = CameraModule(this, this, preview, engine) { telemetry, status ->
            OverlayRuntime.publish(telemetry, status)
        }.also { it.bind() }
    }

    override fun onDestroy() {
        cameraModule?.close()
        cameraModule = null
        overlayRoot?.let { root -> runCatching { windowManager.removeView(root) } }
        overlayRoot = null
        OverlayRuntime.detach()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "HandFree 控制", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
