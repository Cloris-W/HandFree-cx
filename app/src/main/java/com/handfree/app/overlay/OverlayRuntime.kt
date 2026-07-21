package com.handfree.app.overlay

import android.os.Handler
import android.os.Looper
import com.handfree.app.model.GestureFrame
import com.handfree.app.model.GestureSettings

/** Main-thread bridge between the vision pipeline and the overlay renderer. */
object OverlayRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var isActive: Boolean = false
        private set
    @Volatile var lastFrame: GestureFrame = GestureFrame()
        private set
    private var overlay: HandOverlayCanvasView? = null
    private var settingsSink: ((GestureSettings) -> Unit)? = null
    private var languageSink: ((String) -> Unit)? = null

    fun attach(view: HandOverlayCanvasView) {
        overlay = view
        isActive = true
        publish(lastFrame)
    }

    fun detach() {
        overlay = null
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
        mainHandler.post { overlay?.update(frame) }
    }
}
