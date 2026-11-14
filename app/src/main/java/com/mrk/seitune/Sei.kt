// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * "sei": the app's own engine, shared by streaming, downloading and lyrics. Everything in here is native (no yt-dlp, no network of its own).
 *
 * Client health: every YouTube player client (ANDROID, IOS, ANDROID_VR, MWEB ...) gets a score. A client that fails three times in a row
 * is paused (a circuit breaker) so a lookup no longer waits on clients that are known to answer LOGIN_REQUIRED or to cut links off after
 * the first MB. The pause grows with every failed comeback (10, 20, 40, 60 min, with a little jitter), a success closes the breaker at once,
 * and only one trial at a time is let through after a pause.
 *
 * What is new compared to a plain breaker:
 *  - failures are classified (bot check, unplayable, cut off, network, other); a network failure is the phone's fault, not the client's,
 *    so it never counts toward a pause
 *  - clients are ranked by a score (success rate, recent streak, answer speed), best first
 *  - the state survives an app restart (a pause is not forgotten when the app is swiped away)
 *  - download helpers: exponential backoff with jitter, a measured download speed and the number of parallel pieces chosen from it
 */
object Sei {
    private const val OPEN_AFTER = 3
    private const val BASE_PAUSE_MS = 10 * 60_000L
    private const val MAX_PAUSE_MS = 60 * 60_000L
    private const val TRIAL_WINDOW_MS = 30_000L

    /** Why a client failed. */
    enum class Why {
        BOT, UNPLAYABLE, CUT, NETWORK, OTHER,
        STALE,  // the client version / session was refused ("page needs to be reloaded"): our side, fixed by reading them again, short pause
        VIDEO   // this one video is unavailable / private / blocked: says nothing about the client, no strike
    }

    fun classify(why: String): Why {
        val w = why.lowercase()
        return when {
            // our own side: no visitorData / no PO token could be made. Not the client's fault, so no strike (a bot check
            // answered to a request that carried no visitorData is the same: it is retried once a visitorData exists)
            "no visitordata" in w || "no po token" in w -> Why.NETWORK
            "needs to be reloaded" in w || "page needs" in w -> Why.STALE
            "video unavailable" in w || "private video" in w || "this video is not available" in w || "has been removed" in w ||
                "not available in your country" in w || "members-only" in w || "copyright" in w -> Why.VIDEO
            "not a bot" in w || "sign in" in w || "login_required" in w || "bot check" in w -> Why.BOT
            "unplayable" in w || "not available" in w || "unavailable" in w -> Why.UNPLAYABLE
            "unknownhost" in w || "unable to resolve" in w || "no address associated" in w || "timeout" in w || "timed out" in w ||
                "connection reset" in w || "connection refused" in w || "failed to connect" in w || "unreachable" in w ||
                "software caused connection abort" in w -> Why.NETWORK
            "cut off" in w || "link refused" in w || "403" in w || "410" in w || "forbidden" in w -> Why.CUT
            else -> Why.OTHER
        }
    }

    private class Health {
        var ok = 0
        var fail = 0
        var streak = 0          // failures in a row (network failures excluded)
        var trips = 0           // times the breaker opened since the last success: the pause grows with it
        var until = 0L          // paused until (epoch ms)
        var lastFail = 0L
        var lastOk = 0L
        var lastWhy = ""
        var lastKind = Why.OTHER
        var latency = 0.0       // moving average of the player answer, ms
        var trial = false
        var trialAt = 0L
    }

    private val clients = ConcurrentHashMap<String, Health>()
    @Volatile private var prefs: SharedPreferences? = null

    private fun health(name: String): Health = clients.getOrPut(name) { Health() }

    // ---------------------------------------------------------------------------------- persistence

