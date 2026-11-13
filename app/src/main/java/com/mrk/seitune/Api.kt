// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.net.ConnectivityManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ApiError(val code: Int, message: String) : Exception(message)

/**
 * The endpoints the UI calls, answered in-process, plus the offline side:
 *
 *  - /api/stream      picks the best source for a track: download > cache > network (ad-free, via yt-dlp)
 *  - auto cache       songs/videos you stream are also kept in a size-limited cache (see [Offline.enforce])
 *  - /api/download    permanent audio (MP3/M4A) or video (MP4) downloads; a cached copy is promoted, not re-fetched
 *  - /api/cache/pin, /remove, /clear   list, pin, remove, clear
 *  - /api/settings    prefer offline, auto-cache toggles, Wi-Fi only, cache limit, video quality
 *
 * The UI keeps calling fetch('/api/...') and never knows there is no server behind it.
 */
class Api private constructor(private val ctx: Context) {

    private val off = Offline.get(ctx)
    private val cfg = Settings(ctx)

    private class Job(val id: Long, val vid: String, val title: String, val kind: String, val pl: String = "") {
        @Volatile var state = "running" // running | done | failed
        @Volatile var queued = true      // waiting for a free download slot
        @Volatile var pct = 0f
        @Volatile var msg = ""
        @Volatile var save: android.net.Uri? = null // folder the user picked for this download (null = app storage only)
    }

    private class CacheJob(val vid: String, val kind: String) {
        @Volatile var pct = 0f
    }

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

    // googlevideo links are minted for one player client (ANDROID_VR, IOS...) and answer 403 to a different User-Agent
    private val uaMap = ConcurrentHashMap<String, String>()
    fun uaFor(url: String): String = uaMap[url] ?: UA

    private val jobs = ArrayList<Job>()
    private val nextJob = AtomicLong(1)
    private var feed: Pair<Long, String>? = null
    private val streamCache = HashMap<String, Pair<Long, String>>()

    // auto-cache work runs one at a time so it never competes with what you are playing
    private val cacheExec = Executors.newSingleThreadScheduledExecutor()
    private val caching = ConcurrentHashMap<String, CacheJob>()

    // downloads: a queue with a user-chosen number of parallel slots
    private val dlExec = java.util.concurrent.ThreadPoolExecutor(
        cfg.parallel, cfg.parallel, 30, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { r -> Thread(r).also { it.priority = Thread.NORM_PRIORITY } }
    ).apply { allowCoreThreadTimeOut(true) }

    private fun resizeDownloads(n: Int) {
        if (n > dlExec.maximumPoolSize) { dlExec.maximumPoolSize = n; dlExec.corePoolSize = n }
        else { dlExec.corePoolSize = n; dlExec.maximumPoolSize = n }
    }

    init {
        off.enforce(limitBytes())
    }

    private fun limitBytes(): Long = cfg.cacheMb.toLong() * 1024L * 1024L

    // ------------------------------------------------------------ routing

    fun handle(req: WebResourceRequest): WebResourceResponse = try {
        val u = req.url
        when (u.path) {
            "/api/home" -> {
                if (u.getQueryParameter("fresh") == "1") dropFeedCache()
                json(home())
            }
            "/api/search" -> {
                if (u.getQueryParameter("fresh") == "1") dropFeedCache()
                val q = (u.getQueryParameter("q") ?: "").trim()
                if (q.length < 2 || q.length > 200) throw ApiError(400, "query too short")
                json(search(q, 20).toString())
            }
            "/api/stream" -> json(
                resolve(
                    validId(u.getQueryParameter("id")).also { if (u.getQueryParameter("fresh") == "1") forget(it) },
                    if (u.getQueryParameter("mode") == "video") "video" else "audio",
                    (u.getQueryParameter("title") ?: "").take(200),
                    (u.getQueryParameter("ch") ?: "").take(100),
                    (u.getQueryParameter("dur") ?: "").take(20),
                    u.getQueryParameter("nocache") == "1",
                    u.getQueryParameter("via") == "ytdlp"
                ).toString()
            )
            "/api/media" -> media(u.getQueryParameter("u"), req.requestHeaders)
            "/api/status" -> json(status().toString())
            "/api/library" -> json(library().toString())
            "/api/file" -> file(u.getQueryParameter("d"), u.getQueryParameter("n"), req.requestHeaders)
            "/api/delete" -> {
                val d = u.getQueryParameter("d")
                val f = safeFile(d, u.getQueryParameter("n")) ?: throw ApiError(404, "no such file")
                val kind = if (d == "v") "video" else "audio"
                off.byFile(kind, f.name)?.let { off.drop(it, deleteFile = false) }
                if (!f.delete()) throw ApiError(502, "could not delete")
                if (kind == "audio") Lyrics.delete(off.music, null, f.name) // the lyrics saved beside it go with it
                json("{\"ok\":true}")
            }
            "/api/lyrics" -> json(lyricsJson(u.getQueryParameter("id"), u.getQueryParameter("d"), u.getQueryParameter("n")).toString())
            "/api/download" -> {
                val id = validId(u.getQueryParameter("id"))
                val kind = if (u.getQueryParameter("kind") == "video") "video" else "audio"
                val title = (u.getQueryParameter("title") ?: "").take(200)
                val ch = (u.getQueryParameter("ch") ?: "").take(100)
                val dur = (u.getQueryParameter("dur") ?: "").take(20)
                val q = u.getQueryParameter("q")?.toIntOrNull() ?: cfg.quality
                val fmt = if (u.getQueryParameter("fmt") == "mp3") "mp3" else "m4a"
                val pl = (u.getQueryParameter("pl") ?: "").take(100)
                json(JSONObject().put("job", startDownload(id, title, ch, dur, kind, q, fmt, pl)).toString())
            }
            "/api/plsearch" -> {
                val q = (u.getQueryParameter("q") ?: "").trim()
                if (q.length < 2 || q.length > 200) throw ApiError(400, "query too short")
                json(plSearch(q, 24).toString())
            }
            "/api/plhome" -> json(plHome().toString())
            "/api/plitems" -> json(plItems(u.getQueryParameter("id") ?: "").toString())
            "/api/prewarm" -> {
                prewarm(validId(u.getQueryParameter("id")), if (u.getQueryParameter("mode") == "video") "video" else "audio")
                json("{\"ok\":true}")
            }
            "/api/jobs" -> json(jobsJson().toString())
            "/api/cache" -> json(cacheJson().toString())
            "/api/cache/pin" -> {
                val m = off.get("cache", kindOf(u.getQueryParameter("kind")), validId(u.getQueryParameter("id")))
                    ?: throw ApiError(404, "not cached")
                off.pin(m, u.getQueryParameter("on") != "0")
                json(cacheJson().toString())
            }
            "/api/cache/remove" -> {
                off.get("cache", kindOf(u.getQueryParameter("kind")), validId(u.getQueryParameter("id")))
                    ?.let { off.drop(it) }
                json(cacheJson().toString())
            }
            "/api/cache/clear" -> {
                // everything except pinned items; downloads are never part of the cache
                for (m in off.all("cache")) if (!m.pinned) off.drop(m)
                json(cacheJson().toString())
            }
            "/api/settings" -> {
                applySettings(u)
                json(cfg.toJson().put("savePath", PublicSave.path(ctx)).put("saveDisplay", PublicSave.display(ctx)).toString())
            }
            "/api/import/list" -> json(importJson().toString())
            "/api/import/scan" -> {
                MusicFolders.get(ctx).scan()
                json(importJson().toString())
            }
            "/api/import/remove" -> {
                MusicFolders.get(ctx).remove(u.getQueryParameter("f") ?: "")
                json(importJson().toString())
            }
            "/api/update" -> json(JSONObject().put("msg", update()).toString())
            "/api/debug" -> json(JSONObject().put("log", DebugLog.text()).toString())
            "/api/debug/clear" -> {
                DebugLog.clear()
                json("{\"ok\":true}")
            }
            "/api/debuglog" -> {
                DebugLog.add("page", (u.getQueryParameter("m") ?: "").take(300))
                json("{\"ok\":true}")
            }
            else -> throw ApiError(404, "not found")
        }
    } catch (e: ApiError) {
        if (req.url.path != "/api/debuglog") DebugLog.add("api", "${req.url.path} -> HTTP ${e.code}: ${e.message}")
        text(e.message ?: "error", e.code)
    } catch (e: Throwable) {
        DebugLog.add("api", "${req.url.path} -> ", e)
        text(lastLine(e, "error"), 502)
    }

    // ----------------------------------------------------------- helpers

    /**
     * Passes a YouTube media link through the app to the page's <video>, with our own headers and Range handling
     * (the same way the downloader reads these links), so the WebView never trips over a Referer/Origin check.
     */
    private fun media(link: String?, headers: Map<String, String>): WebResourceResponse {
        val url = link ?: throw ApiError(400, "no link")
        val host = try { URL(url).host.lowercase() } catch (_: Throwable) { "" }
        if (!url.startsWith("https://") || !(host.endsWith(".googlevideo.com") || host.endsWith(".youtube.com"))) {
            throw ApiError(400, "bad link")
        }
        // YouTube refuses open-ended Range requests from a player; bounded pieces always work
        val ch = Chunks(url, uaFor(url))
        val len = try { ch.size() } catch (e: Throwable) { throw ApiError(502, "media: " + (e.message ?: "no answer")) }
        val type = (android.net.Uri.parse(url).getQueryParameter("mime") ?: "video/mp4").substringBefore(';')
        return ranged(len, type, headers) { start -> ChunkStream(ch, start, len - 1) }
    }

    private fun awaitReady() {
        if (!Engine.ready.await(25, TimeUnit.SECONDS)) throw ApiError(502, "Downloader is still starting, try again in a moment")
        Engine.error?.let { throw ApiError(502, "Downloader failed to start: $it") }
    }

    private fun validId(s: String?): String {
        val id = s ?: ""
        if (id.length !in 6..20 || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
            throw ApiError(400, "bad id")
        }
        return id
    }

    private fun kindOf(s: String?) = if (s == "video") "video" else "audio"

