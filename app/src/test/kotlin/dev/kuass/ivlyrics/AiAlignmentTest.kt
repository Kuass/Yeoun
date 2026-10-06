package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class AiAlignmentTest {
    @Test fun `blank lines outside code fences do not count as lyric lines`() {
        assertEquals(listOf("one", "two"), Ai.align("\n\n```text\none\ntwo\n```\n\n", 2))
    }

    @Test fun `indented fences and CRLF output accept surrounding whitespace`() {
        assertEquals(listOf("one", "two"), Ai.align(" \t\r\n  ```text\r\n one \r\n two \r\n  ```\r\n\t ", 2))
    }

    @Test fun `empty lines inside fences retain their original indices when the count matches`() {
        assertEquals(listOf("one", "", "three"), Ai.align("\n```\none\n\nthree\n```\n", 3))
        assertEquals(listOf("", "two", ""), Ai.align("\n```\n\ntwo\n\n```\n", 3))
    }

    @Test fun `one-sided fences retain existing tolerance with exterior blank lines`() {
        assertEquals(listOf("one", "two"), Ai.align("\n```\none\ntwo\n", 2))
        assertEquals(listOf("one", "two"), Ai.align("\none\ntwo\n```\n", 2))
    }

    @Test fun `unfenced exact-count blank lines remain in place`() {
        assertEquals(listOf("", "one", "two", ""), Ai.align("\none\ntwo\n", 4))
        assertEquals(listOf("one", ""), Ai.align("one\n \t", 2))
    }

    @Test fun `stripping the wrapper never truncates extra lines or fills missing ones`() {
        val output = "\n```\none\ntwo\nthree\n```\n"
        assertNull(Ai.align(output, 2))
        assertNull(Ai.align(output, 4))
        assertNull(Ai.align("\n```\nonly\n```\n", 2))
    }

    @Test fun `wrapped chunk results map back across blank and instrumental input lines`() {
        val lines = listOf("", "one", "♪", "two", "")
        val result = Ai.withContentLines(lines) { chunk ->
            Ai.align("\n```\n${chunk.joinToString("\n") { it.uppercase() }}\n```\n", chunk.size)
        }
        assertEquals(listOf("", "ONE", "", "TWO", ""), result)
    }
}
