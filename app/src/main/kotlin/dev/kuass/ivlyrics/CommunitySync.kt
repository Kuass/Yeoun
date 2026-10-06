package dev.kuass.ivlyrics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.roundToLong

/**
 * Community sync data from ivLyrics: per-character timings authored against a specific LRCLIB text.
 * The public OpenDB tells which ISRCs have data; the API returns the timing; the LRCLIB record referenced by the
 * data supplies the exact base text so character indexes line up.
 */
class CommunitySync internal constructor(
    cacheDir: File,
    private val get: (String, Int, Map<String, String>, Set<Int>) -> String? = Http::get,
    private val resolveIsrc: (String, String, Long) -> String? = Isrc::resolve,
    private val log: (String, Exception?) -> Unit = { message, error ->
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
    },
) {
    constructor(ctx: Context) : this(ctx.cacheDir)

    companion object {
        private const val TAG = "CommunitySync"
        private const val API = "https://lyrics.api.ivl.is/lyrics/sync-data"
        private const val REQUEST_VERSION = "20260701"
        private const val OPENDB = "https://ivlis.kr/ivLyrics/opendb/"
        private const val OPENDB_TTL_MS = 24 * 60 * 60 * 1000L
        /** The API only answers requests carrying the Spotify client origin; the user chose to send it. */
        private const val ORIGIN = "https://xpui.app.spotify.com"
        const val ID = "community"

        fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
    }


    private val dir = File(cacheDir, "community").apply { mkdirs() }
    private var openDb: Map<String, Set<String>>? = null
    private var openDbLoadedAt = 0L

    /** Providers whose data is authored against an LRCLIB text we can fetch; most common first. */
    private val PROVIDERS = listOf("lrclib")

    /**
     * Karaoke lyrics for the track, or null when the community has nothing usable.
     * The Spotify track id (from the media session) is the primary key; ISRC via Deezer is the fallback.
     */
    fun lookup(trackId: String?, title: String, artist: String, durationSec: Long): Lyrics? = try {
        // A miss can include a temporary ISRC/OpenDB failure; retry only when the caller loads again.
        trackId?.let { byQuery("trackId=$it") } ?: byIsrc(title, artist, durationSec)
    } catch (e: Exception) {
        log("lookup failed for $artist - $title", e); null
    }

    private fun byIsrc(title: String, artist: String, durationSec: Long): Lyrics? {
        val isrc = resolveIsrc(title, artist, durationSec) ?: return null
        val providers = providersFor(isrc).filter { it in PROVIDERS }
        if (providers.isEmpty()) return null
        return byQuery("isrc=$isrc", providers)
    }

    private fun byQuery(query: String, providers: List<String> = PROVIDERS): Lyrics? {
        for (provider in providers) {
            val data = fetchSyncData(query, provider) ?: continue
            val base = baseLines(data) ?: continue
            val lines = CommunitySyncCodec.apply(base, data) ?: continue
            log("$query: applied $provider, ${lines.size} lines", null)
            return Lyrics(lines, true, ID)
        }
        return null
    }

    // ---- OpenDB -----------------------------------------------------------------------------------

    private fun providersFor(isrc: String): List<String> {
        val map = loadOpenDb() ?: return emptyList()
        return map.filter { isrc in it.value }.keys.sortedBy { if (it == "lrclib") 0 else 1 }
    }

    @Synchronized
    private fun loadOpenDb(): Map<String, Set<String>>? {
        openDb?.takeIf { System.currentTimeMillis() - openDbLoadedAt < OPENDB_TTL_MS }?.let { return it }
        val file = File(dir, "opendb.json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < OPENDB_TTL_MS) {
            runCatching { parseProviderMap(JSONObject(file.readText())) }.getOrNull()
                ?.takeIf { m -> m.values.sumOf { it.size } > 0 }
                ?.let { openDb = it; openDbLoadedAt = System.currentTimeMillis(); return it }
        }
        val manifest = get("${OPENDB}data/manifest.json", 10_000, emptyMap(), setOf(404))?.let(::JSONObject) ?: return openDb
        val merged = mutableMapOf<String, MutableSet<String>>()
        val base = manifest.optJSONObject("base")?.optString("url") ?: return openDb
        // The base file wraps the provider map in "items"; deltas carry "add"/"remove" maps at the top level.
        get("$OPENDB$base", 20_000, emptyMap(), setOf(404))?.let(::JSONObject)?.let { mergeProviderMap(merged, it.optJSONObject("items") ?: it.optJSONObject("data") ?: it) } ?: return openDb
        val deltas = manifest.optJSONArray("deltas") ?: JSONArray()
        for (i in 0 until deltas.length()) {
            val url = deltas.optJSONObject(i)?.optString("url") ?: continue
            val delta = get("$OPENDB$url", 10_000, emptyMap(), setOf(404))?.let(::JSONObject) ?: continue
            delta.optJSONObject("add")?.let { mergeProviderMap(merged, it) }
            delta.optJSONObject("remove")?.let { rm -> rm.keys().forEach { p -> merged[p]?.removeAll(rm.optJSONArray(p)?.toStringList()?.toSet().orEmpty()) } }
        }
        val json = JSONObject().also { o -> merged.forEach { (p, set) -> o.put(p, JSONArray(set.toList())) } }
        file.writeText(json.toString())
        openDb = merged; openDbLoadedAt = System.currentTimeMillis()
        return merged
    }

    private fun mergeProviderMap(into: MutableMap<String, MutableSet<String>>, o: JSONObject) {
        o.keys().forEach { p -> o.optJSONArray(p)?.let { into.getOrPut(p) { mutableSetOf() }.addAll(it.toStringList()) } }
    }

    private fun parseProviderMap(o: JSONObject): Map<String, Set<String>> =
        o.keys().asSequence().associateWith { p -> o.optJSONArray(p)?.toStringList()?.toSet() ?: emptySet() }

    private fun JSONArray.toStringList() = (0 until length()).map { optString(it) }

    // ---- API --------------------------------------------------------------------------------------

    private fun fetchSyncData(query: String, provider: String): CommunitySyncCodec.SyncData? {
        val url = "$API?$query&request-version=$REQUEST_VERSION&provider=${Http.enc(provider)}"
        // 400 comes back when the server cannot map the track id; 404 when the provider has no data.
        val body = get(url, 15_000, mapOf("Origin" to ORIGIN), setOf(400, 404)) ?: return null
        return CommunitySyncCodec.parse(JSONObject(body))
    }

    // ---- base text and application ------------------------------------------------------------------

    /** The LRCLIB record the data was authored against, as trimmed NFC non-empty lines. */
    private fun baseLines(data: CommunitySyncCodec.SyncData): List<String>? {
        val id = data.lrclibId ?: return null
        val rec = get("https://lrclib.net/api/get/$id", 10_000, emptyMap(), setOf(404))?.let(::JSONObject) ?: return null
        val synced = rec.optString("syncedLyrics").takeIf { it.isNotBlank() }
        val plain = rec.optString("plainLyrics").takeIf { it.isNotBlank() }
        val text = (if (data.preferSynced) synced?.let { Lrc.parse(it).joinToString("\n") { l -> l.text } } else null) ?: plain ?: return null
        return text.lines().map { nfc(it).trim() }.filter { it.isNotEmpty() }
    }

}

