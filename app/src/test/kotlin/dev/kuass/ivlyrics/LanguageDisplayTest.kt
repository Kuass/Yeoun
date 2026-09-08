package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class LanguageDisplayTest {
    @Test fun `clear song language covers short uncertain inserts without a new language prompt`() {
        val korean = List(20) { "오늘도 너에게 노래를 들려줄게" }
        val lines = korean + listOf("Eins, zwei, Polizei", "Drei, vier, Grenadier", "Fünf, sechs, alte Hex", "Sieben, acht – gute Nacht", "(Break it down)")
        val result = SourceLanguage.detectLines(lines)
        assertEquals(List(25) { "ko" }, result)
        assertTrue(result.none { LanguageDisplay.resolve(it, "ko", null).needsChoice })
    }

    @Test fun `confident foreign lines keep their choices inside a Korean majority song`() {
        val lines = List(20) { "오늘도 너에게 노래를 들려줄게" } + listOf("I will stay with you", "君の声が聞こえる")
        assertEquals(listOf("en", "ja"), SourceLanguage.detectLines(lines).takeLast(2))
    }

    @Test fun `dominant language does not swallow long unknown passages unsupported scripts or explicit overrides`() {
        val korean = List(20) { "오늘도 너에게 노래를 들려줄게" }
        val unknown = listOf("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda", "العربية")
        assertEquals(listOf("und", "und"), SourceLanguage.detectLines(korean + unknown).takeLast(2))
        assertEquals(List(21) { "de" }, SourceLanguage.detectLines(korean + "adlib", "de"))
    }

    @Test fun `short Korean and normalized Hangul are Korean without prompting`() {
        listOf("너", "왜?", "꿈", "\u1102\u1165", "ㄴㅓ").forEach {
            assertEquals(it, "ko", SourceLanguage.detect(it))
        }
        assertEquals("", SourceLanguage.detect("…"))
        assertEquals("", SourceLanguage.detect("♪ ♫"))
    }

    @Test fun `short English phrases in a Korean song follow confident English lines without relabelling Korean`() {
        val lines = listOf("오늘도 창문을 열어", "조용히 아침을 기다려", "아직도 노래가 들려", "너", "왜",
            "I will stay with you", "You are in my dreams", "Come on", "Everybody sing", "Here we go", "Let's go")
        assertEquals(List(5) { "ko" } + List(6) { "en" }, SourceLanguage.detectLines(lines))
        assertEquals("und", SourceLanguage.detectLines(listOf("오늘도 창문을 열어", "xyzzy qqqqq")).last())
    }

    @Test fun `ambiguous Latin lines do not inherit across competing languages or different scripts`() {
        val lines = listOf("I will stay with you", "You are in my dreams", "je suis avec toi", "mon amour pour toujours", "xyzzy qqqqq", "العربية")
        val result = SourceLanguage.detectLines(lines)
        assertEquals("und", result[4])
        assertEquals("und", result[5])
    }

    @Test fun `foreign language waits for explicit choice while own language stays original`() {
        assertTrue(LanguageDisplay.resolve("ja", "ko", null).needsChoice)
        assertEquals(LanguageDisplay.Decision(false, false, false), LanguageDisplay.resolve("ko", "ko", null))
        assertEquals(LanguageDisplay.Decision(true, false, false), LanguageDisplay.resolve("ja", "ko", LanguageDisplay.Choice(true, false)))
        assertEquals(LanguageDisplay.Decision(false, true, false), LanguageDisplay.resolve("en", "ko", LanguageDisplay.Choice(false, true)))
        assertFalse(LanguageDisplay.resolve("ja", "ko", LanguageDisplay.Choice(false, false)).needsChoice)
    }

    @Test fun `mixed songs retain language choices per line and ignore section markers`() {
        val detected = SourceLanguage.detectLines(listOf("[Chorus]", "오늘도 너를 기다리고 있어", "I will find you in the morning", "君の声が聞こえる", ""))
        assertEquals(listOf("", "ko", "en", "ja", ""), detected)
        assertEquals("und", SourceLanguage.detect("xyzzy qqqqq"))
        assertEquals("fr", SourceLanguage.detect("je suis avec toi mon amour pour toujours"))
        assertEquals("es", SourceLanguage.detect("quiero bailar contigo mi corazón esta noche"))
        assertEquals("zh", SourceLanguage.detect("我想念你的声音"))
    }

    @Test fun `display modes round trip independently and unset differs from original only`() {
        for (tr in listOf(false, true)) for (ph in listOf(false, true)) {
            val choice = LanguageDisplay.Choice(tr, ph)
            assertEquals(choice, LanguageDisplay.decode(LanguageDisplay.encode(choice)))
        }
        assertNull(LanguageDisplay.decode(null))
        assertNull(LanguageDisplay.decode("broken"))
    }

    @Test fun `extra line windows are independent of the original window`() {
        val style = LyricsView.Style(18, 0, 0, false, 80, translationPrev = 2, translationNext = 1, phoneticPrev = 0, phoneticNext = 3)
        val rows = LyricsRows.build(3, 8, style, true, true)
        assertEquals(listOf(3), rows.filter { it.original }.map { it.index })
        assertEquals(listOf(1, 2, 3, 4), rows.filter { it.translation }.map { it.index })
        assertEquals(listOf(3, 4, 5, 6), rows.filter { it.phonetic }.map { it.index })
        assertTrue(LyricsRows.build(0, 0, style, true, true).isEmpty())
        assertTrue(LyricsRows.build(-1, 8, style, true, true).all { it.index >= 0 })
        assertEquals(listOf(3), LyricsRows.build(3, 8, style, false, false).map { it.index })
    }
}
