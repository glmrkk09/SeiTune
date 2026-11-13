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

    /** The saved lyrics of a track: by the [id] in the file name, or as the sibling of a known audio file. .lrc beats .txt. */
    fun find(dir: File, id: String?, audioFile: String?): File? {
        val files = dir.listFiles() ?: return null
        val cands = files.filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 && it.extension.lowercase() in setOf("lrc", "txt") }
        val base = audioFile?.let { File(it).nameWithoutExtension }
        val hit = cands.filter { f ->
            val stem = f.nameWithoutExtension
            (base != null && stem == base) || (!id.isNullOrEmpty() && stem.endsWith("[$id]"))
        }
        return hit.firstOrNull { it.extension.equals("lrc", true) } ?: hit.firstOrNull()
    }

    fun delete(dir: File, id: String?, audioFile: String?) {
        while (true) {
            val f = find(dir, id, audioFile) ?: break
            if (!f.delete()) break
        }
    }

    /** Writes the lyrics beside the audio. A synced file replaces a plain one (and the other way round is never done). */
    fun save(dir: File, base: String, kind: String, text: String): File {
        dir.mkdirs()
        val out = File(dir, "$base.$kind")
        out.writeText(text.trim() + "\n", Charsets.UTF_8)
        if (kind == "lrc") File(dir, "$base.txt").delete()
        return out
    }

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

    // ------------------------------------------------------------ lrclib.net

    private fun get(url: String): String? {
        val con = URL(url).openConnection() as HttpURLConnection
        return try {
            con.setRequestProperty("User-Agent", UA)
            con.connectTimeout = 6000
            con.readTimeout = 8000
            if (con.responseCode !in 200..299) null else con.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            con.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Looks the song up on lrclib.net by title, artist and duration. Synced lyrics win over plain ones. */
    fun fromLrclib(title: String, channel: String, durSec: Int): Found? {
        val (artist, track) = guess(title, channel)
        if (track.isBlank()) return null
        val urls = ArrayList<String>()
        if (artist.isNotBlank()) {
            // exact lookup first: it needs the duration to be within 2 s
            if (durSec > 0) urls.add("https://lrclib.net/api/get?track_name=${enc(track)}&artist_name=${enc(artist)}&duration=$durSec")
            urls.add("https://lrclib.net/api/search?track_name=${enc(track)}&artist_name=${enc(artist)}")
        }
        urls.add("https://lrclib.net/api/search?q=${enc("$artist $track".trim())}")
        for (u in urls) {
            val body = get(u) ?: continue
            val list = ArrayList<JSONObject>()
            try {
                if (body.trimStart().startsWith("[")) {
                    val a = JSONArray(body)
                    for (i in 0 until a.length()) list.add(a.getJSONObject(i))
                } else list.add(JSONObject(body))
            } catch (_: Throwable) { continue }
            pick(list, durSec)?.let { return it }
        }
        return null
    }

    private fun pick(list: List<JSONObject>, durSec: Int): Found? {
        fun fits(o: JSONObject): Boolean {
            val d = o.optDouble("duration", 0.0)
            return durSec <= 0 || d <= 0.0 || abs(d - durSec) <= 5.0 // a different cut / live version has another length
        }
        val ok = list.filter { fits(it) && !it.optBoolean("instrumental", false) }
        fun closest(l: List<JSONObject>) = l.minByOrNull { abs(it.optDouble("duration", durSec.toDouble()) - durSec) }
        closest(ok.filter { it.optString("syncedLyrics").isNotBlank() })?.let {
            return Found("lrc", it.optString("syncedLyrics"), "lrclib.net")
        }
        closest(ok.filter { it.optString("plainLyrics").isNotBlank() })?.let {
            return Found("txt", it.optString("plainLyrics"), "lrclib.net")
        }
        return null
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

    private fun clean(s: String): String =
        unescape(s.replace(Regex("""<[^>]*>"""), "")).replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()

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
