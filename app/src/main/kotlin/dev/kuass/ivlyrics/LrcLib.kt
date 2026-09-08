package dev.kuass.ivlyrics

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class Lyrics(val lines: List<LrcLine>, val synced: Boolean, val source: String = "") {
    val isEmpty get() = lines.isEmpty()
    val hasSyllables get() = lines.any { !it.syllables.isNullOrEmpty() }
    companion object { val NONE = Lyrics(emptyList(), false) }
}

/** Lyrics from lrclib.net: synced when available, otherwise plain text spread over the track duration. */
object LrcLib {
    private const val TAG = "LrcLib"
    private const val BASE = "https://lrclib.net/api"
    private const val DURATION_TOLERANCE_SEC = 3L
    const val ID = "lrclib"

    data class Candidate(val durationSec: Long, val synced: String?, val plain: String?)

    data class SearchHit(val id: Long, val title: String, val artist: String, val album: String, val candidate: Candidate)

    fun searchQuery(query: String): List<SearchHit> {
        require(query.isNotBlank())
        return parseSearch(Http.get("$BASE/search?q=${Http.enc(query.trim())}") ?: "[]")
    }

    fun parseSearch(body: String): List<SearchHit> {
        val array = JSONArray(body)
        return (0 until minOf(array.length(), 100)).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val value = candidate(row)
            if (value.synced == null && value.plain == null) return@mapNotNull null
            SearchHit(row.optLong("id"), row.optString("trackName"), row.optString("artistName"), row.optString("albumName"), value)
        }
    }

    fun fetch(title: String, artist: String, album: String, durationSec: Long): Lyrics = try {
        val exact = Http.get("$BASE/get?" + query(title, artist, album, durationSec))?.let { candidate(JSONObject(it)) }
        val searched = if (exact?.synced == null) search(title, artist, durationSec) else null
        val best = exact?.takeIf { it.synced != null }
            ?: searched?.takeIf { it.synced != null }
            ?: exact?.takeIf { it.plain != null }
            ?: searched
        toLyrics(best, durationSec)
    } catch (e: Exception) {
        // IOException from the network, JSONException from a non-JSON 200 body (CDN/maintenance page).
        Log.w(TAG, "lyrics fetch failed for $artist - $title", e)
        Lyrics.NONE
    }

    fun toLyrics(c: Candidate?, durationSec: Long): Lyrics = when {
        c?.synced != null -> Lyrics(Lrc.parse(c.synced), true, ID)
        c?.plain != null -> Lyrics(spread(c.plain, durationSec), false, ID)
        else -> Lyrics.NONE
    }

    /** ponytail: unsynced text gets evenly spaced timestamps so the synced renderer can show it; no real timing. */
    fun spread(plain: String, durationSec: Long): List<LrcLine> {
        val texts = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return emptyList()
        val stepMs = if (durationSec > 0) durationSec * 1000 / texts.size else 4000L
        return texts.mapIndexed { i, t -> LrcLine(i * stepMs, t) }
    }

    private fun search(title: String, artist: String, durationSec: Long): Candidate? {
        val body = Http.get("$BASE/search?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}") ?: return null
        val arr = JSONArray(body)
        return choose((0 until arr.length()).map { candidate(arr.getJSONObject(it)) }, durationSec)
    }

    /** Synced candidate within tolerance of [durationSec] first, then a plain one; any when duration is unknown. */
    fun choose(candidates: List<Candidate>, durationSec: Long): Candidate? {
        val near = candidates.filter { durationSec <= 0 || abs(it.durationSec - durationSec) <= DURATION_TOLERANCE_SEC }
        return near.firstOrNull { it.synced != null } ?: near.firstOrNull { it.plain != null }
    }

    private fun candidate(o: JSONObject) = Candidate(
        o.optDouble("duration", 0.0).toLong(),
        o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() && Lrc.parse(it).any { line -> line.text.isNotBlank() } },
        o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() && !o.optBoolean("instrumental") },
    )

    private fun query(title: String, artist: String, album: String, durationSec: Long) =
        "track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}&album_name=${Http.enc(album)}&duration=$durationSec"
}
