package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsRequestGateTest {
    private val song = Triple("song", "artist", 180L)

    @Test fun `saving local lyrics rejects a pending online result for the same song`() {
        val gate = LyricsRequestGate()
        val online = gate.begin(song)
        val local = gate.begin(song)
        assertFalse(gate.accepts(online))
        assertTrue(gate.accepts(local))
    }

    @Test fun `old translation is rejected after a language change or local deletion`() {
        val gate = LyricsRequestGate()
        val translated = gate.begin(song)
        val changedLanguage = gate.begin(song)
        val removedLocal = gate.begin(song)
        assertFalse(gate.accepts(translated))
        assertFalse(gate.accepts(changedLanguage))
        assertTrue(gate.accepts(removedLocal))
    }

    @Test fun `returning to the same song does not reactivate an earlier request`() {
        val gate = LyricsRequestGate()
        val old = gate.begin(song)
        gate.begin(Triple("other", "artist", 200L))
        val current = gate.begin(song)
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(current))
        gate.invalidate()
        assertFalse(gate.accepts(current))
    }
}