/** Pure parsing and application of community sync data; kept free of Android context so it is unit-testable. */
object CommunitySyncCodec {
    data class SyncLine(val start: Int, val end: Int, val charTimesMs: List<Long?>)
    data class SyncData(val lines: List<SyncLine>, val lrclibId: Long?, val lineCharCounts: List<Int>?, val preferSynced: Boolean)

    fun parse(root: JSONObject): SyncData? {
        val data = root.optJSONObject("data") ?: root
        val sd = data.optJSONObject("syncData") ?: return null
        val src = sd.optJSONObject("source")
        val arr = sd.optJSONArray("lines") ?: return null
        val lines = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { expand(it) }
        if (lines.isEmpty()) return null
        val counts = src?.optJSONArray("lineCharCounts")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
        val lrclibId = src?.optLong("lrclibId", -1)?.takeIf { it > 0 }
        return SyncData(lines, lrclibId, counts, src?.optString("preferredLyricsSource") != "plain")
    }

    /** Seconds to ms, or null for the NaN that org.json returns for missing or non-numeric values. */
    private fun ms(seconds: Double): Long? = if (seconds.isNaN() || seconds.isInfinite()) null else (seconds * 1000).roundToLong()

    /** Character times in ms for the line's [start, end] range; word/line granularity is expanded to characters. */
    private fun expand(o: JSONObject): SyncLine? {
        val start = o.optInt("start", -1); val end = o.optInt("end", -1)
        if (start < 0 || end < start) return null
        val length = end - start + 1
        val chars: List<Long?> = when {
            o.has("chars") -> {
                val a = o.optJSONArray("chars") ?: return null
                if (a.length() != length) return null
                (0 until a.length()).map { i -> if (a.isNull(i)) null else ms(a.optDouble(i)) ?: return null }
            }
            o.optString("granularity") == "line" -> { val t = ms(o.optDouble("timing")) ?: return null; List(length) { t } }
            o.optString("granularity") == "word" -> {
                val marks = o.optJSONArray("timing") ?: return null
                val out = MutableList<Long?>(length) { null }
                var from = 0
                for (i in 0 until marks.length()) {
                    val m = marks.optJSONArray(i) ?: return null
                    val to = m.optInt(0); val t = ms(m.optDouble(1)) ?: return null
                    if (to < from || to >= length) return null
                    for (k in from..to) out[k] = t
                    from = to + 1
                }
                out
            }
            else -> return null
        }
        return SyncLine(start, end, chars)
    }

