// SEITUNE - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import java.util.concurrent.Executors

/**
 * One background thread for work that must never run on the UI thread. MediaPlayer.reset()/release() wait for the player's
 * own reader thread, and a reader stuck on a slow network request used to freeze the whole app ("SeiTune isn't responding").
 * One thread, so teardowns happen one after another and in order.
 */
object Bg {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "seitune-release").also { it.isDaemon = true } }
    fun run(block: () -> Unit) {
        try { exec.execute { try { block() } catch (_: Throwable) { } } } catch (_: Throwable) { block() }
    }
}
