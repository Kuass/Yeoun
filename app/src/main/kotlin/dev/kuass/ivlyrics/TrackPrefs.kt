package dev.kuass.ivlyrics

import android.content.Context
import org.json.JSONObject

/** Per-track overrides (sync offset, translation language), keyed like the service's track key. */
class TrackPrefs(ctx: Context) {
    companion object {
        const val NAME = "tracks"
        /** Length-prefixed fields, so a `|` inside a title or artist cannot collide with another track. */
        fun key(title: String, artist: String, durationSec: Long) = "${title.length}:$title|${artist.length}:$artist|$durationSec"
    }

    data class Entry(val offsetMs: Int = 0, val lang: String? = null, val videoId: String? = null) {
        val isDefault get() = offsetMs == 0 && lang == null && videoId == null
    }

    private val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(key: String): Entry = sp.getString(key, null)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()?.let { Entry(it.optInt("offset"), it.optString("lang").takeIf { l -> l.isNotEmpty() }, it.optString("video").takeIf { v -> v.isNotEmpty() }) }
    } ?: Entry()

    fun set(key: String, entry: Entry) {
        if (entry.isDefault) sp.edit().remove(key).apply()
        else sp.edit().putString(key, JSONObject().put("offset", entry.offsetMs).put("lang", entry.lang ?: "").put("video", entry.videoId ?: "").toString()).apply()
    }
}
