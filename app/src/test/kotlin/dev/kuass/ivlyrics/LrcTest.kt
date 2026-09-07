package dev.kuass.ivlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    private val sample = """
        [ti:ignored]
        [00:12.50]첫 줄
        [00:15.1]둘째 줄
        [01:00.123]셋째 줄
        [00:20.00]
        [00:30.00][00:40.00]반복 줄
    """.trimIndent()

    @Test
    fun `parses timestamps with 1 2 and 3 fraction digits and sorts by time`() {
        val lines = Lrc.parse(sample)
        assertEquals(listOf(12500L, 15100L, 20000L, 30000L, 40000L, 60123L), lines.map { it.timeMs })
        assertEquals("반복 줄", lines[3].text)
        assertEquals("반복 줄", lines[4].text)
        assertEquals("", lines[2].text)
    }

    @Test
    fun `indexAt returns -1 before first line and last matching index otherwise`() {
        val lines = Lrc.parse(sample)
        assertEquals(-1, Lrc.indexAt(lines, 0))
        assertEquals(0, Lrc.indexAt(lines, 12500))
        assertEquals(0, Lrc.indexAt(lines, 15099))
        assertEquals(1, Lrc.indexAt(lines, 15100))
        assertEquals(5, Lrc.indexAt(lines, 999_999))
    }

    @Test
    fun `choose prefers synced within tolerance then plain and ignores far durations`() {
        val c = listOf(
            LrcLib.Candidate(200, null, "plain near"),
            LrcLib.Candidate(150, "[00:01.00]wrong length", null),
            LrcLib.Candidate(202, "[00:01.00]right", null),
        )
        assertEquals("[00:01.00]right", LrcLib.choose(c, 200)?.synced)
        assertEquals("[00:01.00]wrong length", LrcLib.choose(c, 0)?.synced)
        assertEquals("plain near", LrcLib.choose(listOf(c[0]), 200)?.plain)
        assertNull(LrcLib.choose(c, 300))
    }

    @Test
    fun `spread spaces plain lines evenly over the duration and drops blanks`() {
        val lines = LrcLib.spread("a\n\nb\n c \n", 90)
        assertEquals(listOf("a", "b", "c"), lines.map { it.text })
        assertEquals(listOf(0L, 30000L, 60000L), lines.map { it.timeMs })
        assertFalse(LrcLib.toLyrics(LrcLib.Candidate(90, null, "a\nb"), 90).synced)
        assertTrue(LrcLib.toLyrics(LrcLib.Candidate(90, "[00:01.00]a", null), 90).synced)
    }
}
