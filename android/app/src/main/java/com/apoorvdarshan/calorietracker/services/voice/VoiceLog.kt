package com.apoorvdarshan.calorietracker.services.voice

import android.util.Log

/**
 * Logcat for the voice assistant. Messages never carry keys, transcripts or tool data.
 * The JVM unit tests run against the stub android.jar, where `Log` throws; logging must
 * never change what the engine does, so failures to log are ignored.
 */
internal object VoiceLog {
    fun info(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.w(tag, message) else Log.w(tag, message, error) }
    }
}
