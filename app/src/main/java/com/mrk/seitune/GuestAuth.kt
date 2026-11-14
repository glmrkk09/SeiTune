// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Zero-login answer to YouTube's "Sign in to confirm you're not a bot":
 *  - [isBotCheck]      recognises the error text (straight or curly apostrophe)
 *  - [visitorData]     a fresh guest visitorData token read from youtube.com
 *  - [cookieFile]      the cookies.txt to hand to yt-dlp: a user-imported one wins, else guest cookies harvested
 *                      by an off-screen WebView ([harvestGuestCookies]) into yt_guest_cookies.txt
 */
object GuestAuth {
    const val USER_COOKIES = "cookies.txt"
    const val GUEST_COOKIES = "yt_guest_cookies.txt"
    private const val GUEST_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Volatile private var cachedVisitor: Pair<Long, String>? = null
    /** INNERTUBE_CONTEXT_CLIENT_VERSION of the page last read, for the PO-token client's player request. */
    @Volatile var webVersion: String? = null
    private val harvestMutex = Mutex()

    fun isBotCheck(msg: String?): Boolean {
        val m = (msg ?: "").replace('\u2019', '\'').lowercase()
        return "sign in to confirm you're not a bot" in m || "confirm you're not a bot" in m
    }

    /** The token if one was fetched in the last 30 min, else null at once (never waits for the network). */
    fun visitorDataCached(): String? {
        val c = cachedVisitor
        return if (c != null && System.currentTimeMillis() - c.first < 30 * 60_000L) c.second else null
    }

    /** Real WebView User-Agent, set by Api at start (a frozen Chrome 124 string gets YouTube's "unsupported browser" page). */
    @Volatile var webUa: String? = null
    private fun ua(): String = webUa ?: UA

    private val vdLock = Any()
    @Volatile private var vdFailedAt = 0L

    /**
     * Any player/innertube answer carries responseContext.visitorData. Feeding it in here means the clients that need a visitorData
     * (ANDROID_VR, IOS, MWEB, the PO-token binding test) get one even when scraping youtube.com fails, because ANDROID answers anyway.
     */
    fun noteVisitor(v: String?) {
        val s = v?.trim()
        if (s.isNullOrEmpty() || s.length < 10) return
        val c = cachedVisitor
        if (c == null || System.currentTimeMillis() - c.first >= 30 * 60_000L) cachedVisitor = System.currentTimeMillis() to s
    }

    /** A rejected client version / visitorData ("The page needs to be reloaded"): forget both so the next lookup reads them again. */
    fun markStale() { cachedVisitor = null; webVersion = null; vdFailedAt = 0L }

    /** The page's client version when it was read, else a version dated two days ago (YouTube accepts recent builds; a 2025 one is refused as outdated). */
    fun webVersionOrGuess(): String {
        webVersion?.let { return it }
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.add(java.util.Calendar.DAY_OF_YEAR, -2)
        return "2." + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(cal.time) + ".01.00"
    }

