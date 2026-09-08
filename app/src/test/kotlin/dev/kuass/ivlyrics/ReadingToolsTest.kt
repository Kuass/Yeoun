package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class ReadingToolsTest {
    @Test fun `offline fallback respects disabled providers and never resurrects removed local lyrics`() {
        val selected = LyricsSources.Selection(listOf(LrcLib.ID), false)
        assertTrue(selected.acceptsCached(LrcLib.ID))
        assertFalse(selected.acceptsCached(LyricsPlus.ID))
        assertFalse(selected.acceptsCached(CommunitySync.ID))
        assertFalse(selected.acceptsCached(LocalLyrics.ID))
        assertTrue(selected.copy(community = true).acceptsCached(CommunitySync.ID))
    }

    @Test fun `pronunciation pairs preserve original punctuation and spaces without guessing mismatched chunks`() {
        val parts = requireNotNull(InlinePronunciation.align("Hello,   my friend!", "헬로｜마이｜프렌드"))
        assertEquals(listOf(0 to 6, 9 to 11, 12 to 19), parts.map { it.start to it.end })
        assertEquals(listOf("헬로", "마이", "프렌드"), parts.map { it.reading })
        assertNull(InlinePronunciation.align("one two", "하나"))
        assertNull(InlinePronunciation.align("one two", "하나｜"))
        assertNull(InlinePronunciation.align("مرحبا", "hello"))
        assertEquals("하나 둘", InlinePronunciation.plain("하나｜둘"))
    }
    @Test fun `manual scroll stays paused during touch and resumes three seconds after release`() {
        val follow = ScrollFollow()
        assertFalse(follow.paused(1))
        follow.begin(); assertTrue(follow.paused(100000))
        follow.end(100000); assertTrue(follow.paused(102999)); assertFalse(follow.paused(103000))
        follow.begin(); follow.resume(); assertFalse(follow.paused(103001))
    }
    @Test fun `progress distinguishes hidden choice setup running partial failures and successful empty edits`() {
        fun channel(values: List<String>?, configured: Boolean = true, done: Boolean = false, edits: Set<Int> = emptySet()) =
            ExtrasProgress.channel(listOf(true, true), values, edits, configured, done, false)
        assertEquals(ExtrasProgress.State.SETUP, channel(null, false).state)
        assertEquals(ExtrasProgress.State.RUNNING, channel(listOf("one", "")).state)
        assertEquals(ExtrasProgress.Channel(ExtrasProgress.State.FAILED, 1, 2), channel(listOf("one", ""), done = true))
        assertEquals(ExtrasProgress.State.READY, channel(listOf("one", ""), edits = setOf(1)).state)
        assertEquals(ExtrasProgress.State.CHOICE, ExtrasProgress.channel(emptyList(), null, emptySet(), true, false, true).state)
    }
    @Test fun `backup round trip filters keys and validates all payloads before restore`() {
        val data = BackupCodec.Data(mapOf(Prefs.API_KEY to "secret", Prefs.FONT_SP to 22, LanguagePrefs.PREFIX + "en" to "01"),
            mapOf("track" to """{"offset":100,"lang":"ko"}"""), mapOf("track" to "[00:01]hello"), emptyMap(), mapOf("a".repeat(64) to ExtrasEdits(mapOf(0 to "hi")).encode()))
        val encoded = BackupCodec.encode(data)
        assertFalse(encoded.contains("secret"))
        val restored = BackupCodec.read(encoded.byteInputStream())
        assertEquals(22, restored.settings[Prefs.FONT_SP])
        assertEquals(data.local, restored.local)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded.replace("\"version\":1", "\"version\":99")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(data.copy(edits = mapOf("../path" to "{}")))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(data.copy(local = mapOf("track" to "no timing")))) }
    }
    @Test fun `offline originals retain source and syllable timing without inventing sync for plain lyrics`() {
        val lyrics = Lyrics(listOf(LrcLine(1000, "one", listOf(Syl(1000, 1400, "one")))), true, LyricsPlus.ID)
        val song = SongArchiveCodec.Song(Triple("song", "artist", 120L), lyrics, 20)
        assertEquals(song, SongArchiveCodec.decode(SongArchiveCodec.encode(song)))
        val plain = song.copy(lyrics = lyrics.copy(synced = false))
        assertFalse(SongArchiveCodec.decode(SongArchiveCodec.encode(plain)).lyrics.synced)
    }
}
