// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** Page -> service commands and service -> page commands, in-process, no polling. */
object PlayerBus {
    @Volatile var sink: ((String) -> Unit)? = null
    fun send(cmd: String): Boolean {
        val s = sink ?: return false
        s(cmd)
        return true
    }
}

/**
 * Keeps music playing with the screen off.
 *
 * Zero-waste design: everything here is event-driven. There is no timer, loop or thread while
 * music plays. The CPU wake lock (and Wi-Fi lock, for streams only) is held ONLY while a song is
 * actually playing or loading, and is dropped the moment it is paused. A single one-shot timer
 * stops the whole service after a few minutes of pause.
 */
class PlaybackService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    private var state = "idle"           // idle | loading | playing | paused
    private var title = ""
    private var artist = ""
    private var position = 0L
    private var duration = 0L
    private var fg = false
    private var noisyOn = false
    private var lastKey = ""

    // native audio player: keeps playing when the WebView is paused/throttled in the background
    private var mp: MediaPlayer? = null
    @Volatile private var mpReady = false
    private var wantPlay = false
    private var startAt = 0L
    private var aSrc: YtDataSource? = null
    private var nOnline = false
    private var resumeOnFocus = false
    private var focusReq: AudioFocusRequest? = null
    private val am by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val idleStop = Runnable { shutdown() }

    private val noisy = object : BroadcastReceiver() {
        // headphones unplugged / bluetooth dropped: pause instead of blasting the speaker
        override fun onReceive(c: Context?, i: Intent?) { doPause("audio became noisy (headphones/bluetooth/route change)") }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SEITUNE:play").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SEITUNE:wifi")?.apply { setReferenceCounted(false) }

        session = MediaSession(this, "SEITUNE").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { doPlay() }
                override fun onPause() { doPause("media session pause (notification, headset or another app)") }
                override fun onStop() { doPause("media session stop") }
                override fun onSkipToNext() { PlayerBus.send("next") }
                override fun onSkipToPrevious() { PlayerBus.send("prev") }
                override fun onSeekTo(pos: Long) { if (mp != null) nSeek(pos) else PlayerBus.send("seek:$pos") }
            })
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_PREV -> cmd("prev")
            A_NEXT -> cmd("next")
            A_TOGGLE -> if (state == "playing" || state == "loading") doPause("notification button") else doPlay()
            A_STOP -> shutdown()
            A_PLAY -> {
                state = "loading"
                title = i?.getStringExtra("t") ?: ""; artist = i?.getStringExtra("a") ?: ""
                if (!fg) goForeground()
                nPlay(
                    i?.getStringExtra("u") ?: "", i?.getLongExtra("p", 0L) ?: 0L,
                    title, artist, i?.getBooleanExtra("o", false) ?: false
                )
            }
            else -> {
                // A_UPDATE (started with startForegroundService): must go foreground right away
                val st = i?.getStringExtra("st") ?: "idle"
                if (st == "idle") {
                    goForeground(); shutdown()
                } else {
                    update(
                        st, i?.getStringExtra("t") ?: "", i?.getStringExtra("a") ?: "",
                        i?.getLongExtra("p", 0L) ?: 0L, i?.getLongExtra("d", 0L) ?: 0L,
                        i?.getBooleanExtra("o", false) ?: false
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun cmd(c: String) {
        if (!PlayerBus.send(c)) shutdown() // the page is gone, nothing left to control
    }

    private fun doPlay() { if (mp != null) nResume() else PlayerBus.send("play") }
    private fun doPause(why: String = "") { if (mp != null) nPause(why) else PlayerBus.send("pause") }

    // ------------------------------------------------------- native player

    // a short transient loss (a message ping, a notification sound) only lowers the volume: pausing and resuming for every
    // ping is what sounded like stuttering. Only a loss that lasts (a call, an alarm, an assistant) really pauses.
    private val transientPause = Runnable {
        if (mp != null && mpReady && wantPlay) { resumeOnFocus = true; nPause("audio focus lost for a while (call, alarm, assistant)") }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { c ->
        DebugLog.add("audio", "focus change $c")
        if (c == AudioManager.AUDIOFOCUS_LOSS) {
            ui.removeCallbacks(transientPause)
            resumeOnFocus = false; nPause("audio focus lost for good (another app took the sound)")
        } else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            try { mp?.setVolume(0.2f, 0.2f) } catch (_: Exception) { }
            ui.removeCallbacks(transientPause)
            ui.postDelayed(transientPause, 1500)
        } else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            try { mp?.setVolume(0.3f, 0.3f) } catch (_: Exception) { }
        } else if (c == AudioManager.AUDIOFOCUS_GAIN) {
            ui.removeCallbacks(transientPause)
            try { mp?.setVolume(1f, 1f) } catch (_: Exception) { }
            if (resumeOnFocus) { resumeOnFocus = false; nResume() }
        }
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun getFocus(): Boolean {
        val r = if (Build.VERSION.SDK_INT >= 26) {
            val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs()).setOnAudioFocusChangeListener(focusListener).build()
                .also { focusReq = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun dropFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) focusReq?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        } catch (_: Exception) { }
    }

    fun hasPlayer() = mp != null

    fun nPos(): Long = try { if (mpReady) (mp?.currentPosition ?: 0).toLong() else startAt } catch (_: Exception) { startAt }
    fun nDur(): Long = try { if (mpReady) (mp?.duration ?: 0).toLong().coerceAtLeast(0L) else 0L } catch (_: Exception) { 0L }

    private fun releasePlayer() {
        ui.removeCallbacks(transientPause)
        val p = mp
        mp = null; mpReady = false; wantPlay = false; resumeOnFocus = false
        // first: cancel the old track's in-flight chunk requests so they stop using bandwidth at once and reset() cannot block on them
        val src = aSrc
        aSrc = null
        if (p != null) {
            try { p.setOnCompletionListener(null); p.setOnErrorListener(null); p.setOnPreparedListener(null) } catch (_: Exception) { }
        }
        // reset()/release() wait for the player's reader thread, which can be inside a slow network request: never on the UI thread
        Bg.run {
            try { src?.close() } catch (_: Exception) { }
            if (p != null) {
                try { p.reset() } catch (_: Exception) { }
                try { p.release() } catch (_: Exception) { }
            }
        }
        dropFocus()
    }

    fun nPlay(url: String, startMs: Long, t: String, a: String, online: Boolean) {
        releasePlayer()
        if (url.isEmpty()) return
        title = t; artist = a; nOnline = online; startAt = startMs
        val p = MediaPlayer()
        mp = p; wantPlay = true
        try {
            p.setAudioAttributes(attrs())
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            val local = Api.get(applicationContext).localPath(url)
            val picked = Api.get(applicationContext).contentUri(url) // song from an imported music folder
            if (local != null) p.setDataSource(local)
            else if (picked != null) p.setDataSource(applicationContext, picked)
            else {
                val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                if (url.startsWith("https://") && host.endsWith(".googlevideo.com")) {
                    // the plain open-ended request MediaPlayer sends is refused or throttled by YouTube: read in bounded pieces
                    val ds = YtDataSource(Chunks(url, Api.get(applicationContext).uaFor(url)))
                    aSrc = ds
                    p.setDataSource(ds)
                    DebugLog.add("audio", "opening via chunked reader, host=$host")
                } else {
                    p.setDataSource(applicationContext, Uri.parse(url), mapOf("User-Agent" to UA))
                    DebugLog.add("audio", "opening plain, host=$host")
                }
            }
            ui.postDelayed({
                if (mp === p && !mpReady) DebugLog.add("audio", "STILL NOT PREPARED after 10 s (network stuck?)")
            }, 10_000)
            p.setOnPreparedListener { m ->
                if (mp !== m) return@setOnPreparedListener
                DebugLog.add("audio", "prepared dur=${(try { m.duration } catch (_: Exception) { -1 })}ms wantPlay=$wantPlay")
                mpReady = true
                try { if (startAt > 0) m.seekTo(startAt.toInt()) } catch (_: Exception) { }
                PlayerBus.send("ev:prepared:" + nDur())
                if (wantPlay) {
                    val gotFocus = getFocus() // a refused request (another app, screen recorder...) must not block playing
                    DebugLog.add("audio", "starting, focus=${if (gotFocus) "granted" else "REFUSED (playing anyway)"}")
                    m.start()
                    nState("playing")
                } else nState("paused")
            }
            p.setOnSeekCompleteListener { m ->
                if (mp === m) { position = nPos(); PlayerBus.send("ev:seeked"); publishSession() }
            }
            p.setOnCompletionListener { m ->
                if (mp !== m) return@setOnCompletionListener
                wantPlay = false
                if (!PlayerBus.send("ev:ended")) nState("paused") // no page left to pick the next song
            }
            p.setOnErrorListener { m, what, extra ->
                DebugLog.add("audio", "MediaPlayer error what=$what extra=$extra url=${url.substringBefore('?').takeLast(50)}")
                if (mp === m) {
                    val at = nPos() // where the song was when the stream broke: the retry carries on from here, not from 0 s
                    releasePlayer()
                    startAt = at
                    PlayerBus.send("ev:error")
                    nState("paused")
                }
                true
            }
            nState("loading")
            p.prepareAsync()
        } catch (e: Exception) {
            DebugLog.add("audio", "could not open sound", e)
            releasePlayer()
            PlayerBus.send("ev:error")
            nState("paused")
        }
    }

    private fun nState(st: String) {
        update(st, title, artist, nPos(), nDur(), nOnline)
        if (st == "playing") PlayerBus.send("ev:playing")
        else if (st == "paused") PlayerBus.send("ev:paused")
    }

    fun nPause(why: String = "the page (play/pause tap or app logic)") {
        val p = mp ?: return
        DebugLog.add("audio", "pause requested by: $why (ready=$mpReady)")
        wantPlay = false
        try { if (mpReady && p.isPlaying) p.pause() } catch (_: Exception) { }
        nState("paused")
    }

    fun nResume() {
        val p = mp ?: return
        wantPlay = true
        if (!mpReady) return // starts by itself when prepared
        ui.removeCallbacks(transientPause)
        try { if (p.isPlaying) { nState("playing"); return } } catch (_: Exception) { }
        getFocus() // same: play even if the focus request is refused
        try { p.start() } catch (_: Exception) { return }
        nState("playing")
    }

    fun nSeek(ms: Long) {
        val p = mp ?: return
        if (!mpReady) { startAt = ms; return }
        try { p.seekTo(ms.toInt()) } catch (_: Exception) { }
    }

    fun nVolume(v: Float) { try { mp?.setVolume(v, v) } catch (_: Exception) { } }

    // ------------------------------------------------------------------ state

    fun update(st: String, t: String, a: String, pos: Long, dur: Long, online: Boolean) {
        if (st == "idle") { shutdown(); return }
        state = st; title = t; artist = a; position = pos; duration = dur
        ui.removeCallbacks(idleStop)
        when (st) {
            "playing" -> { lock(true, 0L); wifiLock(online); noisy(true) }
            "loading" -> { lock(true, LOAD_LOCK_MS); wifiLock(online); noisy(false) } // safety timeout
            else -> {
                lock(false, 0L); wifiLock(false); noisy(false)
                ui.postDelayed(idleStop, IDLE_MS)
            }
        }
        publishSession()
        val key = "$state|$title|$artist"
        if (!fg) goForeground() else if (key != lastKey) nm.notify(ID, notification())
        lastKey = key
    }

    private fun lock(on: Boolean, timeoutMs: Long) {
        val w = wake ?: return
        if (on) {
            if (timeoutMs > 0) w.acquire(timeoutMs) else w.acquire(MAX_PLAY_MS)
        } else if (w.isHeld) w.release()
    }

    private fun wifiLock(on: Boolean) {
        val w = wifi ?: return
        if (on) { if (!w.isHeld) w.acquire() } else if (w.isHeld) w.release()
    }

    private fun noisy(on: Boolean) {
        if (on == noisyOn) return
        noisyOn = on
        try {
            if (on) {
                val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, RECEIVER_NOT_EXPORTED)
                else registerReceiver(noisy, f)
            } else unregisterReceiver(noisy)
        } catch (_: Exception) { }
    }

    private fun publishSession() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (duration > 0) duration else -1L)
                .build()
        )
        val s = when (state) {
            "playing" -> PlaybackState.STATE_PLAYING
            "loading" -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        // position + speed let the lock screen draw the progress bar by itself: no updates needed
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(s, position, if (s == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
        if (!session.isActive) session.isActive = true
    }

    // ----------------------------------------------------------- notification

    private fun goForeground() {
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(ID, n)
            fg = true
        } catch (_: Exception) {
            stopSelf() // not allowed to run in the foreground right now
        }
    }

    private fun action(icon: Int, label: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun notification(): Notification {
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val on = state == "playing" || state == "loading"
        b.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(if (title.isEmpty()) "SEITUNE" else title)
            .setContentText(artist)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", A_PREV))
            .addAction(
                action(
                    if (on) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (on) "Pause" else "Play", A_TOGGLE
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", A_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return b.build()
    }

    // --------------------------------------------------------------- shutdown

    private fun shutdown() {
        ui.removeCallbacks(idleStop)
        releasePlayer()
        lock(false, 0L); wifiLock(false); noisy(false)
        if (::session.isInitialized && session.isActive) session.isActive = false
        state = "idle"; lastKey = ""
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        fg = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiped away from recents: the page dies with the task, so stop everything
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        ui.removeCallbacksAndMessages(null)
        releasePlayer()
        lock(false, 0L); wifiLock(false); noisy(false)
        if (::session.isInitialized) session.release()
        super.onDestroy()
    }

    companion object {
        const val CH = "playback"
        const val ID = 41
        const val A_UPDATE = "com.mrk.seitune.UPDATE"
        const val A_PREV = "com.mrk.seitune.PREV"
        const val A_NEXT = "com.mrk.seitune.NEXT"
        const val A_TOGGLE = "com.mrk.seitune.TOGGLE"
        const val A_STOP = "com.mrk.seitune.STOP"
        const val A_PLAY = "com.mrk.seitune.PLAY"
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val IDLE_MS = 5 * 60_000L          // paused this long -> stop service
        private const val LOAD_LOCK_MS = 2 * 60_000L     // stream lookup safety timeout
        private const val MAX_PLAY_MS = 12 * 60 * 60_000L // leak guard if the page dies mid-song

        @Volatile var instance: PlaybackService? = null

        /** Start a song in the native player (starts the service if needed). */
        fun nplay(ctx: Context, url: String, startMs: Long, t: String, a: String, online: Boolean) {
            val s = instance
            if (s != null) { s.nPlay(url, startMs, t, a, online); return }
            if (url.isEmpty()) return // "drop the current song" with nothing running: nothing to start
            val i = Intent(ctx, PlaybackService::class.java).setAction(A_PLAY)
                .putExtra("u", url).putExtra("p", startMs).putExtra("t", t).putExtra("a", a).putExtra("o", online)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) { }
        }

        /** Called by the page (via [MainActivity]) on every play / pause / track change. */
        fun push(ctx: Context, st: String, t: String, a: String, pos: Long, dur: Long, online: Boolean) {
            val s = instance
            if (s != null) { s.update(st, t, a, pos, dur, online); return }
            if (st == "idle" || st == "paused") return // nothing running, nothing to start
            val i = Intent(ctx, PlaybackService::class.java).setAction(A_UPDATE)
                .putExtra("st", st).putExtra("t", t).putExtra("a", a)
                .putExtra("p", pos).putExtra("d", dur).putExtra("o", online)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) { /* app is in the background and may not start a service */ }
        }
    }
}
