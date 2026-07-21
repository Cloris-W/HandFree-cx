package com.handfree.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.handfree.app.model.GestureAction

class HandFreeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: HandFreeAccessibilityService? = null

        fun dispatch(action: GestureAction, width: Int, height: Int) {
            instance?.enqueue(action, width, height)
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun enqueue(action: GestureAction, width: Int, height: Int) {
        mainHandler.post { perform(action, width, height) }
    }

    private fun perform(action: GestureAction, width: Int, height: Int) {
        when (action) {
            is GestureAction.Click -> dispatchTap(action.position.x * width, action.position.y * height)
            is GestureAction.Swipe -> dispatchSwipe(action, width, height)
        }
    }

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

    private fun dispatchSwipe(action: GestureAction.Swipe, width: Int, height: Int) {
        // Android screen coordinates use the same positive-down Y axis as normalized landmarks.
        val path = Path().apply {
            moveTo(action.start.x * width, action.start.y * height)
            lineTo(action.end.x * width, action.end.y * height)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMillis))
                .build(),
            null,
            null
        )
    }
}
