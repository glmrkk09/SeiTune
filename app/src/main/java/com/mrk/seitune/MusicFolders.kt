// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Music folders the user imported with Android's folder picker (no storage permission needed).
 * The files stay where they are: the app only keeps the folder's permission and an index of the songs
 * in it. Playback reads them through the content resolver, so nothing is copied and no space is used.
 */
class MusicFolders private constructor(private val ctx: Context) {

    class Root(val id: String, val uri: Uri, val name: String)

    class Track(
        val id: String, val uri: Uri, val name: String, @Volatile var title: String,
        @Volatile var artist: String, @Volatile var duration: String, val size: Long,
        val folder: String, val tree: String, @Volatile var metaDone: Boolean
    ) {
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
    }

    private val file = File(ctx.filesDir, "import-index.json")
    private val roots = ArrayList<Root>()
    private val tracks = LinkedHashMap<String, Track>()
    private val scanning = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.priority = Thread.MIN_PRIORITY } }

    init { load() }

    val isScanning: Boolean get() = scanning.get()

    // ---------------------------------------------------------------- queries

    @Synchronized fun rootList(): List<Root> = roots.toList()

    @Synchronized fun trackList(): List<Track> = tracks.values.toList()

    @Synchronized fun track(id: String?): Track? = if (id == null) null else tracks[id]

    // ---------------------------------------------------------------- changes

    /** Adds a folder (permission already taken by the activity) and scans it in the background. */
    fun add(tree: Uri) {
        synchronized(this) {
            if (roots.none { it.uri == tree }) roots.add(Root(hash(tree.toString()), tree, rootName(tree)))
            save()
        }
        scan()
    }

    fun remove(rootId: String) {
        synchronized(this) {
            val r = roots.firstOrNull { it.id == rootId } ?: return
            roots.remove(r)
            tracks.values.removeAll { it.tree == r.id }
            // SaveDir may use the same folder for downloads: keep its permission then
            if (SaveDir.last(ctx) != r.uri) {
                try {
                    ctx.contentResolver.releasePersistableUriPermission(r.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) { }
            }
            save()
        }
    }

    /** Lists every audio file in every imported folder, then reads tags (title, artist, length). */
    fun scan() {
        if (!scanning.compareAndSet(false, true)) return
        exec.execute {
            try {
                val old = synchronized(this) { tracks.toMap() }
                val found = LinkedHashMap<String, Track>()
                for (r in rootList()) {
                    try {
                        walk(r, DocumentsContract.getTreeDocumentId(r.uri), r.name, 0, old, found)
                    } catch (_: Throwable) { /* permission gone or folder deleted: it just shows 0 songs */ }
                }
                synchronized(this) {
                    tracks.clear()
                    found.values.sortedWith(compareBy<Track>({ it.folder.lowercase() }, { it.title.lowercase() }))
                        .forEach { tracks[it.id] = it }
                    save()
                }
                readTags()
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun walk(r: Root, docId: String, folder: String, depth: Int, old: Map<String, Track>, out: MutableMap<String, Track>) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(r.uri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
        )
        val dirs = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (name.startsWith(".")) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 12) dirs.add(id to name)
                    continue
                }
                if (!isAudio(name, mime)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(r.uri, id)
                val tid = hash(uri.toString())
                val prev = old[tid]
                out[tid] = prev ?: Track(
                    tid, uri, name, name.substringBeforeLast('.'), "", "",
                    c.getLong(3).coerceAtLeast(0L), folder, r.id, false
                )
            }
        }
        for ((id, name) in dirs) walk(r, id, name, depth + 1, old, out)
    }

    /** Tags are read after the list is shown, so a big folder appears at once and fills in by itself. */
    private fun readTags() {
        val todo = trackList().filter { !it.metaDone }
        if (todo.isEmpty()) return
        val mr = MediaMetadataRetriever()
        var n = 0
        try {
            for (t in todo) {
                try {
                    mr.setDataSource(ctx, t.uri)
                    mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { t.title = it }
                    mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }?.let { t.artist = it }
                    mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { t.duration = fmt(it) }
                } catch (_: Throwable) { /* unreadable tags: the file name is used */ }
                t.metaDone = true
                if (++n % 40 == 0) synchronized(this) { save() }
            }
        } finally {
            try { mr.release() } catch (_: Throwable) { }
            synchronized(this) { save() }
        }
    }

    // ---------------------------------------------------------------- storage

    private fun load() {
        try {
            if (!file.isFile) return
            val o = JSONObject(file.readText())
            val f = o.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until f.length()) {
                val x = f.getJSONObject(i)
                val u = Uri.parse(x.getString("uri"))
                roots.add(Root(hash(u.toString()), u, x.optString("name")))
            }
            val t = o.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until t.length()) {
                val x = t.getJSONObject(i)
                val u = Uri.parse(x.getString("uri"))
                val id = hash(u.toString())
                tracks[id] = Track(
                    id, u, x.optString("name"), x.optString("title"), x.optString("artist"),
                    x.optString("duration"), x.optLong("size"), x.optString("folder"),
                    x.optString("tree"), x.optBoolean("meta")
                )
            }
        } catch (_: Throwable) {
            roots.clear(); tracks.clear()
        }
    }

    @Synchronized
    private fun save() {
        try {
            val f = JSONArray()
            for (r in roots) f.put(JSONObject().put("uri", r.uri.toString()).put("name", r.name))
            val t = JSONArray()
            for (x in tracks.values) {
                t.put(
                    JSONObject().put("uri", x.uri.toString()).put("name", x.name).put("title", x.title)
                        .put("artist", x.artist).put("duration", x.duration).put("size", x.size)
                        .put("folder", x.folder).put("tree", x.tree).put("meta", x.metaDone)
                )
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("folders", f).put("tracks", t).toString())
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Throwable) { }
    }

    private fun rootName(tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Music"
    } catch (_: Throwable) {
        "Music"
    }

    companion object {
        private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mka")

        fun isAudio(name: String, mime: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in AUDIO || mime.startsWith("audio/")

        private fun hash(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        private fun fmt(ms: Long): String {
            val s = ms / 1000
            return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
        }

        @Volatile private var inst: MusicFolders? = null

        fun get(ctx: Context): MusicFolders =
            inst ?: synchronized(this) { inst ?: MusicFolders(ctx.applicationContext).also { inst = it } }
    }
}
