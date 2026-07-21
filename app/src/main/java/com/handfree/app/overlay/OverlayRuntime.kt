package com.handfree.app.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.GestureSettings

/** Main-thread bridge between the vision pipeline and the overlay renderer. */
object OverlayRuntime {
    private const val FRAME_INTERVAL_MILLIS = 16L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderLock = Any()
    @Volatile var isActive: Boolean = false
        private set
    @Volatile var lastFrame: GestureFrame = GestureFrame()
        private set
    private var renderer: ((GestureFrame) -> Unit)? = null
    private var settingsSink: ((GestureSettings) -> Unit)? = null
    private var languageSink: ((String) -> Unit)? = null
    private var pendingFrame: GestureFrame? = null
    private var renderScheduled = false
    private var lastRenderMillis = 0L

    private val renderRunnable = Runnable {
        val frame = synchronized(renderLock) {
            renderScheduled = false
            lastRenderMillis = SystemClock.uptimeMillis()
            pendingFrame.also { pendingFrame = null }
        }
        frame?.let { renderer?.invoke(it) }
        synchronized(renderLock) {
            if (pendingFrame != null && !renderScheduled) scheduleRenderLocked()
        }
    }

    fun attach(renderer: (GestureFrame) -> Unit) {
        synchronized(renderLock) {
            this.renderer = renderer
            pendingFrame = lastFrame
            renderScheduled = false
            lastRenderMillis = 0L
            mainHandler.removeCallbacks(renderRunnable)
            scheduleRenderLocked()
        }
        isActive = true
    }

    fun detach() {
        synchronized(renderLock) {
            renderer = null
            pendingFrame = null
            renderScheduled = false
            mainHandler.removeCallbacks(renderRunnable)
        }
        settingsSink = null
        languageSink = null
        isActive = false
    }

    fun bindSettingsSink(sink: (GestureSettings) -> Unit) {
        settingsSink = sink
    }

    fun updateSettings(settings: GestureSettings) {
        mainHandler.post { settingsSink?.invoke(settings) }
    }

    fun bindLanguageSink(sink: (String) -> Unit) {
        languageSink = sink
    }

    fun updateLanguage(languageTag: String) {
        mainHandler.post { languageSink?.invoke(languageTag) }
    }

    fun publish(frame: GestureFrame) {
        lastFrame = frame
        synchronized(renderLock) {
            pendingFrame = frame
            if (!renderScheduled) scheduleRenderLocked()
        }
    }

    private fun scheduleRenderLocked() {
        val elapsed = SystemClock.uptimeMillis() - lastRenderMillis
        val delayMillis = (FRAME_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L)
        renderScheduled = true
        mainHandler.postDelayed(renderRunnable, delayMillis)
    }
}
