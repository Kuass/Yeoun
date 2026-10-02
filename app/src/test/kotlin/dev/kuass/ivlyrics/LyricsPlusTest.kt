package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsPlusTest {
    @Test fun `explicit unsynced payloads ignore placeholder zero timestamps`() {
        // LyricsPlus emits type None with time 0 for every unsynced Deezer line.
        val lyrics = LyricsPlus.parse("""{"type":"None","lyrics":[
            {"time":0,"duration":0,"text":"first"},
            {"time":0,"duration":0,"text":"second"},
            {"time":0,"duration":0,"text":"third"}
        ]}""", 90)

        assertFalse(lyrics.synced)
        assertEquals(listOf(0L, 30_000L, 60_000L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("first", "second", "third"), lyrics.lines.map { it.text })
        assertFalse(LyricsSources.isFinal(lyrics, false, false))
    }

    @Test fun `a single plain line is retained without claiming synchronization`() {
        val lyrics = LyricsPlus.parse("""{"lyrics":[{"text":"only lyric"}]}""", 100)

        assertFalse(lyrics.synced)
        assertEquals(listOf(LrcLine(0, "only lyric")), lyrics.lines)
        assertEquals(LyricsPlus.ID, lyrics.source)
    }

    @Test fun `partially timed payloads retain every line in original order`() {
        for (timedCount in 1..2) {
            val items = (0..2).joinToString(",") { index ->
                val timing = if (index < timedCount) "\"time\":${(index + 1) * 1000}," else ""
                "{$timing\"text\":\"line $index\"}"
            }
            val lyrics = LyricsPlus.parse("""{"type":"Line","lyrics":[$items]}""", 90)

            assertFalse("$timedCount timed lines must not discard the others", lyrics.synced)
            assertEquals(listOf("line 0", "line 1", "line 2"), lyrics.lines.map { it.text })
            assertEquals(listOf(0L, 30_000L, 60_000L), lyrics.lines.map { it.timeMs })
            assertFalse(lyrics.hasSyllables)
        }
    }

    @Test fun `invalid timestamps fall back to plain text instead of fabricated timing`() {
        for (time in listOf("null", "-1", "\"invalid\"", "\"NaN\"", "\"Infinity\"", "1e30")) {
            val lyrics = LyricsPlus.parse("""{"lyrics":[{"time":$time,"text":"keep me"}]}""", 10)

            assertFalse("Invalid time $time must not count as synchronized", lyrics.synced)
            assertEquals(listOf(LrcLine(0, "keep me")), lyrics.lines)
        }
    }

    @Test fun `valid single and simultaneous timed lines remain synchronized`() {
        val single = LyricsPlus.parse("""{"type":"Line","lyrics":[{"time":0,"text":"only"}]}""", 100)
        assertTrue(single.synced)
        assertEquals(listOf(LrcLine(0, "only")), single.lines)

        val together = LyricsPlus.parse("""{"type":"Line","lyrics":[
            {"time":1200,"text":"later"},{"time":100,"text":"first"},{"time":100,"text":"together"},
            {"text":" "},{"time":null,"text":""}
        ]}""", 100)
        assertTrue(together.synced)
        assertEquals(listOf(LrcLine(100, "first"), LrcLine(100, "together"), LrcLine(1200, "later")), together.lines)
    }

    @Test fun `valid word timing still supplies the exact highlighted text`() {
        val lyrics = LyricsPlus.parse("""{"type":"Word","lyrics":[
            {"time":100,"text":"ignored","syllabus":[
                {"time":100,"duration":300,"text":"one "},{"time":400,"duration":500,"text":"two"}
            ]},
            {"time":1000,"text":"line without word timing"}
        ]}""", 100)

        assertTrue(lyrics.synced)
        assertEquals(listOf(
            LrcLine(100, "one two", listOf(Syl(100, 400, "one "), Syl(400, 900, "two"))),
            LrcLine(1000, "line without word timing")
        ), lyrics.lines)
    }

    @Test fun `invalid word timing retains the complete line without partial highlighting`() {
        for (time in listOf("", "\"time\":null,", "\"time\":\"invalid\",", "\"time\":-1,")) {
            val lyrics = LyricsPlus.parse("""{"type":"Word","lyrics":[
                {"time":100,"text":"one two","syllabus":[
                    {"time":100,"duration":300,"text":"one "},{$time"duration":500,"text":"two"}
                ]}
            ]}""", 100)

            assertTrue(lyrics.synced)
            assertEquals(listOf(LrcLine(100, "one two")), lyrics.lines)
            assertFalse(lyrics.hasSyllables)
        }
    }

    @Test fun `malformed syllable entries discard highlighting without losing words`() {
        val lyrics = LyricsPlus.parse("""{"lyrics":[{"time":100,"text":"one two","syllabus":[
            {"time":100,"text":"one "},null,{"time":400,"text":"two"}
        ]}]}""", 100)

        assertEquals(listOf(LrcLine(100, "one two")), lyrics.lines)
    }

    @Test fun `syllable ends cannot overflow into negative time`() {
        val lyrics = LyricsPlus.parse("""{"lyrics":[{"time":100,"text":"one","syllabus":[
            {"time":9223372036854774784,"duration":4096,"text":"one"}
        ]}]}""", 100)

        assertEquals(Long.MAX_VALUE, lyrics.lines.single().syllables!!.single().endMs)
    }
}
