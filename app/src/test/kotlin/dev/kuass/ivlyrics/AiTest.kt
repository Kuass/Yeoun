package dev.kuass.ivlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {
    @Test
    fun `align accepts exact count, strips code fences and stray blank lines, rejects mismatch`() {
        assertEquals(listOf("가", "", "나"), Ai.align("가\n\n나", 3))
        assertEquals(listOf("가", "나"), Ai.align("```\n가\n나\n```", 2))
        assertEquals(listOf("가", "나"), Ai.align("가\n\n나\n\n", 2))
        assertNull(Ai.align("가\n나\n다", 2))
        assertNull(Ai.align("가", 2))
    }

    @Test
    fun `withContentLines sends only content lines and maps results back to original positions`() {
        val lines = listOf("", "night", "♪", "sky", "")
        var sent: List<String>? = null
        val out = Ai.withContentLines(lines) { sub -> sent = sub; sub.map { "$it!" } }
        assertEquals(listOf("night", "sky"), sent)
        assertEquals(listOf("", "night!", "", "sky!", ""), out)
        assertNull(Ai.withContentLines(listOf("", "♪")) { it })
        assertNull(Ai.withContentLines(lines) { null })
    }

    @Test
    fun `withContentLines splits into chunks of 20 and blanks only the failed chunk`() {
        val lines = List(45) { "line $it" }
        val sizes = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val out = Ai.withContentLines(lines) { sub -> sizes.add(sub.size); if (sub.first() == "line 20") null else sub.map { it.uppercase() } }!!
        assertEquals(listOf(20, 20, 5), sizes.sorted().reversed())
        assertEquals("LINE 0", out[0]); assertEquals("LINE 19", out[19])
        assertEquals("", out[20]); assertEquals("", out[39])
        assertEquals("LINE 40", out[40]); assertEquals("LINE 44", out[44])
    }

    @Test
    fun `isAlreadyIn detects the target language by script and English by common words`() {
        val ko = Lang.target("ko"); val en = Lang.target("en"); val ja = Lang.target("ja")
        assertTrue(Lang.isAlreadyIn(listOf("사랑해 baby", "Every time I look up in the sky", "너를 위해"), ko))
        assertFalse(Lang.isAlreadyIn(listOf("夢ならばどれほどよかったでしょう", "Yeah", "사랑"), ko))
        assertTrue(Lang.isAlreadyIn(listOf("夢ならばどれほどよかったでしょう", "未だにあなたのことを夢にみる"), ja))
        assertTrue(Lang.isAlreadyIn(listOf("All I need is one", "One old man is enough", "Babe, you got it wrong", "Please turn your fears into trust"), en))
        assertFalse(Lang.isAlreadyIn(listOf("사랑해", "너를 위해 부르는 노래"), en))
        assertFalse(Lang.isAlreadyIn(listOf("♪", ""), ko))
    }

    @Test
    fun `options fingerprint changes with any prompt-affecting setting`() {
        val base = Ai.Options(Lang.target("ko"), Lang.Style.NATURAL, "", Lang.Notation.SCRIPT)
        assertEquals(base.fingerprint, base.copy(instruction = "  ").fingerprint)
        assertFalse(base.fingerprint == base.copy(style = Lang.Style.LITERAL).fingerprint)
        assertFalse(base.fingerprint == base.copy(target = Lang.target("en")).fingerprint)
        assertFalse(base.fingerprint == base.copy(notation = Lang.Notation.IPA).fingerprint)
        assertFalse(base.fingerprint == base.copy(instruction = "반말로").fingerprint)
    }
}
