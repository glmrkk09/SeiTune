// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved copy of a YouTube track.
 *  tier "dl"    = a download the user asked for. Permanent, never touched by the cache cleaner.
 *  tier "cache" = a copy kept automatically while listening. Counted against the cache limit.
 */
class Meta(
    val id: String,
    val kind: String,        // "audio" | "video"
    val tier: String,        // "dl" | "cache"
    val file: String,
    var title: String,
    var channel: String,
    var duration: String,
    var size: Long,
    var plays: Int,
    var last: Long,
    var pinned: Boolean,
    val added: Long
) {
    val key: String get() = "$tier:$kind:$id"

    /** Cache priority: 2 = pinned (kept), 1 = frequent (3+ plays), 0 = recent. Lowest goes first when space runs out. */
    val prio: Int get() = if (pinned) 2 else if (plays >= 3) 1 else 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("tier", tier).put("file", file)
        .put("title", title).put("channel", channel).put("duration", duration)
        .put("size", size).put("plays", plays).put("last", last)
        .put("pinned", pinned).put("added", added)

    companion object {
        fun from(o: JSONObject) = Meta(
            o.getString("id"), o.getString("kind"), o.getString("tier"), o.getString("file"),
            o.optString("title"), o.optString("channel"), o.optString("duration"),
            o.optLong("size"), o.optInt("plays"), o.optLong("last"),
            o.optBoolean("pinned"), o.optLong("added")
        )
    }
}

/** User choices. Stored in SharedPreferences, read on every call so changes apply at once. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Play a saved copy (download first, then cache) before asking the network. */
    var preferOffline: Boolean
        get() = p.getBoolean("preferOffline", true)
        set(v) { p.edit().putBoolean("preferOffline", v).apply() }

    var autoCacheAudio: Boolean
        get() = p.getBoolean("autoCacheAudio", true)
        set(v) { p.edit().putBoolean("autoCacheAudio", v).apply() }

    var autoCacheVideo: Boolean
        get() = p.getBoolean("autoCacheVideo", false)
        set(v) { p.edit().putBoolean("autoCacheVideo", v).apply() }

    /** Do not auto-cache on mobile data. */
    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", false)
        set(v) { p.edit().putBoolean("wifiOnly", v).apply() }

    /** Cache size limit in MB. 0 = no limit. */
    var cacheMb: Int
        get() = p.getInt("cacheMb", 512)
        set(v) { p.edit().putInt("cacheMb", v.coerceIn(0, 100_000)).apply() }

    /** Highest video height used for streaming, caching and video downloads. */
    var quality: Int
        get() = p.getInt("quality", 720)
        set(v) { p.edit().putInt("quality", if (v in listOf(360, 480, 720, 1080)) v else 720).apply() }

    /** How many downloads run at the same time (the rest wait in a queue). */
    var parallel: Int
        get() = p.getInt("parallel", 3)
        set(v) { p.edit().putInt("parallel", v.coerceIn(1, 6)).apply() }

    /** Faster downloads: big chunks + parallel fragments (YouTube slows down plain single-stream downloads). */
    var turbo: Boolean
        get() = p.getBoolean("turbo", true)
        set(v) { p.edit().putBoolean("turbo", v).apply() }

    fun toJson(): JSONObject = JSONObject()
        .put("preferOffline", preferOffline).put("autoCacheAudio", autoCacheAudio)
        .put("autoCacheVideo", autoCacheVideo).put("wifiOnly", wifiOnly)
        .put("cacheMb", cacheMb).put("quality", quality)
        .put("parallel", parallel).put("turbo", turbo)
}

/**
 * Where saved songs and videos live, and the index that remembers which YouTube id each file is.
 *
 * Folders (no storage permission needed):
 *   Android/data/com.mrk.seitune/files/Music    audio downloads
 *   Android/data/com.mrk.seitune/files/Movies   video downloads
 *   Android/data/com.mrk.seitune/files/Cache    automatic cache
 *
 * Playback order (the cache priority):  download  ->  cache  ->  network.
 * Cleanup order when the cache is over its limit: unpinned only, "recent" before "frequent",
 * oldest first. Pinned items and downloads are never removed automatically.
 */
class Offline private constructor(private val ctx: Context) {

    val music: File = folder(Environment.DIRECTORY_MUSIC, "Music")
    val video: File = folder(Environment.DIRECTORY_MOVIES, "Movies")
    val cache: File = folder("Cache", "Cache")

    private val idxFile = File(ctx.filesDir, "offline-index.json")
    private val idx = LinkedHashMap<String, Meta>()
    private val sideFiles = setOf("lrc", "txt", "part")
    private val idInName = Regex("""\[([A-Za-z0-9_-]{6,20})\]$""")

    init {
        load()
        reconcile()
    }

    private fun folder(type: String, fallback: String): File =
        (ctx.getExternalFilesDir(type) ?: File(ctx.filesDir, fallback)).apply { mkdirs() }

    fun dirFor(m: Meta): File = when {
        m.tier == "cache" -> cache
        m.kind == "video" -> video
        else -> music
    }

    fun fileOf(m: Meta): File = File(dirFor(m), m.file)

