package dev.kuass.ivlyrics

import android.util.Log
import org.json.JSONObject
import kotlin.math.abs

/** Resolves a track's ISRC through Deezer's public API (no key needed). */
object Isrc {
    private const val TAG = "Isrc"
    private const val SEARCH = "https://api.deezer.com/search"
    private const val TRACK = "https://api.deezer.com/track/"
    private const val DURATION_TOLERANCE_SEC = 4L
    private val VARIANT = Regex("(?i)remix|live|instrumental|acoustic|sped up|slowed|version|ver\\.|edit|karaoke")
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class Candidate(val id: Long, val title: String, val artist: String, val durationSec: Long)

    fun resolve(title: String, artist: String, durationSec: Long): String? {
        val key = "$title|$artist|$durationSec"
        memo[key]?.let { return it.ifEmpty { null } }
        val isrc = try { lookup(title, artist, durationSec) } catch (e: Exception) { Log.w(TAG, "lookup failed: ${e.message}"); null }
        memo[key] = isrc ?: ""
        return isrc
    }

    private fun lookup(title: String, artist: String, durationSec: Long): String? {
        val primaryArtist = artist.split(",").first().trim()
        val candidates = search("track:\"$title\" artist:\"$primaryArtist\"").ifEmpty { search("$title $primaryArtist") }
        val match = choose(candidates, title, primaryArtist, durationSec) ?: return null
        val track = Http.get("$TRACK${match.id}", 10_000)?.let(::JSONObject) ?: return null
        return track.optString("isrc").trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")) }
    }

    private fun search(q: String): List<Candidate> {
        val body = Http.get("$SEARCH?limit=10&q=${Http.enc(q)}", 10_000) ?: return emptyList()
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map {
            Candidate(it.optLong("id"), it.optString("title"), it.optJSONObject("artist")?.optString("name") ?: "", it.optLong("duration"))
        }
    }

    /** Title must match loosely; prefer the right duration, then non-variants, then the artist. */
    fun choose(candidates: List<Candidate>, title: String, artist: String, durationSec: Long): Candidate? {
        val t = norm(title)
        val a = norm(artist)
        val titled = candidates.filter { val c = norm(it.title); c.startsWith(t) || t.startsWith(c) }
        if (titled.isEmpty()) return null
        val wantVariant = VARIANT.containsMatchIn(title)
        return titled.sortedWith(compareBy(
            { if (durationSec <= 0 || abs(it.durationSec - durationSec) <= DURATION_TOLERANCE_SEC) 0 else 1 },
            { if (!wantVariant && VARIANT.containsMatchIn(it.title)) 1 else 0 },
            { if (a.isNotEmpty() && norm(it.artist).replace(" ", "").contains(a.replace(" ", ""))) 0 else 1 },
        )).first()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
}
