package dev.kuass.ivlyrics

import android.util.Log
import org.json.JSONObject
import kotlin.math.abs

/**
 * Lyrically (Paxsenix) via its Apple Music catalog route: iTunes search picks the track, then the API returns
 * an LRC document. Carries original-script lyrics (e.g. Hangul) where LRCLIB often only has romanization.
 */
object Lyrically {
    private const val TAG = "Lyrically"
    private const val SEARCH = "https://itunes.apple.com/search"
    private const val LYRICS = "https://lyrics.paxsenix.org/apple-music/lyrics"
    private const val DURATION_TOLERANCE_MS = 4000L
    const val ID = "lyrically"

    data class Candidate(val id: String, val title: String, val artist: String, val durationMs: Long)

    fun fetch(title: String, artist: String, durationSec: Long): Lyrics = try {
        val term = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val body = Http.get("$SEARCH?term=${Http.enc(term)}&entity=song&limit=25") ?: return Lyrics.NONE
        val results = JSONObject(body).optJSONArray("results") ?: return Lyrics.NONE
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }.map {
            Candidate(it.optLong("trackId").toString(), it.optString("trackName"), it.optString("artistName"), it.optLong("trackTimeMillis"))
        }
        val match = choose(candidates, title, artist, durationSec * 1000) ?: return Lyrics.NONE
        val doc = Http.get("$LYRICS?id=${match.id}&v=2", 12000)?.let(::JSONObject) ?: return Lyrics.NONE
        parse(doc, durationSec)
    } catch (e: Exception) {
        Log.w(TAG, "lyrics fetch failed for $artist - $title", e)
        Lyrics.NONE
    }

    internal fun parse(doc: JSONObject, durationSec: Long): Lyrics {
        val timed = parseTimedLines(doc)
        val plain = doc.optString("plain").takeIf { !doc.isNull("plain") && it.isNotBlank() }
        return when {
            timed.isNotEmpty() -> Lyrics(timed, true, ID)
            plain != null -> Lyrics(LrcLib.spread(plain, durationSec), false, ID)
            else -> Lyrics.NONE
        }
    }

    /** Lines from the `lyrics` array with word/syllable timings; falls back to the `lrc` document when tokens are missing. */
    fun parseTimedLines(doc: JSONObject): List<LrcLine> {
        val arr = doc.optJSONArray("lyrics")
        val timingCandidates = mutableListOf<Long>()
        val lines = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.mapNotNull { line ->
            val start = line.optLong("timestamp", -1)
            if (start < 0) return@mapNotNull null
            val tokens = line.optJSONArray("text")
            val rawTokens = (0 until (tokens?.length() ?: 0)).mapNotNull { tokens!!.optJSONObject(it) }
            // Preserve timing eligibility before removing null text: cleanup must not turn a multi-line
            // zero-timestamp document into a supposedly synchronized singleton that hides its fallback.
            if (rawTokens.any { (it.has("text") && it.isNull("text")) || it.optString("text").isNotBlank() }) timingCandidates += start
            val syls = rawTokens.filterNot { it.isNull("text") }.map {
                Token(it.optString("text"), it.optLong("timestamp", start), it.optLong("endtime", start), it.optBoolean("part"))
            }
            Lrc.lineFromSyllables(start, joinTokens(syls))
        }.sortedBy { it.timeMs }
        // Unsynced documents (syncType null) still carry a `lyrics` array, with every timestamp at 0; treating that
        // as timing pins the overlay to the last line for the whole track. Only distinct times are real timing.
        if (lines.isNotEmpty() && timingCandidates.distinct().size >= minOf(timingCandidates.size, 2)) return lines
        return doc.optString("lrc").takeIf { it.isNotBlank() }?.let(Lrc::parse) ?: emptyList()
    }

    data class Token(val text: String, val startMs: Long, val endMs: Long, val part: Boolean)

    /** `part == true` means the next token continues the same word, so no space follows it. */
    fun joinTokens(tokens: List<Token>): List<Syl> = tokens.mapIndexed { i, t ->
        val sep = if (t.part || i == tokens.lastIndex) "" else " "
        Syl(t.startMs, t.endMs, t.text + sep)
    }

    /** Best iTunes hit: title must match loosely, prefer a duration within tolerance, then the artist match. */
    fun choose(candidates: List<Candidate>, title: String, artist: String, durationMs: Long): Candidate? {
        val t = norm(title)
        val a = norm(artist).split(",").first().trim()
        val titled = candidates.filter { norm(it.title).startsWith(t) || t.startsWith(norm(it.title)) }
        if (titled.isEmpty()) return null
        val wantVariant = VARIANT.containsMatchIn(title)
        return titled.sortedWith(compareBy(
            { if (durationMs <= 0 || abs(it.durationMs - durationMs) <= DURATION_TOLERANCE_MS) 0 else 1 },
            { if (!wantVariant && VARIANT.containsMatchIn(it.title)) 1 else 0 },
            { if (a.isNotEmpty() && norm(it.artist).contains(a)) 0 else 1 },
        )).first()
    }

    private val VARIANT = Regex("(?i)remix|live|instrumental|acoustic|sped up|slowed|version|edit|karaoke")

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
}
