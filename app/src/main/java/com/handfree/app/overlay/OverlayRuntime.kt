package com.handfree.app.overlay

import android.os.Handler
import android.os.Looper
import com.handfree.app.model.GestureTelemetry

/** Main-thread bridge between the vision pipeline and the overlay renderer. */
object OverlayRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var isActive: Boolean = false
        private set
    @Volatile var lastTelemetry: GestureTelemetry = GestureTelemetry()
        private set
    @Volatile var lastStatus: String = "SEARCHING"
        private set
    private var overlay: HandFreeOverlayView? = null

    fun attach(view: HandFreeOverlayView) {
        overlay = view
        isActive = true
        publish(lastTelemetry, lastStatus)
    }

    fun detach() {
        overlay = null
        isActive = false
    }

    fun publish(telemetry: GestureTelemetry, status: String) {
        lastTelemetry = telemetry
        lastStatus = status
        mainHandler.post { overlay?.update(telemetry, status) }
    }
}

