package com.handfree.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.handfree.app.databinding.ActivityMainBinding
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.GestureSettings
import com.handfree.app.model.HudStatus
import com.handfree.app.overlay.HandFreeOverlayService
import com.handfree.app.overlay.OverlayRuntime
import com.handfree.app.permissions.PermissionWizard
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFERENCES_NAME = "handfree_settings"
        private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
        private const val KEY_PINCH_THRESHOLD = "pinch_threshold"
        private const val KEY_THUMB_EXTENSION_THRESHOLD = "thumb_extension_threshold"
        private const val KEY_FINGER_FOLD_THRESHOLD = "finger_fold_threshold"
        private const val KEY_LANGUAGE_TAG = "language_tag"
        private const val UI_REFRESH_MILLIS = 120L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionWizard: PermissionWizard
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
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
        binding.tutorialButton.setOnClickListener { openLiveCalibrationWizard() }
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
        binding.pipView.update(displayedFrame)
        binding.gateText.text = when {
            OverlayRuntime.isActive -> getString(R.string.gate_running)
            snapshot.ready -> getString(R.string.gate_ready)
            else -> getString(R.string.gate_missing, snapshot.summary(this))
        }
        binding.startButton.text = getString(if (OverlayRuntime.isActive) R.string.action_stop else R.string.action_start)
    }

    private fun openLiveCalibrationWizard() {
        val savedSettings = readGestureSettings()
        var draftSettings = savedSettings
        val container = createDialogContainer()
        val instructions = createDialogText(getString(R.string.sandbox_message))
        val sensitivityReadout = createDialogText("")
        val thumbReadout = createDialogText("")
        val foldReadout = createDialogText("")
        container.addView(instructions)
        container.addView(sensitivityReadout)
        val sensitivitySlider = createSlider(25, ((draftSettings.motionSensitivity - 0.5f) * 10f).toInt()) { progress ->
            draftSettings = draftSettings.copy(motionSensitivity = 0.5f + progress / 10f)
            sensitivityReadout.text = getString(R.string.settings_motion_sensitivity_value, draftSettings.motionSensitivity)
            OverlayRuntime.updateSettings(draftSettings)
        }
        container.addView(sensitivitySlider)
        container.addView(thumbReadout)
        val thumbSlider = createSlider(34, ((draftSettings.thumbExtensionThreshold - 0.70f) * 20f).toInt()) { progress ->
            draftSettings = draftSettings.copy(thumbExtensionThreshold = 0.70f + progress / 20f)
            thumbReadout.text = getString(R.string.settings_thumb_extension_value, draftSettings.thumbExtensionThreshold)
            OverlayRuntime.updateSettings(draftSettings)
        }
        container.addView(thumbSlider)
        container.addView(foldReadout)
        val foldSlider = createSlider(25, ((draftSettings.fingerFoldThreshold - 0.35f) * 20f).toInt()) { progress ->
            draftSettings = draftSettings.copy(fingerFoldThreshold = 0.35f + progress / 20f)
            foldReadout.text = getString(R.string.settings_finger_fold_value, draftSettings.fingerFoldThreshold)
            OverlayRuntime.updateSettings(draftSettings)
        }
        container.addView(foldSlider)

        fun refreshReadouts() {
            sensitivityReadout.text = getString(R.string.settings_motion_sensitivity_value, draftSettings.motionSensitivity)
            thumbReadout.text = getString(R.string.settings_thumb_extension_value, draftSettings.thumbExtensionThreshold)
            foldReadout.text = getString(R.string.settings_finger_fold_value, draftSettings.fingerFoldThreshold)
        }
        refreshReadouts()
        binding.pipView.setCalibrationMode(true, 1)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sandbox_title)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                saveGestureSettings(draftSettings)
                Toast.makeText(this, getString(R.string.toast_calibration_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                OverlayRuntime.updateSettings(savedSettings)
            }
            .create()
        dialog.setOnDismissListener { binding.pipView.setCalibrationMode(false) }
        dialog.show()
    }

    private fun openSettings() {
        val savedSettings = readGestureSettings()
        var draftSettings = savedSettings
        val container = createDialogContainer()
        val sensitivityReadout = createDialogText(getString(R.string.settings_motion_sensitivity_value, draftSettings.motionSensitivity))
        container.addView(createDialogText(getString(R.string.settings_motion_sensitivity)))
        container.addView(sensitivityReadout)
        container.addView(createSlider(25, ((draftSettings.motionSensitivity - 0.5f) * 10f).toInt()) { progress ->
            draftSettings = draftSettings.copy(motionSensitivity = 0.5f + progress / 10f)
            sensitivityReadout.text = getString(R.string.settings_motion_sensitivity_value, draftSettings.motionSensitivity)
            OverlayRuntime.updateSettings(draftSettings)
        })
        container.addView(createDialogText(getString(R.string.settings_language)))
        val languageTags = arrayOf("en", "fr", "zh-TW")
        val languageLabels = listOf(
            getString(R.string.language_english),
            getString(R.string.language_french),
            getString(R.string.language_traditional_chinese)
        )
        val languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languageLabels)
            setSelection(languageTags.indexOf(currentLanguageTag()).coerceAtLeast(0))
        }
        container.addView(languageSpinner)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                saveGestureSettings(draftSettings)
                val languageTag = languageTags[languageSpinner.selectedItemPosition]
                preferences.edit().putString(KEY_LANGUAGE_TAG, languageTag).apply()
                OverlayRuntime.updateLanguage(languageTag)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                OverlayRuntime.updateSettings(savedSettings)
            }
            .show()
    }

    private fun createDialogContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
    }

    private fun createDialogText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        textSize = 15f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun createSlider(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun readGestureSettings(): GestureSettings = GestureSettings(
        motionSensitivity = preferences.getFloat(KEY_MOTION_SENSITIVITY, 1.0f),
        pinchThreshold = preferences.getFloat(KEY_PINCH_THRESHOLD, 0.22f),
        thumbExtensionThreshold = preferences.getFloat(KEY_THUMB_EXTENSION_THRESHOLD, 1.20f),
        fingerFoldThreshold = preferences.getFloat(KEY_FINGER_FOLD_THRESHOLD, 0.88f)
    ).normalized()

    private fun saveGestureSettings(settings: GestureSettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putFloat(KEY_MOTION_SENSITIVITY, normalized.motionSensitivity)
            .putFloat(KEY_PINCH_THRESHOLD, normalized.pinchThreshold)
            .putFloat(KEY_THUMB_EXTENSION_THRESHOLD, normalized.thumbExtensionThreshold)
            .putFloat(KEY_FINGER_FOLD_THRESHOLD, normalized.fingerFoldThreshold)
            .apply()
        OverlayRuntime.updateSettings(normalized)
    }

    private fun currentLanguageTag(): String {
        val selected = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return if (selected.isBlank()) preferences.getString(KEY_LANGUAGE_TAG, "en") ?: "en" else selected
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
