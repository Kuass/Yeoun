package dev.kuass.ivlyrics

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Finds a YouTube video for a track: a manual per-track choice first, otherwise YouTube Data API search when a key is set. */
object VideoMatch {
    private const val TAG = "VideoMatch"
    private const val SEARCH = "https://www.googleapis.com/youtube/v3/search"
    private val memo = ConcurrentHashMap<String, String>()
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_ID = Regex("(?:v=|/shorts/|/embed/|/live/|youtu\\.be/)([A-Za-z0-9_-]{11})")

    /** Video id from a pasted YouTube URL or a bare id; null when unrecognizable. */
    fun parseVideoId(input: String): String? {
        val s = input.trim()
        if (ID.matches(s)) return s
        return URL_ID.find(s)?.groupValues?.get(1)
    }

    fun search(apiKey: String, title: String, artist: String): String? {
        if (apiKey.isBlank()) return null
        val key = "$title|$artist"
        memo[key]?.let { return it.ifEmpty { null } }
        val q = "$artist $title official"
        val found = try {
            val url = "$SEARCH?part=snippet&type=video&videoCategoryId=10&maxResults=5&videoEmbeddable=true&q=${Http.enc(q)}&key=${Http.enc(apiKey)}"
            val body = Http.get(url, 10_000, nullOn = setOf(403, 404))
            body?.let(::JSONObject)?.optJSONArray("items")?.let { items ->
                (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("id")?.optString("videoId")?.takeIf { id -> ID.matches(id) } }
            }?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "search failed for $q: ${e.message}"); null
        }
        memo[key] = found ?: ""
        return found
    }
}
