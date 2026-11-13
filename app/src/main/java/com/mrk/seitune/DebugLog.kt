// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of what went wrong (stream, picture, sound, download). Shown in Settings > Debug log. */
object DebugLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, msg: String) {
        val line = fmt.format(Date()) + " [" + tag + "] " + msg.replace('\n', ' ').take(400)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun add(tag: String, msg: String, e: Throwable) {
        val at = e.stackTrace.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
        val raw = e.message ?: ""
        val m = if (raw.contains("Traceback")) raw.lines().lastOrNull { it.isNotBlank() }?.trim() ?: raw else raw
        add(tag, msg + ": " + e.javaClass.simpleName + " " + m + " @ " + at)
    }

    fun clear() = synchronized(lines) { lines.clear() }

    fun text(): String {
        val head = "SEITUNE debug log\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "yt-dlp: " + (if (Engine.ready.count == 0L) (Engine.error?.let { "FAILED: $it" } ?: "ready, ffmpeg=${Engine.ffmpegOk}") else "still starting") + "\n" +
            "----\n"
        val body = synchronized(lines) { lines.joinToString("\n") }
        return head + if (body.isEmpty()) "(nothing logged yet)" else body
    }
}
