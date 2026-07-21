package com.handfree.app.overlay

import android.view.Choreographer
import android.os.Handler
import android.os.Looper
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.GestureSettings

/** Main-thread bridge between the vision pipeline and the overlay renderer. */
object OverlayRuntime {
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

    private val frameCallback = Choreographer.FrameCallback {
        val frame = synchronized(renderLock) {
            if (!renderScheduled) return@FrameCallback
            renderScheduled = false
            pendingFrame.also { pendingFrame = null }
        }
        frame?.let { renderer?.invoke(it) }
        synchronized(renderLock) {
            if (pendingFrame != null && !renderScheduled) scheduleRenderLocked()
        }
    }

    private val renderRunnable = Runnable {
        synchronized(renderLock) {
            if (!renderScheduled) return@Runnable
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun attach(renderer: (GestureFrame) -> Unit) {
        synchronized(renderLock) {
            this.renderer = renderer
            pendingFrame = lastFrame
            renderScheduled = false
            removeScheduledRenderLocked()
            scheduleRenderLocked()
        }
        isActive = true
    }

    fun detach() {
        synchronized(renderLock) {
            renderer = null
            pendingFrame = null
            renderScheduled = false
            removeScheduledRenderLocked()
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
        renderScheduled = true
        mainHandler.post(renderRunnable)
    }

    private fun removeScheduledRenderLocked() {
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.post { Choreographer.getInstance().removeFrameCallback(frameCallback) }
    }
}
