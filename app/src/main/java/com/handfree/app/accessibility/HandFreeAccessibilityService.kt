package com.handfree.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
import com.handfree.app.model.GestureAction

class HandFreeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: HandFreeAccessibilityService? = null

        fun dispatch(action: GestureAction, width: Int, height: Int) {
            instance?.perform(action, width, height)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun perform(action: GestureAction, width: Int, height: Int) {
        val x = when (action) {
            is GestureAction.Click -> action.x * width
            is GestureAction.DragStart -> action.x * width
            is GestureAction.DragMove -> action.x * width
            GestureAction.DragEnd -> 0f
        }
        val y = when (action) {
            is GestureAction.Click -> action.y * height
            is GestureAction.DragStart -> action.y * height
            is GestureAction.DragMove -> action.y * height
            GestureAction.DragEnd -> 0f
        }

        when (action) {
            is GestureAction.Click -> dispatchTap(x, y)
            is GestureAction.DragStart -> dragPath = Path().apply { moveTo(x, y) }
            is GestureAction.DragMove -> dragPath?.lineTo(x, y)
            GestureAction.DragEnd -> dispatchDrag()
        }
    }

    private var dragPath: Path? = null

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
                .build(),
            null,
            null
        )
    }

    private fun dispatchDrag() {
        val path = dragPath ?: return
        dragPath = null
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 360))
                .build(),
            null,
            null
        )
    }
}

