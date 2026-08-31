package com.isaque.signalplay

import android.content.Context
import android.view.Surface
import org.freedesktop.gstreamer.GStreamer
import java.util.concurrent.atomic.AtomicLong

class SrtPlayer(context: Context, private val listener: Listener) {
    interface Listener {
        fun onSrtState(state: String, detail: String)
        fun onSrtDiagnostics(report: String)
    }

    private val nextSessionId = AtomicLong(0L)

    @Volatile
    private var activeSessionId = 0L

    init {
        GStreamer.init(context.applicationContext)
        nativeClassInit()
        nativeCreate()
    }

    @Synchronized
    fun play(uri: String, surface: Surface, deJitterMs: Int) {
        val sessionId = nextSessionId.incrementAndGet()
        activeSessionId = sessionId
        nativePlay(uri, surface, deJitterMs, sessionId)
    }

    @Synchronized
    fun stop() {
        /* Invalidate queued callbacks before native teardown starts. */
        activeSessionId = 0L
        nativeStop()
    }

    @Synchronized
    fun release() {
        activeSessionId = 0L
        nativeRelease()
    }

    @Suppress("unused")
    private fun onNativeState(sessionId: Long, state: String, detail: String) {
        if (sessionId != activeSessionId) return
        listener.onSrtState(state, detail)
    }

    @Suppress("unused")
    private fun onNativeDiagnostics(sessionId: Long, report: String) {
        if (sessionId != activeSessionId) return
        listener.onSrtDiagnostics(report)
    }

    private external fun nativeCreate()
    private external fun nativePlay(uri: String, surface: Surface, deJitterMs: Int, sessionId: Long)
    private external fun nativeStop()
    private external fun nativeRelease()

    companion object {
        init {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("signalplay_srt")
        }

        @JvmStatic private external fun nativeClassInit(): Boolean
    }
}
