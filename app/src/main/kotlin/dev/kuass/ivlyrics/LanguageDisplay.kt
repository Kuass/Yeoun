package dev.kuass.ivlyrics

import android.content.Context
import androidx.core.content.edit

object LanguageDisplay {
    data class Choice(val translation: Boolean, val phonetic: Boolean)
    data class Decision(val translation: Boolean, val phonetic: Boolean, val needsChoice: Boolean)

    fun resolve(source: String, target: String, choice: Choice?): Decision = when {
        source.isEmpty() -> Decision(false, false, false)
        choice != null -> Decision(choice.translation, choice.phonetic, false)
        SourceLanguage.normalize(source) == SourceLanguage.normalize(target) -> Decision(false, false, false)
        else -> Decision(false, false, true)
    }

    fun encode(choice: Choice) = "${if (choice.translation) 1 else 0}${if (choice.phonetic) 1 else 0}"
    fun decode(raw: String?): Choice? = raw?.takeIf { it in setOf("00", "01", "10", "11") }
        ?.let { Choice(it[0] == '1', it[1] == '1') }
}

class LanguagePrefs(ctx: Context) {
    private val prefs = Prefs(ctx)
    fun get(source: String): LanguageDisplay.Choice? = LanguageDisplay.decode(prefs.sp.getString(key(source), null))
    fun set(source: String, choice: LanguageDisplay.Choice) = prefs.putString(key(source), LanguageDisplay.encode(choice))
    fun reset(source: String) = prefs.sp.edit { remove(key(source)) }
    private fun key(source: String) = PREFIX + SourceLanguage.normalize(source)
    companion object { const val PREFIX = "language_display_" }
}

/** Conservative, offline hints; ambiguous text stays unknown and can be corrected per song. */
object SourceLanguage {
    val CODES = listOf("ko", "en", "ja", "zh", "es", "fr", "de", "pt", "vi", "id", "th", "ru", "und")
    fun normalize(code: String) = code.lowercase(java.util.Locale.ROOT).substringBefore('-')
    private val tokens = Regex("[\\p{L}']+")
    private val section = Regex("^\\[[^]]+]$")
    private val hints = mapOf(
        "en" to "i you the and my your we love will with that this don't i'm me of in",
        "es" to "yo quiero contigo corazón eres estoy tus para por mi mis amor noche bailar",
        "fr" to "je suis avec toi mon amour pour toujours tu moi nous dans une des pas",
        "de" to "ich du wir nicht bist mein deine liebe und mit für dich mich ein",
        "pt" to "eu você voce não nao meu minha coração saudade uma com nós teu",
        "vi" to "anh em tôi không của yêu đêm một những người biết quên được thương nhớ",
        "id" to "aku kamu kau tidak tak bisa ingin karena cinta hati rindu yang dan untuk",
    ).mapValues { it.value.split(' ').toSet() }

    private val scripts = mapOf(
        "ko" to Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\ud7b0-\\ud7ff가-힣]"),
        "ja" to Regex("[ぁ-ゟ゠-ヿｦ-ﾟ]"),
        "zh" to Regex("[一-鿿]"),
        "ru" to Regex("[Ѐ-ӿ]"),
        "th" to Regex("[฀-๿]"),
    )

    fun detect(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).trim()
        if (normalized.none(Char::isLetter) || section.matches(normalized)) return ""
        val counts = scripts.mapValues { (_, pattern) -> pattern.findAll(normalized).count() }.toMutableMap()
        // A single Hangul syllable is enough when it is the whole word, but not a Korean artist name inside English.
        if (counts.getValue("ko") > 0 && normalized.count(Char::isLetter) == counts.getValue("ko")) return "ko"
        if (counts.getValue("ja") > 0) { counts["ja"] = counts.getValue("ja") + counts.getValue("zh"); counts["zh"] = 0 }
        counts.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { return it.key }
        val words = tokens.findAll(normalized.lowercase(java.util.Locale.ROOT)).map { it.value }.toSet()
        val scored = hints.mapValues { (_, vocabulary) -> words.count { it in vocabulary } }.entries.sortedByDescending { it.value }
        return scored.firstOrNull()?.takeIf { it.value >= 2 && it.value > scored[1].value }?.key ?: "und"
    }

    private fun mostlyLatin(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        val latin = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        return latin > 0 && latin * 2 > letters.length
    }

    fun detectLines(lines: List<String>, override: String? = null): List<String> {
        val detected = lines.map(::detect)
        if (override != null && override in CODES) return detected.map { if (it.isEmpty()) "" else override }
        // Preserve confidently detected foreign lines, while using the song's context for short uncertain inserts.
        val contentCount = detected.count { it.isNotEmpty() }
        val primary = detected.filter { it.isNotEmpty() && it != "und" }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.takeIf { it.value >= 3 && it.value * 5 >= contentCount * 4 }?.key
        val evidence = detected.filter { it in hints.keys }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val fallback = evidence.firstOrNull()?.takeIf {
            it.value >= 2 && it.value >= (evidence.getOrNull(1)?.value ?: 0) * 3
        }?.key
        return detected.mapIndexed { index, code ->
            when {
                code != "und" -> code
                !mostlyLatin(lines[index]) -> code
                fallback != null -> fallback
                primary != null && tokens.findAll(lines[index]).take(9).count() <= 8 -> primary
                else -> code
            }
        }
    }

    fun label(code: String, ctx: Context): String = when (normalize(code)) {
        "und" -> ctx.getString(R.string.language_unknown)
        "zh" -> ctx.getString(R.string.language_chinese)
        else -> Lang.TARGETS.firstOrNull { it.code == normalize(code) }?.native ?: code
    }
}
