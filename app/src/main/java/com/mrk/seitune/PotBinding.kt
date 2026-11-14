// SEITUNE - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Which "content binding" a web BotGuard pot= needs on a player client's links, measured on the device and remembered.
 *
 * What YouTube does (documented in yt-dlp's PO Token Guide): a PO token is bound to a content id, either the video id or the
 * session's visitor id (or the account session id when signed in). It also says a token made by BotGuard (web) cannot be used on
 * Android or iOS, which have their own attestation. So for the ANDROID and IOS clients the expected answer is NONE. This object does
 * not trust that blindly: it measures once per client, on a link that is cut off after the first MB, and keeps the result.
 *
 *  - VID / VD : a pot= minted from the video id / the visitorData opened the cut-off range
 *  - NONE     : both bindings were minted, both links were still refused (confirmed by [NONE_VOTES] separate measurements).
 *               From then on no token is minted for that client's links. Measured again after [RETEST_NONE_MS].
 *  - UNKNOWN  : not measured yet, or the measurement was inconclusive (token not minted, 5xx, timeout, 416 ...). Never read as NONE.
 */
object PotBinding {
    enum class Kind { UNKNOWN, NONE, VID, VD }

    private const val RETEST_NONE_MS = 7L * 24 * 3600_000
    private const val RETRY_MS = 5 * 60_000L
    private const val NONE_VOTES = 2

    private class Entry(val kind: Kind, val at: Long)

    private val verdicts = ConcurrentHashMap<String, Entry>()
    private val votes = ConcurrentHashMap<String, Int>()
    private val lastTry = ConcurrentHashMap<String, Long>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("sei_pot", Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) {
            if (!k.startsWith("v.") || v !is String) continue
            val parts = v.split('@')
            val kd = try { Kind.valueOf(parts[0]) } catch (_: Throwable) { continue }
            verdicts[k.removePrefix("v.")] = Entry(kd, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        if (verdicts.isNotEmpty()) DebugLog.sei("pot", "remembered bindings: " + summary())
    }

    fun name(k: Kind) = when (k) { Kind.VID -> "video id"; Kind.VD -> "visitorData"; Kind.NONE -> "none (web tokens refused)"; Kind.UNKNOWN -> "unknown" }

    /** What is known for [client]. A NONE older than a week reads as UNKNOWN, so it is measured again. */
    fun kind(client: String): Kind {
        val e = verdicts[client] ?: return Kind.UNKNOWN
        if (e.kind == Kind.NONE && System.currentTimeMillis() - e.at > RETEST_NONE_MS) return Kind.UNKNOWN
        return e.kind
    }

    /** True when a measurement may start now: none running for this client, and the last inconclusive one is 5 minutes old. */
    fun mayLearn(client: String): Boolean =
        !busy.contains(client) && System.currentTimeMillis() - (lastTry[client] ?: 0L) >= RETRY_MS

    private fun set(client: String, k: Kind) {
        verdicts[client] = Entry(k, System.currentTimeMillis())
        votes.remove(client)
        try { prefs?.edit()?.putString("v.$client", k.name + "@" + System.currentTimeMillis())?.apply() } catch (_: Throwable) { }
        DebugLog.sei("pot", "$client: pot binding = ${name(k)} (saved)")
    }

    /** A learned binding stopped working: measure again next time. */
    fun forget(client: String) {
        verdicts.remove(client)
        votes.remove(client)
        try { prefs?.edit()?.remove("v.$client")?.apply() } catch (_: Throwable) { }
    }

    /** Set by hand (the PO token test for the web client). */
    fun record(client: String, k: Kind) = set(client, k)

    /**
     * Measures which binding opens a cut-off link. [candidates] are tried in order; [test] mints the token and probes the link:
     * true = the cut-off range now opens, false = still refused, null = could not tell. Returns the verdict (UNKNOWN when inconclusive
     * or when NONE is not yet confirmed). [force] = a deliberate manual test: ignores the retry pause, and one NONE is enough.
     */
    fun learn(client: String, candidates: List<Pair<Kind, String>>, force: Boolean = false, test: (Kind, String) -> Boolean?): Kind {
        if (!busy.add(client)) return kind(client)
        try {
            if (!force && !mayLearnIgnoringBusy(client)) return kind(client)
            var unsure = false
            for ((k, ident) in candidates) {
                if (ident.isEmpty()) { unsure = true; DebugLog.sei("pot", "$client: no ${name(k)} to mint from, binding not tested"); continue }
                val r = try { test(k, ident) } catch (_: Throwable) { null }
                DebugLog.sei("pot", "$client: pot minted from the ${name(k)} -> " + when (r) { true -> "range opens"; false -> "still refused"; null -> "inconclusive" })
                if (r == true) { set(client, k); return k }
                if (r == null) unsure = true
            }
            lastTry[client] = System.currentTimeMillis()
            if (unsure) return Kind.UNKNOWN
            val n = (votes[client] ?: 0) + 1
            votes[client] = n
            if (force || n >= NONE_VOTES) { set(client, Kind.NONE); return Kind.NONE }
            DebugLog.sei("pot", "$client: web tokens refused on the first measurement, confirming on another track before giving up on them")
            return Kind.UNKNOWN
        } finally {
            busy.remove(client)
        }
    }

    private fun mayLearnIgnoringBusy(client: String) = System.currentTimeMillis() - (lastTry[client] ?: 0L) >= RETRY_MS

    fun summary(): String {
        if (verdicts.isEmpty()) return "nothing measured yet"
        val now = System.currentTimeMillis()
        return verdicts.entries.sortedBy { it.key }.joinToString(", ") { (c, e) ->
            c + "=" + e.kind.name.lowercase() + " (" + (now - e.at) / 3_600_000 + "h ago)"
        }
    }

    fun json(): JSONArray {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for ((c, e) in verdicts.entries.sortedBy { it.key }) {
            arr.put(JSONObject().put("client", c).put("binding", e.kind.name).put("ageSec", (now - e.at) / 1000))
        }
        return arr
    }
}
