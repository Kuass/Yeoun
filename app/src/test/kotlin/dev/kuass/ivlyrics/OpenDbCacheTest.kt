package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URI

/** Exercises the production adapter with real temporary files and inert transport responses. */
class OpenDbCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `a directory at the cache file does not discard downloaded provider data`() {
        val root = temporary.newFolder()
        assertTrue(File(root, "community/opendb.json").mkdirs())
        assertUsableWithoutDiskCache(root)
    }

    @Test fun `a file at the cache directory does not discard downloaded provider data`() {
        val root = temporary.newFolder()
        File(root, "community").writeText("occupied")
        assertUsableWithoutDiskCache(root)
        assertEquals("occupied", File(root, "community").readText())
    }

    @Test fun `a cache directory removed after construction does not discard downloaded data`() {
        val root = temporary.newFolder()
        val requests = Requests()
        val errors = mutableListOf<Exception>()
        val adapter = community(root, requests, errors)
        assertTrue(File(root, "community").delete())
        assertUsableWithoutDiskCache(root, requests, errors, adapter)
    }

    @Test fun `a successful disk cache can be reused by a new adapter`() {
        val root = temporary.newFolder()
        val requests = Requests()
        val errors = mutableListOf<Exception>()
        val adapter = community(root, requests, errors)
        requests.openDb(); requests.lyrics()
        assertLyrics(adapter.lookup(null, "Song", "Artist", 180))
        requests.assertDone(4)
        assertTrue(File(root, "community/opendb.json").isFile)
        val reloaded = community(root, requests, errors)
        requests.lyrics()
        assertLyrics(reloaded.lookup(null, "Song", "Artist", 180))
        requests.assertDone(6)
        assertTrue(errors.isEmpty())
    }

    @Test fun `a direct track lookup does not require the OpenDB cache`() {
        val root = temporary.newFolder()
        File(root, "community").writeText("occupied")
        val requests = Requests()
        val errors = mutableListOf<Exception>()
        val adapter = community(root, requests, errors)
        requests.lyrics("trackId=example-track")
        assertLyrics(adapter.lookup("example-track", "Song", "Artist", 180))
        requests.assertDone(2)
        assertTrue(errors.isEmpty())
    }

    @Test fun `malformed provider data remains a failed lookup and is not cached`() {
        val root = temporary.newFolder()
        val requests = Requests()
        val errors = mutableListOf<Exception>()
        val adapter = community(root, requests, errors)
        requests.expect("/ivLyrics/opendb/data/manifest.json", MANIFEST)
        requests.expect("/ivLyrics/opendb/data/base.json", "not json", 20_000)
        assertNull(adapter.lookup(null, "Song", "Artist", 180))
        requests.assertDone(2)
        assertEquals(1, errors.size)
        assertFalse(File(root, "community/opendb.json").exists())
        requests.openDb(); requests.lyrics()
        assertLyrics(adapter.lookup(null, "Song", "Artist", 180))
        requests.assertDone(6)
    }

    private fun assertUsableWithoutDiskCache(
        root: File,
        requests: Requests = Requests(),
        errors: MutableList<Exception> = mutableListOf(),
        adapter: CommunitySync = community(root, requests, errors),
    ) {
        requests.openDb(); requests.lyrics()
        assertLyrics(adapter.lookup(null, "Song", "Artist", 180))
        requests.assertDone(4)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IOException)
        // A successful in-memory snapshot avoids downloading the same data again.
        requests.lyrics()
        assertLyrics(adapter.lookup(null, "Another title", "Artist", 180))
        requests.assertDone(6)
        assertEquals(1, errors.size)
    }

    private fun community(root: File, requests: Requests, errors: MutableList<Exception>) =
        CommunitySync(root, requests::get, { _, _, _ -> ISRC }) { _, error -> error?.let(errors::add) }

    private fun assertLyrics(lyrics: Lyrics?) {
        assertNotNull(lyrics)
        assertEquals(CommunitySync.ID, lyrics!!.source)
        assertTrue(lyrics.synced)
        assertEquals(listOf("hello"), lyrics.lines.map { it.text })
        assertEquals(listOf(1000L, 1200L, 1400L, 1600L, 1800L), lyrics.lines.single().syllables!!.map { it.startMs })
    }

    private class Requests {
        private data class Reply(val path: String, val body: String, val timeout: Int, val query: String?)
        private val replies = ArrayDeque<Reply>()
        private var count = 0
        fun expect(path: String, body: String, timeout: Int = 10_000, query: String? = null) {
            replies.addLast(Reply(path, body, timeout, query))
        }
        fun openDb() {
            expect("/ivLyrics/opendb/data/manifest.json", MANIFEST)
            expect("/ivLyrics/opendb/data/base.json", """{"items":{"lrclib":["$ISRC"]}}""", 20_000)
        }
        fun lyrics(query: String = "isrc=$ISRC") {
            expect("/lyrics/sync-data", SYNC, 15_000, "$query&request-version=20260701&provider=lrclib")
            expect("/api/get/42", """{"plainLyrics":"hello"}""")
        }
        fun get(url: String, timeout: Int, headers: Map<String, String>, nullOn: Set<Int>): String {
            assertTrue("Unexpected request: $url", replies.isNotEmpty())
            val reply = replies.removeFirst()
            val uri = URI(url)
            assertEquals(reply.path, uri.path)
            assertEquals(reply.query, uri.query)
            assertEquals(reply.timeout, timeout)
            val sync = reply.path == "/lyrics/sync-data"
            assertEquals(if (sync) mapOf("Origin" to "https://xpui.app.spotify.com") else emptyMap(), headers)
            assertEquals(if (sync) setOf(400, 404) else setOf(404), nullOn)
            count++
            return reply.body
        }
        fun assertDone(expectedCount: Int) {
            assertTrue("Unused responses: $replies", replies.isEmpty())
            assertEquals(expectedCount, count)
        }
    }

    companion object {
        private const val ISRC = "USAAA0000001"
        private const val MANIFEST = """{"base":{"url":"data/base.json"}}"""
        private const val SYNC = """{"data":{"syncData":{"source":{"lrclibId":42,"preferredLyricsSource":"plain","lineCharCounts":[5]},"lines":[{"start":0,"end":4,"chars":[1,1.2,1.4,1.6,1.8]}]}}}"""
    }
}
