// SEITUNE - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Optional signed-in requests for the web-family player client (MWEB), from the cookies of a Google sign-in done in a WebView.
 * Inert until such cookies exist: no sign-in screen is part of this change, and the guest cookies GuestAuth harvests never hold SAPISID.
 *
 * Facts relied on (checked against yt-dlp's YouTube extractor and other open clients):
 *  - Authorization: SAPISIDHASH <unix seconds>_<hex SHA-1 of "<unix seconds> <SAPISID> <origin>">, origin = https://www.youtube.com
 *  - sent together with Cookie, X-Origin and X-Goog-AuthUser: 0
 *  - SAPISID is taken from the SAPISID cookie, or from __Secure-3PAPISID when that is all there is
 *  - yt-dlp documents "account cookies not supported" for the android and ios clients: never use this for them
 */
object YtAuth {
    private const val ORIGIN = "https://www.youtube.com"

    private fun jar(): String? = try { CookieManager.getInstance().getCookie(ORIGIN) } catch (_: Throwable) { null }

    private fun value(cookies: String, name: String): String? =
        cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private fun sapisid(cookies: String): String? = value(cookies, "SAPISID") ?: value(cookies, "__Secure-3PAPISID")

    /** True when the cookie jar holds a signed-in Google session. */
    fun signedIn(): Boolean = jar()?.let { sapisid(it) != null } ?: false

    fun sapisidHash(sapisid: String, origin: String = ORIGIN, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$nowSec $sapisid $origin".toByteArray(Charsets.UTF_8))
        return "SAPISIDHASH ${nowSec}_" + d.joinToString("") { "%02x".format(it) }
    }

    /** Adds the signed-in headers to [con] (before it is connected). False, and nothing added, when not signed in. */
    fun apply(con: HttpURLConnection): Boolean {
        val cookies = jar() ?: return false
        val sid = sapisid(cookies) ?: return false
        con.setRequestProperty("Cookie", cookies)
        con.setRequestProperty("Authorization", sapisidHash(sid))
        con.setRequestProperty("X-Origin", ORIGIN)
        con.setRequestProperty("X-Goog-AuthUser", "0")
        return true
    }
}
