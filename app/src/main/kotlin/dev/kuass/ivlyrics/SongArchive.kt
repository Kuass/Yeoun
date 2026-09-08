package dev.kuass.ivlyrics

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object SongArchiveCodec {
    data class Song(val track: Triple<String, String, Long>, val lyrics: Lyrics, val playedAt: Long)
    fun encode(song: Song): String = JSONObject().put("title", song.track.first).put("artist", song.track.second).put("duration", song.track.third)
        .put("playedAt", song.playedAt).put("synced", song.lyrics.synced).put("source", song.lyrics.source)
        .put("lines", JSONArray(song.lyrics.lines.map { line -> JSONObject().put("time", line.timeMs).put("text", line.text)
            .put("syllables", line.syllables?.let { JSONArray(it.map { syl -> JSONObject().put("start", syl.startMs).put("end", syl.endMs).put("text", syl.text) }) }) })).toString()
    fun decode(raw: String): Song {
        val json = JSONObject(raw); val lines = json.getJSONArray("lines")
        require(lines.length() in 1..10000)
        return Song(Triple(json.getString("title"), json.getString("artist"), json.getLong("duration")),
            Lyrics(List(lines.length()) { i ->
                val line = lines.getJSONObject(i); val syllables = line.optJSONArray("syllables")
                LrcLine(line.getLong("time"), line.getString("text"), syllables?.let { arr -> List(arr.length()) { j ->
                    val syl = arr.getJSONObject(j); Syl(syl.getLong("start"), syl.getLong("end"), syl.getString("text"))
                } })
            }, json.getBoolean("synced"), json.getString("source")), json.getLong("playedAt"))
    }
}

/** Bounded offline originals; corrupt files are skipped and never replace a live response. */
class SongArchive(ctx: Context) {
    companion object { private val lock = Any(); const val LIMIT = 100; const val MAX_BYTES = 20L * 1024 * 1024 }
    private val dir = File(ctx.filesDir, "song-archive").apply { mkdirs() }
    private fun name(track: Triple<String, String, Long>) = MessageDigest.getInstance("SHA-256")
        .digest(TrackPrefs.key(track.first, track.second, track.third).toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
    fun get(track: Triple<String, String, Long>): SongArchiveCodec.Song? = synchronized(lock) { read(File(dir, name(track))) }
    private fun read(file: File) = runCatching { SongArchiveCodec.decode(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }.getOrNull()
    fun recent(): List<SongArchiveCodec.Song> = synchronized(lock) { dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read).sortedByDescending { it.playedAt } }
    fun save(track: Triple<String, String, Long>, lyrics: Lyrics) = synchronized(lock) {
        if (lyrics.isEmpty) return@synchronized
        val raw = SongArchiveCodec.encode(SongArchiveCodec.Song(track, lyrics, System.currentTimeMillis()))
        if (raw.toByteArray().size > MAX_BYTES) return@synchronized
        val file = AtomicFile(File(dir, name(track)))
        val out = file.startWrite()
        try { out.write(raw.toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        val ordered = dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
        var bytes = 0L
        ordered.forEachIndexed { i, old -> bytes += old.length(); if (i >= LIMIT || bytes > MAX_BYTES) AtomicFile(old).delete() }
    }
    fun remove(track: Triple<String, String, Long>) = synchronized(lock) { AtomicFile(File(dir, name(track))).delete() }
}
