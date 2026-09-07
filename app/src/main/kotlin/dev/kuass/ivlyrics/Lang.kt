package dev.kuass.ivlyrics

// Adapted from ivLyrics for Android; modified 2026-09-08.
// Upstream attribution and LGPL-2.1 text: THIRD_PARTY_NOTICES.md and LICENSE.

/** Target languages, translation styles and pronunciation notations, ported from ivLyrics desktop prompts. */
object Lang {
    data class Target(val code: String, val name: String, val native: String, val phoneticDesc: String, val script: Script?)
    data class Script(val name: String, val instruction: String)

    val LATIN = Script(
        "standard Latin alphabet",
        "Use only Latin letters (including language-appropriate Latin diacritics), spaces, apostrophes, and hyphens for pronounceable lyric sounds. Never use Hiragana, Katakana, Kanji/Hanzi, Hangul, Thai, Cyrillic, Arabic, Devanagari, Bengali, or any other non-Latin script for lyric sounds.",
    )
    val IPA = Script(
        "International Phonetic Alphabet (IPA)",
        "Write the sung pronunciation in Unicode IPA using broad, readable phonemic transcription. Use IPA stress, length, tone, and combining marks only when they materially affect pronunciation. Do not use ordinary romanization or the source orthography, and do not wrap output lines in slashes or square brackets.",
    )

    val TARGETS = listOf(
        Target("ko", "Korean", "한국어", "Korean Hangul pronunciation (e.g., こんにちは → 콘니치와)",
            Script("Korean Hangul", "Write every pronounceable lyric sound in Hangul. Do not use Hiragana, Katakana, Kanji/Hanzi, Thai, Cyrillic, Arabic, or Latin letters for lyric sounds. Latin letters may remain only inside an exact structural marker such as [Chorus].")),
        Target("en", "English", "English", "English romanization (e.g., こんにちは → konnichiwa)", null),
        Target("ja", "Japanese", "日本語", "Japanese Katakana pronunciation",
            Script("Japanese Katakana", "Write every pronounceable lyric sound in Katakana. Do not use Hiragana, Kanji/Hanzi, Hangul, Thai, or another language script for lyric sounds.")),
        Target("zh-cn", "Simplified Chinese", "简体中文", "Chinese characters for pronunciation",
            Script("Simplified Chinese characters", "Write every pronounceable lyric sound with natural Simplified Chinese phonetic approximations. Do not copy Japanese Kana, Korean Hangul, Thai, or another source-language script.")),
        Target("zh-tw", "Traditional Chinese", "繁體中文", "Chinese characters for pronunciation",
            Script("Traditional Chinese characters", "Write every pronounceable lyric sound with natural Traditional Chinese phonetic approximations. Do not copy Japanese Kana, Korean Hangul, Thai, or another source-language script.")),
        Target("es", "Spanish", "Español", "Spanish phonetic spelling", null),
        Target("fr", "French", "Français", "French phonetic spelling", null),
        Target("de", "German", "Deutsch", "German phonetic spelling", null),
        Target("pt", "Portuguese", "Português", "Portuguese phonetic spelling", null),
        Target("vi", "Vietnamese", "Tiếng Việt", "Vietnamese phonetic spelling", null),
        Target("id", "Indonesian", "Bahasa Indonesia", "Indonesian phonetic spelling", null),
        Target("th", "Thai", "ไทย", "Thai script pronunciation",
            Script("Thai script", "Write every pronounceable lyric sound in Thai script using natural Thai phonetic spelling. Do not use Japanese Kana, Han characters, Hangul, or another source-language script.")),
        Target("ru", "Russian", "Русский", "Russian Cyrillic pronunciation",
            Script("Russian Cyrillic", "Write every pronounceable lyric sound in Cyrillic using natural Russian phonetic spelling. Do not use Japanese Kana, Han characters, Hangul, Thai, or another source-language script.")),
    )

    fun target(code: String): Target = TARGETS.firstOrNull { it.code == code } ?: TARGETS.first()

