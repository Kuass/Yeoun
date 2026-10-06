package dev.kuass.ivlyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.LinkedList
import kotlin.random.Random

class SungCharsTest {
    @Test fun `empty and not yet started lines have no sung characters`() {
        assertEquals(0, Lrc.sungChars(emptyList(), Long.MAX_VALUE))
        assertEquals(0, Lrc.sungChars(listOf(Syl(100, 200, "hello")), 99))
    }

    @Test fun `inclusive starts count UTF-16 characters including spaces and empty syllables`() {
        val syllables = listOf(Syl(0, 1, "한 "), Syl(100, 110, ""), Syl(100, 200, "🎵"), Syl(200, 300, "e\u0301"))
        assertEquals(2, Lrc.sungChars(syllables, 0))
        assertEquals(4, Lrc.sungChars(syllables, 100))
        assertEquals(6, Lrc.sungChars(syllables, 200))
        assertEquals(6, Lrc.sungChars(syllables, Long.MAX_VALUE))
    }

    @Test fun `only the started prefix is counted even when later timestamps are out of order`() {
        val syllables = listOf(Syl(-10, -5, "one"), Syl(20, 30, "two"), Syl(0, 10, "three"))
        assertEquals(0, Lrc.sungChars(syllables, Long.MIN_VALUE))
        assertEquals(3, Lrc.sungChars(syllables, 0))
        assertEquals(11, Lrc.sungChars(syllables, 20))
    }

    @Test fun `stops reading at the first future syllable`() {
        val syllables = object : AbstractList<Syl>() {
            override val size = 100
            override fun get(index: Int): Syl = when (index) {
                0 -> Syl(0, 5, "a")
                1 -> Syl(10, 15, "b")
                else -> error("The remaining syllables must not be read")
            }
        }
        assertEquals(1, Lrc.sungChars(syllables, 5))
    }

    @Test fun `matches the previous prefix sum for array and linked lists across seeks`() {
        val random = Random(72139)
        repeat(1000) {
            val syllables = List(random.nextInt(100)) {
                Syl(random.nextLong(-100, 100), random.nextLong(-100, 100), listOf("", "a", "한 ", "🎵", "e\u0301").random(random))
            }
            for (position in listOf(Long.MIN_VALUE, -100L, random.nextLong(-100, 100), 0L, 99L, Long.MAX_VALUE)) {
                val expected = syllables.takeWhile { it.startMs <= position }.sumOf { it.text.length }
                assertEquals(expected, Lrc.sungChars(syllables, position))
                assertEquals(expected, Lrc.sungChars(LinkedList(syllables), position))
            }
        }
    }
}
