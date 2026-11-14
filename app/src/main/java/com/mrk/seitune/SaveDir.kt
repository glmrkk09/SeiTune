// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File

/**
 * The folder the user picked for finished downloads (Android's folder picker, which also lets them
 * create a new folder). Access is kept across restarts with a persisted permission.
 * The app keeps its own copy for the offline library; this adds a second copy in the chosen folder.
 */
object SaveDir {
    private const val PREFS = "mp_save"
    private const val KEY = "tree"
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last folder that was picked, even if its permission is gone (used to open the picker there). */
    fun last(c: Context): Uri? = prefs(c).getString(KEY, null)?.let { Uri.parse(it) }

    /** The picked folder, only if the app can still write to it. */
    fun get(c: Context): Uri? {
        val u = last(c) ?: return null
        val ok = c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
        return if (ok) u else null
    }

    fun set(c: Context, u: Uri) {
        val old = prefs(c).getString(KEY, null)
        prefs(c).edit().putString(KEY, u.toString()).apply()
        if (old != null && old != u.toString()) {
            try { c.contentResolver.releasePersistableUriPermission(Uri.parse(old), RW) } catch (_: Throwable) {}
        }
    }

    /** Display name of the picked folder, or "" when none. */
    fun name(c: Context): String {
        val u = get(c) ?: return ""
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(u, DocumentsContract.getTreeDocumentId(u))
            c.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** Finds (or creates) a sub-folder called [name] inside the picked folder. One at a time, so parallel downloads share it. */
    @Synchronized
    private fun subDir(c: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val r = c.contentResolver
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        r.query(
            kids,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use {
            while (it.moveToNext()) {
                if (it.getString(1) == name && it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(r, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    /**
     * Copies [src] into the folder as [name]. With [sub] the file goes into a sub-folder of that name
     * (created if needed), e.g. a playlist's own folder. Returns null on success, or a short error message.
     */
    fun save(c: Context, tree: Uri, src: File, name: String, sub: String? = null): String? {
        return try {
            val r = c.contentResolver
            var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            if (!sub.isNullOrBlank()) {
                parent = subDir(c, tree, parent, sub) ?: return "could not create the folder \"$sub\""
            }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase())
                ?: "application/octet-stream"
            val doc = DocumentsContract.createDocument(r, parent, mime, name)
                ?: return "could not create the file in that folder"
            val out = r.openOutputStream(doc, "w") ?: return "could not write to that folder"
            out.use { o -> src.inputStream().use { it.copyTo(o, 64 * 1024) } }
            null
        } catch (e: Throwable) {
            e.message ?: "copy failed"
        }
    }
}

object PublicSave {
    private const val PREFS = "mp_save"
    private const val KEY = "path"
    const val DEFAULT = "Music/SeiTune"
    const val ROOT = "/storage/emulated/0/"
    private val audioRoots = setOf("Music", "Podcasts", "Audiobooks", "Recordings")
    private val videoRoots = setOf("Movies", "DCIM", "Pictures")
    private val downloadRoots = setOf("Download", "Documents")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun path(c: Context): String = prefs(c).getString(KEY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT

    fun display(c: Context): String = ROOT + path(c) + "/"

    fun set(c: Context, raw: String): String? {
        var p = raw.trim().replace('\\', '/')
        if (p.startsWith(ROOT)) p = p.removePrefix(ROOT)
        p = p.trim('/')
        if (p.isEmpty()) {
            prefs(c).edit().remove(KEY).apply()
            return null
        }
        val parts = p.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.any { ch -> ch in "<>:\"|?*" || ch.code < 32 } }) return "Invalid folder name"
        if (parts[0] !in audioRoots + videoRoots + downloadRoots) return "Folder must start with Music, Download, Documents or Movies"
        prefs(c).edit().putString(KEY, p).apply()
        return null
    }

    fun save(c: Context, src: File, name: String, video: Boolean, sub: String?): String? {
        return try {
            val base = path(c)
            val first = base.substringBefore('/')
            val rest = if (base.contains('/')) base.substringAfter('/') else ""
            val tail = listOf(rest, sub ?: "").filter { it.isNotBlank() }.joinToString("/")
            val root = when {
                first in downloadRoots -> first
                video -> if (first in videoRoots) first else "Movies"
                else -> if (first in audioRoots) first else "Music"
            }
            val rel = if (tail.isEmpty()) root else "$root/$tail"
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase())
                ?: if (video) "video/mp4" else "audio/mp4"
            if (android.os.Build.VERSION.SDK_INT >= 29) saveScoped(c, src, name, mime, video, root, rel)
            else saveLegacy(c, src, name, rel)
        } catch (e: Throwable) {
            e.message ?: "copy failed"
        }
    }

    private fun saveScoped(c: Context, src: File, name: String, mime: String, video: Boolean, root: String, rel: String): String? {
        val r = c.contentResolver
        val coll = when {
            root in downloadRoots -> android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            video -> android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relDir = "$rel/"
        r.query(
            coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, relDir), null
        )?.use { if (it.moveToFirst()) return null }
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(coll, v) ?: return "could not create the file in $relDir"
        try {
            val out = r.openOutputStream(uri, "w") ?: throw java.io.IOException("could not write to $relDir")
            out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
            val done = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }
            r.update(uri, done, null, null)
        } catch (e: Throwable) {
            try { r.delete(uri, null, null) } catch (_: Throwable) { }
            throw e
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(c: Context, src: File, name: String, rel: String): String? {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), rel)
        if (!dir.isDirectory && !dir.mkdirs()) return "could not create $rel"
        val dst = File(dir, name)
        if (dst.exists()) return null
        src.copyTo(dst, false)
        android.media.MediaScannerConnection.scanFile(c, arrayOf(dst.absolutePath), null, null)
        return null
    }
}
