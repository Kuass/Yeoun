package dev.kuass.ivlyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionTest {
    @Test fun `paused and other non-playing states retain the reported position`() {
        assertEquals(10_000L, at(playing = false, now = 20_000))
        assertEquals(10_000L, at(playing = false, speed = Float.NaN, now = Long.MAX_VALUE))
    }

    @Test fun `playing extrapolates from the last update using playback speed`() {
        assertEquals(11_000L, at(now = 2_000))
        assertEquals(10_500L, at(speed = 0.5f, now = 2_000))
        assertEquals(11_500L, at(speed = 1.5f, now = 2_000))
    }

    @Test fun `no elapsed time and zero speed leave the base position unchanged`() {
        assertEquals(10_000L, at(now = 1_000))
        assertEquals(10_000L, at(speed = 0f, now = 20_000))
    }

    @Test fun `fractional advances truncate toward zero before adding the base`() {
        assertEquals(10_001L, at(speed = 0.5f, now = 1_003))
        assertEquals(9_999L, at(speed = -0.5f, now = 1_003))
    }

    @Test fun `future update times and negative speeds retain signed extrapolation`() {
        assertEquals(9_500L, at(now = 500))
        assertEquals(9_000L, at(speed = -1f, now = 2_000))
    }

    @Test fun `negative positions and offsets are not clamped`() {
        assertEquals(-1L, at(position = -1, playing = false))
        assertEquals(-1_001L, at(position = -1, playing = false, offset = -1_000))
        assertEquals(-500L, at(position = 0, now = 500))
    }

    @Test fun `global and track offsets are added for both playing and paused displays`() {
        assertEquals(11_350L, at(now = 2_000, offset = 500, trackOffset = -150))
        assertEquals(10_350L, at(playing = false, now = 2_000, offset = 500, trackOffset = -150))
        assertEquals(10_000L, at(offset = -500, trackOffset = 500))
    }

    @Test fun `omitted offsets keep authored timestamps at the raw session position`() {
        val raw = PlaybackPosition.at(10_000, true, 1_000, 1f, 2_000)
        val displayed = at(now = 2_000, offset = 500, trackOffset = 250)
        assertEquals(11_000L, raw)
        assertEquals(raw + 750, displayed)
    }

    @Test fun `offset addition stays in Long arithmetic`() {
        assertEquals(4_294_967_294L, at(position = 0, playing = false, offset = Int.MAX_VALUE, trackOffset = Int.MAX_VALUE))
    }

    @Test fun `large elapsed times retain the existing Float precision`() {
        // Converting the elapsed Long to Float rounds 16_777_217 to 16_777_216.
        assertEquals(16_787_216L, at(now = 16_778_217))
    }

    private fun at(
        position: Long = 10_000,
        playing: Boolean = true,
        speed: Float = 1f,
        now: Long = 1_000,
        offset: Int = 0,
        trackOffset: Int = 0,
    ) = PlaybackPosition.at(position, playing, 1_000, speed, now, offset, trackOffset)
}
