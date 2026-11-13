// SEITUNE for Android - Copyright (C) 2026 Glmrk. GNU GPL v3.
package com.mrk.seitune

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Last resort when a song has no lyrics anywhere: the song itself is listened to, on the phone, and the words are written
 * down with their times as an LRC file. Uses Vosk (offline speech recognition) with its small English model, which is
 * downloaded once (~40 MB) the first time it is needed. Sung vocals over loud music are hard for any recogniser, so
 * the result is a best effort that follows the song's timing, not a perfect transcript. Other languages are not covered.
 */
object Transcribe {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val RATE = 16000
    private const val MAX_SECONDS = 15 * 60

    @Volatile private var model: Model? = null

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-model")

    private fun ensureModel(ctx: Context): Model {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val dir = modelDir(ctx)
            if (!File(dir, "am").isDirectory) download(ctx, dir)
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            return Model(dir.absolutePath).also { model = it }
        }
    }

    /** One-time download of the speech model; the zip's top folder is dropped so the files land straight in [dir]. */
    private fun download(ctx: Context, dir: File) {
        val tmp = File(ctx.filesDir, "vosk-model.tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        DebugLog.add("lyrics", "downloading the speech model (one time, ~40 MB)")
        val con = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10000
            con.readTimeout = 20000
            if (con.responseCode !in 200..299) throw java.io.IOException("model HTTP ${con.responseCode}")
            ZipInputStream(con.inputStream.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue // zip-slip guard
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
        } finally { con.disconnect() }
        if (!File(tmp, "am").isDirectory) { tmp.deleteRecursively(); throw java.io.IOException("speech model incomplete") }
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw java.io.IOException("could not install the speech model")
    }

    private class Word(val w: String, val start: Double)

    /** LRC text for the audio file, or null when nothing intelligible was heard. Slow (CPU work): call from a background thread. */
    fun lrcFor(ctx: Context, audio: File): String? {
        val rec = Recognizer(ensureModel(ctx), RATE.toFloat())
        rec.setWords(true)
        val words = ArrayList<Word>()
        fun take(json: String) {
            val arr = try { JSONObject(json).optJSONArray("result") } catch (_: Throwable) { null } ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val w = o.optString("word")
                if (w.isNotBlank()) words.add(Word(w, o.optDouble("start", 0.0)))
            }
        }
        try {
            val ex = MediaExtractor()
            ex.setDataSource(audio.absolutePath)
            var ti = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) return null
            ex.selectTrack(ti)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var step = srcRate.toDouble() / RATE
            var next = 0.0
            var frame = 0L
            val pcm = ByteArray(8000)
            var pn = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            val limit = MAX_SECONDS.toLong() * 1_000_000L
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > limit) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    step = srcRate.toDouble() / RATE
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = sb.remaining() / ch
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until ch) sum += sb.get(f * ch + c).toInt()
                        val s = (sum / ch).toShort().toInt()
                        while (next <= frame) { // down-mix to mono, then pick samples at 16 kHz
                            pcm[pn++] = (s and 0xff).toByte(); pcm[pn++] = ((s shr 8) and 0xff).toByte()
                            next += step
                            if (pn >= pcm.size) {
                                if (rec.acceptWaveForm(pcm, pn)) take(rec.result)
                                pn = 0
                            }
                        }
                        frame++
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (pn > 0 && rec.acceptWaveForm(pcm, pn)) take(rec.result)
            take(rec.finalResult)
            try { codec.stop(); codec.release(); ex.release() } catch (_: Throwable) { }
        } finally {
            try { rec.close() } catch (_: Throwable) { }
        }
        if (words.size < 8) return null
        // words -> lines: a new line after a pause of ~0.9 s or about 8 words
        val sb = StringBuilder()
        var line = ArrayList<Word>()
        fun flush() { if (line.isNotEmpty()) { sb.append(Lyrics.stamp((line[0].start * 1000).toLong())).append(line.joinToString(" ") { it.w }).append('\n'); line = ArrayList() } }
        var last = 0.0
        for (w in words) {
            if (line.isNotEmpty() && (w.start - last > 0.9 || line.size >= 8)) flush()
            line.add(w); last = w.start + 0.3
        }
        flush()
        return sb.toString().trim()
    }
}
