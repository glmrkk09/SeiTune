// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Last-resort lookup, the "play it, then grab the link" way of in-app-browser downloaders: a real YouTube page is
 * loaded in an off-screen WebView, the page's own player starts (muted), and the media request it makes to
 * googlevideo.com is noticed. The page's player brings its own session/PO token, so that link can work where yt-dlp is refused.
 *
 * Experimental. YouTube's web player may use segmented (SABR/POST) requests, which are not a plain file: those are only
 * logged (host and key parameters, never the full link) so the debug log shows what the page really asked for.
 * Audio only for now: the web player asks for audio and picture as separate files.
 */
object WebSniffer {
    class Hit(val url: String, val ua: String, val headers: Map<String, String> = emptyMap())

    private class Seen(val url: String, val method: String, val headers: Map<String, String>)

    /** Blocking, for worker threads. Never call on the main thread. */
    fun find(ctx: Context, id: String, timeoutMs: Long = 20_000L): Hit? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return try { runBlocking(Dispatchers.IO) { findSuspend(ctx, id, timeoutMs) } } catch (_: Throwable) { null }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun findSuspend(ctx: Context, id: String, timeoutMs: Long): Hit? {
        val app = ctx.applicationContext
        val seen = ConcurrentLinkedQueue<Seen>()
        var wv: WebView? = null
        var ua = ""
        try {
            withContext(Dispatchers.Main) {
                val w = WebView(app) // never attached to a window
                wv = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.mediaPlaybackRequiresUserGesture = false
                ua = w.settings.userAgentString // the page's own User-Agent: the link must be fetched with the same one
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.webViewClient = object : WebViewClient() {
                    // runs on a Chromium thread: only record, never touch the WebView here
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url
                        if ((u.host ?: "").endsWith("googlevideo.com") && (u.path ?: "") == "/videoplayback") {
                            // Referer/Origin the page sent: the link has to be fetched with the same ones
                            val h = request.requestHeaders?.filterKeys { it.equals("Referer", true) || it.equals("Origin", true) } ?: emptyMap()
                            seen.add(Seen(u.toString(), request.method ?: "GET", h))
                        }
                        return null // let it through: the page keeps playing
                    }

                    // main thread: say where the page ended up (consent / sign-in / interstitial pages block the player)
                    override fun onPageFinished(view: WebView, url: String?) {
                        val u = Uri.parse(url ?: return)
                        DebugLog.sei("sniff", "page loaded: ${u.host}${u.path} title=" + (view.title ?: "").take(40))
                    }
                }
                w.loadUrl("https://m.youtube.com/watch?v=" + Uri.encode(id))
            }
            val end = System.currentTimeMillis() + timeoutMs
            var logged = 0
            while (System.currentTimeMillis() < end) {
                withContext(Dispatchers.Main) { // keep nudging the player: start (muted) as soon as the <video> exists
                    wv?.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play();}})()", null)
                }
                delay(1000)
                while (true) {
                    val s = seen.poll() ?: break
                    val u = Uri.parse(s.url)
                    val mime = u.getQueryParameter("mime") ?: "?"
                    if (logged < 10) {
                        logged++
                        DebugLog.sei("sniff", "${s.method} videoplayback itag=${u.getQueryParameter("itag")} mime=$mime " +
                            "c=${u.getQueryParameter("c")} range=${u.getQueryParameter("range") != null} pot=${u.getQueryParameter("pot") != null} sabr=${u.getQueryParameter("sabr")}")
                    }
                    // a plain, whole-file audio link: GET, not segmented. m4a is preferred; webm/opus is taken if nothing else came in 6 s
                    val okMime = mime.startsWith("audio/mp4") || (mime.startsWith("audio/webm") && System.currentTimeMillis() > end - timeoutMs + 6000)
                    if (s.method == "GET" && okMime && u.getQueryParameter("sabr") == null) {
                        val b = u.buildUpon().clearQuery()
                        for (n in u.queryParameterNames) {
                            if (n == "range" || n == "rn" || n == "rbuf" || n == "sq") continue // piece-specific, the player adds its own
                            for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
                        }
                        return Hit(b.build().toString(), ua, s.headers)
                    }
                }
            }
            DebugLog.sei("sniff", "no plain audio link from the page in ${timeoutMs / 1000}s")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.sei("sniff", "failed: " + (e.message ?: e.javaClass.simpleName))
            return null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { try { wv?.stopLoading(); wv?.destroy() } catch (_: Throwable) { } }
        }
    }
}