    enum class Style(val code: String, val instruction: String) {
        NATURAL("natural", "Use natural, idiomatic phrasing while preserving each line's meaning, tone, imagery, and level of formality. Do not add, omit, or move meaning between lines."),
        LITERAL("literal", "Stay close to the original wording, word order, imagery, metaphors, and ambiguity. Change only what is necessary for grammatical, understandable target-language text."),
        ADAPTIVE("adaptive", "Use nearby lines as context so the lyrics read as one smooth, connected passage. You may lightly reshape idioms and phrasing for fluency, but do not add, omit, or move meaning between lines.");

        companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: NATURAL }
    }

    enum class Notation(val code: String) {
        SCRIPT("script"), LATIN("latin"), IPA("ipa");

        companion object { fun of(code: String) = entries.firstOrNull { it.code == code } ?: SCRIPT }
    }

    /** Script used for pronunciation output for [target] under [notation]. */
    fun scriptFor(target: Target, notation: Notation): Script = when (notation) {
        Notation.IPA -> IPA
        Notation.LATIN -> LATIN
        Notation.SCRIPT -> target.script ?: LATIN
    }

    private val HANGUL = Regex("[가-힣]")
    private val KANA = Regex("[ぁ-ゟ゠-ヿ]")
    private val HAN = Regex("[一-鿿]")
    private val CYRILLIC = Regex("[Ѐ-ӿ]")
    private val THAI = Regex("[฀-๿]")
    private val LATIN_WORD = Regex("[A-Za-z]{2,}")
    private val ENGLISH_HINTS = setOf("the", "you", "and", "to", "me", "my", "is", "it", "in", "of", "love", "your", "don't", "i'm", "we", "that")
    private const val MAJORITY = 0.5

    private val NATIVE_SCRIPT = Regex("[\u1100-\u11FF\u3130-\u318F\uAC00-\uD7AF\u3040-\u30FF\u4E00-\u9FFF]")
    private val ROMANIZED_KO_TOKEN = Regex("eo|eu|ae|yeo|wae|oe|(?:neun|reul|eul|ege|eseo|deul|nikka|jiman|myeon)$")
    private val ROMANIZED_KO_WORDS = setOf("nan", "nal", "nae", "neo", "neol", "geu", "uri", "urin", "mal", "sarang", "saram", "tto", "maeum", "saranghae")

    /**
     * Romanized Korean ("tteonabeorin neoneun...") instead of Hangul. Ported from ivLyrics desktop.
     * ponytail: token-ratio heuristic; songs with only a short repeated hook may slip through.
     */
    fun looksRomanizedKorean(lines: List<String>): Boolean {
        val text = lines.joinToString("\n")
        if (text.isBlank() || NATIVE_SCRIPT.containsMatchIn(text)) return false
        val tokens = Regex("[a-z]+").findAll(text.lowercase()).map { it.value }.toSet()
        if (tokens.size < 8) return false
        val hits = tokens.count { ROMANIZED_KO_TOKEN.containsMatchIn(it) || it in ROMANIZED_KO_WORDS }
        return hits >= 4 && hits >= tokens.size * 0.3
    }

    /** True when the lyrics already read as [target], so translation and pronunciation would be pointless. */
    fun isAlreadyIn(lines: List<String>, target: Target): Boolean {
        val content = lines.filter { it.isNotBlank() && it.trim() != LyricsView.BLANK_LINE }
        if (content.isEmpty()) return false
        val ratio: (Regex) -> Double = { re -> content.count { re.containsMatchIn(it) }.toDouble() / content.size }
        return when (target.code) {
            "ko" -> ratio(HANGUL) > MAJORITY
            "ja" -> ratio(KANA) > MAJORITY
            "zh-cn", "zh-tw" -> ratio(HAN) > MAJORITY && ratio(KANA) < 0.1
            "ru" -> ratio(CYRILLIC) > MAJORITY
            "th" -> ratio(THAI) > MAJORITY
            "en" -> {
                val words = content.flatMap { LATIN_WORD.findAll(it.lowercase()).map { m -> m.value } }
                words.size >= 8 && words.count { it in ENGLISH_HINTS }.toDouble() / words.size > 0.12
            }
            else -> false
        }
    }
}
