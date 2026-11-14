// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch

/** Sets up the bundled downloader (python + yt-dlp) once, off the UI thread. */
object Engine {
    val ready = CountDownLatch(1)
    @Volatile var error: String? = null
    @Volatile var ffmpegOk = false
    @Volatile private var lastTry = 0L
    /** Lookups hold the read side while yt-dlp runs; an update takes the write side (rewriting the zip under a running process gives ZipImportError). */
    val procLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private const val UPDATE_EVERY_MS = 12 * 60 * 60 * 1000L

    /**
     * YouTube keeps changing what it accepts (that is what causes "HTTP Error 403: Forbidden"), and the
     * yt-dlp bundled in the APK gets old fast. So we pull the newest yt-dlp (nightly) at start, at most
     * every 12 hours, and again on demand when a download/stream gets a 403.
     * Returns true only if an update was actually attempted now.
     */
    @Synchronized
    fun updateNow(ctx: Context, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < 30 * 60_000L) return false   // do not hammer GitHub
        val w = procLock.writeLock()
        if (!(try { w.tryLock(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { false })) return false // busy: try later
        lastTry = now
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            ctx.getSharedPreferences("engine", Context.MODE_PRIVATE).edit()
                .putLong("lastUpdate", System.currentTimeMillis()).apply()
        } catch (_: Throwable) {
            // offline or GitHub unreachable: keep using the version we have
        } finally {
            w.unlock()
        }
        return true
    }

    private fun describe(t: Throwable): String {
        var root: Throwable = t
        var depth = 0
        while (root.cause != null && root.cause !== root && depth < 10) {
            root = root.cause!!
            depth++
        }
        val where = root.stackTrace.firstOrNull()?.let { " @ ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}$where"
    }

    fun start(app: Application) {
        Thread {
            var failure: Throwable? = null
            for (attempt in 1..2) {
                try {
                    YoutubeDL.getInstance().init(app)
                    failure = null
                    break
                } catch (e: Throwable) {
                    failure = e
                    try { Thread.sleep(400) } catch (_: InterruptedException) { }
                }
            }
            failure?.let {
                error = describe(it)
                ready.countDown()
                return@Thread
            }
            // Searching only needs python + yt-dlp. Release everyone waiting NOW; the slow parts
            // (ffmpeg unpack, yt-dlp nightly update) used to block the first search for a long time.
            ready.countDown()
            // unpack the bundled ffmpeg after search is already released; until then downloads keep the original m4a/mp4 streams
            try { FFmpeg.getInstance().init(app); ffmpegOk = true } catch (_: Throwable) { ffmpegOk = false }
            // update later, in the background, once the first search/play has had its chance
            try { Thread.sleep(45_000) } catch (_: InterruptedException) { }
            val last = app.getSharedPreferences("engine", Context.MODE_PRIVATE).getLong("lastUpdate", 0L)
            if (System.currentTimeMillis() - last > UPDATE_EVERY_MS) updateNow(app)
        }.start()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Sei.init(this) // client health, pauses and measured speed from earlier runs
        Engine.start(this)
        Thread { try { Api.get(this).warm() } catch (_: Throwable) { } }.also { it.priority = Thread.MIN_PRIORITY }.start()
    }
}
