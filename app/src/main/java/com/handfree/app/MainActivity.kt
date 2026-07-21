package com.handfree.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.handfree.app.databinding.ActivityMainBinding
import com.handfree.app.model.HudStatus
import com.handfree.app.overlay.HandFreeOverlayService
import com.handfree.app.overlay.OverlayRuntime
import com.handfree.app.permissions.PermissionWizard

class MainActivity : AppCompatActivity() {
    companion object {
        private const val UI_REFRESH_MILLIS = 120L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionWizard: PermissionWizard
    private val uiHandler = Handler(Looper.getMainLooper())
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, UI_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        permissionWizard = PermissionWizard(this)

        binding.startButton.setOnClickListener { continueStartFlow() }
        binding.settingsButton.setOnClickListener { openSettings() }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiTicker)
        super.onPause()
    }

    private fun continueStartFlow() {
        if (OverlayRuntime.isActive) {
            stopService(Intent(this, HandFreeOverlayService::class.java))
            refreshUi()
            return
        }
        val snapshot = permissionWizard.snapshot()
        when {
            !snapshot.cameraGranted -> requestCamera.launch(Manifest.permission.CAMERA)
            !snapshot.overlayGranted -> permissionWizard.openOverlaySettings()
            !snapshot.accessibilityEnabled -> permissionWizard.openAccessibilitySettings()
            else -> {
                if (permissionWizard.notificationPermissionNeeded()) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                HandFreeOverlayService.start(this)
                Toast.makeText(this, getString(R.string.toast_service_started), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshUi() {
        val snapshot = permissionWizard.snapshot()
        val sourceFrame = OverlayRuntime.lastFrame
        val status = if (OverlayRuntime.isActive) sourceFrame.status else HudStatus.SEARCHING
        val displayedFrame = sourceFrame.copy(status = status)
        val telemetry = displayedFrame.telemetry
        binding.statePill.text = getString(statusTextResource(status))
        binding.statePill.setTextColor(ContextCompat.getColor(this, statusColorResource(status, snapshot.ready)))
        binding.telemetryText.text = getString(
            R.string.hud_telemetry,
            getString(statusTextResource(status)),
            telemetry.fps,
            telemetry.handScale,
            telemetry.pinchDistance,
            telemetry.motionSensitivity
        )
        binding.gateText.text = when {
            OverlayRuntime.isActive -> getString(R.string.gate_running)
            snapshot.ready -> getString(R.string.gate_ready)
            else -> getString(R.string.gate_missing, snapshot.summary(this))
        }
        binding.startButton.text = getString(if (OverlayRuntime.isActive) R.string.action_stop else R.string.action_start)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun statusTextResource(status: HudStatus): Int = when (status) {
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

    private fun statusColorResource(status: HudStatus, ready: Boolean): Int = when (status) {
        HudStatus.CLICK_ACTIVE, HudStatus.DRAG_ENGAGED -> R.color.success
        HudStatus.CAMERA_ERROR, HudStatus.NO_FRONT_CAMERA, HudStatus.MODEL_ERROR, HudStatus.VISION_ERROR -> R.color.danger
        HudStatus.SEARCHING, HudStatus.CAMERA_STARTING, HudStatus.MODEL_REQUIRED -> if (ready) R.color.cyan else R.color.warning
        HudStatus.TRACKING, HudStatus.DRAG_PREPARED -> R.color.cyan
    }
}
