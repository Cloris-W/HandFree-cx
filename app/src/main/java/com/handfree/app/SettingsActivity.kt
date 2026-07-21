package com.handfree.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.handfree.app.model.GestureSettings
import com.handfree.app.overlay.OverlayRuntime

class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val PREFERENCES_NAME = "handfree_settings"
        private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
        private const val KEY_PINCH_THRESHOLD = "pinch_threshold"
        private const val KEY_THUMB_EXTENSION_THRESHOLD = "thumb_extension_threshold"
        private const val KEY_FINGER_FOLD_THRESHOLD = "finger_fold_threshold"
        private const val PREF_AUTO_RECENTER_ENABLED = "auto_recenter_enabled"
        private const val PREF_IDLE_RECENTER_ENABLED = "idle_recenter_enabled"
        private const val KEY_LANGUAGE_TAG = "language_tag"
        private const val SENSITIVITY_MIN = 0.5f
        private const val SENSITIVITY_STEP = 0.1f
        private const val SENSITIVITY_MAX_PROGRESS = 95
    }

    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private lateinit var savedSettings: GestureSettings
    private lateinit var draftSettings: GestureSettings
    private var settingsSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        savedSettings = readGestureSettings()
        draftSettings = savedSettings

        val sensitivityReadout = findViewById<TextView>(R.id.sensitivityReadout)
        val sensitivitySlider = findViewById<SeekBar>(R.id.sensitivitySlider)
        val autoRecenterSwitch = findViewById<SwitchCompat>(R.id.autoRecenterSwitch)
        val idleRecenterSwitch = findViewById<SwitchCompat>(R.id.idleRecenterSwitch)
        val languageSpinner = findViewById<Spinner>(R.id.languageSpinner)
        val saveButton = findViewById<MaterialButton>(R.id.saveButton)
        val languageTags = arrayOf("en", "fr", "zh-TW")

        fun refreshSensitivityReadout() {
            sensitivityReadout.text = getString(
                R.string.settings_motion_sensitivity_value,
                draftSettings.motionSensitivity
            )
        }

        sensitivitySlider.max = SENSITIVITY_MAX_PROGRESS
        sensitivitySlider.progress = ((draftSettings.motionSensitivity - SENSITIVITY_MIN) / SENSITIVITY_STEP)
            .toInt()
            .coerceIn(0, SENSITIVITY_MAX_PROGRESS)
        sensitivitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                draftSettings = draftSettings.copy(
                    motionSensitivity = SENSITIVITY_MIN + progress * SENSITIVITY_STEP
                )
                refreshSensitivityReadout()
                OverlayRuntime.updateSettings(draftSettings)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        refreshSensitivityReadout()

        autoRecenterSwitch.isChecked = draftSettings.autoRecenterEnabled
        autoRecenterSwitch.setOnCheckedChangeListener { _, enabled ->
            draftSettings = draftSettings.copy(autoRecenterEnabled = enabled)
            OverlayRuntime.updateSettings(draftSettings)
        }

        idleRecenterSwitch.isChecked = draftSettings.idleRecenterEnabled
        idleRecenterSwitch.setOnCheckedChangeListener { _, enabled ->
            draftSettings = draftSettings.copy(idleRecenterEnabled = enabled)
            OverlayRuntime.updateSettings(draftSettings)
        }

        val languageLabels = listOf(
            getString(R.string.language_english),
            getString(R.string.language_french),
            getString(R.string.language_traditional_chinese)
        )
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageLabels)
        languageSpinner.setSelection(languageTags.indexOf(currentLanguageTag()).coerceAtLeast(0))

        saveButton.setOnClickListener {
            saveGestureSettings(draftSettings)
            val languageTag = languageTags[languageSpinner.selectedItemPosition]
            preferences.edit().putString(KEY_LANGUAGE_TAG, languageTag).apply()
            OverlayRuntime.updateLanguage(languageTag)
            settingsSaved = true
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
            finish()
        }
    }

    override fun onDestroy() {
        if (!settingsSaved) OverlayRuntime.updateSettings(savedSettings)
        super.onDestroy()
    }

    private fun readGestureSettings(): GestureSettings = GestureSettings(
        motionSensitivity = preferences.getFloat(KEY_MOTION_SENSITIVITY, 1.0f),
        pinchThreshold = preferences.getFloat(KEY_PINCH_THRESHOLD, 0.22f),
        thumbExtensionThreshold = preferences.getFloat(KEY_THUMB_EXTENSION_THRESHOLD, 1.20f),
        fingerFoldThreshold = preferences.getFloat(KEY_FINGER_FOLD_THRESHOLD, 0.88f),
        autoRecenterEnabled = preferences.getBoolean(PREF_AUTO_RECENTER_ENABLED, false),
        idleRecenterEnabled = preferences.getBoolean(PREF_IDLE_RECENTER_ENABLED, false)
    ).normalized()

    private fun saveGestureSettings(settings: GestureSettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putFloat(KEY_MOTION_SENSITIVITY, normalized.motionSensitivity)
            .putFloat(KEY_PINCH_THRESHOLD, normalized.pinchThreshold)
            .putFloat(KEY_THUMB_EXTENSION_THRESHOLD, normalized.thumbExtensionThreshold)
            .putFloat(KEY_FINGER_FOLD_THRESHOLD, normalized.fingerFoldThreshold)
            .putBoolean(PREF_AUTO_RECENTER_ENABLED, normalized.autoRecenterEnabled)
            .putBoolean(PREF_IDLE_RECENTER_ENABLED, normalized.idleRecenterEnabled)
            .apply()
        OverlayRuntime.updateSettings(normalized)
    }

    private fun currentLanguageTag(): String {
        val selected = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return if (selected.isBlank()) preferences.getString(KEY_LANGUAGE_TAG, "en") ?: "en" else selected
    }
}
