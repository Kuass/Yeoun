package dev.kuass.ivlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLogicTest {
    @Test
    fun `window clamps to list bounds`() {
        assertEquals(0..3, LyricsView.window(1, 2, 2, 10))
        assertEquals(7..9, LyricsView.window(8, 1, 5, 10))
        assertEquals(4..4, LyricsView.window(4, 0, 0, 10))
        assertEquals(0..1, LyricsView.window(-1, 1, 2, 10))
    }

    @Test
    fun `track keys do not collide when a pipe appears in the title or artist`() {
        assertEquals(false, TrackPrefs.key("A|B", "C", 200) == TrackPrefs.key("A", "B|C", 200))
        assertEquals(TrackPrefs.key("A", "B", 1), TrackPrefs.key("A", "B", 1))
    }

    @Test
    fun `YouTube ids are parsed from urls and bare ids only`() {
        assertEquals("dQw4w9WgXcQ", VideoMatch.parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", VideoMatch.parseVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoMatch.parseVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoMatch.parseVideoId(" dQw4w9WgXcQ "))
        assertNull(VideoMatch.parseVideoId("not a video"))
    }

    @Test
    fun `LrcWriter emits only timed lines and round-trips through the parser`() {
        val lrc = LrcWriter.write(listOf("a", "b", "c"), listOf(1500L, null, 61_230L))
        assertEquals("[00:01.50]a\n[01:01.23]c", lrc)
        assertEquals(listOf(1500L to "a", 61_230L to "c"), Lrc.parse(lrc).map { it.timeMs to it.text })
        assertEquals("", LrcWriter.write(listOf("a"), listOf(null)))
        assertEquals("00:00.00", LrcWriter.stamp(-500))
    }

    @Test
    fun `provider preset resolves both ways`() {
        assertEquals("Groq", Providers.nameFor("https://api.groq.com/openai/v1/"))
        assertEquals(Providers.CUSTOM, Providers.nameFor("https://example.com/v1"))
        assertEquals("https://openrouter.ai/api/v1", Providers.baseUrlFor("OpenRouter"))
        assertNull(Providers.baseUrlFor(Providers.CUSTOM))
    }

    @Test
    fun `parseModels reads ids sorted and ignores junk`() {
        val body = """{"data":[{"id":"gpt-5"},{"id":"claude-x"},{"object":"model"},{"id":""}]}"""
        assertEquals(listOf("claude-x", "gpt-5"), Ai.parseModels(body))
        assertEquals(emptyList<String>(), Ai.parseModels("{}"))
    }
}
