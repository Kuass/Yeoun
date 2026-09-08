package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class HeightTransitionTest {
    @Test fun `growth and shrink start at the visible height and settle at the target`() {
        val motion = HeightTransition()
        assertEquals(100, motion.update(100, 0, true))
        assertEquals(100, motion.update(240, 100, true))
        assertTrue(motion.update(240, 250, true) in 101..239)
        assertEquals(240, motion.update(240, 450, true))
        assertEquals(240, motion.update(80, 500, true))
        assertTrue(motion.update(80, 650, true) in 81..239)
        assertEquals(80, motion.update(80, 850, true))
    }
    @Test fun `interruption retargets continuously and reduced motion settles immediately`() {
        val motion = HeightTransition()
        motion.update(100, 0, true)
        motion.update(300, 10, true)
        val current = motion.update(300, 130, true)
        assertEquals(current, motion.update(60, 130, true))
        assertEquals(60, motion.update(60, 480, true))
        assertEquals(400, motion.update(400, 490, false))
        assertFalse(motion.running(490))
    }
}
