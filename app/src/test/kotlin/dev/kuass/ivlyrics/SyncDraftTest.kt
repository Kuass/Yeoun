package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class SyncDraftTest {
    @Test fun `a new track cannot save the previous track text or timing`() {
        val draft = SyncDraft()
        draft.selectTrack("first", listOf("first lyric"))
        assertTrue(draft.stamp(1200, "first"))
        draft.selectTrack("second")
        assertEquals("second", draft.key)
        assertTrue(draft.lines.isEmpty())
        assertTrue(draft.times.isEmpty())
        assertEquals("", LrcWriter.write(draft.lines, draft.times))
        assertFalse(draft.stamp(2000, "second"))
    }

    @Test fun `repeated metadata preserves edits for the same track`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b"))
        draft.stamp(1000, "track")
        assertFalse(draft.selectTrack("track"))
        assertEquals(listOf(1000L, null), draft.times)
        assertEquals(1, draft.cursor)
    }

    @Test fun `restamping invalidates conflicting following times and undo restores them`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b", "c"), listOf(1000L, 2000L, 3000L))
        draft.select(0)
        assertTrue(draft.stamp(2500, "track"))
        assertEquals(listOf(2500L, null, 3000L), draft.times)
        draft.undo()
        assertEquals(listOf(1000L, 2000L, 3000L), draft.times)
        assertEquals(0, draft.cursor)
    }

    @Test fun `an earlier timestamp never silently rewrites preceding lyrics`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b"), listOf(2000L, null))
        assertFalse(draft.stamp(1000, "track"))
        assertEquals(listOf(2000L, null), draft.times)
        assertEquals(1, draft.cursor)
    }

    @Test fun `restoring a partial draft preserves null times and can continue`() {
        val draft = SyncDraft()
        draft.selectTrack("track", listOf("a", "b"), listOf(1000L, null))
        assertTrue(draft.stamp(2000, "track"))
        assertEquals(listOf("a", "b"), Lrc.parse(LrcWriter.write(draft.lines, draft.times)).map { it.text })
    }

    @Test fun `missing and different session identities cannot stamp a retained draft`() {
        val draft = SyncDraft()
        draft.selectTrack("saved", listOf("a", "b"), listOf(1000L, null))
        for (session in listOf(null, "other")) {
            assertFalse(draft.canStamp(session))
            assertFalse(draft.stamp(2000, session))
            assertEquals(listOf(1000L, null), draft.times)
            assertEquals(1, draft.cursor)
        }
    }

    @Test fun `identifying the same track again allows the retained draft to continue`() {
        val draft = SyncDraft()
        draft.selectTrack("saved", listOf("a", "b"), listOf(1000L, null))
        assertFalse(draft.canStamp(null))
        assertTrue(draft.canStamp("saved"))
        assertTrue(draft.stamp(2000, "saved"))
        assertEquals(listOf(1000L, 2000L), draft.times)
        assertFalse(draft.canStamp("saved"))
    }

    @Test fun `rejected session identity leaves undo history untouched`() {
        val draft = SyncDraft()
        draft.selectTrack("saved", listOf("a", "b"))
        assertTrue(draft.stamp(1000, "saved"))
        assertFalse(draft.stamp(2000, "other"))
        draft.undo()
        assertEquals(listOf<Long?>(null, null), draft.times)
        assertEquals(0, draft.cursor)
    }

    @Test fun `an unselected or finished draft cannot accept timestamps`() {
        val draft = SyncDraft()
        assertFalse(draft.canStamp(null))
        assertFalse(draft.stamp(0, null))
        draft.selectTrack("saved", listOf("a"))
        assertFalse(draft.stamp(-1, "saved"))
        assertTrue(draft.stamp(0, "saved"))
        assertFalse(draft.stamp(1000, "saved"))
    }
}
