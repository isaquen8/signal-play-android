package com.isaque.signalplay

import android.content.Context
import android.view.Surface
import org.freedesktop.gstreamer.GStreamer

class SrtPlayer(context: Context, private val listener: Listener) {
    interface Listener {
        fun onSrtState(state: String, detail: String)
        fun onSrtDiagnostics(report: String)
    }

    init {
        GStreamer.init(context.applicationContext)
        nativeClassInit()
        nativeCreate()
    }

    fun play(uri: String, surface: Surface) = nativePlay(uri, surface)
    fun stop() = nativeStop()
    fun release() = nativeRelease()

    @Suppress("unused")
    private fun onNativeState(state: String, detail: String) = listener.onSrtState(state, detail)

    @Suppress("unused")
    private fun onNativeDiagnostics(report: String) = listener.onSrtDiagnostics(report)

    private external fun nativeCreate()
    private external fun nativePlay(uri: String, surface: Surface)
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
