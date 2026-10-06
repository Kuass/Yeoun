package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

class CommunityEmptyTimingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `one line without any known timestamp is not usable sync data`() {
        assertNull(CommunitySyncCodec.apply(listOf("hello"), data(List(5) { null })))
    }

    @Test fun `an empty base cannot produce a successful empty result`() {
        assertNull(CommunitySyncCodec.apply(emptyList(), data(listOf(1000L)).copy(lineCharCounts = emptyList())))
    }

    @Test fun `zero timestamps and partially known character timings remain usable`() {
        for (times in listOf(List(5) { 0L }, listOf(null, 1000L, null, null, null))) {
            val result = CommunitySyncCodec.apply(listOf("hello"), data(times))!!
            assertEquals(listOf("hello"), result.map { it.text })
            assertEquals(times.filterNotNull().first(), result.single().timeMs)
            assertEquals("hello", result.single().syllables!!.joinToString("") { it.text })
        }
    }

    @Test fun `existing nonempty multi-line coverage threshold is unchanged`() {
        val data = CommunitySyncCodec.SyncData(listOf(CommunitySyncCodec.SyncLine(3, 5, listOf(1000L, 1100L, 1200L))), 42, listOf(3, 3, 5), false)
        assertEquals(listOf("two"), CommunitySyncCodec.apply(listOf("one", "two", "three"), data)!!.map { it.text })
    }

    @Test fun `empty track-id timing falls back to a usable ISRC result`() {
        val requests = mutableListOf<String>()
        var resolutions = 0
        val store = CommunitySync(temporary.newFolder(), { url, _, _, _ ->
            requests += url
            when {
                url.contains("trackId=") -> EMPTY_SYNC
                URI(url).path == "/ivLyrics/opendb/data/manifest.json" -> """{"base":{"url":"data/base.json"}}"""
                URI(url).path == "/ivLyrics/opendb/data/base.json" -> """{"items":{"lrclib":["$ISRC"]}}"""
                url.contains("isrc=") -> GOOD_SYNC
                URI(url).path == "/api/get/42" -> BASE
                else -> throw AssertionError("Unexpected request: $url")
            }
        }, { title, artist, duration ->
            assertEquals("Song", title); assertEquals("Artist", artist); assertEquals(180L, duration)
            resolutions++; ISRC
        }) { _, error -> assertNull(error) }
        val result = store.lookup("example-track", "Song", "Artist", 180)
        assertEquals(listOf("hello"), result!!.lines.map { it.text })
        assertEquals(1, resolutions)
        assertEquals(6, requests.size)
        assertTrue(requests[4].contains("isrc=$ISRC"))
        assertTrue(result.synced)
    }

    @Test fun `empty timing returns null when no ISRC fallback exists`() {
        var resolutions = 0
        val store = CommunitySync(temporary.newFolder(), { url, _, _, _ ->
            when {
                url.contains("trackId=") -> EMPTY_SYNC
                URI(url).path == "/api/get/42" -> BASE
                else -> throw AssertionError("Unexpected request: $url")
            }
        }, { _, _, _ -> resolutions++; null }) { _, error -> assertNull(error) }
        assertNull(store.lookup("example-track", "Song", "Artist", 180))
        assertEquals(1, resolutions)
    }

    private fun data(times: List<Long?>) = CommunitySyncCodec.SyncData(
        listOf(CommunitySyncCodec.SyncLine(0, times.lastIndex, times)), 42, listOf(times.size), false,
    )

    companion object {
        private const val ISRC = "USAAA0000001"
        private const val BASE = """{"plainLyrics":"hello"}"""
        private const val EMPTY_SYNC = """{"data":{"syncData":{"source":{"lrclibId":42,"preferredLyricsSource":"plain","lineCharCounts":[5]},"lines":[{"start":0,"end":4,"chars":[null,null,null,null,null]}]}}}"""
        private const val GOOD_SYNC = """{"data":{"syncData":{"source":{"lrclibId":42,"preferredLyricsSource":"plain","lineCharCounts":[5]},"lines":[{"start":0,"end":4,"chars":[1,1.1,1.2,1.3,1.4]}]}}}"""
    }
}