    private fun lastLine(e: Throwable, fallback: String): String =
        (e.message ?: "").lines().lastOrNull { it.isNotBlank() }?.trim() ?: fallback

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun resp(mime: String, data: ByteArray, code: Int = 200) = WebResourceResponse(
        mime, "utf-8", code, reason(code),
        mutableMapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data)
    )

    private fun json(body: String) = resp("application/json", body.toByteArray())
    private fun text(msg: String, code: Int) = resp("text/plain", msg.toByteArray(), code)

    private fun metered(): Boolean = try {
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Throwable) {
        false
    }

    private fun applySettings(u: android.net.Uri) {
        fun flag(k: String): Boolean? = u.getQueryParameter(k)?.let { it == "1" || it == "true" }
        flag("preferOffline")?.let { cfg.preferOffline = it }
        flag("autoCacheAudio")?.let { cfg.autoCacheAudio = it }
        flag("autoCacheVideo")?.let { cfg.autoCacheVideo = it }
        flag("wifiOnly")?.let { cfg.wifiOnly = it }
        u.getQueryParameter("quality")?.toIntOrNull()?.let { cfg.quality = it }
        flag("turbo")?.let { cfg.turbo = it }
        u.getQueryParameter("savePath")?.let { v -> PublicSave.set(ctx, v)?.let { throw ApiError(400, it) } }
        u.getQueryParameter("parallel")?.toIntOrNull()?.let { cfg.parallel = it; resizeDownloads(cfg.parallel) }
        u.getQueryParameter("cacheMb")?.toIntOrNull()?.let {
            cfg.cacheMb = it
            off.enforce(limitBytes())
        }
    }

    /** Native player: turns "/api/file?d=..&n=.." into the real file so MediaPlayer can open it directly. */
    fun localPath(url: String): String? = try {
        val u = android.net.Uri.parse(url)
        if (u.path != "/api/file") null else safeFile(u.getQueryParameter("d"), u.getQueryParameter("n"))?.absolutePath
    } catch (_: Throwable) {
        null
    }

    /** Native player: an imported song (/api/file?d=i&n=id) is opened straight from its folder, not copied. */
    fun contentUri(url: String): android.net.Uri? = try {
        val u = android.net.Uri.parse(url)
        if (u.path != "/api/file" || u.getQueryParameter("d") != "i") null
        else MusicFolders.get(ctx).track(u.getQueryParameter("n"))?.uri
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------ yt-dlp

    private val searchCache = HashMap<String, Pair<Long, String>>()
    private val searchLocks = HashMap<String, Any>()

    private fun search(query: String, count: Int): JSONArray {
        val sk = "$count|${query.lowercase()}"
        val lock = synchronized(searchLocks) { searchLocks.getOrPut(sk) { Any() } }
        return synchronized(lock) {
            synchronized(searchCache) {
                searchCache[sk]?.let { (t, body) ->
                    if (System.currentTimeMillis() - t < 10 * 60_000) return JSONArray(body)
                }
            }
            val res = searchRaw(query, count)
            if (res.length() > 0) synchronized(searchCache) {
                if (searchCache.size > 40) searchCache.clear()
                searchCache[sk] = System.currentTimeMillis() to res.toString()
            }
            res
        }
    }

    private fun runsText(o: JSONObject?): String {
        val r = o?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until r.length()) sb.append(r.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    /** Direct call to YouTube's search: about half a second and it does not need the Python engine at all. */
    private fun innerSearch(query: String, count: Int): JSONArray {
        val con = URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 4000
            con.readTimeout = 6000
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("X-YouTube-Client-Name", "1")
            con.setRequestProperty("X-YouTube-Client-Version", "2.20260101.00.00")
            val client = JSONObject().put("clientName", "WEB").put("clientVersion", "2.20260101.00.00")
                .put("hl", "en").put("gl", "US")
            val body = JSONObject().put("context", JSONObject().put("client", client))
                .put("query", query).put("params", "EgIQAQ==") // videos only
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) return JSONArray()
            val text = con.inputStream.bufferedReader().use { it.readText() }
            val sections = JSONObject(text).optJSONObject("contents")
                ?.optJSONObject("twoColumnSearchResultsRenderer")?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return JSONArray()
            val arr = JSONArray()
            for (i in 0 until sections.length()) {
                val items = sections.getJSONObject(i).optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
                for (k in 0 until items.length()) {
                    val v = items.getJSONObject(k).optJSONObject("videoRenderer") ?: continue
                    val id = v.optString("videoId")
                    if (id.length !in 6..20) continue
                    val dur = v.optJSONObject("lengthText")?.optString("simpleText") ?: "" // live streams have none
                    val title = runsText(v.optJSONObject("title"))
                    if (dur.isEmpty() || title.isEmpty()) continue
                    arr.put(
                        JSONObject().put("id", id).put("title", title)
                            .put("channel", runsText(v.optJSONObject("ownerText"))).put("duration", dur)
                    )
                    if (arr.length() >= count) return arr
                }
            }
            return arr
        } finally {
            con.disconnect()
        }
    }

    private fun searchRaw(query: String, count: Int): JSONArray {
        try {
            val quick = innerSearch(query, count)
            if (quick.length() > 0) return quick
        } catch (_: Throwable) { /* fall back to yt-dlp below */ }
        awaitReady()
        val r = YoutubeDLRequest("ytsearch$count:$query")
        r.addOption("--flat-playlist")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "10")
        r.addOption("--print", "%(id)s\t%(title)s\t%(channel)s\t%(duration_string)s")
        val out = YoutubeDL.getInstance().execute(r).out
        val arr = JSONArray()
        for (line in out.lines()) {
            val p = line.split('\t')
            if (p.size >= 4 && p[0].length in 6..20) {
                arr.put(
                    JSONObject().put("id", p[0]).put("title", p[1])
                        .put("channel", p[2]).put("duration", p[3])
                )
            }
        }
        return arr
    }

    // ------------------------------------------------------------ playlists

    private val plCache = HashMap<String, Pair<Long, String>>()

    private fun leaves(o: Any?, out: MutableList<String>) {
        when (o) {
            is String -> out.add(o)
            is JSONObject -> for (k in o.keys()) leaves(o.opt(k), out)
            is JSONArray -> for (i in 0 until o.length()) leaves(o.opt(i), out)
        }
    }

    private fun plItem(id: String, title: String, channel: String, o: Any?): JSONObject? {
        if (id.length < 10 || id.startsWith("RD") || title.isBlank()) return null // RD = endless auto-mixes
        val strs = ArrayList<String>().also { leaves(o, it) }
        val count = strs.firstNotNullOfOrNull { Regex("""^(\d[\d,]*)\s+(videos?|songs?|tracks?)$""").find(it.trim())?.groupValues?.get(1) } ?: ""
        val thumb = strs.firstOrNull { it.startsWith("https://i.ytimg.com/") } ?: ""
        return JSONObject().put("id", id).put("title", title).put("channel", channel)
            .put("count", count.replace(",", "")).put("thumb", thumb)
    }

    private fun collectPlaylists(o: Any?, out: JSONArray, seen: HashSet<String>, count: Int) {
        if (out.length() >= count) return
        when (o) {
            is JSONObject -> {
                o.optJSONObject("playlistRenderer")?.let { p ->
                    val id = p.optString("playlistId")
                    val t = p.optJSONObject("title")
                    val title = t?.optString("simpleText").takeUnless { it.isNullOrEmpty() } ?: runsText(t)
                    val by = runsText(p.optJSONObject("shortBylineText"))
                    plItem(id, title, by, p)?.let { if (seen.add(id)) out.put(it) }
                }
                o.optJSONObject("lockupViewModel")?.let { p ->
                    if (p.optString("contentType").contains("PLAYLIST")) {
                        val id = p.optString("contentId")
                        val md = p.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                        val title = md?.optJSONObject("title")?.optString("content") ?: ""
                        val by = md?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                            ?.optJSONArray("metadataRows")?.optJSONObject(0)?.optJSONArray("metadataParts")
                            ?.optJSONObject(0)?.optJSONObject("text")?.optString("content") ?: ""
                        plItem(id, title, by, p)?.let { if (seen.add(id)) out.put(it) }
                    }
                }
                for (k in o.keys()) {
                    if (k != "playlistRenderer" && k != "lockupViewModel") collectPlaylists(o.opt(k), out, seen, count)
                }
            }
            is JSONArray -> for (i in 0 until o.length()) collectPlaylists(o.opt(i), out, seen, count)
        }
    }

    /** YouTube search filtered to playlists (same direct call as the song search). */
    private fun innerPlaylists(query: String, count: Int): JSONArray {
        val con = URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 4000
            con.readTimeout = 7000
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("X-YouTube-Client-Name", "1")
            con.setRequestProperty("X-YouTube-Client-Version", "2.20260101.00.00")
            val client = JSONObject().put("clientName", "WEB").put("clientVersion", "2.20260101.00.00")
                .put("hl", "en").put("gl", "US")
            val body = JSONObject().put("context", JSONObject().put("client", client))
                .put("query", query).put("params", "EgIQAw==") // playlists only
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) return JSONArray()
            val root = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val arr = JSONArray()
            collectPlaylists(root.optJSONObject("contents"), arr, HashSet(), count)
            return arr
        } finally {
            con.disconnect()
        }
    }

    private fun plSearch(query: String, count: Int): JSONArray {
        val sk = "s|$count|${query.lowercase()}"
        synchronized(plCache) {
            plCache[sk]?.let { (t, body) -> if (System.currentTimeMillis() - t < 10 * 60_000) return JSONArray(body) }
        }
        var res = try { innerPlaylists(query, count) } catch (_: Throwable) { JSONArray() }
        if (res.length() == 0) { // fall back to yt-dlp reading the same search page
            awaitReady()
            val r = YoutubeDLRequest("https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(query, "UTF-8") + "&sp=EgIQAw%3D%3D")
            r.addOption("--flat-playlist")
            r.addOption("--no-warnings")
            r.addOption("--socket-timeout", "10")
            r.addOption("--playlist-end", "$count")
            r.addOption("--print", "%(id)s\t%(title)s\t%(uploader)s\t%(playlist_count)s")
            res = JSONArray()
            for (line in YoutubeDL.getInstance().execute(r).out.lines()) {
                val p = line.split('\t')
                if (p.size >= 4 && p[0].length >= 10 && !p[0].startsWith("RD") && p[1].isNotBlank()) {
                    res.put(JSONObject().put("id", p[0]).put("title", p[1])
                        .put("channel", if (p[2] == "NA") "" else p[2])
                        .put("count", if (p[3].all { it.isDigit() }) p[3] else "").put("thumb", ""))
                }
            }
        }
        if (res.length() > 0) synchronized(plCache) {
            if (plCache.size > 60) plCache.clear()
            plCache[sk] = System.currentTimeMillis() to res.toString()
        }
        return res
    }

    /** Playlists shown when nothing is typed: related to the artists you saved or cached most, else popular ones. */
    private fun plHome(): JSONArray {
        val counts = HashMap<String, Int>()
        for (m in off.everything()) {
            val ch = m.channel.removeSuffix(" - Topic").trim()
            if (ch.length > 1) counts[ch] = (counts[ch] ?: 0) + 1
        }
        val seeds = counts.entries.sortedByDescending { it.value }.take(2).map { "${it.key} playlist" }
        val queries = seeds + listOf("best music playlists", "chill playlist", "anime music playlist")
        val hk = "h|" + queries.joinToString("|")
        synchronized(plCache) {
            plCache[hk]?.let { (t, body) -> if (System.currentTimeMillis() - t < 15 * 60_000) return JSONArray(body) }
        }
        val out = JSONArray()
        val seen = HashSet<String>()
        var firstErr: Throwable? = null
        for (q in queries) {
            if (out.length() >= 16) break
            try {
                val r = plSearch(q, 8)
                for (i in 0 until r.length()) {
                    val o = r.getJSONObject(i)
                    if (seen.add(o.optString("id"))) out.put(o)
                }
            } catch (e: Throwable) {
                if (firstErr == null) firstErr = e
            }
        }
        if (out.length() == 0) throw firstErr ?: ApiError(502, "no playlists found")
        synchronized(plCache) { plCache[hk] = System.currentTimeMillis() to out.toString() }
        return out
    }

    /** The tracks of one playlist (first 200), read with yt-dlp. */
    private fun plItems(id: String): JSONObject {
        if (id.length !in 10..64 || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
            throw ApiError(400, "bad playlist id")
        }
        val ck = "i|$id"
        synchronized(plCache) {
            plCache[ck]?.let { (t, body) -> if (System.currentTimeMillis() - t < 10 * 60_000) return JSONObject(body) }
        }
        awaitReady()
        val r = YoutubeDLRequest("https://www.youtube.com/playlist?list=$id")
        r.addOption("--flat-playlist")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "10")
        r.addOption("--playlist-end", "200")
        r.addOption("--print", "%(playlist_title)s\t%(id)s\t%(title)s\t%(channel,uploader)s\t%(duration_string)s")
        val out = YoutubeDL.getInstance().execute(r).out
        val arr = JSONArray()
        var name = ""
        for (line in out.lines()) {
            val p = line.split('\t')
            if (p.size < 5) continue
            if (name.isEmpty() && p[0] != "NA") name = p[0]
            val t = p[2]
            if (p[1].length !in 6..20 || t.isBlank() || t == "[Private video]" || t == "[Deleted video]") continue
            arr.put(JSONObject().put("id", p[1]).put("title", t)
                .put("channel", if (p[3] == "NA") "" else p[3]).put("duration", p[4]))
        }
        if (arr.length() == 0) throw ApiError(502, "could not read that playlist")
        val res = JSONObject().put("id", id).put("title", name).put("items", arr)
        synchronized(plCache) {
            if (plCache.size > 60) plCache.clear()
            plCache[ck] = System.currentTimeMillis() to res.toString()
        }
        return res
    }

    /** Pull-to-refresh: forget remembered search results and the Home feed. */
    private fun dropFeedCache() {
        synchronized(searchCache) { searchCache.clear() }
        synchronized(this) { feed = null }
    }

    /** Called once at app start: the Home feed is fetched before the page asks for it. */
    fun warm() {
        // Load python + yt-dlp now (a no-network --version run) so the first tap does not pay the cold start
        try {
            if (Engine.ready.await(60, java.util.concurrent.TimeUnit.SECONDS) && Engine.error == null) {
                val r = YoutubeDLRequest(emptyList<String>())
                r.addOption("--version")
                Engine.procLock.readLock().lock()
                try { YoutubeDL.getInstance().execute(r, "warm-" + System.nanoTime()) } finally { Engine.procLock.readLock().unlock() }
            }
        } catch (_: Throwable) { }
        try { home() } catch (_: Throwable) { }
    }

    private fun home(): String {
        synchronized(this) {
            feed?.let { (t, body) -> if (System.currentTimeMillis() - t < 15 * 60_000) return body }
        }
        val body = search("trending music", 20).toString()
        synchronized(this) { feed = System.currentTimeMillis() to body }
        return body
    }

    // YouTube hands out media links that answer "403 Forbidden" for some player clients. We try the
    // clients one after another; if all fail we update yt-dlp once and go through them again.
    // the default client's links are cut off (403 after the first MB) without a PO token, the android client's links play through
    private val clients = listOf<String?>("android", null, "tv", "mweb", "web_safari", "ios")

    private fun fatal(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return "private video" in m || "video unavailable" in m || "removed" in m || "copyright" in m ||
            "members-only" in m || "age-restricted" in m && "sign in" in m
    }

    // the client that worked last time goes first, so only the very first play pays for trial and error
    // remembered across app restarts too, so even the first play after launch starts with the client that worked
    private val prefs = ctx.getSharedPreferences("links", Context.MODE_PRIVATE)
    @Volatile private var goodClient: String? = prefs.getString("goodClient", null)?.takeIf { it in clients.filterNotNull() }
    @Volatile private var haveGood = prefs.getBoolean("haveGood", false) &&
        (prefs.getString("goodClient", null).let { it == null || it in clients.filterNotNull() })
    private fun forgetClient() {
        haveGood = false
        prefs.edit().putBoolean("haveGood", false).apply() // a stale saved client must not lead the order after a restart
    }
    private fun rememberClient(c: String?) {
        goodClient = c; haveGood = true
        prefs.edit().putString("goodClient", c).putBoolean("haveGood", true).apply()
    }

    private fun <T> tryClients(body: (String?) -> T): T {
        var last: Throwable? = null
        for (round in 0..1) {
            val order = if (haveGood) listOf(goodClient) + clients.filter { it != goodClient } else clients
            for (c in order) {
                try {
                    val v = body(c)
                    rememberClient(c)
                    return v
                } catch (e: Throwable) {
                    last = e
                    if (fatal(e)) throw e
                }
            }
            forgetClient()
            if (round == 0 && !Engine.updateNow(ctx)) break
        }
        throw last ?: ApiError(502, "failed")
    }

    private val POT = "@pot"

    private fun client(r: YoutubeDLRequest, c: String?) {
        // translated subtitles are never needed; skipping them trims extraction work
        r.addOption("--extractor-args", when {
            c == POT -> "youtube:formats=missing_pot;skip=translated_subs"   // last resort: also list formats yt-dlp hides when it has no PO token
            c != null -> "youtube:player_client=$c;skip=translated_subs"
            else -> "youtube:skip=translated_subs"
        })
    }

    private fun rangeOk(url: String, ua: String, a: Long, b: Long, onTotal: (Long) -> Unit = {}): Boolean {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "GET"
            con.setRequestProperty("Range", "bytes=$a-$b")
            con.setRequestProperty("User-Agent", ua)
            con.connectTimeout = 5000
            con.readTimeout = 5000
            val code = con.responseCode
            con.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let(onTotal)
            if (code !in 200..299) DebugLog.add("link", "probe bytes=$a-$b -> HTTP $code (${con.url.host})")
            return code in 200..299
        } finally {
            con.disconnect()
        }
    }

    /** Same probe, but asking the way yt-dlp does: range=a-b inside the link and no Range header. */
    private fun paramOk(url: String, ua: String, a: Long, b: Long): Boolean = try {
        val con = URL(url + (if (url.contains('?')) "&" else "?") + "range=$a-$b").openConnection() as HttpURLConnection
        try {
            con.setRequestProperty("User-Agent", ua)
            con.connectTimeout = 5000
            con.readTimeout = 5000
            val ok = con.responseCode in 200..299
            if (ok) DebugLog.add("link", "range header refused but range= parameter works")
            ok
        } finally {
            con.disconnect()
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Asks the server for 2 bytes at the start AND 2 bytes a few MB in, the way the player will. Some links (those that
     * need a PO token) answer the start but refuse everything after the first few MB with 403, so the start alone is not enough.
     */
    private fun playable(url: String, deep: Boolean = true): Boolean = try {
        val ua = uaFor(url)
        var total = -1L
        if (!(rangeOk(url, ua, 0, 1) { total = it } || paramOk(url, ua, 0, 1))) false
        else if (!deep) {
            // yt-dlp links: ONE small check of the exact piece the player asks second (1 MiB on, aligned like the chunked reader).
            // Links without a PO token answer the first MB and then 403, which used to stop the song a few seconds in.
            val m1 = 1_048_576L
            total <= m1 + 1024 || rangeOk(url, ua, m1, m1 + 1023) || paramOk(url, ua, m1, m1 + 1023)
        }
        else {
            // links that need a PO token answer the first ~1 MB and then 403, even for small songs, so test just past 1 MiB too
            // the player asks for 1 MB pieces (1048576-2097151), so test a range that ENDS at 2 MiB, not just starts at 1 MiB
            val m1 = 2_097_150L
            var ok = total <= m1 + 2 || rangeOk(url, ua, m1, m1 + 1) || paramOk(url, ua, m1, m1 + 1)
            if (ok && total > 8_000_000L) ok = rangeOk(url, ua, 6_000_000L, 6_000_001L) || paramOk(url, ua, 6_000_000L, 6_000_001L)
            ok
        }
    } catch (_: Throwable) {
        false
    }

    // YouTube now refuses the fast direct links (LOGIN_REQUIRED / 403 after the first MB), so go straight to yt-dlp
    private val useDirect = false
    @Volatile private var directFails = 0
    @Volatile private var directOffUntil = 0L
    @Volatile private var lastDirectErr = ""
    @Volatile private var lastVia = ""
    private val warming = HashSet<String>()
    private val warmExec = Executors.newFixedThreadPool(2)
    private val directExec = Executors.newCachedThreadPool()

    private class VClient(val name: String, val version: String, val ua: String, val id: String, val extra: (JSONObject) -> Unit)

    private val vclients = listOf(
        VClient(
            "ANDROID_VR", "1.60.19",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", "28"
        ) { it.put("deviceMake", "Oculus").put("deviceModel", "Quest 3").put("osName", "Android").put("osVersion", "12L").put("androidSdkVersion", 32) },
        VClient(
            "ANDROID_VR", "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", "28"
        ) { it.put("deviceMake", "Oculus").put("deviceModel", "Quest 3").put("osName", "Android").put("osVersion", "12L").put("androidSdkVersion", 32) },
        VClient(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", "5"
        ) { it.put("deviceMake", "Apple").put("deviceModel", "iPhone16,2").put("osName", "iPhone").put("osVersion", "18.3.2.22D82") }
    )

    /** The link's own expiry (YouTube puts expire=<unix seconds> in it), minus a safety margin; 45 min when absent. */
    private fun expiryOf(url: String, savedAt: Long): Long {
        val e = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (e != null) e * 1000L - 10 * 60_000L else savedAt + 45 * 60_000L
    }

    init {
        // links saved by an earlier run are still good until they expire: replaying a song is then instant
        try {
            val saved = prefs.getString("cache", null)
            if (saved != null) {
                val arr = org.json.JSONArray(saved)
                val now = System.currentTimeMillis()
                synchronized(streamCache) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val t = o.getLong("t"); val u = o.getString("u")
                        if (now < expiryOf(u, t)) streamCache[o.getString("k")] = t to u
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    private fun persistCache() {
        try {
            val arr = org.json.JSONArray()
            synchronized(streamCache) {
                for ((k, v) in streamCache) arr.put(JSONObject().put("k", k).put("t", v.first).put("u", v.second))
            }
            prefs.edit().putString("cache", arr.toString()).apply()
        } catch (_: Throwable) { }
    }

    private fun cacheGet(key: String): String? = synchronized(streamCache) {
        streamCache[key]?.let { (t, u) -> if (System.currentTimeMillis() < expiryOf(u, t) && u !in badUrls) u else null }
    }

    // which track/client a link came from, and which clients' links were cut off (403 after the first MB) for a track
    private val urlInfo = ConcurrentHashMap<String, Pair<String, String>>()
    private val clientBlock = ConcurrentHashMap<String, MutableSet<String>>()
    private val badUrls: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private fun cname(c: String?) = c ?: "default"

    /** The player got 403 for a piece after the first one: forget this link and make the next lookup use a different client. */
    private fun onBadLink(url: String) {
        badUrls.add(url)
        if (badUrls.size > 200) badUrls.clear()
        val (id, cn) = urlInfo[url] ?: return
        if (clientBlock.size > 100) clientBlock.clear()
        clientBlock.getOrPut(id) { ConcurrentHashMap.newKeySet<String>() }.add(cn)
        if (cname(goodClient) == cn) forgetClient()
        synchronized(streamCache) { streamCache.keys.removeAll { it.startsWith("$id|") } }
        persistCache()
        DebugLog.add("link", "$id: the $cn client's link was cut off (403 after the first MB); other clients go first for this track now")
    }

    init {
        Chunks.onForbidden = { u, _ -> onBadLink(u) }
        Chunks.refresher = { u -> refreshLink(u) }
    }

    /** A link was cut off mid-song: find a new one for the same track (same kind of file) so playback can carry on. */
    private fun refreshLink(old: String): Pair<String, String>? {
        val id = urlInfo[old]?.first ?: return null
        val mime = try { android.net.Uri.parse(old).getQueryParameter("mime") ?: "" } catch (_: Throwable) { "" }
        val kind = if (mime.startsWith("audio/")) "audio" else "video"
        DebugLog.add("link", "$id: looking for a fresh $kind link to carry on from where the old one was cut off")
        return try {
            val fresh = ytLinkRun(id, kind, cfg.quality)[kind] ?: return null
            cachePut(keyOf(id, kind, cfg.quality), fresh)
            fresh to uaFor(fresh)
        } catch (e: Throwable) {
            DebugLog.add("link", "$id: no fresh link found", e)
            null
        }
    }

    /**
     * YouTube's ready-made files with picture AND sound (itag 18 = 360p mp4, 22 = 720p mp4, ...). A link of one of these is
     * recognised from its own address, so no second yt-dlp run and no probe is needed to know it carries both.
     */
    private val muxedItags = setOf("17", "18", "22", "36", "37", "43", "59", "78", "82", "83", "84", "85")

    private fun isMuxedUrl(u: String): Boolean = try {
        val p = android.net.Uri.parse(u)
        val itag = p.getQueryParameter("itag")
        itag != null && itag in muxedItags && (p.getQueryParameter("mime") ?: "video/").startsWith("video/")
    } catch (_: Throwable) {
        false
    }

    private fun cachePut(key: String, url: String) {
        var alsoVideo = false
        synchronized(streamCache) {
            if (streamCache.size > 50) {
                val now = System.currentTimeMillis()
                streamCache.entries.removeAll { now >= expiryOf(it.value.second, it.value.first) }
                if (streamCache.size > 50) streamCache.entries.sortedBy { it.value.first }.take(10).forEach { streamCache.remove(it.key) }
            }
            val now = System.currentTimeMillis()
            streamCache[key] = now to url
            // an "audio" lookup that came back as a picture+sound file also fills the video slot: switching to the video
            // (or the next track in video mode) then finds both links in the cache, without another lookup or probe
            if (key.endsWith("|a") && isMuxedUrl(url)) {
                val vk = key.removeSuffix("|a") + "|v|" + cfg.quality
                if (streamCache[vk] == null) { streamCache[vk] = now to url; alsoVideo = true }
            }
        }
        if (alsoVideo) DebugLog.add("link", "${key.removeSuffix("|a")}: audio link is a picture+sound file, saved for video too")
        persistCache()
    }

    private fun keyOf(id: String, kind: String, q: Int, silent: Boolean = false) =
        if (kind == "video") (if (silent) "$id|vo|$q" else "$id|v|$q") else "$id|a"

    private fun pickFormat(sd: JSONObject, kind: String, q: Int, allowSilent: Boolean): Pair<String, Boolean>? {
        if (kind == "video") {
            val fm = sd.optJSONArray("formats")
            var best: String? = null
            var bh = -1
            var any: String? = null
            if (fm != null) for (i in 0 until fm.length()) {
                val f = fm.getJSONObject(i)
                val u = f.optString("url")
                if (u.isEmpty() || !f.optString("mimeType").startsWith("video/mp4")) continue
                if (any == null) any = u
                val h = f.optInt("height", 0)
                if (h in 1..q && h > bh) { bh = h; best = u }
            }
            if (best == null) best = any
            if (best != null) return best to false
            if (!allowSilent) return null
            val ad = sd.optJSONArray("adaptiveFormats") ?: return null
            val pq = minOf(q, 480)
            var sbest: String? = null
            var sh = -1
            var smallest: String? = null
            var smallH = Int.MAX_VALUE
            for (i in 0 until ad.length()) {
                val f = ad.getJSONObject(i)
                val u = f.optString("url")
                val mt = f.optString("mimeType")
                if (u.isEmpty() || !mt.startsWith("video/mp4") || !mt.contains("avc1")) continue
                val h = f.optInt("height", 0)
                if (h in 1..pq && h > sh) { sh = h; sbest = u }
                if (h in 1 until smallH) { smallH = h; smallest = u }
            }
            val pick = sbest ?: smallest ?: return null
            return pick to true
        }
        val fm = sd.optJSONArray("adaptiveFormats") ?: return null
        var best: String? = null
        var bb = -1
        var bestWebm: String? = null
        var bw = -1
        for (i in 0 until fm.length()) {
            val f = fm.getJSONObject(i)
            val u = f.optString("url")
            val mt = f.optString("mimeType")
            if (u.isEmpty() || !mt.startsWith("audio/")) continue
            val br = f.optInt("bitrate", 0)
            if (mt.startsWith("audio/mp4")) { if (br > bb) { bb = br; best = u } }
            else if (br > bw) { bw = br; bestWebm = u }
        }
        val pick = best ?: bestWebm ?: return null
        return pick to false
    }

    /** What the clients answered for one video. Audio and video requests (and prewarm) share one lookup. */
    private class Pf {
        val got = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        val pending = AtomicInteger(0)
    }

    private val pfs = HashMap<String, Pair<Long, Pf>>()

    private fun playerFetch(id: String): Pf {
        return synchronized(pfs) {
            val now = System.currentTimeMillis()
            val e = pfs[id]
            if (e != null && now - e.first < 5 * 60_000 && (e.second.got.isNotEmpty() || e.second.pending.get() > 0)) return@synchronized e.second
            if (pfs.size > 40) pfs.clear()
            val pf = Pf()
            pf.pending.set(vclients.size)
            pfs[id] = now to pf
            for (c in vclients) {
                directExec.execute {
                    try {
                        pf.got.add(fetchSd(c, id))
                    } catch (t: Throwable) {
                        pf.errs.add(t.message ?: t.javaClass.simpleName)
                    } finally {
                        pf.pending.decrementAndGet()
                    }
                }
            }
            pf
        }
    }

    private fun forget(id: String) {
        synchronized(pfs) { pfs.remove(id) }
        synchronized(streamCache) { streamCache.keys.removeAll { it.startsWith("$id|") } }
    }

    private fun fetchSd(c: VClient, id: String): JSONObject {
        val con = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 3000
            con.readTimeout = 6000
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", c.ua)
            con.setRequestProperty("X-YouTube-Client-Name", c.id)
            con.setRequestProperty("X-YouTube-Client-Version", c.version)
            val client = JSONObject().put("clientName", c.name).put("clientVersion", c.version).put("hl", "en").put("gl", "US")
            c.extra(client)
            val body = JSONObject().put("context", JSONObject().put("client", client))
                .put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true)
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) throw ApiError(502, "${c.name} ${c.version}: HTTP ${con.responseCode}")
            val root = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val st = root.optJSONObject("playabilityStatus")?.optString("status") ?: "?"
            if (st != "OK") throw ApiError(502, "${c.name} ${c.version}: $st")
            val sd = root.optJSONObject("streamingData") ?: throw ApiError(502, "${c.name} ${c.version}: no streaming data")
            return sd.put("_ua", c.ua)
        } finally {
            con.disconnect()
        }
    }

    /**
     * Asks YouTube's player endpoint (all app clients at once, one lookup per video) and takes the first answer
     * that has a usable, reachable link for what was asked. Returns (link, pictureOnly). pictureOnly is true for a
     * video-only stream: the sound then comes from the audio link. Null when every client fails.
     */
    private fun directInfo(id: String, kind: String, q: Int, verify: Boolean, allowSilent: Boolean): Pair<String, Boolean>? {
        val pf = playerFetch(id)
        val deadline = System.currentTimeMillis() + 7000
        var tried = 0
        while (true) {
            val n = pf.got.size
            while (tried < n) {
                val sd = pf.got[tried]
                tried++
                val pick = pickFormat(sd, kind, q, allowSilent)
                if (pick != null) {
                    val ua = sd.optString("_ua")
                    if (ua.isNotEmpty()) {
                        if (uaMap.size > 200) uaMap.clear()
                        uaMap[pick.first] = ua
                    }
                }
                if (pick != null && (!verify || playable(pick.first))) return pick
            }
            if (pf.pending.get() == 0 && tried >= pf.got.size) break
            if (System.currentTimeMillis() > deadline) break
            try { Thread.sleep(25) } catch (_: InterruptedException) { break }
        }
        DebugLog.add("link", "direct lookup failed for $id ($kind): " + (if (pf.errs.isNotEmpty()) pf.errs.joinToString("; ") else if (pf.got.isEmpty()) "timed out" else "no usable format or link refused (403?)"))
        lastDirectErr = if (pf.errs.isNotEmpty()) pf.errs.joinToString("; ") else if (pf.got.isEmpty()) "timed out" else "no usable format or link refused"
        synchronized(pfs) { if (pfs[id]?.second === pf) pfs.remove(id) } // next try asks again
        return null
    }

    private fun directStreamUrl(id: String, kind: String, q: Int, verify: Boolean = true): String? =
        directInfo(id, kind, q, verify, false)?.first

    private val warmSlot = java.util.concurrent.Semaphore(1)

    /**
     * Looks the link up ahead of a tap so playing starts at once. The direct fast path first; when YouTube refuses it
     * (the usual case now) yt-dlp resolves it quietly in the background, one song at a time, and the link is kept ~45 min.
     */
    private fun prewarm(id: String, kind: String) {
        val q = cfg.quality
        val ck = keyOf(id, kind, q)
        val haveVideo = cacheGet(keyOf(id, "video", q)) != null          // picture + sound in one file
        val haveSilent = cacheGet(keyOf(id, "video", q, true)) != null   // picture only: needs the audio link next to it
        val haveAudio = cacheGet(keyOf(id, "audio", q)) != null
        if (kind == "video") { if (haveVideo || (haveSilent && haveAudio)) return }
        else if (haveAudio || haveVideo) return // the video link already carries the sound
        synchronized(warming) { if (warming.size >= 8 || !warming.add(ck)) return }
        warmExec.execute {
            try {
                var got = false
                if (useDirect && System.currentTimeMillis() >= directOffUntil) {
                    directInfo(id, kind, q, true, kind == "video")?.let { d ->
                        cachePut(keyOf(id, kind, q, d.second), d.first); got = true
                        // a picture-only link needs its sound: warm the audio link in the same pass, so both are cached
                        if (kind == "video" && d.second && cacheGet(keyOf(id, "audio", q)) == null) {
                            directInfo(id, "audio", q, true, false)?.let { a -> cachePut(keyOf(id, "audio", q), a.first) }
                        }
                    }
                }
                if (!got && Engine.ready.count == 0L && Engine.error == null) {
                    // wait (briefly) for any tap in progress to finish before starting a background lookup
                    var waited = 0
                    while (fgActive.get() > 0 && waited < 30_000) { Thread.sleep(250); waited += 250 }
                    if (fgActive.get() > 0) return@execute
                    warmSlot.acquire()
                    try {
                        // one yt-dlp run asks for the audio link AND the ready-made mp4, and linkOnce caches the other one too
                        if (fgActive.get() == 0 && cacheGet(ck) == null) cachePut(ck, ytLink(id, kind, q, true))
                    } finally {
                        warmSlot.release()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                synchronized(warming) { warming.remove(ck) }
            }
        }
    }

    private val linkExec = Executors.newCachedThreadPool()
    private val linkSlots = java.util.concurrent.Semaphore(3) // a background prewarm uses 1 (one client at a time), so a tap's two-client wave always fits
    /** Number of user taps currently waiting on a lookup; background prewarm stands aside while this is above 0. */
    private val fgActive = AtomicInteger(0)
    private fun ytLinkFg(id: String, kind: String, q: Int): String {
        fgActive.incrementAndGet()
        try { return ytLink(id, kind, q, false) } finally { fgActive.decrementAndGet() }
    }
    private val linkFlight = ConcurrentHashMap<String, java.util.concurrent.FutureTask<Map<String, String>>>()

    private fun stopProcs(pids: List<String>) {
        for (p in pids) try { YoutubeDL.getInstance().destroyProcessById(p) } catch (_: Throwable) { }
    }

    /**
     * One yt-dlp run asks for BOTH the audio-only link and a ready-made mp4 (picture + sound), so a single lookup
     * serves audio mode and video mode. Returns kind -> link; [want] is always in it.
     */
    private fun linkOnce(id: String, want: String, q: Int, c: String?, pid: String): Map<String, String> {
        val r = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
        r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/ba*," +
            "b[ext=mp4][vcodec^=avc1][height<=$q]/b[ext=mp4][height<=$q]/b[ext=mp4]/b/w")
        r.addOption("--print", "%(vcodec)s\t%(acodec)s\t%(url)s")
        r.addOption("--socket-timeout", "8")
        r.addOption("--no-check-formats")
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        client(r, c)
        linkSlots.acquire()
        val out = try {
            Engine.procLock.readLock().lock()
            try { YoutubeDL.getInstance().execute(r, pid).out } finally { Engine.procLock.readLock().unlock() }
        } finally { linkSlots.release() }
        var audio: String? = null
        var video: String? = null
        for (line in out.lines()) {
            val p = line.split('\t')
            if (p.size < 3 || !p[2].startsWith("http")) continue
            if (p[0] == "none") { if (audio == null) audio = p[2] }
            else if (p[1] != "none" && video == null) video = p[2] // picture AND sound in one file
        }
        var got = if (want == "video") video else audio
        // audio asked but only a picture+sound file came back (itag 18 / mp4 with video): it plays as sound too, no second run needed
        if (got == null && want == "audio" && video != null) {
            got = video
            DebugLog.add("link", "$id: no audio-only link, using the picture+sound file for sound")
        }
        if (audio == null && video == null) {
            // this yt-dlp did not print per-format lines: fall back to the plain one-link request
            val r2 = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
            r2.addOption("-f", if (want == "video") "b[ext=mp4][vcodec^=avc1][height<=$q]/b[ext=mp4][height<=$q]/b[ext=mp4]/b/best/w" else "bestaudio[ext=m4a]/bestaudio/ba*/best/w")
            r2.addOption("-g")
            r2.addOption("--socket-timeout", "8")
            r2.addOption("--no-check-formats")
            r2.addOption("--no-playlist")
            r2.addOption("--no-warnings")
            client(r2, c)
            linkSlots.acquire()
            val out2 = try {
                Engine.procLock.readLock().lock()
                try { YoutubeDL.getInstance().execute(r2, pid + "b").out } finally { Engine.procLock.readLock().unlock() }
            } finally { linkSlots.release() }
            got = out2.lines().firstOrNull { it.startsWith("http") }
        }
        val u = got ?: throw ApiError(502, "no stream URL returned")
        if (!playable(u, deep = false)) {
            clientBlock.getOrPut(id) { ConcurrentHashMap.newKeySet<String>() }.add(cname(c))
            throw ApiError(502, "HTTP Error 403: Forbidden")
        }
        if (urlInfo.size > 200) urlInfo.clear()
        urlInfo[u] = id to cname(c)
        val res = HashMap<String, String>()
        res[want] = u
        // the audio answer is itself a picture+sound file: the video slot gets the same link right now (no lookup, no probe;
        // it was just checked by playable() above)
        if (want == "audio" && (u == video || isMuxedUrl(u))) {
            res["video"] = u
            cachePut(keyOf(id, "video", q), u)
        }
        // the other kind is checked and remembered in the background, so the next tap (audio or video) is instant
        val other = (if (want == "video") audio else video)?.takeIf { it != u }
        val otherKind = if (want == "video") "audio" else "video"
        if (other != null) { res["maybe_$otherKind"] = other; urlInfo[other] = id to cname(c) } // callers sharing this run can use it
        if (other != null) linkExec.execute {
            try { if (playable(other, deep = false)) { urlInfo[other] = id to cname(c); cachePut(keyOf(id, otherKind, q), other) } } catch (_: Throwable) { }
        }
        return res
    }

    /** Same lookup asked twice at once (prewarm + tap, audio + video) shares one yt-dlp run; one quiet retry before giving up. */
    private fun ytLink(id: String, kind: String, q: Int, bg: Boolean = false): String {
        val key = "$id|$q"
        var task = linkFlight[key]
        var mine = false
        if (task == null) {
            val t = java.util.concurrent.FutureTask<Map<String, String>> {
                try { ytLinkRun(id, kind, q, bg) }
                catch (e: Throwable) {
                    if (fatal(e) || (e is ApiError && e.code == 503)) throw e
                    DebugLog.add("link", "yt-dlp lookup failed for $id ($kind), retrying once: " + lastLine(e, "?").take(160))
                    ytLinkRun(id, kind, q, bg)
                }
            }
            val prev = linkFlight.putIfAbsent(key, t)
            if (prev == null) { task = t; mine = true } else task = prev
        }
        val map = try {
            if (mine) task!!.run()
            try { task!!.get() } catch (e: java.util.concurrent.ExecutionException) {
                val c = e.cause ?: e
                // a tap joined a background lookup that stood aside for it: do the lookup itself, never fail with "busy"
                if (!mine && !bg && c is ApiError && c.code == 503) ytLinkRun(id, kind, q, false) else throw c
            }
        } finally {
            if (mine) linkFlight.remove(key)
        }
        map[kind]?.let { return it }
        // the shared run was for the other kind and it may have cached this one meanwhile
        cacheGet(keyOf(id, kind, q))?.let { return it }
        // Joined a run that was for the other kind (the page asks for picture and sound at the same moment). This used to start a
        // SECOND yt-dlp run 9-25 s long. The same run already produced what is needed:
        if (kind == "audio") map["video"]?.let { DebugLog.add("link", "$id: audio reuses the picture+sound link of the same lookup"); return it }
        map["maybe_$kind"]?.let { m -> if (playable(m, deep = false)) { cachePut(keyOf(id, kind, q), m); return m } }
        return ytLinkRun(id, kind, q, bg)[kind] ?: throw ApiError(502, "no stream URL returned")
    }

    /** The yt-dlp fallback for a media link: two clients are tried at the same time, the first good link wins. */
    private fun ytLinkRun(id: String, kind: String, q: Int, bg: Boolean = false): Map<String, String> {
        var last: Throwable? = null
        for (round in 0..1) {
            val base: List<String?> = if (haveGood) listOf(goodClient) + clients.filter { it != goodClient } else clients
            val blocked = clientBlock[id]
            // clients whose links for THIS track were cut off with 403 go last (stable sort keeps the rest in order)
            val order: List<String?> = if (blocked.isNullOrEmpty()) base else base.sortedBy { if (cname(it) in blocked) 1 else 0 }
            for (wave in order.chunked(if (bg) 1 else 2)) { // background: one client at a time, to leave CPU/RAM for the tap
                if (bg && fgActive.get() > 0) throw ApiError(503, "busy with a tap") // stand aside; the tap does its own lookup
                val svc = java.util.concurrent.ExecutorCompletionService<Pair<String?, Map<String, String>>>(linkExec)
                val pids = ArrayList<String>()
                for (c in wave) {
                    val pid = "lnk-" + System.nanoTime() + "-" + (c ?: "default")
                    pids.add(pid)
                    svc.submit(java.util.concurrent.Callable<Pair<String?, Map<String, String>>> { Pair(c, linkOnce(id, kind, q, c, pid)) })
                }
                var won: Map<String, String>? = null
                for (i in wave.indices) {
                    try {
                        val res = svc.take().get()
                        if (res.first != POT) rememberClient(res.first)
                        won = res.second
                        break
                    } catch (e: java.util.concurrent.ExecutionException) {
                        val cause = e.cause ?: e
                        last = cause
                        if (fatal(cause)) { stopProcs(pids); throw cause }
                    }
                }
                stopProcs(pids)
                if (won != null) return won
            }
            forgetClient()
            if (round == 0 && !Engine.updateNow(ctx)) break
        }
        // every client said "Requested format is not available": yt-dlp hides formats that need a PO token. Try once with them listed.
        if ((last?.message ?: "").contains("Requested format is not available", true)) {
            val pid = "lnk-pot-" + System.nanoTime()
            try {
                DebugLog.add("link", "no client had a usable format for $id, trying once with formats that need a PO token")
                return linkOnce(id, kind, q, POT, pid)
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: ApiError(502, "failed")
    }

    /** One direct media link: (link, pictureOnly). No ads: this is the media file itself. */
    private fun streamInfo(id: String, kind: String, viaYt: Boolean = false): Pair<String, Boolean> {
        val q = cfg.quality
        if (viaYt) {
            // second chance after the direct link failed to play: let yt-dlp pick one ready-made mp4 (picture + sound)
            try {
                awaitReady()
            } catch (e: ApiError) {
                throw ApiError(502, "Could not get the stream. " + (e.message ?: ""))
            }
            val u = ytLinkFg(id, kind, q)
            lastVia = "yt-dlp(" + (goodClient ?: "default") + ") retry"
            return u to false
        }
        cacheGet(keyOf(id, kind, q))?.let { lastVia = "cache"; return it to false }
        if (kind == "video") cacheGet(keyOf(id, kind, q, true))?.let { lastVia = "cache"; return it to true }
        // audio asked while the same track's picture+sound link is already known: reuse it instead of a second 10-30 s yt-dlp run
        if (kind == "audio" && !linkFlight.containsKey("$id|$q")) {
            cacheGet(keyOf(id, "video", q))?.let { lastVia = "cache (video link)"; return it to false }
        }
        if (useDirect && System.currentTimeMillis() > directOffUntil) {
            val d = directInfo(id, kind, q, true, kind == "video")
            if (d != null) {
                directFails = 0
                lastVia = "direct"
                cachePut(keyOf(id, kind, q, d.second), d.first)
                return d
            }
            if (++directFails >= 4) {
                directFails = 0
                directOffUntil = System.currentTimeMillis() + 2 * 60_000
            }
        }
        try {
            awaitReady()
        } catch (e: ApiError) {
            throw ApiError(502, "Could not get the stream (" + lastDirectErr.ifBlank { "no answer" }.take(160) + "). " + (e.message ?: ""))
        }
        val url = ytLinkFg(id, kind, q)
        lastVia = "yt-dlp(" + (goodClient ?: "default") + ")" + (if (useDirect) " after direct failed: " + lastDirectErr.take(160) else "")
        cachePut(keyOf(id, kind, q), url)
        return url to false
    }

    private fun quickUrl(id: String, kind: String, q: Int): String? {
        val ck = keyOf(id, kind, q)
        cacheGet(ck)?.let { return it }
        if (!useDirect || System.currentTimeMillis() < directOffUntil) return null
        val u = directStreamUrl(id, kind, q, true) ?: return null
        cachePut(ck, u)
        return u
    }

    private fun evictUrl(id: String, kind: String, q: Int) {
        synchronized(streamCache) { streamCache.remove(keyOf(id, kind, q)) }
    }

    private fun update(): String {
        awaitReady()
        val s = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
        synchronized(streamCache) { streamCache.clear() }
        clientBlock.clear(); badUrls.clear() // a newer yt-dlp may fix what was blocked
        return if (s.toString().contains("ALREADY")) "Downloader is up to date" else "Downloader updated"
    }

    // --------------------------------------------------- source priority

    private fun localJson(m: Meta): JSONObject {
        off.touch(m)
        val url = "/api/file?d=" + off.letterOf(m) + "&n=" + URLEncoder.encode(m.file, "UTF-8")
        return JSONObject().put("url", url).put("src", m.tier).put("kind", m.kind)
    }

    /**
     * Cache priority, in order:
     *   1. a download of this track        (src "dl")
     *   2. a cached copy                   (src "cache")
     *   3. the network stream              (src "net"), which is also copied into the cache if enabled
     * With "prefer offline" off, step 3 goes first and the saved copy is only the fallback when the
     * network fails (no signal, 403, ...). Audio can be played from a saved video file, never the reverse.
     */
    // the page can ask for the same track several times at once (7 identical requests were seen): one runs, the rest wait for its answer
    private val resolveFlight = ConcurrentHashMap<String, java.util.concurrent.FutureTask<JSONObject>>()

    private fun resolve(id: String, mode: String, title: String, channel: String, dur: String, noCache: Boolean = false, viaYt: Boolean = false): JSONObject {
        val key = "$id:$mode:$viaYt:$noCache"
        val mine = java.util.concurrent.FutureTask<JSONObject> { resolveImpl(id, mode, title, channel, dur, noCache, viaYt) }
        val prev = resolveFlight.putIfAbsent(key, mine)
        if (prev == null) {
            try { mine.run() } finally { resolveFlight.remove(key, mine) }
            try { return mine.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        }
        DebugLog.add("stream", "$id $mode: same request already running, waiting for it")
        // JSONObject is copied so no caller can change what another one reads
        try { return JSONObject(prev.get().toString()) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
    }

    private fun resolveImpl(id: String, mode: String, title: String, channel: String, dur: String, noCache: Boolean = false, viaYt: Boolean = false): JSONObject {
        fun saved(): Meta? = off.local(id, mode) ?: if (mode == "audio") off.local(id, "video") else null

        if (cfg.preferOffline) saved()?.let { return localJson(it) }
        try {
            val info = streamInfo(id, mode, viaYt)
            if (!noCache && !viaYt && !info.second) maybeCache(id, mode, title, channel, dur)
            DebugLog.add("stream", "$id $mode ok via=${lastVia.ifBlank { "cache" }} silent=${info.second} host=" + (try { URL(info.first).host } catch (_: Throwable) { "?" }) + " ua=" + (if (uaMap.containsKey(info.first)) "client" else "default"))
            return JSONObject().put("url", info.first).put("src", "net").put("kind", mode).put("silent", info.second)
                .put("via", lastVia).put("host", try { URL(info.first).host } catch (_: Throwable) { "" })
        } catch (e: Throwable) {
            DebugLog.add("stream", "$id $mode failed", e)
            // no network or YouTube refused: any saved copy beats an error, even video when audio was asked
            (saved() ?: off.local(id, if (mode == "audio") "video" else "audio"))?.let { return localJson(it) }
            throw e
        }
    }

    // --------------------------------------------------- automatic cache

    private fun maybeCache(id: String, kind: String, title: String, channel: String, dur: String) {
        if (kind == "audio" && !cfg.autoCacheAudio) return
        if (kind == "video" && !cfg.autoCacheVideo) return
        if (cfg.wifiOnly && metered()) return
        if (off.get("dl", kind, id) != null || off.get("cache", kind, id) != null) return
        val key = "$kind:$id"
        val cj = CacheJob(id, kind)
        if (caching.putIfAbsent(key, cj) != null) return
        // a short wait so the saving does not fight the stream while it is starting
        cacheExec.schedule(Runnable {
            try {
                runCache(id, kind, title, channel, dur, cj)
            } catch (_: Throwable) {
                // caching is best effort; streaming already works
            } finally {
                caching.remove(key)
            }
        }, 3, TimeUnit.SECONDS)
    }

    /** Speed options: YouTube throttles plain downloads, so ask for big chunks and fetch fragments in parallel. */
    private fun speed(r: YoutubeDLRequest) {
        r.addOption("--socket-timeout", "15")
        r.addOption("--postprocessor-args", "ffmpeg:-threads 2") // keep the phone responsive while converting/merging
        if (cfg.turbo) {
            r.addOption("--http-chunk-size", "10M")
            r.addOption("--concurrent-fragments", "3")
            r.addOption("--throttled-rate", "100K")
        }
    }

    /** Format selector for video files that may be merged from two streams (needs the bundled ffmpeg). */
    private fun videoFormat(q: Int): String =
        if (Engine.ffmpegOk) {
            "bv*[vcodec^=avc1][height<=$q]+ba[ext=m4a]/b[ext=mp4][height<=$q]/bv*[height<=$q]+ba/b[height<=$q]"
        } else {
            "b[ext=mp4][height<=$q]/b[height<=$q]"
        }

    private fun runCache(id: String, kind: String, title: String, channel: String, dur: String, cj: CacheJob) {
        awaitReady()
        val tag = if (kind == "video") "v" else "a"
        val prefix = "${tag}_$id"
        tryClients { c ->
            val r = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
            if (kind == "video") {
                r.addOption("-f", videoFormat(cfg.quality))
                if (Engine.ffmpegOk) r.addOption("--merge-output-format", "mp4")
            } else {
                r.addOption("-f", "bestaudio[ext=m4a]/bestaudio") // keep the original audio, no conversion
            }
            r.addOption("--no-playlist")
            r.addOption("--retries", "3")
            r.addOption("--fragment-retries", "3")
            speed(r)
            r.addOption("-o", off.cache.absolutePath + "/" + prefix + ".%(ext)s")
            client(r, c)
            cj.pct = 0f
            YoutubeDL.getInstance().execute(r) { progress, _, _ ->
                if (progress >= 0f) cj.pct = progress
            }
        }
        val done = Regex("^" + Regex.escape(prefix) + "\\.(m4a|mp4|webm|opus|ogg|mp3|aac|mkv)$")
        val f = off.cache.listFiles()?.firstOrNull { it.isFile && done.matches(it.name) && it.length() > 0 }
        // leftovers of a failed merge or an interrupted run
        off.cache.listFiles()?.forEach { if (it.name.startsWith("$prefix.") && it != f) it.delete() }
        if (f == null) return
        if (off.get("dl", kind, id) != null) { // the user downloaded it meanwhile: the permanent copy wins
            f.delete()
            return
        }
        val now = System.currentTimeMillis()
        off.put(Meta(id, kind, "cache", f.name, title.ifBlank { id }, channel, dur, f.length(), 1, now, false, now))
        off.enforce(limitBytes())
    }

    // --------------------------------------------------------- downloads

    private fun startDownload(id: String, title: String, channel: String, dur: String, kind: String, q: Int, fmt: String, pl: String = ""): Long {
        val job: Job
        synchronized(jobs) {
            jobs.firstOrNull { it.vid == id && it.kind == kind && it.state == "running" }?.let { return it.id }
            job = Job(nextJob.getAndIncrement(), id, title.ifBlank { id }, kind, pl.trim())
            jobs.removeAll { it.vid == id && it.kind == kind }
            if (jobs.size > 40) jobs.removeAll { it.state != "running" }
            jobs.add(job)
        }
        dlExec.execute { runDownload(job, channel, dur, q, fmt) }
        return job.id
    }

    private fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(70).ifEmpty { "track" }

    /** A cached copy becomes a download without touching the network: the file is moved, not fetched again. */
    private fun promote(c: Meta, title: String, channel: String, dur: String): Meta? {
        val src = off.fileOf(c)
        val dstDir = if (c.kind == "video") off.video else off.music
        val t = title.ifBlank { c.title }
        val dst = File(dstDir, safeName(t) + " [" + c.id + "]." + src.extension)
        if (!src.renameTo(dst)) {
            try {
                src.copyTo(dst, true)
                src.delete()
            } catch (_: Throwable) {
                return null
            }
        }
        val m = Meta(
            c.id, c.kind, "dl", dst.name, t, channel.ifBlank { c.channel }, dur.ifBlank { c.duration },
            dst.length(), c.plays, c.last, false, System.currentTimeMillis()
        )
        off.drop(c, deleteFile = false)
        off.put(m)
        return m
    }

    /** Downloads the audio stream with a few parallel range requests. Returns false to fall back to yt-dlp. */
    private fun fastAudio(j: Job, dir: File, q: Int = 0): Boolean {
        var tmp: File? = null
        try {
            val video = j.kind == "video"
            // video without ffmpeg is one ready file with sound anyway, so the direct link is the same file yt-dlp would pick
            val kindKey = if (video) "video" else "audio"
            val urlQ = if (video) q else cfg.quality
            val url = quickUrl(j.vid, kindKey, urlQ) ?: return false
            val probe = URL(url).openConnection() as HttpURLConnection
            probe.setRequestProperty("User-Agent", uaFor(url))
            probe.setRequestProperty("Range", "bytes=0-0")
            probe.connectTimeout = 4000
            probe.readTimeout = 4000
            val code = probe.responseCode
            val total = probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
            val mime = probe.contentType ?: ""
            probe.disconnect()
            if (code !in 200..299 || total <= 0L) {
                evictUrl(j.vid, kindKey, urlQ)
                return false
            }
            // an audio download must be an audio-only stream: if the link turns out to be picture + sound, let yt-dlp pick audio only
            if (!video && mime.isNotEmpty() && !mime.startsWith("audio/")) return false
            val ext = if (video) "mp4" else if (mime.contains("webm")) "webm" else "m4a"
            val name = safeName(j.title) + " [" + j.vid + "]." + ext
            val part = File(dir, "$name.part").also { tmp = it }
            RandomAccessFile(part, "rw").use { it.setLength(total) }
            val chunk = 1L shl 20
            val chunks = ((total + chunk - 1) / chunk).toInt()
            val next = AtomicInteger(0)
            val got = AtomicLong(0)
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val ch = RandomAccessFile(part, "rw").channel
            val pool = Executors.newFixedThreadPool(6)
            val tasks = (0 until 6).map {
                pool.submit(Runnable {
                    val buf = ByteArray(64 * 1024)
                    while (!failed.get()) {
                        val i = next.getAndIncrement()
                        if (i >= chunks) break
                        val a = i * chunk
                        val b = minOf(total - 1, a + chunk - 1)
                        var ok = false
                        var tries = 0
                        while (!ok && tries < 3 && !failed.get()) {
                            tries++
                            try {
                                val con = URL(url).openConnection() as HttpURLConnection
                                con.setRequestProperty("User-Agent", uaFor(url))
                                con.setRequestProperty("Range", "bytes=$a-$b")
                                con.connectTimeout = 8000
                                con.readTimeout = 10000
                                if (con.responseCode !in 200..299) { con.disconnect(); continue }
                                var pos = a
                                con.inputStream.use { ins ->
                                    while (true) {
                                        val n = ins.read(buf)
                                        if (n < 0) break
                                        ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                                        pos += n
                                        j.pct = got.addAndGet(n.toLong()) * 100f / total
                                    }
                                }
                                con.disconnect()
                                ok = pos > b
                                if (!ok) got.addAndGet(-(pos - a)) // incomplete piece: count it again on retry
                            } catch (_: Throwable) { }
                        }
                        if (!ok) failed.set(true)
                    }
                })
            }
            tasks.forEach { try { it.get() } catch (_: Throwable) { failed.set(true) } }
            pool.shutdown()
            ch.close()
            if (failed.get() || got.get() < total) { part.delete(); return false }
            val out = File(dir, name)
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) { part.delete(); return false }
            return true
        } catch (_: Throwable) {
            tmp?.delete()
            return false
        }
    }

    private fun runDownload(j: Job, channel: String, dur: String, q: Int, fmt: String) {
        try {
            j.queued = false
            val kind = j.kind
            // lyrics are looked up at the same time as the audio is fetched and saved beside it (audio downloads only)
            val lyr = if (kind == "audio") linkExec.submit(java.util.concurrent.Callable { findLyrics(j.vid, j.title, channel, dur) }) else null
            if (j.pl.isNotBlank()) off.get("dl", kind, j.vid)?.let { d ->
                exportTo(j, d) // already on the device: just copy it into the playlist folder
                j.pct = 100f
                j.state = "done"
                return
            }
            off.get("cache", kind, j.vid)?.let { c ->
                val pm = promote(c, j.title, channel, dur)
                if (pm != null) {
                    exportTo(j, pm)
                    if (kind == "audio") attachLyrics(lyr, pm, channel, dur)
                    j.pct = 100f
                    j.state = "done"
                    return
                }
            }
            val dir = if (kind == "video") off.video else off.music
            dir.mkdirs()
            // audio (and video without ffmpeg): fetch the stream link straight away (no yt-dlp start-up) in parallel pieces
            val quick = ((kind == "audio" && !(fmt == "mp3" && Engine.ffmpegOk)) || (kind == "video" && !Engine.ffmpegOk)) && fastAudio(j, dir, q)
            if (!quick) {
            awaitReady()
            tryClients { c ->
                val r = YoutubeDLRequest("https://www.youtube.com/watch?v=${j.vid}")
                if (kind == "video") {
                    r.addOption("-f", videoFormat(q))
                    if (Engine.ffmpegOk) r.addOption("--merge-output-format", "mp4")
                } else if (fmt == "mp3" && Engine.ffmpegOk) {
                    r.addOption("-x")
                    r.addOption("--audio-format", "mp3")
                    r.addOption("--audio-quality", "0")
                    r.addOption("--embed-metadata")
                } else {
                    r.addOption("-f", "bestaudio[ext=m4a]/bestaudio") // keep the original audio, no conversion
                }
                r.addOption("--no-playlist")
                r.addOption("--windows-filenames")
                r.addOption("--retries", "3")
                r.addOption("--fragment-retries", "3")
                speed(r)
                // the id in the name lets the app recognise this file later (offline-first playback)
                r.addOption("-o", dir.absolutePath + "/%(title).70s [%(id)s].%(ext)s")
                client(r, c)
                j.pct = 0f
                YoutubeDL.getInstance().execute(r) { progress, _, _ ->
                    if (progress >= 0f) j.pct = progress
                }
            }
            }
            val f = dir.listFiles()
                ?.filter { it.isFile && it.nameWithoutExtension.endsWith("[${j.vid}]") && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
                ?: throw ApiError(502, "download finished but the file was not found")
            val now = System.currentTimeMillis()
            val saved = Meta(j.vid, kind, "dl", f.name, j.title, channel, dur, f.length(), 0, now, false, now)
            off.put(saved)
            off.get("cache", kind, j.vid)?.let { off.drop(it) } // the permanent copy replaces the cached one
            exportTo(j, saved)
            if (kind == "audio") attachLyrics(lyr, saved, channel, dur)
            j.pct = 100f
            j.state = "done"
        } catch (e: Throwable) {
            j.msg = lastLine(e, "download failed")
            j.state = "failed"
            DebugLog.add("download", "${j.kind} ${j.vid} \"${j.title.take(40)}\" failed", e)
        }
    }

    // ------------------------------------------------------------ lyrics

    /** What /api/lyrics answers: {"found":true,"kind":"lrc"|"txt","text":"..."} for the lyrics saved beside a downloaded song. */
    private fun lyricsJson(idArg: String?, d: String?, n: String?): JSONObject {
        val id = idArg?.takeIf { it.isNotBlank() }?.let { validId(it) }
        val audioName = if (d == "m" && !n.isNullOrEmpty() && !n.contains('/') && !n.contains('\\')) n else null
        if (id == null && audioName == null) throw ApiError(400, "bad id")
        val f = Lyrics.find(off.music, id, audioName) ?: return JSONObject().put("found", false)
        val text = try { f.readText(Charsets.UTF_8).removePrefix("\uFEFF") } catch (_: Throwable) { return JSONObject().put("found", false) }
        if (text.isBlank()) return JSONObject().put("found", false)
        // a .lrc whose lines carry no timestamps is shown as plain text
        val kind = if (f.extension.equals("lrc", true) && Lyrics.isSynced(text)) "lrc" else "txt"
        return JSONObject().put("found", true).put("kind", kind).put("text", text.take(200_000)).put("file", f.name)
    }

    /**
     * Finds lyrics for a track: lrclib synced -> YouTube captions (converted to LRC) -> lrclib plain. Null when nothing exists.
     * Runs in parallel with the audio download.
     */
    private fun findLyrics(id: String, title: String, channel: String, dur: String): Lyrics.Found? {
        var plain: Lyrics.Found? = null
        try {
            val f = Lyrics.fromLrclib(title, channel, Lyrics.seconds(dur))
            if (f != null && f.kind == "lrc") return f
            plain = f
        } catch (e: Throwable) { DebugLog.add("lyrics", "$id: lrclib failed", e) }
        try {
            captionsLrc(id)?.let { return Lyrics.Found("lrc", it, "YouTube captions") }
        } catch (e: Throwable) { DebugLog.add("lyrics", "$id: captions failed", e) }
        return plain
    }

    /** Saves what was found beside the audio file (same base name). Nothing found: the song is listened to and the words are written down. */
    private fun attachLyrics(fut: java.util.concurrent.Future<Lyrics.Found?>?, m: Meta, channel: String, dur: String) {
        val base = File(m.file).nameWithoutExtension
        val have = Lyrics.find(off.music, m.id, m.file)
        if (have != null && have.extension.equals("lrc", true)) return
        val found = try { fut?.get(30, TimeUnit.SECONDS) } catch (_: Throwable) { null }
        if (found != null) {
            Lyrics.save(off.music, base, found.kind, found.text)
            DebugLog.add("lyrics", "${m.id}: ${found.kind} lyrics saved (${found.source})")
            if (found.kind == "lrc") return
        }
        if (found != null || have != null) return // plain lyrics exist: keep them
        // no lyrics anywhere: generate them from the audio in the background (the song is playable meanwhile)
        linkExec.execute {
            try {
                val lrc = Transcribe.lrcFor(ctx, File(off.music, m.file))
                if (lrc != null) {
                    Lyrics.save(off.music, base, "lrc", lrc)
                    DebugLog.add("lyrics", "${m.id}: no lyrics found online, generated from the audio")
                } else DebugLog.add("lyrics", "${m.id}: no lyrics found online and none could be heard in the audio")
            } catch (e: Throwable) { DebugLog.add("lyrics", "${m.id}: generating lyrics failed", e) }
        }
    }

    /** yt-dlp: only the subtitles (manual or automatic), no media; srv1 or vtt, turned into LRC lines. Null when the video has none. */
    private fun captionsLrc(id: String): String? {
        awaitReady()
        val tmp = File(ctx.cacheDir, "lyr-$id-" + System.nanoTime()).also { it.mkdirs() }
        try {
            val r = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
            r.addOption("--skip-download")
            r.addOption("--write-subs")
            r.addOption("--write-auto-subs")
            r.addOption("--sub-langs", "en.*,.*-orig") // English, or the video's own language when it is not English
            r.addOption("--sub-format", "srv1/vtt")
            r.addOption("--no-playlist")
            r.addOption("--no-warnings")
            r.addOption("--socket-timeout", "8")
            r.addOption("-o", tmp.absolutePath + "/%(id)s.%(ext)s")
            client(r, if (haveGood) goodClient else null)
            Engine.procLock.readLock().lock()
            try { YoutubeDL.getInstance().execute(r, "sub-" + System.nanoTime()) } finally { Engine.procLock.readLock().unlock() }
            val files = (tmp.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension.lowercase() in setOf("srv1", "vtt", "xml") && it.length() > 0 }
                .sortedBy { if (it.name.contains("-orig")) 1 else 0 } // a plain "en" track first
            for (f in files) {
                val lrc = Lyrics.captionsToLrc(f)
                if (lrc.lines().size >= 4) return lrc
            }
            return null
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Also puts a copy in the folder the user picked ("Title.m4a", without the id tag). A failure is reported, not fatal. */
    private fun exportTo(j: Job, m: Meta) {
        val f = off.fileOf(m)
        val err = PublicSave.save(ctx, f, safeName(m.title) + "." + f.extension, m.kind == "video", j.pl.takeIf { it.isNotBlank() }?.let { safeName(it) })
        if (err != null) j.msg = "Saved in the app, but not copied to your folder: $err"
    }

    fun beginDownload(id: String, title: String, ch: String, dur: String, kind: String, q: Int, fmt: String, pl: String): Long =
        startDownload(
            validId(id), title.take(200), ch.take(100), dur.take(20),
            if (kind == "video") "video" else "audio",
            if (q > 0) q else cfg.quality,
            if (fmt == "mp3") "mp3" else "m4a",
            pl.take(100)
        )

    fun jobsText(): String = jobsJson().toString()

    private fun jobsJson(): JSONObject {
        val arr = JSONArray()
        synchronized(jobs) {
            for (j in jobs) {
                arr.put(
                    JSONObject().put("id", j.id).put("vid", j.vid).put("title", j.title).put("kind", j.kind)
                        .put("state", j.state).put("pct", j.pct.toDouble()).put("msg", j.msg).put("queued", j.queued)
                )
            }
        }
        val c = JSONArray()
        for (cj in caching.values) {
            c.put(JSONObject().put("vid", cj.vid).put("kind", cj.kind).put("pct", cj.pct.toDouble()))
        }
        return JSONObject().put("jobs", arr).put("caching", c)
    }

    // ----------------------------------------------------- what is saved

    /** id -> {"a": "dl"|"cache", "v": "dl"|"cache"}: lets the cards show a downloaded / cached badge. */
    private fun status(): JSONObject {
        val o = JSONObject()
        for (m in off.everything()) {
            val e = o.optJSONObject(m.id) ?: JSONObject().also { o.put(m.id, it) }
            val k = if (m.kind == "video") "v" else "a"
            if (m.tier == "dl" || !e.has(k)) e.put(k, m.tier)
        }
        return o
    }

    private fun cacheJson(): JSONObject {
        val items = JSONArray()
        val list = off.all("cache").sortedWith(compareByDescending<Meta> { it.prio }.thenByDescending { it.last })
        for (m in list) {
            items.put(
                JSONObject().put("id", m.id).put("kind", m.kind).put("title", m.title).put("channel", m.channel)
                    .put("duration", m.duration).put("size", m.size).put("plays", m.plays)
                    .put("pinned", m.pinned).put("prio", m.prio).put("last", m.last)
                    .put("file", m.file)
            )
        }
        var dl = 0L
        for (m in off.all("dl")) dl += m.size
        return JSONObject().put("used", off.cacheBytes()).put("limit", limitBytes()).put("downloads", dl)
            .put("items", items).put("settings", cfg.toJson())
    }

    // ----------------------------------------------------------- library

    private val audioExt = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav", "aac", "webm")
    private val videoExt = setOf("mp4", "mkv", "webm", "mov")

    private fun library(): JSONArray {
        val rows = ArrayList<Pair<Long, JSONObject>>()
        for ((letter, dir, kind) in listOf(Triple("m", off.music, "audio"), Triple("v", off.video, "video"))) {
            val exts = if (kind == "video") videoExt else audioExt
            for (f in dir.listFiles() ?: emptyArray()) {
                if (!f.isFile || f.name.startsWith(".") || f.extension.lowercase() !in exts) continue
                val m = off.byFile(kind, f.name)
                val stem = f.nameWithoutExtension
                val id = m?.id ?: off.idFromName(stem) ?: ""
                val title = m?.title?.takeIf { it.isNotBlank() }
                    ?: (if (id.isNotEmpty()) stem.removeSuffix("[$id]").trim() else stem)
                rows.add(
                    f.lastModified() to JSONObject().put("name", f.name).put("d", letter).put("kind", kind)
                        .put("title", title).put("channel", m?.channel ?: "").put("duration", m?.duration ?: "")
                        .put("id", id).put("size", f.length())
                )
            }
        }
        rows.sortByDescending { it.first }
        val arr = JSONArray()
        for (r in rows) arr.put(r.second)
        return arr
    }

    private fun safeFile(d: String?, name: String?): File? {
        val dir = off.dirByKey(d) ?: return null
        if (name.isNullOrEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
        val ext = File(name).extension.lowercase()
        val ok = when (d) {
            "m" -> ext in audioExt
            "v" -> ext in videoExt
            else -> ext in audioExt || ext in videoExt
        }
        if (!ok) return null
        val f = File(dir, name)
        return if (f.isFile) f else null
    }

    private fun mime(ext: String, video: Boolean) = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus", "ogg", "oga" -> "audio/ogg"
        "webm" -> if (video) "video/webm" else "audio/webm"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "mka" -> "audio/x-matroska"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    /** Plays saved songs and videos; honours Range requests so seeking works. */
    private fun file(d: String?, name: String?, headers: Map<String, String>): WebResourceResponse {
        if (d == "i") return importedFile(name, headers)
        val f = safeFile(d, name) ?: throw ApiError(404, "no such file")
        val len = f.length()
        if (len == 0L) throw ApiError(404, "empty file")
        val isVideo = d == "v" || (d == "c" && f.name.startsWith("v_"))
        return ranged(len, mime(f.extension, isVideo), headers) { start ->
            FileInputStream(f).also { it.channel.position(start) }
        }
    }

    /** A song from an imported music folder: read through the content resolver, Range requests included. */
    private fun importedFile(id: String?, headers: Map<String, String>): WebResourceResponse {
        val t = MusicFolders.get(ctx).track(id) ?: throw ApiError(404, "no such file")
        val pfd0 = try { ctx.contentResolver.openFileDescriptor(t.uri, "r") } catch (_: Throwable) { null }
            ?: throw ApiError(404, "file not found (was it moved or deleted?)")
        var len = pfd0.statSize
        if (len <= 0L) len = t.size
        pfd0.close()
        if (len <= 0L) throw ApiError(404, "empty file")
        return ranged(len, mime(t.ext, false), headers) { start ->
            val pfd = ctx.contentResolver.openFileDescriptor(t.uri, "r") ?: throw ApiError(404, "file not found")
            val ins = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            try { ins.channel.position(start) } catch (_: Throwable) { ins.skip(start) }
            ins
        }
    }

    private fun ranged(len: Long, type: String, headers: Map<String, String>, open: (Long) -> InputStream): WebResourceResponse {
        var start = 0L
        var end = len - 1
        var partial = false
        val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=")
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-', "")
            if (a.isEmpty()) {
                val n = b.toLongOrNull()
                if (n != null && n > 0) { start = maxOf(0L, len - n); partial = true }
            } else {
                val s = a.toLongOrNull()
                if (s != null && s >= len) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", 416, "Range Not Satisfiable",
                        mutableMapOf("Content-Range" to "bytes */$len", "Accept-Ranges" to "bytes"),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (s != null) {
                    start = s
                    end = if (b.isEmpty()) len - 1 else minOf(b.toLongOrNull() ?: (len - 1), len - 1)
                    partial = true
                }
            }
        }
        val n = end - start + 1
        val hdr = mutableMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to n.toString(),
            "Cache-Control" to "no-store"
        )
        if (partial) hdr["Content-Range"] = "bytes $start-$end/$len"
        return WebResourceResponse(
            type, null, if (partial) 206 else 200, if (partial) "Partial Content" else "OK",
            hdr, Limited(open(start), n)
        )
    }

    // ----------------------------------------------------- imported folders

    private fun importJson(): JSONObject {
        val mf = MusicFolders.get(ctx)
        val tl = mf.trackList()
        val folders = JSONArray()
        for (r in mf.rootList()) {
            folders.put(JSONObject().put("id", r.id).put("name", r.name).put("count", tl.count { it.tree == r.id }))
        }
        val tracks = JSONArray()
        for (t in tl) {
            tracks.put(
                JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
                    .put("duration", t.duration).put("size", t.size).put("folder", t.folder).put("tree", t.tree)
            )
        }
        return JSONObject().put("scanning", mf.isScanning).put("folders", folders).put("tracks", tracks)
    }

    private class Limited(src: InputStream, private var left: Long) : FilterInputStream(src) {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = super.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    companion object {
        @Volatile private var inst: Api? = null

        /** One Api per process, so downloads and caching keep going if the activity is recreated. */
        fun get(ctx: Context): Api =
            inst ?: synchronized(this) { inst ?: Api(ctx.applicationContext).also { inst = it } }
    }
}
