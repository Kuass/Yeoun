package dev.kuass.ivlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SourcesTest {
    @Test
    fun `order puts the preferred provider first and drops disabled ones`() {
        val all = setOf("lrclib", "lyrically", "lyricsplus")
        assertEquals(listOf("lrclib", "lyrically", "lyricsplus"), LyricsSources.order("lrclib", all))
        assertEquals(listOf("lyricsplus", "lrclib", "lyrically"), LyricsSources.order("lyricsplus", all))
        assertEquals(listOf("lyrically"), LyricsSources.order("lrclib", setOf("lyrically")))
        assertEquals(emptyList<String>(), LyricsSources.order("lrclib", emptySet()))
    }

    @Test
    fun `Lyrically unsynced document with every timestamp at zero is not treated as timed`() {
        // Shape of the real apple_music response for Lee Yong Shin - With my Heart: syncType null, all timestamps 0.
        val doc = org.json.JSONObject("""{"provider":"apple_music","syncType":null,"lrc":"","lyrics":[
            {"timestamp":0,"endtime":0,"text":[{"text":"외로운 사람들의","timestamp":0,"endtime":0,"part":false}]},
            {"timestamp":0,"endtime":0,"text":[{"text":"마음을 열어줄꺼야","timestamp":0,"endtime":0,"part":false}]},
            {"timestamp":0,"endtime":0,"text":[{"text":"넘치는 음악속의 리듬을 리듬을","timestamp":0,"endtime":0,"part":false}]}]}""")
        assertEquals(emptyList<LrcLine>(), Lyrically.parseTimedLines(doc))

        val timed = org.json.JSONObject("""{"lyrics":[
            {"timestamp":0,"text":[{"text":"a","timestamp":0,"endtime":100,"part":false}]},
            {"timestamp":1200,"text":[{"text":"b","timestamp":1200,"endtime":1300,"part":false}]}]}""")
        assertEquals(listOf(0L, 1200L), Lyrically.parseTimedLines(timed).map { it.timeMs })
        val single = org.json.JSONObject("""{"lyrics":[{"timestamp":0,"text":[{"text":"only","timestamp":0,"endtime":100,"part":false}]}]}""")
        assertEquals(listOf("only"), Lyrically.parseTimedLines(single).map { it.text })
    }

    @Test
    fun `Lyrically tokens join without a space after part tokens and sungChars counts started units`() {
        val syls = Lyrically.joinTokens(listOf(
            Lyrically.Token("thunder", 100, 300, true), Lyrically.Token("clouds,", 300, 500, false), Lyrically.Token("oh", 500, 600, false),
        ))
        assertEquals("thunderclouds, oh", syls.joinToString("") { it.text })
        assertEquals(0, Lrc.sungChars(syls, 50))
        assertEquals("thunder".length, Lrc.sungChars(syls, 100))
        assertEquals("thunderclouds, ".length, Lrc.sungChars(syls, 499))
        assertEquals("thunderclouds, oh".length, Lrc.sungChars(syls, 5000))
    }

    @Test
    fun `romanized Korean synced results yield to original-script text when deferral is on`() {
        val romanized = Lyrics(listOf(
            LrcLine(0, "tteonabeorin neoneun nae maeum"), LrcLine(1, "sarang neol geu uri"), LrcLine(2, "eodie isseodo nan"),
            LrcLine(3, "saranghae nal tto"), LrcLine(4, "geuriwo neoege")), true, "lrclib")
        val hangul = Lyrics(listOf(LrcLine(0, "떠나버린 너는 내 마음")), true, "lyrically")
        val english = Lyrics(listOf(LrcLine(0, "All I need is one"), LrcLine(1, "One old man is enough"), LrcLine(2, "Babe you got it wrong please")), true, "lrclib")
        assertTrue(LyricsSources.isRomanized(romanized))
        assertFalse(LyricsSources.isRomanized(english))
        assertFalse(LyricsSources.isFinal(romanized, false, true))
        assertTrue(LyricsSources.isFinal(romanized, false, false))
        assertTrue(LyricsSources.isFinal(english, false, true))
        assertEquals("lyrically", LyricsSources.best(listOf(romanized, hangul), true).source)
        assertEquals("lrclib", LyricsSources.best(listOf(romanized), true).source)
    }

    @Test
    fun `LRC offset header shifts timestamps earlier`() {
        val lines = Lrc.parse("[offset:+500]\n[00:10.00]a\n[00:00.20]b")
        assertEquals(listOf(0L to "b", 9500L to "a"), lines.map { it.timeMs to it.text })
        assertEquals(listOf(10_000L), Lrc.parse("[00:10.00]a").map { it.timeMs })
    }

    @Test
    fun `LyricsPlus payload parses timed lines and falls back to spread plain text`() {
        val timed = """{"type":"Line","lyrics":[{"time":1200,"duration":800,"text":"b"},{"time":100,"duration":900,"text":"a b","syllabus":[{"time":100,"duration":400,"text":"a "},{"time":500,"duration":500,"text":"b"}]},{"time":null,"text":""}]}"""
        val got = LyricsPlus.parse(timed, 200)
        assertEquals(true, got.synced)
        assertEquals(listOf(100L to "a b", 1200L to "b"), got.lines.map { it.timeMs to it.text })
        assertEquals(listOf(100L, 500L), got.lines[0].syllables!!.map { it.startMs })
        assertEquals(true, got.hasSyllables)
        val plain = LyricsPlus.parse("""{"lyrics":[{"text":"x"},{"text":"y"}]}""", 100)
        assertEquals(false, plain.synced)
        assertEquals(listOf(0L, 50_000L), plain.lines.map { it.timeMs })
        assertEquals(true, LyricsPlus.parse("{}", 100).isEmpty)
    }

    @Test
    fun `Lyrically picks the title match nearest in duration, ignoring remixes and bracketed suffixes`() {
        val c = listOf(
            Lyrically.Candidate("remix", "Thunderclouds (feat. Sia) [Lost Frequencies Remix]", "LSD", 198_493),
            Lyrically.Candidate("orig", "Thunderclouds (feat. Sia, Diplo & Labrinth)", "LSD", 187_026),
            Lyrically.Candidate("other", "Something Else", "LSD", 187_000),
        )
        assertEquals("orig", Lyrically.choose(c, "Thunderclouds (feat. Sia, Diplo, and Labrinth)", "Sia, Diplo, Labrinth, LSD", 187_000)?.id)
        assertEquals("orig", Lyrically.choose(c, "Thunderclouds", "LSD", 0)?.id)
        assertNull(Lyrically.choose(c, "Rooftop", "N.Flying", 210_000))
    }
}
