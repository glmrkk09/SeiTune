// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lyrics for downloaded songs. Saved next to the audio file with the same base name:
 *   "Title [id].m4a"  ->  "Title [id].lrc"  (synced, "[mm:ss.xx] line")  or  "Title [id].txt"  (plain)
 *
 * Sources, in order: lrclib.net (title + artist + duration), then the video's own YouTube captions via yt-dlp,
 * converted to LRC timestamps (see [Api]). Everything here is plain functions, no state.
 */
object Lyrics {
    private const val UA = "SEITUNE-Android/1.0 (https://github.com/glmrk/seitune)"

    class Found(val kind: String, val text: String, val source: String) // kind: "lrc" | "txt"

    private val tsTag = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    fun isSynced(text: String): Boolean = tsTag.containsMatchIn(text)

    // ------------------------------------------------------------ local files

    /** The saved lyrics of a track: by the [id] in the file name, or as the sibling of a known audio file. .lrc beats .txt. Junk files (e.g. "null") are removed. */
    fun find(dir: File, id: String?, audioFile: String?): File? {
        val files = dir.listFiles() ?: return null
        val cands = files.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 && it.extension.lowercase() in setOf("lrc", "txt") }
        val base = audioFile?.let { File(it).nameWithoutExtension }
        val hit = cands.filter { f ->
            val stem = f.nameWithoutExtension
            (base != null && stem == base) || (!id.isNullOrEmpty() && stem.endsWith("[$id]"))
        }.sortedBy { if (it.extension.equals("lrc", true)) 0 else 1 }
        for (f in hit) {
            if (fileUsable(f)) return f
            DebugLog.sei("lyrics", "removed unusable lyrics file ${f.name}")
            f.delete() // it will be searched for again
        }
        return null
    }

    private fun fileUsable(f: File): Boolean = try {
        val head = f.bufferedReader(Charsets.UTF_8).use { r ->
            val b = CharArray(2000)
            val n = r.read(b)
            if (n <= 0) "" else String(b, 0, n)
        }
        usable(head.removePrefix("\uFEFF"))
    } catch (_: Throwable) {
        true
    }

    fun delete(dir: File, id: String?, audioFile: String?) {
        while (true) {
            val f = find(dir, id, audioFile) ?: break
            if (!f.delete()) break
        }
        // translated copies: "Title [id].en.lrc"
        val base = audioFile?.let { File(it).nameWithoutExtension }
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.extension.lowercase() !in setOf("lrc", "txt")) return@forEach
            val stem = f.nameWithoutExtension
            val head = stem.substringBeforeLast('.', "")
            if (head.isEmpty() || !langTag.matches(stem.substringAfterLast('.'))) return@forEach
            if ((base != null && head == base) || (!id.isNullOrEmpty() && head.endsWith("[$id]"))) f.delete()
        }
    }

    /** Writes the lyrics beside the audio. A synced file replaces a plain one (and the other way round is never done). */
    fun save(dir: File, base: String, kind: String, text: String): File {
        dir.mkdirs()
        val out = File(dir, "$base.$kind")
        if (!usable(text)) { DebugLog.sei("lyrics", "not saving empty lyrics for $base"); return out }
        out.writeText(text.trim() + "\n", Charsets.UTF_8)
        if (kind == "lrc") File(dir, "$base.txt").delete()
        return out
    }

    // ------------------------------------------------------------ translation (no yt-dlp: plain HTTPS)

    private val langTag = Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2,4})?$")
    fun validLang(code: String) = langTag.matches(code)

    class Translated(val text: String, val src: String)

    /** The translated copy of a lyrics file lives beside it: "Title [id].lrc" -> "Title [id].en.lrc". */
    fun variant(orig: File, lang: String): File =
        File(orig.parentFile, orig.nameWithoutExtension + "." + lang.lowercase() + "." + orig.extension.lowercase())

    private val leadStamps = Regex("""^((?:\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])+)(.*)$""")
    private val headerTag = Regex("""^\[[A-Za-z]+:.*]$""")

    /** One request: the text (lines separated by \n) -> (translated text, detected source language). Null when offline / refused. */
    private fun gt(target: String, q: String): Pair<String, String>? {
        val con = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${enc(target)}&dt=t&ie=UTF-8&oe=UTF-8")
            .openConnection() as HttpURLConnection
        return try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            con.connectTimeout = 6000
            con.readTimeout = 12000
            con.outputStream.use { it.write(("q=" + enc(q)).toByteArray(Charsets.UTF_8)) }
            if (con.responseCode !in 200..299) null else {
                val root = JSONArray(con.inputStream.bufferedReader().use { it.readText() })
                val segs = root.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until segs.length()) sb.append(segs.optJSONArray(i)?.optString(0, "") ?: "")
                sb.toString() to root.optString(2, "")
            }
        } catch (_: Throwable) {
            null
        } finally {
            con.disconnect()
        }
    }

    /**
     * Translates the words of a .lrc / plain text and keeps everything else (timestamps, blank lines, [ar:..] tags) exactly,
     * so the translated copy lines up with the original line for line. Null when the network refused.
     */
    fun translate(text: String, target: String): Translated? {
        val raw = text.replace("\r", "").split('\n')
        val stamps = ArrayList<String>(raw.size)
        val bodies = ArrayList<String>(raw.size)
        for (r in raw) {
            val m = leadStamps.matchEntire(r)
            if (m != null) { stamps.add(m.groupValues[1]); bodies.add(m.groupValues[2].trim()) } else { stamps.add(""); bodies.add(r.trim()) }
        }
        val todo = bodies.indices.filter { bodies[it].isNotEmpty() && !headerTag.matches(bodies[it]) }
        if (todo.isEmpty()) return null
        val out = HashMap<Int, String>()
        var src = ""
        // batches of about 3000 characters, one line per row; the answer must have the same number of rows
        var i = 0
        while (i < todo.size) {
            val batch = ArrayList<Int>()
            var n = 0
            while (i < todo.size && (batch.isEmpty() || n + bodies[todo[i]].length < 3000)) { batch.add(todo[i]); n += bodies[todo[i]].length + 1; i++ }
            val one = gt(target, batch.joinToString("\n") { bodies[it].replace('\n', ' ') }) ?: return null
            if (src.isEmpty()) src = one.second
            val rows = one.first.trimEnd('\n').split('\n')
            if (rows.size == batch.size) {
                for ((k, idx) in batch.withIndex()) out[idx] = rows[k].trim()
            } else {
                // the service merged or split lines: ask line by line (4 at a time)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                try {
                    val fut = batch.map { idx -> idx to pool.submit(java.util.concurrent.Callable { gt(target, bodies[idx]) }) }
                    for ((idx, f) in fut) out[idx] = (f.get()?.first ?: return null).trim()
                } finally { pool.shutdown() }
            }
        }
        val sb = StringBuilder()
        for (k in raw.indices) {
            val t = out[k]
            if (t != null) sb.append(stamps[k]).append(t) else sb.append(raw[k])
            if (k < raw.size - 1) sb.append('\n')
        }
        return Translated(sb.toString(), src)
    }

    /** "ja-JP" and "ja" are the same language, "zh-CN" and "zh-TW" too: compare the first part. */
    fun sameLang(a: String, b: String) = a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    // ------------------------------------------------------------ names

    /** "3:45" / "1:02:03" / "225" -> seconds, 0 when unknown. */
    fun seconds(dur: String): Int {
        val p = dur.trim().split(':').mapNotNull { it.toIntOrNull() }
        return when (p.size) {
            1 -> p[0]
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> 0
        }
    }

    private val junk = Regex(
        """\s*[(\[][^)\]]*(official|lyric|video|audio|visuali[sz]er|hd|hq|4k|remaster|explicit|clip|mv|m/v)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )

    /** (artist, track) guessed from the video title and channel: "Artist - Song (Official Video)" / channel "Artist - Topic". */
    fun guess(title: String, channel: String): Pair<String, String> {
        val t = title.replace(junk, "").replace(Regex("""\s+"""), " ").trim()
        val ch = channel.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "").trim()
        val i = t.indexOf(" - ")
        if (i > 0) return t.substring(0, i).trim() to t.substring(i + 3).trim()
        return ch to t
    }

    // ------------------------------------------------------------ web search (lrclib, NetEase, lyrics.ovh)

    class Query(val artist: String, val track: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String) = enc(s).replace("+", "%20")

    /** org.json's optString turns a JSON null into the text "null": that text used to be saved as the lyrics. */
    private fun str(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        val s = o.optString(key, "")
        return if (s.equals("null", true)) "" else s
    }

    private val stampsAll = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    /** Real lyrics: not empty, not "null", and with some letters or digits once the time stamps are taken away. */
    fun usable(text: String?): Boolean {
        val t = stampsAll.replace(text ?: return false, "").trim()
        if (t.isEmpty() || t.equals("null", true) || t.equals("undefined", true)) return false
        return t.count { it.isLetterOrDigit() } >= 3
    }

    private val quoted = Regex("""[「『]([^」』]{2,})[」』]""")
    private val bracketGroup = Regex("""[【\[(（「『][^】\])）」』]*[】\])）」』]""")
    private val noise = Regex(
        """歌ってみた|歌わせていただきました|カバー|\bcover(ed)?\b|\blyrics?\b|\bm/?v\b|\bamv\b|\bofficial\b|\bmusic video\b|\bvideo\b|\bhd\b|\b4k\b|\bfeat\.?.*$|\bft\.?.*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Search queries guessed from a video title / file name, best first. Handles "Artist - Song", "Song「日本語タイトル」歌ってみた【Singer】",
     * "(Official Video)" and similar: the words in 「」 become a query of their own, brackets and cover words are dropped.
     */
    fun candidates(title: String, channel: String): List<Query> {
        val ch = channel.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "").trim()
        val inner = quoted.findAll(title).map { it.groupValues[1].trim() }.toList()
        val base0 = noise.replace(title.replace(bracketGroup, " "), " ").replace(Regex("""\s+"""), " ").trim().trim('-', '–', '/', '|', ' ')
        // "Sparkle | Your Name AMV": what follows a bar is the show or the uploader, the song is what stands before it
        val base = (if (base0.contains('|') && base0.substringBefore('|').trim().length >= 2) base0.substringBefore('|') else base0).trim().trim('-', '–', '/', '|', ' ')
        val out = ArrayList<Query>()
        fun add(a: String, t: String) {
            val tt = t.trim()
            val aa = a.trim()
            if (tt.length < 2) return
            if (out.none { it.artist.equals(aa, true) && it.track.equals(tt, true) }) out.add(Query(aa, tt))
        }
        val cut = Regex("""\s[-–]\s""").find(base)
        if (cut != null) add(base.substring(0, cut.range.first), base.substring(cut.range.last + 1))
        add(ch, base)
        add("", base)
        for (q in inner) { add(ch, q); add("", q) }
        return out.take(6)
    }

    /** One search: remembers every answer (the loose second pass costs no new requests), stops after a time budget. */
    private class Run {
        val deadline = System.currentTimeMillis() + 28_000
        val memo = HashMap<String, String?>()
        val failed = LinkedHashSet<String>()
        var requests = 0
        fun left() = deadline - System.currentTimeMillis()
        fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
            if (memo.containsKey(url)) return memo[url]
            if (left() < 1500) return null
            requests++
            val body = httpGet(url, headers) { failed.add(it) }
            memo[url] = body
            return body
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>, onFail: (String) -> Unit): String? {
        val host = try { URL(url).host } catch (_: Throwable) { "?" }
        val con = URL(url).openConnection() as HttpURLConnection
        return try {
            con.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) con.setRequestProperty(k, v)
            con.connectTimeout = 6000
            con.readTimeout = 8000
            val code = con.responseCode
            if (code !in 200..299) { onFail("$host HTTP $code"); null } else con.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            onFail(host + " " + e.javaClass.simpleName)
            null
        } finally {
            con.disconnect()
        }
    }

    private fun parseList(body: String): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        try {
            if (body.trimStart().startsWith("[")) {
                val a = JSONArray(body)
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { list.add(it) }
            } else list.add(JSONObject(body))
        } catch (_: Throwable) { }
        return list
    }

    private fun pick(list: List<JSONObject>, durSec: Int, tol: Double): Found? {
        fun fits(o: JSONObject): Boolean {
            val d = o.optDouble("duration", 0.0)
            return durSec <= 0 || d <= 0.0 || abs(d - durSec) <= tol // a different cut / live version has another length
        }
        val ok = list.filter { fits(it) && !it.optBoolean("instrumental", false) }
        fun closest(l: List<JSONObject>) = l.minByOrNull { abs(it.optDouble("duration", durSec.toDouble()) - durSec) }
        closest(ok.filter { usable(str(it, "syncedLyrics")) })?.let { return Found("lrc", str(it, "syncedLyrics"), "lrclib.net") }
        closest(ok.filter { usable(str(it, "plainLyrics")) })?.let { return Found("txt", str(it, "plainLyrics"), "lrclib.net") }
        return null
    }

    private fun lrclib(run: Run, q: Query, durSec: Int, tol: Double): Found? {
        val urls = ArrayList<String>()
        if (q.artist.isNotBlank()) {
            if (durSec > 0) urls.add("https://lrclib.net/api/get?track_name=${enc(q.track)}&artist_name=${enc(q.artist)}&duration=$durSec")
            urls.add("https://lrclib.net/api/search?track_name=${enc(q.track)}&artist_name=${enc(q.artist)}")
        }
        urls.add("https://lrclib.net/api/search?q=${enc("${q.artist} ${q.track}".trim())}")
        if (q.artist.isNotBlank()) urls.add("https://lrclib.net/api/search?track_name=${enc(q.track)}")
        for (u in urls) {
            val body = run.get(u) ?: continue
            pick(parseList(body), durSec, tol)?.let { return it }
        }
        return null
    }

    private val neHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    )

    /** NetEase Cloud Music: large catalogue of Japanese / Korean / Chinese songs, synced lyrics, no key needed. */
    private fun netease(run: Run, q: Query, durSec: Int, tol: Double): Found? {
        val s = "${q.artist} ${q.track}".trim()
        val body = run.get("https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=${enc(s)}&type=1&offset=0&total=true&limit=8", neHeaders) ?: return null
        val songs = try { JSONObject(body).optJSONObject("result")?.optJSONArray("songs") } catch (_: Throwable) { null } ?: return null
        var bestId = 0L
        var bestDiff = Double.MAX_VALUE
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val d = o.optDouble("duration", 0.0) / 1000.0
            val known = durSec > 0 && d > 0.0
            val diff = if (known) abs(d - durSec) else 0.0
            if (known && diff > tol) continue
            if (diff < bestDiff) { bestDiff = diff; bestId = o.optLong("id", 0L) }
        }
        if (bestId == 0L) return null
        val lb = run.get("https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1", neHeaders) ?: return null
        val raw = try { str(JSONObject(lb).optJSONObject("lrc") ?: return null, "lyric") } catch (_: Throwable) { return null }
        val text = raw.lines().filterNot { it.trimStart().startsWith("{") }.joinToString("\n") // credit lines are JSON
        if (!usable(text) || text.contains("纯音乐")) return null
        return Found(if (isSynced(text)) "lrc" else "txt", text, "NetEase")
    }

    private fun ovh(run: Run, q: Query): Found? {
        if (q.artist.isBlank()) return null
        val body = run.get("https://api.lyrics.ovh/v1/${encPath(q.artist)}/${encPath(q.track)}") ?: return null
        val t = try { str(JSONObject(body), "lyrics") } catch (_: Throwable) { "" }
        return if (usable(t)) Found("txt", t.trim(), "lyrics.ovh") else null
    }

    /**
     * Looks the song up on the web: lrclib.net and NetEase (synced first), then lyrics.ovh (plain). [tagTitle]/[tagArtist] (from the file's
     * own tags) are tried first, then guesses from [title]/[channel]. A second, looser pass accepts a different length (covers, live cuts).
     */
    fun search(title: String, channel: String, durSec: Int, tagTitle: String = "", tagArtist: String = ""): Found? {
        val qs = ArrayList<Query>()
        if (tagTitle.isNotBlank() && !tagTitle.equals("null", true)) qs.add(Query(tagArtist.trim().takeUnless { it.equals("null", true) } ?: "", tagTitle.trim()))
        for (c in candidates(title, channel)) if (qs.none { it.artist.equals(c.artist, true) && it.track.equals(c.track, true) }) qs.add(c)
        if (qs.isEmpty()) return null
        // a mix, a playlist video or a very long upload is not one song: searching for it only wastes requests (and lrclib answers 400 to a long query)
        if (durSec > 1200 || Regex("""\b(mix|playlist|compilation|non[- ]?stop|\d+\s*hours?|songs that i|best of)\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
            if (tagTitle.isBlank()) { DebugLog.sei("lyrics", "web search skipped: \"${title.take(40)}\" looks like a mix or playlist"); DebugLog.count("lyrics skipped"); return null }
        }
        val clean = Regex("""[^\p{L}\p{N}\s'&.,!?()\-]""")
        for (i in qs.indices) qs[i] = Query(qs[i].artist.replace(clean, " ").replace(Regex("""\s+"""), " ").trim(), qs[i].track.replace(clean, " ").replace(Regex("""\s+"""), " ").trim().take(80))
        qs.removeAll { it.track.isBlank() }
        if (qs.isEmpty()) return null
        val run = Run()
        var plain: Found? = null
        fun done(f: Found?): Found? {
            DebugLog.sei(
                "lyrics",
                "web search \"${qs.first().track.take(40)}\": ${run.requests} requests, " + (f?.let { "found ${it.kind} via ${it.source}" } ?: "nothing") +
                    (if (f == null && run.failed.isNotEmpty()) " (" + run.failed.joinToString(", ") + ")" else "")
            )
            DebugLog.count("lyrics " + (f?.source ?: "none"))
            return f
        }
        for (tol in doubleArrayOf(5.0, 20.0)) {
            for (q in qs) {
                if (run.left() < 1500) break
                for (src in 0..1) {
                    val f = try { if (src == 0) lrclib(run, q, durSec, tol) else netease(run, q, durSec, tol) } catch (_: Throwable) { null } ?: continue
                    if (f.kind == "lrc") return done(f)
                    if (plain == null) plain = f
                }
            }
        }
        if (plain == null) {
            for (q in qs) {
                if (run.left() < 1500) break
                val f = try { ovh(run, q) } catch (_: Throwable) { null } ?: continue
                plain = f
                break
            }
        }
        return done(plain)
    }

    // ------------------------------------------------------------ captions -> LRC

    /** [mm:ss.xx] */
    fun stamp(ms: Long): String {
        val m = ms.coerceAtLeast(0L)
        val cs = (m / 10) % 100
        val s = (m / 1000) % 60
        val min = m / 60000
        return "[%02d:%02d.%02d]".format(min, s, cs)
    }

    private fun unescape(s: String): String {
        var t = s
        // captions are often escaped twice (&amp;#39;)
        repeat(2) {
            t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ")
            t = Regex("""&#(\d+);""").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: m.value }
            t = Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: m.value }
        }
        return t
    }

    private val anyTag = Regex("""</?[a-zA-Z][^>]*>|<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

    /** Captions arrive escaped (&lt;font color=...&gt;): decode first, then strip the tags, then decode what was escaped twice. */
    private fun clean(s: String): String {
        var t = unescape(s)
        repeat(2) { t = t.replace(anyTag, "") }
        t = unescape(t)
        return t.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
    }

    /** Same cleanup for lyric text that is already saved on disk (older downloads still hold the tags). */
    fun stripMarkup(text: String): String = text.lines().joinToString("\n") { it.replace(anyTag, "").trimEnd() }

    private fun toLrc(cues: List<Pair<Long, String>>): String {
        val sb = StringBuilder()
        var prev = ""
        for ((t, raw) in cues) {
            val line = clean(raw)
            if (line.isEmpty() || line == prev) continue // auto captions repeat the line that is rolling out
            if (line.startsWith("[") && line.endsWith("]") && line.length < 24) continue // [Music], [Applause]
            sb.append(stamp(t)).append(line).append('\n')
            prev = line
        }
        return sb.toString().trim()
    }

    /** YouTube's srv1 captions: <text start="1.5" dur="2.1">words</text> */
    fun srv1ToLrc(xml: String): String {
        val cues = ArrayList<Pair<Long, String>>()
        for (m in Regex("""<text\s+[^>]*?start="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val t = ((m.groupValues[1].toDoubleOrNull() ?: continue) * 1000).toLong()
            cues.add(t to m.groupValues[2])
        }
        return toLrc(cues)
    }

    /** WebVTT: "00:00:01.000 --> 00:00:03.000" then the text lines. Rolling auto captions keep only their newest line. */
    fun vttToLrc(vtt: String): String {
        val cues = ArrayList<Pair<Long, String>>()
        val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s+-->""")
        val lines = vtt.replace("\r", "").split('\n')
        var i = 0
        while (i < lines.size) {
            val m = time.find(lines[i])
            if (m == null) { i++; continue }
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val ms = ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong()
            i++
            val text = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) { text.add(lines[i]); i++ }
            if (text.isEmpty()) continue
            // rolling captions put the previous line first and the new one last
            cues.add(ms to text.last())
        }
        return toLrc(cues)
    }

    fun captionsToLrc(file: File): String {
        val text = try { file.readText(Charsets.UTF_8) } catch (_: Throwable) { return "" }
        return if (text.contains("<transcript") || text.contains("<text ")) srv1ToLrc(text) else vttToLrc(text)
    }
}