    /** "m" = Music, "v" = Movies, "c" = Cache: the letter the page puts in /api/file URLs. */
    fun dirByKey(d: String?): File? = when (d) {
        "m" -> music
        "v" -> video
        "c" -> cache
        else -> null
    }

    fun letterOf(m: Meta): String = when {
        m.tier == "cache" -> "c"
        m.kind == "video" -> "v"
        else -> "m"
    }

    /** idInName only; used to recognise "Title [id].m4a" names. */
    fun idFromName(stem: String): String? = idInName.find(stem)?.groupValues?.get(1)

    // ---------------------------------------------------------------- index

    private fun load() {
        try {
            if (!idxFile.isFile) return
            val a = JSONArray(idxFile.readText())
            for (i in 0 until a.length()) {
                val m = Meta.from(a.getJSONObject(i))
                idx[m.key] = m
            }
        } catch (_: Throwable) {
            idx.clear()
        }
    }

    @Synchronized
    private fun save() {
        try {
            val a = JSONArray()
            for (m in idx.values) a.put(m.toJson())
            val tmp = File(idxFile.path + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(idxFile)) {
                idxFile.writeText(a.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            // index is a convenience; files stay playable from the Library without it
        }
    }

    /** At start: forget entries whose file is gone, adopt downloads named "Title [id].ext", sweep stray cache files. */
    @Synchronized
    private fun reconcile() {
        val iter = idx.values.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            val f = fileOf(m)
            if (!f.isFile || f.length() == 0L) iter.remove() else m.size = f.length()
        }
        for ((dir, kind) in listOf(music to "audio", video to "video")) {
            for (f in dir.listFiles() ?: emptyArray()) {
                if (!f.isFile || f.name.startsWith(".")) continue
                if (f.extension.lowercase() in sideFiles) continue // lyrics saved beside a song are not media
                val stem = f.nameWithoutExtension
                val id = idFromName(stem) ?: continue
                if (idx.containsKey("dl:$kind:$id")) continue
                val m = Meta(
                    id, kind, "dl", f.name, stem.replace(idInName, "").trim(), "", "",
                    f.length(), 0, f.lastModified(), false, f.lastModified()
                )
                idx[m.key] = m
            }
        }
        val known = idx.values.filter { it.tier == "cache" }.map { it.file }.toSet()
        for (f in cache.listFiles() ?: emptyArray()) {
            if (f.isFile && f.name !in known) f.delete() // leftovers of an interrupted caching run
        }
        save()
    }

    // --------------------------------------------------------------- lookups

    @Synchronized
    fun get(tier: String, kind: String, id: String): Meta? {
        val m = idx["$tier:$kind:$id"] ?: return null
        val f = fileOf(m)
        if (f.isFile && f.length() > 0) return m
        idx.remove(m.key)
        save()
        return null
    }

    /** The playback priority: a download beats a cached copy. */
    @Synchronized
    fun local(id: String, kind: String): Meta? = get("dl", kind, id) ?: get("cache", kind, id)

    @Synchronized
    fun byFile(kind: String, file: String): Meta? =
        idx.values.firstOrNull { it.tier == "dl" && it.kind == kind && it.file == file }

    @Synchronized
    fun all(tier: String): List<Meta> = idx.values.filter { it.tier == tier }

    @Synchronized
    fun everything(): List<Meta> = idx.values.toList()

    @Synchronized
    fun cacheBytes(): Long {
        var n = 0L
        for (m in idx.values) if (m.tier == "cache") n += m.size
        return n
    }

    // --------------------------------------------------------------- changes

    @Synchronized
    fun put(m: Meta) {
        idx[m.key] = m
        save()
    }

    @Synchronized
    fun drop(m: Meta, deleteFile: Boolean = true) {
        idx.remove(m.key)
        if (deleteFile) fileOf(m).delete()
        save()
    }

    /** Called each time a saved copy is played: feeds the "frequent" priority and the recency order. */
    @Synchronized
    fun touch(m: Meta) {
        m.plays++
        m.last = System.currentTimeMillis()
        save()
    }

    @Synchronized
    fun pin(m: Meta, on: Boolean) {
        m.pinned = on
        save()
    }

    /**
     * Brings the cache back under [limitBytes] (0 = no limit). Order: not pinned, "recent" before
     * "frequent", then least recently played. Anything played in the last 2 minutes is left alone
     * so the song you are hearing is never pulled away. Returns how many items were removed.
     */
    @Synchronized
    fun enforce(limitBytes: Long): Int {
        if (limitBytes <= 0L) return 0
        var used = cacheBytes()
        if (used <= limitBytes) return 0
        val now = System.currentTimeMillis()
        val victims = idx.values
            .filter { it.tier == "cache" && !it.pinned && now - it.last > RECENT_MS }
            .sortedWith(compareBy<Meta>({ it.prio }, { it.last }))
        var n = 0
        for (m in victims) {
            if (used <= limitBytes) break
            used -= m.size
            idx.remove(m.key)
            fileOf(m).delete()
            n++
        }
        if (n > 0) save()
        return n
    }

    companion object {
        private const val RECENT_MS = 2 * 60_000L

        @Volatile private var inst: Offline? = null

        fun get(ctx: Context): Offline =
            inst ?: synchronized(this) { inst ?: Offline(ctx.applicationContext).also { inst = it } }
    }
}