    private fun cleanVd(raw: String?): String? {
        val v = raw?.replace("\\u003d", "=")?.replace("%3D", "=")?.replace("%3d", "=")?.replace("\\/", "/")?.trim()
        return if (v != null && v.length >= 12 && v.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '=' }) v else null
    }

    private fun http(method: String, url: String, body: String?, headers: Map<String, String>, errs: MutableList<String>, tag: String): String? = try {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = method
            con.connectTimeout = 6000
            con.readTimeout = 7000
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            for ((k, v) in headers) con.setRequestProperty(k, v)
            if (body != null) { con.doOutput = true; con.outputStream.use { it.write(body.toByteArray()) } }
            val code = con.responseCode
            if (code !in 200..299) { errs.add("$tag HTTP $code"); null }
            else con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    } catch (e: Throwable) { errs.add("$tag ${e.javaClass.simpleName}"); null }

    /** 1. innertube visitor_id endpoint (what an app does on first start). */
    private fun fromInnertube(errs: MutableList<String>): String? {
        val body = """{"context":{"client":{"clientName":"ANDROID","clientVersion":"20.10.38","hl":"en","gl":"US","osName":"Android","osVersion":"11","androidSdkVersion":30}}}"""
        val txt = http("POST", "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body,
            mapOf("Content-Type" to "application/json", "User-Agent" to "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
                "X-YouTube-Client-Name" to "3", "X-YouTube-Client-Version" to "20.10.38"), errs, "visitor_id") ?: return null
        val v = try { cleanVd(org.json.JSONObject(txt).optJSONObject("responseContext")?.optString("visitorData")) } catch (_: Throwable) { null }
        if (v == null) errs.add("visitor_id: none in answer")
        return v
    }

    /** 2. sw.js_data: a small JSON that carries the visitor data (found by its shape, so a moved field still works). */
    private fun fromSwData(errs: MutableList<String>): String? {
        val txt = http("GET", "https://www.youtube.com/sw.js_data", null,
            mapOf("User-Agent" to ua(), "Cookie" to "SOCS=CAI"), errs, "sw.js_data") ?: return null
        val m = Regex("\"(Cgt[A-Za-z0-9_\\-%=]{10,})\"").find(txt)
        val v = cleanVd(m?.groupValues?.get(1))
        if (v == null) errs.add("sw.js_data: none in answer")
        return v
    }

    /** 3. the watch page / home page (also gives the web client version). */
    private fun fromPage(errs: MutableList<String>): String? {
        val html = http("GET", "https://www.youtube.com/", null,
            mapOf("User-Agent" to ua(), "Cookie" to "SOCS=CAI; CONSENT=PENDING+987"), errs, "page") ?: return null
        (Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\"\\s*:\\s*\"([0-9.]+)\"").find(html)
            ?: Regex("\"clientVersion\"\\s*:\\s*\"(2\\.[0-9.]+)\"").find(html))?.groupValues?.get(1)?.let { webVersion = it }
        val raw = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]{10,})\"").find(html)?.groupValues?.get(1)
        val v = cleanVd(raw)
        if (v == null) errs.add("page: no visitorData in the html (consent or unsupported-browser page?)")
        return v
    }

    /**
     * visitorData for the player requests (cached 30 min). One caller reads it, the others wait for that same read and share it; after a
     * failed read the next try is 20 s later (not on every lookup). Sources in order: visitor_id endpoint, sw.js_data, the page.
     * Null only when all three failed; the reason of each is written to the debug log.
     */
    fun visitorData(force: Boolean = false): String? {
        val c = cachedVisitor
        if (!force && c != null && System.currentTimeMillis() - c.first < 30 * 60_000L) return c.second
        return synchronized(vdLock) {
            val c2 = cachedVisitor
            if (!force && c2 != null && System.currentTimeMillis() - c2.first < 30 * 60_000L) return@synchronized c2.second
            if (!force && System.currentTimeMillis() - vdFailedAt < 20_000L) return@synchronized null
            val errs = ArrayList<String>()
            var src = "visitor_id"
            var v = fromInnertube(errs)
            if (v == null) { src = "sw.js_data"; v = fromSwData(errs) }
            if (v == null) { src = "page"; v = fromPage(errs) }
            if (v != null) {
                cachedVisitor = System.currentTimeMillis() to v
                vdFailedAt = 0L
                DebugLog.sei("link", "visitorData read from $src")
            } else {
                vdFailedAt = System.currentTimeMillis()
                DebugLog.sei("link", "visitorData: all sources failed (" + errs.joinToString("; ") + ")")
            }
            v
        }
    }

    /** User-imported cookies.txt first, else a still-fresh guest file; null when neither exists. */
    fun cookieFile(ctx: Context): File? {
        val user = File(ctx.filesDir, USER_COOKIES)
        if (user.isFile && user.length() > 0) return user
        val guest = File(ctx.filesDir, GUEST_COOKIES)
        if (guest.isFile && guest.length() > 0 && System.currentTimeMillis() - guest.lastModified() < GUEST_MAX_AGE_MS) return guest
        return null
    }

    /** Returns a usable cookie file, harvesting guest cookies in a hidden WebView when there is none (or it expired). */
    fun ensureCookies(ctx: Context): File? {
        cookieFile(ctx)?.let { return it }
        return harvestGuestCookies(ctx)
    }

    /**
     * Blocking wrapper for the worker threads that call yt-dlp (Api.kt). Never call it from the main thread:
     * it would wait ~4 s. The real work is [harvestGuestCookiesSuspend], which only touches the WebView on Dispatchers.Main.
     */
    fun harvestGuestCookies(ctx: Context, videoId: String? = null, waitMs: Long = 4000L): File? {
        cookieFile(ctx)?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null // never block the UI thread
        return try { runBlocking(Dispatchers.IO) { harvestGuestCookiesSuspend(ctx, videoId, waitMs) } } catch (_: Throwable) { null }
    }

    /**
     * Loads youtube.com (or watch?v=videoId) in a detached WebView for ~[waitMs], then writes the cookies as a Netscape cookies.txt.
     *  - WebView creation, WebSettings, loadUrl and destroy run on Dispatchers.Main (each takes well under 1 ms: loadUrl is asynchronous)
     *  - the wait (delay), CookieManager.getCookie and the file write run on Dispatchers.IO
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun harvestGuestCookiesSuspend(ctx: Context, videoId: String? = null, waitMs: Long = 4000L): File? =
        harvestMutex.withLock {
            cookieFile(ctx)?.let { return@withLock it } // another caller just did it
            val app = ctx.applicationContext
            val url = if (videoId.isNullOrBlank()) "https://www.youtube.com/" else "https://www.youtube.com/watch?v=" + URLEncoder.encode(videoId, "UTF-8")
            var wv: WebView? = null
            try {
                withContext(Dispatchers.Main) {
                    val w = WebView(app) // never attached to a window: off-screen
                    wv = w
                    w.settings.javaScriptEnabled = true
                    w.settings.domStorageEnabled = true
                    w.settings.userAgentString = UA
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(w, true)
                    w.webViewClient = WebViewClient()
                    w.loadUrl(url)
                }
                withContext(Dispatchers.IO) { delay(waitMs) }
                val str: String? = withContext(Dispatchers.IO) {
                    val cm = CookieManager.getInstance()
                    try { cm.flush() } catch (_: Throwable) { }
                    cm.getCookie("https://youtube.com") ?: cm.getCookie("https://www.youtube.com")
                }
                if (str.isNullOrBlank()) { DebugLog.sei("link", "guest cookie harvest: no cookies"); return@withLock null }
                withContext(Dispatchers.IO) {
                    val f = File(app.filesDir, GUEST_COOKIES)
                    writeNetscape(f, str)
                    DebugLog.sei("link", "guest cookies harvested")
                    f
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            } finally {
                // WebView.destroy() must run on the UI thread, also when we were cancelled
                withContext(NonCancellable + Dispatchers.Main) {
                    try { wv?.stopLoading(); wv?.destroy() } catch (_: Throwable) { }
                }
            }
        }

    /** "a=1; b=2" -> Netscape cookies.txt, one line per cookie, for .youtube.com. */
    fun writeNetscape(f: File, cookieHeader: String) {
        val expiry = System.currentTimeMillis() / 1000 + 180L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n# generated by SEITUNE (guest)\n\n")
        for (part in cookieHeader.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val name = part.substring(0, i).trim()
            val value = part.substring(i + 1).trim()
            if (name.isEmpty()) continue
            // domain \t includeSubdomains \t path \t secure \t expiry \t name \t value
            sb.append(".youtube.com\tTRUE\t/\tTRUE\t").append(expiry).append('\t').append(name).append('\t').append(value).append('\n')
        }
        f.writeText(sb.toString())
    }

    /** Forget the guest cookies (after a bot check that they did not cure) so the next try harvests new ones. */
    fun dropGuest(ctx: Context) {
        try { File(ctx.filesDir, GUEST_COOKIES).delete() } catch (_: Throwable) { }
        cachedVisitor = null
    }
}
