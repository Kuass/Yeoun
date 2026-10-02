package dev.kuass.ivlyrics

import android.util.Log
import org.json.JSONObject

/**
 * LyricsPlus v2 API (github.com/ibratabian17/lyricsplus): aggregates Apple Music, Musixmatch and others.
 * Two public hosts are tried in turn; the payload is `lyrics: [{time, duration, text}]` with times in ms.
 */
object LyricsPlus {
    private const val TAG = "LyricsPlus"
    private val BASES = listOf("https://lyricsplus.prjktla.my.id", "https://lyrics.geeked.wtf")
    const val ID = "lyricsplus"

    fun fetch(title: String, artist: String, album: String, durationSec: Long): Lyrics {
        val query = "title=${Http.enc(title)}&artist=${Http.enc(artist)}" +
            (if (album.isNotBlank()) "&album=${Http.enc(album)}" else "") +
            (if (durationSec > 0) "&duration=$durationSec" else "")
        for (base in BASES) {
            try {
                val body = Http.get("$base/v2/lyrics/get?$query", 10_000) ?: continue
                val got = parse(body, durationSec)
                if (!got.isEmpty) return got
            } catch (e: Exception) {
                Log.w(TAG, "$base failed for $artist - $title: ${e.message}")
            }
        }
        return Lyrics.NONE
    }

    fun parse(body: String, durationSec: Long): Lyrics {
        val document = JSONObject(body)
        val arr = document.optJSONArray("lyrics") ?: return Lyrics.NONE
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val time = o.milliseconds("time")
            val syl = o.optJSONArray("syllabus")?.let { sa ->
                val syllables = mutableListOf<Syl>()
                for (i in 0 until sa.length()) {
                    val s = sa.optJSONObject(i) ?: return@let null
                    val text = s.optString("text")
                    if (text.isEmpty()) continue
                    // Partial karaoke metadata must never replace a complete line with only some of its words.
                    val t = s.milliseconds("time") ?: return@let null
                    val duration = (s.milliseconds("duration") ?: 1L).coerceAtLeast(1L)
                    syllables += Syl(t, t + minOf(duration, Long.MAX_VALUE - t), text)
                }
                syllables
            }?.takeIf { it.isNotEmpty() }
            // Syllables define the text when present so highlight offsets stay exact; otherwise the plain line text.
            val fromSyl = syl?.let { Lrc.lineFromSyllables(time ?: 0L, it) }
            val text = fromSyl?.text ?: o.optString("text").trim()
            if (text.isEmpty()) null else Triple(time, text, fromSyl?.syllables)
        }
        if (items.isEmpty()) return Lyrics.NONE
        // Unsynced providers still emit numeric zero timestamps. Incomplete timing must retain all text,
        // rather than dropping untimed lines or claiming that placeholder times are synchronized lyrics.
        val synced = !document.optString("type").equals("None", ignoreCase = true) && items.all { it.first != null }
        return if (synced) Lyrics(items.map { LrcLine(it.first!!, it.second, it.third) }.sortedBy { it.timeMs }, true, ID)
        else Lyrics(LrcLib.spread(items.joinToString("\n") { it.second }, durationSec), false, ID)
    }

    private fun JSONObject.milliseconds(name: String): Long? = optDouble(name, Double.NaN)
        .takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
}
