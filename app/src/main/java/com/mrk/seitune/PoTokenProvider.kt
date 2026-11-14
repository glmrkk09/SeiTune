// SEITUNE - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * PO token from YouTube's BotGuard, run in a hidden WebView (the JS is in assets/po_token.html). The HTTP calls (Create,
 * GenerateIT) are made here; the WebView only executes JavaScript. Every failure ends as null and the caller carries on without a token.
 * Experimental and fragile: YouTube can change these internal endpoints at any time.
 */
class PoTokenProvider private constructor(private val app: Context) {

    companion object {
        private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // as in NewPipe's PoTokenWebView.kt; check it there if Create answers 4xx
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val BASE = "https://www.youtube.com/api/jnn/v1/"
        private val HOSTS = listOf("youtube.com", "google.com", "googleapis.com", "gstatic.com")
        @Volatile private var inst: PoTokenProvider? = null
        fun get(ctx: Context): PoTokenProvider =
            inst ?: synchronized(this) { inst ?: PoTokenProvider(ctx.applicationContext).also { inst = it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CancellableContinuation<String?>>()
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>() // identifier -> (pot, minted at)
    @Volatile private var wv: WebView? = null
    @Volatile private var loaded: CompletableDeferred<Boolean>? = null
    @Volatile private var initJob: Deferred<Boolean>? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var fails = 0
    @Volatile private var offUntil = 0L

    /** JS -> Kotlin. Runs on a WebView thread: only resume continuations here. */
    private inner class Bridge {
        @JavascriptInterface fun done(reqId: String, ok: Boolean, value: String) {
            val c = pending.remove(reqId) ?: return
            if (ok) c.resume(value) else { DebugLog.sei("pot", "js error: " + value.take(160)); c.resume(null) }
        }
    }

    /** Starts the BotGuard setup in the background so the first real request does not wait for it. */
    fun warmUp() { scope.launch { try { ensureReady() } catch (_: Throwable) { } } }

    /** Blocking, for worker threads (directExec, the chunked reader's refresher). Never call on the main thread. */
    fun getBlocking(identifier: String, timeoutMs: Long = 1500): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return try { runBlocking(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { get(identifier) } } } catch (_: Throwable) { null }
    }

    /** identifier = the video id or the visitorData, whichever YouTube binds the streaming token to (Settings.potBind decides). */
    suspend fun get(identifier: String): String? {
        cache[identifier]?.let { (pot, t) -> if (System.currentTimeMillis() - t < 30 * 60_000) return pot }
        if (System.currentTimeMillis() < offUntil) return null
        if (!ensureReady()) {
            if (++fails >= 3) { fails = 0; offUntil = System.currentTimeMillis() + 10 * 60_000; DebugLog.sei("pot", "3 failures in a row, off for 10 min") }
            return null
        }
        val t0 = System.currentTimeMillis()
        val pot = js("mint", identifier)
        if (pot == null || pot.length < 80) { DebugLog.sei("pot", "mint failed for ${identifier.take(6)}.."); return null }
        fails = 0
        if (cache.size > 100) cache.clear()
        cache[identifier] = pot to System.currentTimeMillis()
        DebugLog.sei("pot", "minted for ${identifier.take(6)}.. len=${pot.length} head=${pot.take(6)} in ${System.currentTimeMillis() - t0} ms")
        return pot
    }

    fun invalidate(identifier: String) { cache.remove(identifier) }

    /** Forget every minted token and the integrity token: the next request runs BotGuard again. */
    fun reset() { cache.clear(); expiresAt = 0L }

    /** One shared setup run: a caller giving up (timeout) never cancels it. */
    private suspend fun ensureReady(): Boolean {
        if (wv != null && System.currentTimeMillis() < expiresAt) return true
        val job = synchronized(this) {
            val j = initJob
            if (j != null && j.isActive) j else scope.async { doInit() }.also { initJob = it }
        }
        return job.await()
    }

    private suspend fun doInit(): Boolean {
        withContext(Dispatchers.Main) { teardown() }
        val t0 = System.currentTimeMillis()
        return try {
            withTimeout(20_000) {
                val ua = WebSettings.getDefaultUserAgent(app)
                DebugLog.sei("pot", "init, WebView=" + (try { WebView.getCurrentWebViewPackage()?.versionName } catch (_: Throwable) { "?" }))
                withContext(Dispatchers.Main) { createWebView() }
                if (loaded?.await() != true) throw IllegalStateException("page not loaded")
                val create = post(BASE + "Create", JSONArray().put(REQUEST_KEY).toString(), ua)
                val bg = js("runBotguard", create) ?: throw IllegalStateException("botguard failed")
                val git = JSONArray(post(BASE + "GenerateIT", JSONArray().put(REQUEST_KEY).put(bg).toString(), ua))
                val integrity = git.getString(0)
                val ttl = git.optLong(1, 21_600)
                js("setIntegrity", integrity) ?: throw IllegalStateException("no minter")
                expiresAt = System.currentTimeMillis() + maxOf(300L, ttl - 600) * 1000
                DebugLog.sei("pot", "ready in ${System.currentTimeMillis() - t0} ms, ttl=${ttl}s")
            }
            true
        } catch (e: Throwable) {
            DebugLog.sei("pot", "init failed", e)
            withContext(NonCancellable + Dispatchers.Main) { teardown() }
            false
        }
    }

    private fun post(url: String, body: String, ua: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 6000
            con.readTimeout = 10_000
            con.setRequestProperty("Content-Type", "application/json+protobuf")
            con.setRequestProperty("x-goog-api-key", API_KEY)
            con.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
            con.setRequestProperty("User-Agent", ua)
            con.outputStream.use { it.write(body.toByteArray()) }
            if (con.responseCode !in 200..299) throw IllegalStateException(url.substringAfterLast('/') + " HTTP " + con.responseCode)
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    /** Calls window.seitune.<fn>(reqId, arg); the JS answers through Bridge.done. */
    private suspend fun js(fn: String, arg: String): String? = suspendCancellableCoroutine { cont ->
        val id = "r" + ids.incrementAndGet()
        pending[id] = cont
        cont.invokeOnCancellation { pending.remove(id) }
        main.post {
            val w = wv
            if (w == null) { pending.remove(id)?.resume(null); return@post }
            w.evaluateJavascript("window.seitune.$fn(${JSONObject.quote(id)},${JSONObject.quote(arg)})", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val w = WebView(app)
        val done = CompletableDeferred<Boolean>()
        loaded = done
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        w.addJavascriptInterface(Bridge(), "Native")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String?) { done.complete(true) }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val sc = r.url.scheme ?: ""
                if (sc != "http" && sc != "https") return null // the page itself (data:/about:) has no host: blocking it left BotGuard waiting until the timeout
                val h = r.url.host ?: ""
                if (HOSTS.any { h == it || h.endsWith(".$it") }) {
                    if (r.url.path != "/") DebugLog.sei("pot", "wv request " + h + r.url.path)
                    return null
                }
                DebugLog.sei("pot", "wv blocked $h")
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                DebugLog.sei("pot", "WebView renderer gone (crash=${d.didCrash()})")
                teardown()
                return true
            }
        }
        wv = w
        val html = app.assets.open("po_token.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null)
    }

    /** Main thread. Fails everything in flight with null. */
    private fun teardown() {
        val w = wv
        wv = null
        expiresAt = 0L
        for (k in pending.keys.toList()) pending.remove(k)?.resume(null)
        if (w != null) try { w.removeJavascriptInterface("Native"); w.stopLoading(); w.destroy() } catch (_: Throwable) { }
    }
}