    /** Call once at start: loads what was learned in earlier runs (pauses included). */
    fun init(ctx: Context) {
        try {
            val p = ctx.applicationContext.getSharedPreferences("sei", Context.MODE_PRIVATE)
            prefs = p
            // pauses saved by builds before the visitorData fix were mostly self-inflicted (a request sent without visitorData): start clean once
            if (p.getInt("ver", 0) < 2) { p.edit().remove("state").putInt("ver", 2).apply(); return }
            val raw = p.getString("state", null) ?: return
            val now = System.currentTimeMillis()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val h = health(o.getString("client"))
                synchronized(h) {
                    h.ok = o.optInt("ok"); h.fail = o.optInt("fail"); h.streak = o.optInt("streak"); h.trips = o.optInt("trips")
                    h.lastFail = o.optLong("lastFail"); h.lastOk = o.optLong("lastOk"); h.lastWhy = o.optString("why")
                    h.latency = o.optDouble("latency", 0.0)
                    h.lastKind = try { Why.valueOf(o.optString("kind", "OTHER")) } catch (_: Throwable) { Why.OTHER }
                    h.until = minOf(o.optLong("until"), now + (if (h.lastKind == Why.CUT) 10 * 60_000L else 2 * MAX_PAUSE_MS)) // pauses saved by an older build are shortened to today's limits
                }
            }
            mbps = p.getFloat("mbps", 0f).toDouble()
            DebugLog.sei("engine", "state loaded: " + summary())
        } catch (_: Throwable) { }
    }

    private fun save() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            for ((name, h) in clients.entries) synchronized(h) {
                arr.put(
                    JSONObject().put("client", name).put("ok", h.ok).put("fail", h.fail).put("streak", h.streak).put("trips", h.trips)
                        .put("until", h.until).put("lastFail", h.lastFail).put("lastOk", h.lastOk).put("why", h.lastWhy)
                        .put("kind", h.lastKind.name).put("latency", h.latency)
                )
            }
            p.edit().putString("state", arr.toString()).putFloat("mbps", mbps.toFloat()).apply()
        } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------------------------- breaker

    /** How long a client rests after it opened the breaker. It depends on why it failed: a cut-off link is often just that one file, a bot check or an outdated client lasts. */
    private fun pauseFor(trips: Int, kind: Why): Long {
        val (first, cap) = when (kind) {
            Why.CUT -> 90_000L to 10 * 60_000L
            Why.BOT -> BASE_PAUSE_MS to MAX_PAUSE_MS
            Why.UNPLAYABLE -> 10 * 60_000L to MAX_PAUSE_MS
            Why.STALE -> 2 * 60_000L to 15 * 60_000L
            else -> 3 * 60_000L to 30 * 60_000L
        }
        val base = minOf(cap, first shl (trips - 1).coerceIn(0, 4))
        return (base * (0.9 + Math.random() * 0.2)).toLong()
    }

    /** A client's link played ([ok]) or failed ([why]: login required, cut off after the first MB, no token ...). */
    fun report(client: String, ok: Boolean, why: String = "") {
        val h = health(client)
        var openedFor = 0L
        var changed = false
        val now = System.currentTimeMillis()
        synchronized(h) {
            if (ok) {
                changed = h.streak > 0 || h.trips > 0
                h.ok++; h.streak = 0; h.trips = 0; h.until = 0L; h.lastOk = now; h.trial = false
                nativeOps.incrementAndGet()
            } else {
                val kind = classify(why)
                h.fail++
                h.lastFail = now
                h.lastWhy = why.take(60)
                h.lastKind = kind
                h.trial = false
                if (kind != Why.NETWORK && kind != Why.VIDEO) { // the phone's connection, our own missing token and a single bad video are not the client's fault: no strike
                    h.streak++
                    if (h.streak >= OPEN_AFTER) {
                        h.trips++
                        openedFor = pauseFor(h.trips, kind)
                        h.until = now + openedFor
                        changed = true
                    }
                }
            }
        }
        if (openedFor > 0) DebugLog.sei("engine", "$client paused for ${openedFor / 60_000} min after $OPEN_AFTER failures in a row (${classify(why)}: $why)")
        if (changed) save()
    }

    /** The player answer of a client took [ms]: feeds the ranking. */
    fun latency(client: String, ms: Long) {
        val h = health(client)
        synchronized(h) { h.latency = if (h.latency == 0.0) ms.toDouble() else h.latency * 0.7 + ms * 0.3 }
    }

    /** False while a client is paused. After the pause, one trial at a time is let through. */
    fun allow(client: String): Boolean {
        val h = clients[client] ?: return true
        return synchronized(h) {
            if (h.streak < OPEN_AFTER) true
            else {
                val now = System.currentTimeMillis()
                if (now < h.until) false
                else if (h.trial && now - h.trialAt < TRIAL_WINDOW_MS) false
                else { h.trial = true; h.trialAt = now; true } // the trial: a failure opens a longer pause, a success closes the breaker
            }
        }
    }

    /** Never pause everything: when every client is paused, all are tried. */
    fun <T> usable(all: List<T>, nameOf: (T) -> String): List<T> = all.filter { allow(nameOf(it)) }.ifEmpty { all }

    /** 0..1, higher is better. A client never seen scores in the middle, so it still gets its chance. */
    fun score(name: String): Double {
        val h = clients[name] ?: return 0.5
        synchronized(h) {
            val n = h.ok + h.fail
            val rate = if (n == 0) 0.5 else (h.ok + 1.0) / (n + 2.0)
            val recent = Math.pow(0.5, h.streak.toDouble())
            val speed = if (h.latency > 0) 1.0 / (1.0 + h.latency / 2000.0) else 0.5
            return rate * recent * (0.8 + 0.2 * speed)
        }
    }

    /** The usable clients, best first. */
    fun <T> rank(all: List<T>, nameOf: (T) -> String): List<T> = usable(all, nameOf).sortedByDescending { score(nameOf(it)) }

    fun summary(): String {
        if (clients.isEmpty()) return "no data yet"
        val now = System.currentTimeMillis()
        return clients.entries.sortedBy { it.key }.joinToString(", ") { (name, h) ->
            synchronized(h) {
                name + " ok=" + h.ok + " fail=" + h.fail +
                    (if (h.streak >= OPEN_AFTER && now < h.until) " (paused ${(h.until - now) / 1000}s, ${h.lastKind.name.lowercase()})" else "") +
                    (if (h.latency > 0) " ${h.latency.toLong()}ms" else "")
            }
        } + ", level " + levelName() + (if (mbps > 0) ", speed ${"%.1f".format(mbps)} MB/s" else "")
    }

    fun json(): JSONObject {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for ((name, h) in clients.entries.sortedBy { it.key }) {
            synchronized(h) {
                arr.put(
                    JSONObject().put("client", name).put("ok", h.ok).put("fail", h.fail).put("streak", h.streak)
                        .put("paused", h.streak >= OPEN_AFTER && now < h.until).put("pausedSec", maxOf(0L, (h.until - now) / 1000))
                        .put("why", h.lastWhy).put("kind", h.lastKind.name).put("trips", h.trips)
                        .put("latencyMs", h.latency.toLong()).put("score", Math.round(score(name) * 100) / 100.0)
                )
            }
        }
        return JSONObject().put("clients", arr).put("level", level()).put("mbps", Math.round(mbps * 10) / 10.0).put("pot", PotBinding.json())
    }

    // ---------------------------------------------------------------------------------- usage

    private val nativeOps = AtomicInteger(0)
    private val ytRuns = AtomicInteger(0)

    /** One job done by the app's own code (a link that played, a native download). */
    fun countNative() { nativeOps.incrementAndGet() }

    /** One yt-dlp run started (a lookup, a download or a subtitle fetch). Returns Unit so it can sit in front of a call. */
    fun countYt() { ytRuns.incrementAndGet() }

    fun nativeCount(): Int = nativeOps.get()
    fun ytCount(): Int = ytRuns.get()

    // ---------------------------------------------------------------------------------- engine levels

    /**
     * The sei engine always works at its highest level (the settings choice was removed): every app client plus the PO-token web client,
     * rescue with on-device tokens, the most retries and link swaps, and the most download pieces at once.
     */
    fun level(): Int = 3

    fun levelName(): String = "max"

    /** Tries for one download piece before the file falls back to yt-dlp. */
    fun pieceTries(): Int = when (level()) { 1 -> 3; 3 -> 7; else -> 5 }

    /** How often a cut-off download may carry on with a fresh link. */
    fun maxSwaps(): Int = when (level()) { 1 -> 2; 3 -> 5; else -> 3 }

    // ---------------------------------------------------------------------------------- download helpers

    /** Wait before try number [attempt] (1 = first retry): 250 ms doubling up to 4 s, randomised so parallel pieces do not all retry at once. */
    fun backoffMs(attempt: Int): Long {
        val b = minOf(4000L, 250L shl (attempt - 1).coerceIn(0, 5))
        return b / 2 + (Math.random() * (b / 2)).toLong()
    }

    /** Runs [body] up to [times] times (null = failed, an exception counts as failed), with backoff in between. */
    fun <T> retry(times: Int, body: (Int) -> T?): T? {
        for (i in 1..times) {
            val r = try { body(i) } catch (_: Throwable) { null }
            if (r != null) return r
            if (i < times) try { Thread.sleep(backoffMs(i)) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    @Volatile private var mbps = 0.0

    /** A finished download of [bytes] took [ms]: the measured speed picks the number of parallel pieces next time. */
    fun reportSpeed(bytes: Long, ms: Long) {
        if (bytes < 256 * 1024L || ms <= 0) return
        val s = bytes / 1048576.0 / (ms / 1000.0)
        mbps = if (mbps == 0.0) s else mbps * 0.6 + s * 0.4
        save()
    }

    /** Parallel pieces for a file of [total] bytes: slow links get more (each request is throttled), fast links need fewer. */
    fun workers(total: Long): Int {
        val pieces = ((total + (1L shl 20) - 1) shr 20).toInt()
        val base = when {
            mbps == 0.0 -> 6
            mbps < 1.5 -> 8
            mbps < 6.0 -> 6
            else -> 4
        }
        val cap = when (level()) { 1 -> 3; 3 -> base + 2; else -> base }
        return minOf(base + (if (level() == 3) 2 else 0), cap).coerceIn(1, maxOf(1, pieces))
    }
}

/** Lyrics stored inside an MP3 (ID3v2 USLT frame). Reads only the tag at the start of the file, never the audio. */
object Id3 {
    private const val MAX_TAG = 4 * 1024 * 1024

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (from >= to) return ""
        val cs = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16 // with a byte order mark
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, from, to - from, cs)
    }

    /** The unsynchronised lyrics of the file, or "" when it has none (or is not an ID3v2.3 / 2.4 file). */
    fun lyrics(open: () -> InputStream?): String {
        val ins = try { open() } catch (_: Throwable) { null } ?: return ""
        return try {
            ins.use { s ->
                val head = ByteArray(10)
                if (readFully(s, head) < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return ""
                val ver = head[3].toInt()
                if (ver != 3 && ver != 4) return ""
                if ((head[5].toInt() and 0x80) != 0) return "" // whole-tag unsynchronisation: rare, not handled
                val size = syncsafe(head, 6)
                if (size <= 0 || size > MAX_TAG) return ""
                val tag = ByteArray(size)
                val got = readFully(s, tag)
                var p = 0
                while (p + 10 <= got) {
                    if (tag[p].toInt() == 0) break // padding
                    val id = String(tag, p, 4, Charsets.ISO_8859_1)
                    val fs = if (ver == 4) syncsafe(tag, p + 4) else be32(tag, p + 4)
                    val start = p + 10
                    if (fs <= 0 || start + fs > got) break
                    if (id == "USLT" && fs > 4) {
                        val enc = tag[start].toInt() and 0xff
                        var q = start + 4 // encoding + 3-byte language, then the descriptor
                        val wide = enc == 1 || enc == 2
                        while (q < start + fs) { // end of the descriptor
                            if (wide) { if (q + 1 < start + fs && tag[q].toInt() == 0 && tag[q + 1].toInt() == 0) { q += 2; break } else q += 2 }
                            else { if (tag[q].toInt() == 0) { q += 1; break } else q += 1 }
                        }
                        val text = decode(tag, q, start + fs, enc).replace("\r\n", "\n").replace('\r', '\n').trim()
                        if (text.isNotEmpty()) return text
                    }
                    p = start + fs
                }
                ""
            }
        } catch (_: Throwable) {
            ""
        }
    }
}
