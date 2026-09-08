package dev.kuass.ivlyrics

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

object PresetValues {
    private val integers = mapOf(Prefs.FONT_SP to 10..40, Prefs.PREV_LINES to 0..3, Prefs.NEXT_LINES to 0..5,
        Prefs.TRANSLATION_PREV to 0..3, Prefs.TRANSLATION_NEXT to 0..5, Prefs.PHONETIC_PREV to 0..3, Prefs.PHONETIC_NEXT to 0..5,
        Prefs.BG_PERCENT to 0..100, Prefs.HIDE_ON_PAUSE_SEC to 0..120, Prefs.OFFSET_MS to -3000..3000)
    private val booleans = setOf(Prefs.INLINE_PRONUNCIATION, Prefs.ANIMATE, Prefs.KARAOKE, Prefs.TRANSLATE, Prefs.PHONETIC)
    private val strings = mapOf(Prefs.TARGET_LANG to Lang.TARGETS.map { it.code }.toSet(),
        Prefs.STYLE to Lang.Style.entries.map { it.code }.toSet(), Prefs.NOTATION to Lang.Notation.entries.map { it.code }.toSet())

    fun filter(values: Map<String, *>): Map<String, Any> = buildMap {
        values.forEach { (key, value) ->
            when {
                key in integers && value is Int && value in integers.getValue(key) && value % when (key) { Prefs.BG_PERCENT, Prefs.HIDE_ON_PAUSE_SEC -> 5; Prefs.OFFSET_MS -> 100; else -> 1 } == 0 -> put(key, value)
                key in booleans && value is Boolean -> put(key, value)
                key in strings && value is String && value in strings.getValue(key) -> put(key, value)
                key.startsWith(LanguagePrefs.PREFIX) && key.removePrefix(LanguagePrefs.PREFIX) in SourceLanguage.CODES && value is String && LanguageDisplay.decode(value) != null -> put(key, value)
            }
        }
    }
    fun decode(raw: String): Map<String, Any> {
        val obj = JSONObject(raw)
        return filter(obj.keys().asSequence().associateWith { obj.get(it) })
    }
    fun encode(values: Map<String, *>) = JSONObject(filter(values)).toString()
    val keys get() = integers.keys + booleans + strings.keys
}

class SettingsPresets(ctx: Context) {
    private val stored = ctx.getSharedPreferences("display-presets", Context.MODE_PRIVATE)
    private val prefs = Prefs(ctx)
    fun names(): List<String> = stored.all.keys.sorted()
    fun save(name: String) {
        require(name.isNotBlank() && name.length <= 40)
        require(name in stored.all || stored.all.size < 20)
        stored.edit { putString(name, PresetValues.encode(prefs.sp.all)) }
    }
    fun remove(name: String) = stored.edit { remove(name) }
    fun apply(name: String) {
        val values = PresetValues.decode(requireNotNull(stored.getString(name, null)))
        prefs.sp.edit {
            // Missing keys mean defaults, including languages deliberately left unconfigured in the saved preset.
            (PresetValues.keys + prefs.sp.all.keys.filter { it.startsWith(LanguagePrefs.PREFIX) }).forEach { remove(it) }
            values.forEach { (key, value) ->
                when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }
}
