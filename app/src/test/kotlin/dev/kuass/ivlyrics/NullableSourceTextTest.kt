package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NullableSourceTextTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `LyricsPlus null line text does not become a lyric`() {
        assertTrue(LyricsPlus.parse("""{"type":"Line","lyrics":[{"time":1000,"text":null}]}""", 180).isEmpty)
    }

    @Test fun `LyricsPlus null syllable text keeps the complete original line`() {
        val result = LyricsPlus.parse("""{"type":"Word","lyrics":[{"time":1000,"text":"hello world","syllabus":[
          {"time":1000,"text":"hello "},{"time":1500,"text":null}]}]}""", 180)
        assertEquals(listOf("hello world"), result.lines.map { it.text })
        assertNull(result.lines.single().syllables)
    }

    @Test fun `LyricsPlus still skips absent and empty syllable text`() {
        val result = LyricsPlus.parse("""{"type":"Word","lyrics":[{"time":1000,"text":"hello world","syllabus":[
          {"time":1000,"text":"hello "},{"time":1200},{"time":1300,"text":""},{"time":1500,"text":"world"}]}]}""", 180)
        assertEquals(listOf("hello world"), result.lines.map { it.text })
        assertEquals(listOf("hello ", "world"), result.lines.single().syllables!!.map { it.text })
    }

    @Test fun `community null plain text does not pass an accidentally matching character count`() {
        assertNull(community("""{"plainLyrics":null}""", 4, "plain"))
    }

    @Test fun `community null synced lyrics allow the existing plain fallback`() {
        val result = community("""{"syncedLyrics":null,"plainLyrics":"hello"}""", 5, "synced")
        assertNotNull(result)
        assertEquals(listOf("hello"), result!!.lines.map { it.text })
        assertTrue(result.synced)
    }

    @Test fun `literal null strings remain real source text`() {
        val plus = LyricsPlus.parse("""{"type":"Line","lyrics":[{"time":1000,"text":"null"}]}""", 180)
        assertEquals(listOf("null"), plus.lines.map { it.text })
        val community = community("""{"plainLyrics":"null"}""", 4, "plain")
        assertEquals(listOf("null"), community!!.lines.map { it.text })
    }

    private fun community(base: String, count: Int, preference: String): Lyrics? {
        val responses = ArrayDeque(listOf(
            """{"data":{"syncData":{"source":{"lrclibId":42,"preferredLyricsSource":"$preference","lineCharCounts":[$count]},
              "lines":[{"start":0,"end":${count - 1},"granularity":"line","timing":1}]}}}""",
            base,
        ))
        val store = CommunitySync(temporary.newFolder(), { _, _, _, _ -> responses.removeFirst() }, { _, _, _ -> null }) { _, _ -> }
        val result = store.lookup("example-track", "Song", "Artist", 180)
        assertTrue(responses.isEmpty())
        return result
    }
}
