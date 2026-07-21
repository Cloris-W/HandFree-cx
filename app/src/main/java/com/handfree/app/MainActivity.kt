package com.handfree.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.handfree.app.databinding.ActivityMainBinding
import com.handfree.app.model.GestureEngine
import com.handfree.app.overlay.HandFreeOverlayService
import com.handfree.app.overlay.OverlayRuntime
import com.handfree.app.permissions.PermissionWizard
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionWizard: PermissionWizard
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        permissionWizard = PermissionWizard(this)

        binding.startButton.setOnClickListener { continueStartFlow() }
        binding.tutorialButton.setOnClickListener { openTutorial() }
        binding.calibrateButton.setOnClickListener { openCalibration() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
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
                Toast.makeText(this, "HandFree 已啟動，前鏡頭辨識只在本機執行。", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshUi() {
        val snapshot = permissionWizard.snapshot()
        val telemetry = OverlayRuntime.lastTelemetry
        val status = if (OverlayRuntime.isActive) OverlayRuntime.lastStatus else "SEARCHING"
        binding.statePill.text = status
        binding.statePill.setTextColor(
            ContextCompat.getColor(
                this,
                if (OverlayRuntime.isActive) R.color.success else if (snapshot.ready) R.color.cyan else R.color.warning
            )
        )
        binding.telemetryText.text = String.format(
            Locale.US,
            "STATE: %s  |  FPS: %02d  |  SCALE: %.2f  |  PINCH: %.2f",
            status,
            telemetry.fps,
            telemetry.handScale,
            telemetry.pinchDistance
        )
        binding.pipView.render(telemetry)
        binding.gateText.text = when {
            OverlayRuntime.isActive -> "控制服務正在運行。回到其他 App 後，游標與遙測列會維持顯示。"
            snapshot.ready -> "核心權限已就緒。按下「啟動 HandFree」開始全域手勢控制。"
            else -> "需要授權：${snapshot.summary()}。按下啟動鈕會逐步開啟系統設定。"
        }
        binding.startButton.text = if (OverlayRuntime.isActive) "停止 HandFree" else "啟動 HandFree"
    }

    private fun openTutorial() {
        val stages = arrayOf(
            "Stage 1 - Aiming\n使用食指指尖移動游標，目標是平穩地停在圓環中。",
            "Stage 2 - Pinch Click\n拇指與食指捏合，連續維持三幀以觸發一次點擊。",
            "Stage 3 - Dragging\n張開手掌五幀進入拖曳；保持張開移動，收手後放開。"
        )
        AlertDialog.Builder(this)
            .setTitle("互動式教學沙盒")
            .setSingleChoiceItems(stages, 0) { dialog, selected ->
                binding.pipView.selectSandboxStage(selected)
                Toast.makeText(this, "已載入 Stage ${selected + 1}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun openCalibration() {
        val preferences = getSharedPreferences("handfree", MODE_PRIVATE)
        val current = preferences.getFloat("pinch_threshold", GestureEngine.DEFAULT_PINCH_THRESHOLD)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val readout = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val slider = SeekBar(this).apply {
            max = 25
            progress = ((current - 0.10f) * 100).toInt().coerceIn(0, 25)
        }
        fun update(value: Float) {
            readout.text = String.format(Locale.US, "Pinch Threshold  %.2f", value)
        }
        update(current)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update(0.10f + progress / 100f)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        container.addView(readout)
        container.addView(slider)
        AlertDialog.Builder(this)
            .setTitle("手勢校準")
            .setMessage("調整拇指與食指距離的觸發值（範圍 0.10 - 0.35）。數值越小，越需要更緊的捏合。")
            .setView(container)
            .setPositiveButton("儲存") { _, _ ->
                val value = 0.10f + slider.progress / 100f
                preferences.edit().putFloat("pinch_threshold", value).apply()
                Toast.makeText(this, "已儲存 ${String.format(Locale.US, "%.2f", value)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
