// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * The app's own browser: one WebView. There is no server and no network port.
 * Requests to the virtual host below never touch the network; they are answered right here,
 * either from the bundled UI (assets) or by [Api] (search, stream, download, cache, library).
 *
 * Background play: the WebView is never paused when the screen turns off or the app is left.
 * [PlaybackService] (foreground service + media notification) keeps the process alive and only
 * holds a CPU wake lock while a song is really playing.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    // created on first use (and warmed up in the background by App), so startup never waits for the file index
    private val api: Api by lazy { Api.get(applicationContext) }

    /** What the page can call: window.AndroidPlayer.update(...) */
    private inner class Bridge {
        @JavascriptInterface
        fun update(state: String, title: String, artist: String, posMs: Long, durMs: Long, online: Boolean) {
            if (state != "playing" && state != "loading" && state != "paused" && state != "idle") return
            runOnUiThread {
                PlaybackService.push(
                    applicationContext, state, title.take(200), artist.take(200),
                    posMs.coerceAtLeast(0L), durMs.coerceAtLeast(0L), online
                )
            }
        }
    }

    /** Native audio player (lives in the foreground service, so it never stops in the background). */
    private inner class NativeBridge {
        @JavascriptInterface
        fun nplay(url: String, startMs: Long, title: String, artist: String, online: Boolean) {
            runOnUiThread {
                PlaybackService.nplay(applicationContext, url, startMs.coerceAtLeast(0L), title.take(200), artist.take(200), online)
            }
        }

        @JavascriptInterface
        fun npause() { runOnUiThread { PlaybackService.instance?.nPause() } }

        /** false = no native song is loaded (the page then starts it again with nplay) */
        @JavascriptInterface
        fun nresume(): Boolean {
            val s = PlaybackService.instance ?: return false
            if (!s.hasPlayer()) return false
            runOnUiThread { s.nResume() }
            return true
        }

        @JavascriptInterface
        fun nseek(ms: Long) { runOnUiThread { PlaybackService.instance?.nSeek(ms.coerceAtLeast(0L)) } }

        @JavascriptInterface
        fun nvol(v: Double) { runOnUiThread { PlaybackService.instance?.nVolume(v.toFloat().coerceIn(0f, 1f)) } }

        @JavascriptInterface
        fun npos(): Long = PlaybackService.instance?.nPos() ?: 0L

        @JavascriptInterface
        fun ndur(): Long = PlaybackService.instance?.nDur() ?: 0L
    }

    // ---- Video mode, Namida style: the sound is always the native audio player; the picture is a second,
    // muted native player drawn on a SurfaceView laid over the page's video box. No WebView <video> involved.
    private lateinit var sv: SurfaceView
    private var vp: MediaPlayer? = null
    private var vSrc: YtDataSource? = null
    private var vReady = false
    private var vUrl: String? = null
    private var vStartMs = 0L
    private var vWant = false
    private var surfaceOk = false
    private var rx = 0
    private var ry = 0
    private var rw = 1
    private var rh = 1
    private var vidW = 0
    private var vidH = 0
    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun jsCall(code: String) {
        if (::web.isInitialized) web.evaluateJavascript(code, null)
    }

    /** Puts the picture inside the page's video box, keeping the video's own shape. */
    private fun fitVideo() {
        if (!::sv.isInitialized) return
        var x = rx
        var y = ry
        var w = rw
        var h = rh
        if (vidW <= 0 || vidH <= 0) {
            w = 1 // not decoded yet: stay out of the way of the thumbnail and the loading spinner
            h = 1
        } else if (rw > 0 && rh > 0) {
            val k = minOf(rw.toFloat() / vidW, rh.toFloat() / vidH)
            w = (vidW * k).toInt()
            h = (vidH * k).toInt()
            x = rx + (rw - w) / 2
            y = ry + (rh - h) / 2
        }
        val lp = FrameLayout.LayoutParams(w.coerceAtLeast(1), h.coerceAtLeast(1))
        lp.leftMargin = x
        lp.topMargin = y
        sv.layoutParams = lp
    }

    private fun vClose() {
        val p = vp
        vp = null
        vReady = false
        vUrl = null
        vWant = false
        vidW = 0
        vidH = 0
        // cut the old track's chunk downloads FIRST: reset()/release() can wait for a read in progress, and the old picture's
        // requests must not keep using the network while the next track loads
        val src = vSrc
        vSrc = null
        if (p != null) {
            try { p.setOnPreparedListener(null); p.setOnErrorListener(null); p.setOnVideoSizeChangedListener(null) } catch (_: Exception) { }
            // let go of the Surface BEFORE releasing: a new player's prepareAsync() fails with IllegalStateException while the old one still holds it
            try { p.setSurface(null) } catch (_: Exception) { }
            vReleasedAt = System.currentTimeMillis()
        }
        // the slow part (cutting the downloads, reset, release) runs on a background thread: it can wait on the network, the UI must not
        Bg.run {
            try { src?.close() } catch (_: Exception) { }
            if (p != null) {
                try { p.reset() } catch (_: Exception) { }
                try { p.release() } catch (_: Exception) { }
            }
        }
        if (::sv.isInitialized) sv.visibility = View.INVISIBLE
    }

    private fun vBegin() {
        val url = vUrl ?: return
        if (vp != null) return // already opening (a retry and surfaceCreated can both ask)
        val p = MediaPlayer()
        vp = p
        vReady = false
        var step = "start"
        var sourceSet = false
        try {
            try { p.reset() } catch (_: Exception) { } // a brand-new player is already idle; this makes sure of it
            // The surface is NOT attached before prepare any more: the log showed IllegalStateException from prepareAsync() on almost
            // every fresh open even though the surface was valid, and it went away on its own a second later. Prepare needs only the
            // source; the surface is attached in onPrepared (or by surfaceCreated), so the surface state cannot block opening.
            step = "source"
            val local = api.localPath(url)
            val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
            if (local != null) p.setDataSource(local)
            else if (url.startsWith("https://") && host.endsWith(".googlevideo.com")) {
                // read in small bounded pieces: the plain open-ended request is refused (403) for these links
                val ds = YtDataSource(Chunks(url, api.uaFor(url)))
                vSrc = ds
                p.setDataSource(ds)
            } else p.setDataSource(applicationContext, Uri.parse(url), mapOf("User-Agent" to UA))
            sourceSet = true
            step = "volume"
            p.setVolume(0f, 0f)
            step = "listeners"
            p.setOnVideoSizeChangedListener { m, w, h ->
                if (vp === m) {
                    vidW = w
                    vidH = h
                    fitVideo()
                }
            }
            p.setOnPreparedListener { m ->
                if (vp === m) {
                    vReady = true
                    try {
                        val surf = sv.holder.surface
                        if (surf != null && surf.isValid) m.setSurface(surf)
                        else DebugLog.add("picture", "surface not ready yet, it is attached when it appears")
                    } catch (_: Exception) { }
                    DebugLog.add("picture", "prepared ${vidW}x$vidH")
                    // a seek always draws a frame, so the picture shows even before it is started (no black box)
                    try { m.seekTo(vStartMs.toInt()) } catch (_: Exception) { }
                    if (vWant) { try { m.start() } catch (_: Exception) { } }
                    jsCall("window.__vev&&window.__vev('prepared')")
                }
            }
            p.setOnErrorListener { m, what, extra ->
                DebugLog.add("picture", "MediaPlayer error what=$what extra=$extra")
                if (vp === m) jsCall("window.__vev&&window.__vev('error','player $what/$extra')")
                true
            }
            step = "prepare"
            if (!sourceSet) throw IllegalStateException("source not set")
            p.prepareAsync()
            DebugLog.add("picture", "opening ${if (local != null) "file" else if (vSrc != null) "chunked reader" else "plain"} host=$host")
        } catch (e: Exception) {
            DebugLog.add("picture", "could not open picture at step '$step'", e)
            if (step == "prepare" && vTries < 5 && vUrl == url) {
                // the surface can still be busy with the previous player for a moment: wait and try once more
                vTries++
                try { p.setOnPreparedListener(null); p.setOnErrorListener(null); p.reset(); p.release() } catch (_: Exception) { }
                try { vSrc?.close() } catch (_: Exception) { }
                vSrc = null
                vp = null
                DebugLog.add("picture", "retrying prepare (#$vTries)")
                web.postDelayed({
                    if (vUrl == url && vp == null) vBegin()
                    else DebugLog.add("picture", "retry skipped (picture changed or already open)")
                }, 150L + 200L * vTries)
                return
            }
            val why = ("step $step " + e.javaClass.simpleName + " " + (e.message ?: "")).replace(Regex("[^A-Za-z0-9 ._:/-]"), "").take(80)
            jsCall("window.__vev&&window.__vev('error','$why')")
        }
    }

    private var vTries = 0
    private var vReleasedAt = 0L

    private fun vOpen(url: String, startMs: Long, x: Int, y: Int, w: Int, h: Int) {
        if (vp != null && vUrl == url) {
            // the same picture is already opening: opening it twice makes the second prepare fail (surface still in use)
            DebugLog.add("picture", "duplicate open ignored")
            rx = x; ry = y; rw = w; rh = h
            fitVideo()
            return
        }
        vTries = 0
        vClose()
        vUrl = url
        vStartMs = startMs
        vWant = false // the page starts it when the sound is really playing
        rx = x
        ry = y
        rw = w
        rh = h
        fitVideo()
        sv.visibility = View.VISIBLE
        // start at once: preparing does not need the surface (it is attached when prepared)
        val wait = 150L - (System.currentTimeMillis() - vReleasedAt)
        if (wait > 0) web.postDelayed({ if (vUrl == url && vp == null) vBegin() }, wait) else vBegin()
    }

    private inner class VideoBridge {
        @JavascriptInterface
        fun open(url: String, startMs: Long, x: Int, y: Int, w: Int, h: Int) {
            runOnUiThread { vOpen(url, startMs.coerceAtLeast(0L), x, y, w, h) }
        }

        @JavascriptInterface
        fun rect(x: Int, y: Int, w: Int, h: Int) {
            runOnUiThread {
                if (x != rx || y != ry || w != rw || h != rh) {
                    rx = x
                    ry = y
                    rw = w
                    rh = h
                    fitVideo()
                }
            }
        }

        @JavascriptInterface
        fun show(on: Boolean) {
            runOnUiThread { if (vUrl != null) sv.visibility = if (on) View.VISIBLE else View.INVISIBLE }
        }

        @JavascriptInterface
        fun play() {
            runOnUiThread {
                vWant = true
                val p = vp
                if (p != null && vReady) {
                    try { if (!p.isPlaying) p.start() } catch (_: Exception) { }
                }
            }
        }

        @JavascriptInterface
        fun pause() {
            runOnUiThread {
                vWant = false
                val p = vp
                if (p != null && vReady) {
                    try { if (p.isPlaying) p.pause() } catch (_: Exception) { }
                }
            }
        }

        @JavascriptInterface
        fun seek(ms: Long) {
            runOnUiThread {
                val p = vp
                if (p != null && vReady) {
                    try {
                        if (Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt())
                    } catch (_: Exception) { }
                } else {
                    vStartMs = ms
                }
            }
        }

        @JavascriptInterface
        fun pos(): Long = try { if (vReady) (vp?.currentPosition ?: 0).toLong() else 0L } catch (_: Exception) { 0L }

        @JavascriptInterface
        fun close() {
            runOnUiThread { vClose() }
        }
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun start(id: String, title: String, ch: String, dur: String, kind: String, q: Int, fmt: String, pl: String): String =
            try {
                api.beginDownload(id, title, ch, dur, kind, q, fmt, pl).toString()
            } catch (e: ApiError) {
                "!" + (e.message ?: "download failed")
            } catch (_: Throwable) {
                "!download failed"
            }

        @JavascriptInterface
        fun jobs(): String = try { api.jobsText() } catch (_: Throwable) { "" }
    }

    /** Folder picker for downloads: window.SaveFolder.pick() opens Android's folder chooser (it has a "new folder" button). */
    private inner class FolderBridge {
        @JavascriptInterface
        fun pick() {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                    if (Build.VERSION.SDK_INT >= 26) {
                        SaveDir.last(applicationContext)?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                    }
                    startActivityForResult(i, REQ_FOLDER)
                } catch (_: Throwable) {
                    folderResult(false)
                }
            }
        }

        /** Name of the folder downloads are copied to, or "" when none is chosen. */
        @JavascriptInterface
        fun name(): String = SaveDir.name(applicationContext)
    }

    /** Music import: window.ImportFolder.pick() opens the folder chooser; the folder is scanned and its songs show in Library > Imported. */
    private inner class ImportBridge {
        @JavascriptInterface
        fun pick() {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                    startActivityForResult(i, REQ_IMPORT)
                } catch (_: Throwable) {
                    importResult(false)
                }
            }
        }
    }

    private fun importResult(ok: Boolean) {
        if (::web.isInitialized) web.evaluateJavascript("window.__import&&window.__import($ok)", null)
    }

    private fun folderResult(ok: Boolean) {
        if (::web.isInitialized) web.evaluateJavascript("window.__folder&&window.__folder($ok)", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT) {
            val tree = data?.data
            var added = false
            if (resultCode == RESULT_OK && tree != null) {
                try {
                    contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    MusicFolders.get(applicationContext).add(tree)
                    added = true
                } catch (_: Throwable) {
                }
            }
            importResult(added)
            return
        }
        if (requestCode != REQ_FOLDER) return
        val uri = data?.data
        var ok = false
        if (resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                SaveDir.set(applicationContext, uri)
                ok = true
            } catch (_: Throwable) {
            }
        }
        folderResult(ok)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sv = SurfaceView(this)
        // A SurfaceView is drawn BEHIND its window by default, so the opaque WebView hid the picture
        // (sound played, no video). On top of the window it shows over the page's video box.
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        sv.visibility = View.INVISIBLE
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceOk = true
                val p = vp
                if (p != null) {
                    // while still preparing, onPrepared attaches the surface (setSurface is not allowed in that state)
                    if (vReady) { try { p.setSurface(holder.surface) } catch (_: Exception) { } }
                } else if (vUrl != null) {
                    vBegin()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceOk = false
                try { vp?.setSurface(null) } catch (_: Exception) { }
            }
        })
        root.addView(sv, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        web.setBackgroundColor(0xFF0B0813.toInt())

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true // theme choice etc.
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        // Keep the page's renderer alive and un-throttled while the screen is off
        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        web.addJavascriptInterface(Bridge(), "AndroidPlayer")
        web.addJavascriptInterface(NativeBridge(), "NativeAudio")
        web.addJavascriptInterface(FolderBridge(), "SaveFolder")
        web.addJavascriptInterface(DownloadBridge(), "DownloadNative")
        web.addJavascriptInterface(ImportBridge(), "ImportFolder")
        web.addJavascriptInterface(VideoBridge(), "NativeVideo")

        // notification / lock screen / headset buttons -> page
        PlayerBus.sink = { c ->
            runOnUiThread {
                if (::web.isInitialized) {
                    web.evaluateJavascript("window.__native&&window.__native('" + c.replace("'", "") + "')", null)
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null // thumbnails and audio streams load normally
                val path = u.path ?: "/"
                return if (path.startsWith("/api/")) api.handle(request) else asset(path)
            }

            // keep the app inside its own window; external web links open in Chrome
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                openInChrome(request.url)
                return true
            }
        }

        // Android 13+: the media notification needs this (music still plays if you say no)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }

        web.loadUrl("https://$HOST/")
    }

    /** Opens an http(s) link in Chrome; falls back to the default browser if Chrome isn't installed. */
    private fun openInChrome(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(Intent(intent).setPackage("com.android.chrome"))
        } catch (_: Exception) {
            try { startActivity(intent) } catch (_: Exception) { }
        }
    }

    private fun asset(path: String): WebResourceResponse {
        val name = if (path == "/") "index.html" else path.trimStart('/')
        return try {
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "html" -> "text/html"
                "png" -> "image/png"
                "js" -> "application/javascript"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "css" -> "text/css"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, "utf-8", assets.open(name))
        } catch (_: IOException) {
            WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                mutableMapOf(), ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    // Deliberately NOT calling web.onPause()/pauseTimers(): that is what silences the music
    // when the screen turns off. The foreground service keeps the process alive instead.

    // Leaving the app while a VIDEO plays: the WebView freezes video in the background, so the page
    // hands the same song over to the native player (audio only, same position).
    override fun onPause() {
        val vpl = vp
        if (vpl != null && vReady) {
            try { if (vpl.isPlaying) vpl.pause() } catch (_: Exception) { }
        }
        if (::web.isInitialized) {
            web.evaluateJavascript("window.__native&&window.__native('bg')", null)
        }
        super.onPause()
    }

    // Screen left (home button, screen off, another app): the picture player is released at once, so a song playing in the
    // background uses no video decoder, no GPU and no network for the picture. The page reopens it in sync when we come back.
    override fun onStop() {
        vClose()
        if (::web.isInitialized) web.evaluateJavascript("window.__picBg&&window.__picBg()", null)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::web.isInitialized) web.evaluateJavascript("window.__picBack&&window.__picBack()", null)
    }

    override fun onDestroy() {
        PlayerBus.sink = null
        vClose()
        // only a real exit stops the music; a system recreate must not
        if (isFinishing) stopService(Intent(this, PlaybackService::class.java))
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    // Back = send the app to the background so the music keeps playing
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::web.isInitialized) {
            moveTaskToBack(true)
            return
        }
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'") { r ->
            if (r?.trim('"') != "1") moveTaskToBack(true)
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        private const val REQ_FOLDER = 4711
        private const val REQ_IMPORT = 4712
    }
}
