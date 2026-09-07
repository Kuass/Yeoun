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
        val arr = JSONObject(body).optJSONArray("lyrics") ?: return Lyrics.NONE
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val time = if (o.has("time") && !o.isNull("time")) o.optDouble("time").toLong() else null
            val syl = o.optJSONArray("syllabus")?.let { sa ->
                (0 until sa.length()).mapNotNull { sa.optJSONObject(it) }.mapNotNull { s ->
                    val t = if (s.has("time") && !s.isNull("time")) s.optDouble("time").toLong() else return@mapNotNull null
                    val text = s.optString("text")
                    if (text.isEmpty()) null else Syl(t, t + maxOf(1L, s.optDouble("duration", 1.0).toLong()), text)
                }
            }?.takeIf { it.isNotEmpty() }
            // Syllables define the text when present so highlight offsets stay exact; otherwise the plain line text.
            val fromSyl = syl?.let { Lrc.lineFromSyllables(time ?: 0L, it) }
            val text = fromSyl?.text ?: o.optString("text").trim()
            if (text.isEmpty()) null else Triple(time, text, fromSyl?.syllables)
        }
        if (items.isEmpty()) return Lyrics.NONE
        val timed = items.filter { it.first != null }
        return if (timed.size >= items.size / 2) Lyrics(timed.map { LrcLine(it.first!!, it.second, it.third) }.sortedBy { it.timeMs }, true, ID)
        else Lyrics(LrcLib.spread(items.joinToString("\n") { it.second }, durationSec), false, ID)
    }
}
