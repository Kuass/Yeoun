package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.URI

/** Runs the production provider adapters with per-test transports, without network or global URL hooks. */
class ProviderRetryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `ISRC retries after an offline lookup and memoizes the recovered id`() {
        val requests = Requests()
        val errors = mutableListOf<Exception>()
        val resolver = Isrc.Resolver(requests::get, errors::add)
        requests.expect("/search", error = IOException("offline"))
        assertNull(resolver.resolve("Song", "Artist", 180))
        assertEquals(1, errors.size)
        requests.assertDone()

        requests.isrc()
        repeat(2) { assertEquals(ISRC, resolver.resolve("Song", "Artist", 180)) }
        requests.assertDone(3)
    }

    @Test fun `ISRC retries empty searches without losing the broad search fallback`() {
        val requests = Requests()
        val resolver = Isrc.Resolver(requests::get) { fail("Unexpected error: $it") }
        repeat(2) { requests.expect("/search", """{"data":[]}""") }
        assertNull(resolver.resolve("Song", "Artist", 180))
        requests.assertDone(2)

        requests.expect("/search", """{"data":[]}""")
        requests.isrc()
        assertEquals(ISRC, resolver.resolve("Song", "Artist", 180))
        requests.assertDone(5)
        assertTrue(requests.urls[3].contains("q=Song+Artist"))
    }

    @Test fun `ISRC retries malformed JSON and missing or invalid recording ids`() {
        for (body in listOf("not json", "{}", """{"isrc":"bad"}""")) {
            val requests = Requests()
            val resolver = Isrc.Resolver(requests::get) { }
            requests.expect("/search", SEARCH)
            requests.expect("/track/42", body)
            assertNull(resolver.resolve("Song", "Artist", 180))
            requests.isrc()
            assertEquals(ISRC, resolver.resolve("Song", "Artist", 180))
            requests.assertDone(4)
        }
    }

    @Test fun `successful ISRC cache still separates track durations`() {
        val requests = Requests()
        val resolver = Isrc.Resolver(requests::get) { fail("Unexpected error: $it") }
        requests.isrc()
        repeat(2) { assertEquals(ISRC, resolver.resolve("Song", "Artist", 180)) }
        requests.isrc()
        assertEquals(ISRC, resolver.resolve("Song", "Artist", 179))
        requests.assertDone(4)
    }

    @Test fun `video search retries after a rejected key is corrected and caches only the valid id`() {
        val requests = Requests()
        val searcher = VideoMatch.Searcher(requests::video) { _, e -> fail("Unexpected error: $e") }
        requests.expect("/youtube/v3/search", null)
        assertNull(searcher.search("invalid-example-key", "Song", "Artist"))
        requests.assertDone(1)
        assertEquals(setOf(403, 404), requests.nullOn.single())

        requests.videoResult()
        repeat(2) { assertEquals(VIDEO, searcher.search("corrected-example-key", "Song", "Artist")) }
        requests.assertDone(2)
        assertTrue(requests.urls.last().contains("key=corrected-example-key"))
    }

    @Test fun `video search retries exceptions empty results malformed JSON and invalid ids`() {
        for (body in listOf(null, "not json", """{"items":[]}""", """{"items":[{"id":{"videoId":"bad"}}]}""")) {
            val requests = Requests()
            val searcher = VideoMatch.Searcher(requests::video) { _, _ -> }
            requests.expect("/youtube/v3/search", body, if (body == null) IOException("offline") else null)
            assertNull(searcher.search("example-key", "Song", "Artist"))
            requests.videoResult()
            assertEquals(VIDEO, searcher.search("example-key", "Song", "Artist"))
            requests.assertDone(2)
        }
    }

    @Test fun `blank video keys do not make requests or poison later valid lookups`() {
        val requests = Requests()
        val searcher = VideoMatch.Searcher(requests::video) { _, e -> fail("Unexpected error: $e") }
        assertNull(searcher.search("  ", "Song", "Artist"))
        requests.assertDone(0)
        requests.videoResult()
        assertEquals(VIDEO, searcher.search("example-key", "Song", "Artist"))
        assertNull(searcher.search("", "Song", "Artist"))
        requests.assertDone(1)
    }

    @Test fun `community retries the same track after its ISRC provider recovers`() {
        val requests = Requests()
        val resolver = Isrc.Resolver(requests::get) { }
        val community = community(requests, resolver::resolve)
        requests.expect("/search", error = IOException("offline"))
        assertNull(community.lookup(null, "Song", "Artist", 180))
        requests.assertDone(1)

        requests.isrc()
        requests.openDb()
        requests.lyrics()
        assertLyrics(community.lookup(null, "Song", "Artist", 180))
        requests.assertDone(7)

        // A later lookup reuses the successful ISRC and OpenDB caches.
        requests.lyrics()
        assertLyrics(community.lookup(null, "Song", "Artist", 180))
        requests.assertDone(9)
    }

    @Test fun `community retries a track id even when its previous ISRC fallback found nothing`() {
        val requests = Requests()
        var resolutions = 0
        val community = community(requests) { _, _, _ -> resolutions++; null }
        requests.expect("/lyrics/sync-data", null)
        assertNull(community.lookup(TRACK, "Song", "Artist", 180))
        requests.assertDone(1)
        assertEquals(1, resolutions)

        requests.lyrics()
        assertLyrics(community.lookup(TRACK, "Song", "Artist", 180))
        requests.assertDone(3)
        assertEquals(1, resolutions)
        assertTrue(requests.urls.last { it.contains("sync-data") }.contains("trackId=$TRACK"))
    }

    @Test fun `community preserves the immediate ISRC fallback after a track id miss`() {
        val requests = Requests()
        val community = community(requests) { _, _, _ -> ISRC }
        requests.expect("/lyrics/sync-data", null)
        requests.openDb()
        requests.lyrics()
        assertLyrics(community.lookup(TRACK, "Song", "Artist", 180))
        requests.assertDone(5)
        assertTrue(requests.urls[3].contains("isrc=$ISRC"))
        assertEquals(mapOf("Origin" to "https://xpui.app.spotify.com"), requests.headers[3])
        assertEquals(setOf(400, 404), requests.nullOn[3])
    }

    @Test fun `community misses retry only on a later lookup with no eager retry loop`() {
        val requests = Requests()
        var resolutions = 0
        val community = community(requests) { _, _, _ -> resolutions++; null }
        repeat(2) { attempt ->
            requests.expect("/lyrics/sync-data", null)
            assertNull(community.lookup(TRACK, "Song", "Artist", 180))
            requests.assertDone(attempt + 1)
            assertEquals(attempt + 1, resolutions)
        }
    }

    @Test fun `community retries an unavailable OpenDB manifest`() {
        val requests = Requests()
        val community = community(requests) { _, _, _ -> ISRC }
        requests.expect("/ivLyrics/opendb/data/manifest.json", null)
        assertNull(community.lookup(null, "Song", "Artist", 180))
        requests.openDb()
        requests.lyrics()
        assertLyrics(community.lookup(null, "Song", "Artist", 180))
        requests.assertDone(5)
    }

    @Test fun `community retries direct HTTP exceptions without consulting the fallback`() {
        val requests = Requests()
        val community = community(requests) { _, _, _ -> fail("Exception should reach the existing outer fallback"); null }
        requests.expect("/lyrics/sync-data", error = IOException("HTTP 503"))
        assertNull(community.lookup(TRACK, "Song", "Artist", 180))
        requests.lyrics()
        assertLyrics(community.lookup(TRACK, "Song", "Artist", 180))
        requests.assertDone(3)
    }

    @Test fun `community can recover from mismatched base text while retaining shape validation`() {
        val requests = Requests()
        val community = community(requests) { _, _, _ -> null }
        requests.expect("/lyrics/sync-data", SYNC)
        requests.expect("/api/get/42", """{"plainLyrics":"wrong shape"}""")
        assertNull(community.lookup(TRACK, "Song", "Artist", 180))
        requests.lyrics()
        assertLyrics(community.lookup(TRACK, "Song", "Artist", 180))
        requests.assertDone(4)
    }

    private fun community(requests: Requests, resolve: (String, String, Long) -> String?) =
        CommunitySync(temporary.newFolder(), requests::get, resolve) { _, _ -> }

    private fun assertLyrics(lyrics: Lyrics?) {
        assertNotNull(lyrics)
        assertEquals(CommunitySync.ID, lyrics!!.source)
        assertTrue(lyrics.synced)
        assertEquals(listOf("hello"), lyrics.lines.map { it.text })
        assertEquals(listOf(1000L, 1200L, 1400L, 1600L, 1800L), lyrics.lines.single().syllables!!.map { it.startMs })
    }

    private class Requests {
        private data class Reply(val path: String, val body: String?, val error: IOException?)
        private val replies = ArrayDeque<Reply>()
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val nullOn = mutableListOf<Set<Int>>()

        fun expect(path: String, body: String? = null, error: IOException? = null) { replies.addLast(Reply(path, body, error)) }
        fun isrc() { expect("/search", SEARCH); expect("/track/42", """{"isrc":"$ISRC"}""") }
        fun videoResult() { expect("/youtube/v3/search", """{"items":[{"id":{"videoId":"$VIDEO"}}]}""") }
        fun openDb() {
            expect("/ivLyrics/opendb/data/manifest.json", """{"base":{"url":"data/base.json"}}""")
            expect("/ivLyrics/opendb/data/base.json", """{"items":{"lrclib":["$ISRC"]}}""")
        }
        fun lyrics() { expect("/lyrics/sync-data", SYNC); expect("/api/get/42", """{"plainLyrics":"hello"}""") }
        fun get(url: String, timeout: Int): String? = get(url, timeout, emptyMap(), setOf(404))
        fun video(url: String, timeout: Int, nullOn: Set<Int>): String? = get(url, timeout, emptyMap(), nullOn)
        fun get(url: String, timeout: Int, headers: Map<String, String>, nullOn: Set<Int>): String? {
            assertTrue("Unexpected request: $url", replies.isNotEmpty())
            val reply = replies.removeFirst()
            assertEquals(reply.path, URI(url).path)
            assertTrue(timeout > 0)
            urls += url
            this.headers += headers
            this.nullOn += nullOn
            reply.error?.let { throw it }
            return reply.body
        }
        fun assertDone(count: Int = urls.size) { assertTrue("Unused responses: $replies", replies.isEmpty()); assertEquals(count, urls.size) }
    }

    companion object {
        private const val ISRC = "USAAA0000001"
        private const val TRACK = "0123456789012345678901"
        private const val VIDEO = "abcdefghijk"
        private const val SEARCH = """{"data":[{"id":42,"title":"Song","artist":{"name":"Artist"},"duration":180}]}"""
        private const val SYNC = """{"data":{"syncData":{"source":{"lrclibId":42,"preferredLyricsSource":"plain","lineCharCounts":[5]},"lines":[{"start":0,"end":4,"chars":[1,1.2,1.4,1.6,1.8]}]}}}"""
    }
}
