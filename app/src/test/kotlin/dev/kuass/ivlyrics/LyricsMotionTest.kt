package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsMotionTest {
    @Test fun `adjacent lines scroll in both directions`() {
        assertTrue(LyricsMotion.shouldScroll(4, 5, sameTrack = true, enabled = true))
        assertTrue(LyricsMotion.shouldScroll(5, 4, sameTrack = true, enabled = true))
    }

    @Test fun `seeks track changes and repeated renders do not scroll`() {
        assertFalse(LyricsMotion.shouldScroll(4, 9, true, true))
        assertFalse(LyricsMotion.shouldScroll(4, 5, false, true))
        assertFalse(LyricsMotion.shouldScroll(4, 4, true, true))
        assertFalse(LyricsMotion.shouldScroll(-1, 0, true, true))
        assertFalse(LyricsMotion.shouldScroll(Int.MIN_VALUE, 0, true, true))
        assertFalse(LyricsMotion.shouldScroll(4, 5, true, false))
    }

}