    /**
     * Maps global character timings onto [base] lines. Returns null when the authored line shape does not match,
     * so a wrong text is never highlighted.
     */
    fun apply(base: List<String>, data: SyncData): List<LrcLine>? {
        val counts = base.map { it.codePointCount(0, it.length) }
        // The authored line shape is the only proof that this text is the one the timing was made for.
        if (data.lineCharCounts == null || data.lineCharCounts != counts) return null
        val offsets = counts.runningFold(0) { acc, n -> acc + n }
        val out = mutableListOf<LrcLine>()
        for ((i, text) in base.withIndex()) {
            val lineStart = offsets[i]; val lineEnd = offsets[i + 1] - 1
            val times = MutableList<Long?>(counts[i]) { null }
            for (sl in data.lines) {
                val from = maxOf(sl.start, lineStart); val to = minOf(sl.end, lineEnd)
                for (g in from..to) times[g - lineStart] = sl.charTimesMs.getOrNull(g - sl.start)
            }
            val known = times.filterNotNull()
            if (known.isEmpty()) continue
            val lineTime = known.first()
            val cps = text.codePoints().toArray()
            val syls = mutableListOf<Syl>()
            var last = lineTime
            for ((k, cp) in cps.withIndex()) {
                val t = times[k] ?: last
                last = maxOf(last, t)
                syls += Syl(last, last, String(Character.toChars(cp)))
            }
            for (k in syls.indices) syls[k] = syls[k].copy(endMs = syls.getOrNull(k + 1)?.startMs ?: (syls[k].startMs + 400))
            out += LrcLine(lineTime, text, syls)
        }
        return out.takeIf { it.isNotEmpty() && it.size >= base.size / 2 }?.sortedBy { it.timeMs }
    }
}
