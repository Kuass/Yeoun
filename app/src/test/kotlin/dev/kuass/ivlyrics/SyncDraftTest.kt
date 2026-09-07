package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class SyncDraftTest {
    @Test fun `a new track cannot save the previous track text or timing`() {
        val draft = SyncDraft()
        draft.selectTrack("first", listOf("first lyric"))
        assertTrue(draft.stamp(1200))
        draft.selectTrack("second")
        assertEquals("second", draft.key)
        assertTrue(draft.lines.isEmpty())
        assertTrue(draft.times.isEmpty())
        assertEquals("", LrcWriter.write(draft.lines, draft.times))
        assertFalse(draft.stamp(2000))
    }

    @Test fun `repeated metadata preserves edits for the same track`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b"))
        draft.stamp(1000)
        assertFalse(draft.selectTrack("track"))
        assertEquals(listOf(1000L, null), draft.times)
        assertEquals(1, draft.cursor)
    }

    @Test fun `restamping invalidates conflicting following times and undo restores them`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b", "c"), listOf(1000L, 2000L, 3000L))
        draft.select(0)
        assertTrue(draft.stamp(2500))
        assertEquals(listOf(2500L, null, 3000L), draft.times)
        draft.undo()
        assertEquals(listOf(1000L, 2000L, 3000L), draft.times)
        assertEquals(0, draft.cursor)
    }

    @Test fun `an earlier timestamp never silently rewrites preceding lyrics`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b"), listOf(2000L, null))
        assertFalse(draft.stamp(1000))
        assertEquals(listOf(2000L, null), draft.times)
        assertEquals(1, draft.cursor)
    }

    @Test fun `restoring a partial draft preserves null times and can continue`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b"), listOf(1000L, null))
        assertTrue(draft.stamp(2000))
        assertEquals(listOf("a", "b"), Lrc.parse(LrcWriter.write(draft.lines, draft.times)).map { it.text })
    }
}
