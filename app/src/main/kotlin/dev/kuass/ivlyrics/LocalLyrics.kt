package dev.kuass.ivlyrics

import android.content.Context

/** Hand-timed lyrics made on this device, stored as LRC per track key. They beat every online source. */
class LocalLyrics(ctx: Context) {
    companion object { const val ID = "local"; private const val NAME = "local_lyrics" }
    private val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(key: String): String? = sp.getString(key, null)
    fun set(key: String, lrc: String) = sp.edit().putString(key, lrc).apply()
    fun remove(key: String) = sp.edit().remove(key).apply()

    fun lyrics(key: String): Lyrics? = get(key)?.let { Lrc.parse(it) }?.takeIf { it.isNotEmpty() }?.let { Lyrics(it, true, ID) }
}

/** Builds an LRC document from line texts and their start times; untimed lines are dropped. */
object LrcWriter {
    fun write(lines: List<String>, timesMs: List<Long?>): String =
        lines.indices.filter { timesMs.getOrNull(it) != null }.map { i -> "[${stamp(timesMs[i]!!)}]${lines[i]}" }.joinToString("\n")

    fun stamp(rawMs: Long): String {
        val ms = rawMs.coerceAtLeast(0)
        val m = ms / 60000; val s = (ms / 1000) % 60; val cs = (ms % 1000) / 10
        return String.format(java.util.Locale.US, "%02d:%02d.%02d", m, s, cs)
    }
}
