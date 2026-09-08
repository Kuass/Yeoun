package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsDocumentTest {
    @Test fun `imports UTF8 BOM CRLF and preserves offset and repeated lines`() {
        val raw = "\uFEFF[offset:100]\r\n[00:01.25][00:03.00]안녕\r\n[00:04.00]"
        val text = LyricsDocument.read(raw.byteInputStream())
        assertFalse(text.startsWith("\uFEFF"))
        assertEquals(listOf(1150L, 2900L, 3900L), Lrc.parse(text).map { it.timeMs })
    }

    @Test fun `rejects plain text metadata only empty timed lines and binary files`() {
        listOf("hello", "[ti:song]", "[00:01.00]   ", "[00:01.00]hi\u0000").forEach {
            assertThrows(IllegalArgumentException::class.java) { LyricsDocument.read(it.byteInputStream()) }
        }
    }

    @Test fun `rejects oversized and malformed UTF8 documents`() {
        assertThrows(IllegalArgumentException::class.java) {
            LyricsDocument.read(ByteArray(LyricsDocument.MAX_BYTES + 1) { 65 }.inputStream())
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            LyricsDocument.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream())
        }
    }

    @Test fun `bad timestamps cannot crash parsing or wrap into negative times`() {
        val lines = Lrc.parse("[999999999999999999999999:00]bad\n[00:99]bad\n[00:02]good")
        assertEquals(listOf(LrcLine(2000, "good")), lines)
        assertEquals(Long.MAX_VALUE, Lrc.parse("[offset:-9223372036854775808]\n[00:01]good").single().timeMs)
    }

    @Test fun `line timing export can be imported again`() {
        val lines = listOf("one", "", "three")
        val times = listOf(10L, 2100L, 65230L)
        val result = Lrc.parse(LyricsDocument.read(LrcWriter.write(lines, times).byteInputStream()))
        assertEquals(lines, result.map { it.text })
        assertEquals(times, result.map { it.timeMs })
    }
}
