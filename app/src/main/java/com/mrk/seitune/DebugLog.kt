// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of what went wrong (stream, picture, sound, download). Shown in Settings > Debug log.
 * The same message repeating within a few seconds is folded into one line ("... (x4)"), and routine successes
 * (e.g. a link probe that answered 206) are only counted, so they no longer push the real errors out of the buffer.
 */
object DebugLog {
    private const val MAX = 500
    private const val FOLD_MS = 8000L
    private class Entry(val time: Long, val tag: String, val msg: String, var last: Long, var n: Int)

    private val lines = ArrayDeque<Entry>()
    private val counters = LinkedHashMap<String, Int>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, msg: String) {
        val now = System.currentTimeMillis()
        val m = msg.replace('\n', ' ').take(400)
        synchronized(lines) {
            // same line seen in the last few lines and seconds: count it instead of adding it again
            val recent = lines.takeLast(12).lastOrNull { it.tag == tag && it.msg == m && now - it.last < FOLD_MS }
            if (recent != null) { recent.n++; recent.last = now; return }
            lines.addLast(Entry(now, tag, m, now, 1))
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    /** The app's own (native) code, no yt-dlp: shown as "sei[downloading]", "sei[stream]", "sei[link]", "sei[lyrics]" ... */
    fun sei(stage: String, msg: String) = add("sei[$stage]", msg)

    fun sei(stage: String, msg: String, e: Throwable) = add("sei[$stage]", msg, e)

    fun add(tag: String, msg: String, e: Throwable) {
        val at = e.stackTrace.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
        val raw = e.message ?: ""
        val m = if (raw.contains("Traceback")) raw.lines().lastOrNull { it.isNotBlank() }?.trim() ?: raw else raw
        add(tag, msg + ": " + e.javaClass.simpleName + " " + m + " @ " + at)
    }

    /** Counts something routine without writing a line (shown in the header of the log). */
    fun count(what: String) = synchronized(lines) { counters[what] = (counters[what] ?: 0) + 1 }

    fun countersJson(): org.json.JSONObject = synchronized(lines) { org.json.JSONObject().also { o -> for ((k, v) in counters) o.put(k, v) } }

    fun clear() = synchronized(lines) { lines.clear(); counters.clear() }

    fun text(): String {
        val head = "SEITUNE debug log\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "yt-dlp: " + (if (Engine.ready.count == 0L) (Engine.error?.let { "FAILED: $it" } ?: "ready, ffmpeg=${Engine.ffmpegOk}") else "still starting") + "\n" +
            "sei clients: " + Sei.summary() + "\n" +
            "sei pot bindings: " + PotBinding.summary() + "\n"
        val (body, stats) = synchronized(lines) {
            lines.joinToString("\n") { e ->
                fmt.format(Date(e.time)) + " [" + e.tag + "] " + e.msg + (if (e.n > 1) " (x${e.n})" else "")
            } to counters.entries.joinToString(", ") { it.key + "=" + it.value }
        }
        return head + (if (stats.isNotEmpty()) "counts: $stats\n" else "") + "----\n" + body.ifEmpty { "(nothing logged yet)" }
    }
}
