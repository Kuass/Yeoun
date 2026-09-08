package dev.kuass.ivlyrics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream

object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val extraBooleans = setOf(Prefs.SRC_LRCLIB, Prefs.SRC_LYRICSPLUS, Prefs.SRC_LYRICALLY, Prefs.SRC_COMMUNITY, Prefs.VIDEO_BG)
    data class Data(val settings: Map<String, Any>, val tracks: Map<String, String>, val local: Map<String, String>, val presets: Map<String, String>, val edits: Map<String, String>)
    fun settings(input: Map<String, *>): Map<String, Any> = PresetValues.filter(input).toMutableMap().apply {
        input.forEach { (key, value) -> when {
            key in extraBooleans && value is Boolean -> put(key, value)
            key == Prefs.SRC_FIRST && value is String && value in LyricsSources.ALL -> put(key, value)
            key == Prefs.APP_LANG && value in listOf("system", "ko", "en") -> put(key, requireNotNull(value))
        } }
    }
    fun read(input: InputStream): Data {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        return decode(text)
    }
    fun encode(data: Data): String = JSONObject().put("format", "YeounBackup").put("version", 1)
        .put("settings", JSONObject(settings(data.settings))).put("tracks", JSONObject(data.tracks))
        .put("local", JSONObject(data.local)).put("presets", JSONObject(data.presets)).put("edits", JSONObject(data.edits)).toString()

    fun decode(text: String): Data {
        require(text.toByteArray().size <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.getString("format") == "YeounBackup" && root.getInt("version") == 1)
        fun map(name: String): Map<String, Any> {
            val obj = root.getJSONObject(name)
            require(obj.length() <= 2000)
            return obj.keys().asSequence().associateWith { key -> require(key.length in 1..2048); obj.get(key) }
        }
        fun strings(name: String) = map(name).mapValues { require(it.value is String); it.value as String }
        val tracks = strings("tracks").mapValues { (_, raw) ->
            val json = JSONObject(raw)
            val offset = json.optInt("offset"); require(offset in -3000..3000 && offset % 100 == 0)
            val lang = json.optString("lang"); require(lang.isEmpty() || Lang.TARGETS.any { it.code == lang })
            val source = json.optString("source"); require(source.isEmpty() || source in LyricsSources.ALL)
            val language = json.optString("sourceLanguage"); require(language.isEmpty() || language in SourceLanguage.CODES)
            val video = json.optString("video"); require(video.isEmpty() || video.matches(Regex("[a-zA-Z0-9_-]{11}")))
            JSONObject().put("offset", offset).put("lang", lang).put("source", source).put("sourceLanguage", language).put("video", video).toString()
        }
        val local = strings("local").onEach { (_, raw) ->
            require(raw.toByteArray().size <= LyricsDocument.MAX_BYTES)
            require(if (raw.startsWith(LocalLyrics.PLAIN_HEADER)) raw.removePrefix(LocalLyrics.PLAIN_HEADER).isNotBlank() else Lrc.parse(raw).any { it.text.isNotBlank() })
        }
        val presets = strings("presets").mapValues { PresetValues.encode(PresetValues.decode(it.value)) }
        require(presets.size <= 20 && presets.keys.all { it.length <= 40 && it.isNotBlank() })
        val edits = strings("edits").mapValues { (key, value) ->
            require(key.matches(Regex("[a-f0-9]{64}")))
            ExtrasEdits.decode(value).also { require(it.translation.size + it.phonetic.size <= 10000) }.encode()
        }
        return Data(settings(map("settings")), tracks, local, presets, edits)
    }
}

class BackupStore(private val ctx: Context) {
    private fun preferences(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun strings(name: String) = preferences(name).all.mapValues { it.value as String }
    fun snapshot(): BackupCodec.Data {
        val edits = File(ctx.filesDir, "lyrics-edits").listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            .associate { it.name.removeSuffix(".json") to it.readText() }
        return BackupCodec.Data(BackupCodec.settings(Prefs(ctx).sp.all), strings(TrackPrefs.NAME), strings("local_lyrics"), strings("display-presets"), edits)
    }
    /** All payloads are validated before merging; existing unrelated entries are retained. */
    fun restore(data: BackupCodec.Data) {
        val validated = BackupCodec.decode(BackupCodec.encode(data))
        require((preferences("display-presets").all.keys + validated.presets.keys).size <= 20)
        fun merge(name: String, values: Map<String, *>) {
            val editor = preferences(name).edit()
            values.forEach { (key, value) -> when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
            } }
            check(editor.commit()) { "Preference storage failed" }
        }
        val store = LyricsEdits(ctx)
        validated.edits.forEach { (key, raw) -> store.set(key, ExtrasEdits.decode(raw)) }
        merge(TrackPrefs.NAME, validated.tracks)
        merge("local_lyrics", validated.local)
        merge("display-presets", validated.presets)
        merge(Prefs.NAME, validated.settings)
        Prefs(ctx).bumpTrackPrefs()
        Prefs(ctx).putString(Prefs.EXTRAS_VERSION, java.util.UUID.randomUUID().toString())
    }
}
