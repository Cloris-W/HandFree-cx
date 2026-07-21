package com.handfree.app.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.handfree.app.accessibility.HandFreeAccessibilityService

data class PermissionSnapshot(
    val cameraGranted: Boolean,
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean
) {
    val ready: Boolean get() = cameraGranted && overlayGranted && accessibilityEnabled

    fun summary(): String = buildList {
        if (!cameraGranted) add("相機")
        if (!overlayGranted) add("懸浮視窗")
        if (!accessibilityEnabled) add("無障礙手勢控制")
    }.joinToString("、")
}

class PermissionWizard(private val context: Context) {
    fun snapshot(): PermissionSnapshot = PermissionSnapshot(
        cameraGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        overlayGranted = Settings.canDrawOverlays(context),
        accessibilityEnabled = isAccessibilityEnabled()
    )

    fun openOverlaySettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openAccessibilitySettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun notificationPermissionNeeded(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val target = ComponentName(context, HandFreeAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                ComponentName(info.resolveInfo.serviceInfo.packageName, info.resolveInfo.serviceInfo.name) == target
            }
    }
}

