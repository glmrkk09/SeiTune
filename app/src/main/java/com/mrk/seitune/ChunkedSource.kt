// SEITUNE - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.media.MediaDataSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Reads one YouTube media link the way the downloader does: in small bounded Range pieces, with retries.
 * YouTube refuses (403) or throttles the plain open-ended request that MediaPlayer and a WebView send,
 * but serves bounded pieces fast. That is why the picture failed while the same link downloaded fine.
 */
class Chunks(firstUrl: String, firstUa: String) {
    // the link and its user agent can be swapped for a fresh one when YouTube cuts the old one off mid-song
    @Volatile private var url = firstUrl
    @Volatile private var ua = firstUa
    @Volatile private var refreshes = 0
    @Volatile private var total = -1L
    @Volatile private var cancelled = false
    private val live: MutableSet<HttpURLConnection> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>())

    /**
     * Stops this reader at once: every request in flight is cut (so a track that was left stops using the network
     * immediately) and no new one is started. Called when the track is switched away from.
     */
    fun cancel() {
        cancelled = true
        for (c in live.toList()) try { c.disconnect() } catch (_: Throwable) { }
        live.clear()
    }

    private fun checkLive() { if (cancelled) throw IOException("cancelled") }

    /** Whole size of the file in bytes (one tiny request, remembered). */
    fun size(): Long {
        if (total > 0) return total
        var last: Exception? = null
        for (t in 0 until 3) {
            try {
                checkLive()
                val con = URL(url).openConnection() as HttpURLConnection
                live.add(con)
                try {
                    checkLive() // cancel() may have run between creating the connection and registering it
                    con.setRequestProperty("User-Agent", ua)
                    con.setRequestProperty("Range", "bytes=0-0")
                    con.connectTimeout = 8000
                    con.readTimeout = 10000
                    val code = con.responseCode
                    if (code !in 200..299) throw IOException("media HTTP $code")
                    val n = con.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                        ?: if (code == 200) con.getHeaderField("Content-Length")?.toLongOrNull() else null
                    if (n == null || n <= 0) throw IOException("unknown size")
                    total = n
                    return n
                } finally {
                    live.remove(con)
                    con.disconnect()
                }
            } catch (e: Exception) {
                last = e
                if (cancelled) break
                try { Thread.sleep(250L * (t + 1)) } catch (_: InterruptedException) { break }
            }
        }
        if (cancelled) throw IOException("cancelled")
        DebugLog.add("chunks", "size failed (${url.substringBefore('?').takeLast(40)})", last ?: IOException("size failed"))
        throw IOException(last?.message ?: "size failed")
    }

    /** Up to [len] bytes from [start] (fewer at the end of the file). */
    fun fetch(start: Long, len: Int): ByteArray {
        var last: Exception? = null
        for (t in 0 until 4) {
            // attempts 1 and 3 ask the way yt-dlp does (range=a-b inside the link, no Range header): some links refuse the header after a few MB
            val viaParam = t % 2 == 1
            try {
                checkLive()
                val link = if (viaParam) url + (if (url.contains('?')) "&" else "?") + "range=$start-${start + len - 1}" else url
                val con = URL(link).openConnection() as HttpURLConnection
                live.add(con)
                try {
                    checkLive() // cancel() may have run between creating the connection and registering it
                    con.setRequestProperty("User-Agent", ua)
                    if (!viaParam) con.setRequestProperty("Range", "bytes=$start-${start + len - 1}")
                    con.connectTimeout = 8000
                    con.readTimeout = 12000
                    val code = con.responseCode
                    if (code != 206 && !(code == 200 && (start == 0L || viaParam))) throw IOException("media HTTP $code" + if (viaParam) " (range param)" else "")
                    val out = ByteArrayOutputStream(len)
                    val buf = ByteArray(32 * 1024)
                    con.inputStream.use { ins ->
                        while (out.size() < len) {
                            val n = ins.read(buf, 0, minOf(buf.size, len - out.size()))
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    if (out.size() == 0) throw IOException("empty piece")
                    return out.toByteArray()
                } finally {
                    live.remove(con)
                    con.disconnect()
                }
            } catch (e: Exception) {
                last = e
                if (cancelled) throw IOException("cancelled") // switched away: no retries, no 403 report
                // both ways (Range header, then range= parameter) were refused with 403: more tries cannot help, it is the link itself
                if (t >= 1 && (e.message ?: "").contains("HTTP 403")) break
                try { Thread.sleep(250L * (t + 1)) } catch (_: InterruptedException) { break }
            }
        }
        DebugLog.add("chunks", "bytes from $start failed", last ?: IOException("fetch failed"))
        // a 403 after the first piece = this link is cut off (no PO token): the app drops it and avoids that client for this track
        if (start > 0 && (last?.message ?: "").contains("HTTP 403")) {
            try { onForbidden?.invoke(url, start) } catch (_: Throwable) { }
            // instead of letting the song die here, get a fresh link for the same file and continue from this very byte
            if (!cancelled && swapLink()) return fetch(start, len)
        }
        throw IOException(last?.message ?: "fetch failed")
    }

    /**
     * Asks the app for a new link of the same track (another player client). It is only used when it points at the very same
     * file (same size in bytes), so the byte positions already played stay valid. At most twice per reader.
     */
    private fun swapLink(): Boolean {
        val r = refresher ?: return false
        if (refreshes >= 2) return false
        refreshes++
        val fresh = try { r(url) } catch (_: Throwable) { null } ?: return false
        if (cancelled) return false
        val oldUrl = url; val oldUa = ua; val oldTotal = total
        url = fresh.first; ua = fresh.second; total = -1L
        val n = try { size() } catch (_: Exception) { -1L }
        if (n > 0 && (oldTotal <= 0 || n == oldTotal)) {
            DebugLog.add("chunks", "carried on with a fresh link (same file, $n bytes)")
            return true
        }
        url = oldUrl; ua = oldUa; total = oldTotal
        DebugLog.add("chunks", "fresh link is a different file ($n vs $oldTotal bytes), not used")
        return false
    }

    companion object {
        const val PIECE = 1 shl 20 // 1 MB
        /** Set by Api: called when a link answers 403 for a piece after the first one. */
        @Volatile var onForbidden: ((String, Long) -> Unit)? = null
        /** Set by Api: returns a new (link, user agent) for the same track, or null. May take several seconds. */
        @Volatile var refresher: ((String) -> Pair<String, String>?)? = null
    }
}

/** For the native MediaPlayer: pieces are fetched one ahead, so playing starts fast and never stalls on one slow request. */
class YtDataSource(private val c: Chunks) : MediaDataSource() {
    private val blocks = HashMap<Long, Future<ByteArray>>()
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r).also { it.isDaemon = true } }
    @Volatile private var closed = false

    override fun getSize(): Long = c.size()

    private fun submit(idx: Long): Future<ByteArray> = synchronized(blocks) {
        if (closed) throw IOException("closed")
        blocks.getOrPut(idx) { pool.submit(Callable { c.fetch(idx * Chunks.PIECE, Chunks.PIECE) }) }
    }

    private fun block(idx: Long, total: Long): ByteArray {
        val f = submit(idx)
        if ((idx + 1) * Chunks.PIECE < total) submit(idx + 1)
        if ((idx + 2) * Chunks.PIECE < total) submit(idx + 2) // two pieces ahead: one slow request no longer stalls the sound
        synchronized(blocks) { blocks.keys.removeAll { it < idx - 1 || it > idx + 3 } }
        return try {
            f.get()
        } catch (e: ExecutionException) {
            synchronized(blocks) { blocks.remove(idx) }
            throw IOException(e.cause?.message ?: "read failed")
        } catch (e: InterruptedException) {
            throw IOException("closed")
        } catch (e: java.util.concurrent.CancellationException) {
            throw IOException("closed")
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("closed")
        val total = c.size()
        if (position >= total) return -1
        var pos = position
        var done = 0
        while (done < size && pos < total) {
            val idx = pos / Chunks.PIECE
            val b = block(idx, total)
            val inBlk = (pos - idx * Chunks.PIECE).toInt()
            val n = minOf(size - done, b.size - inBlk)
            if (n <= 0) break
            System.arraycopy(b, inBlk, buffer, offset + done, n)
            done += n
            pos += n
        }
        return if (done == 0) -1 else done
    }

    /** Immediate stop: the HTTP requests in flight are cut, queued pieces are dropped, nothing new starts. Safe to call twice. */
    override fun close() {
        if (closed) return
        closed = true
        c.cancel()
        pool.shutdownNow()
        synchronized(blocks) {
            for (f in blocks.values) f.cancel(true)
            blocks.clear()
        }
    }
}

/** For the page's <video> through /api/media: the same bounded pieces, read lazily from [start]. */
class ChunkStream(private val c: Chunks, start: Long, private val end: Long) : InputStream() {
    private var pos = start
    private var buf = ByteArray(0)
    private var at = 0

    private fun fill(): Boolean {
        if (pos > end) return false
        val want = minOf((end - pos + 1), Chunks.PIECE.toLong()).toInt()
        buf = c.fetch(pos, want)
        at = 0
        pos += buf.size
        return buf.isNotEmpty()
    }

    override fun read(): Int {
        if (at >= buf.size && !fill()) return -1
        return buf[at++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (at >= buf.size && !fill()) return -1
        val n = minOf(len, buf.size - at)
        System.arraycopy(buf, at, b, off, n)
        at += n
        return n
    }
}
