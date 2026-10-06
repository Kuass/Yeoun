package dev.kuass.ivlyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class ProviderCacheIdentityTest {
    @Test fun `ISRC cache keeps title and artist boundaries distinct`() {
        for ((first, second) in collidingTracks) {
            val requests = ArrayDeque<String>()
            var count = 0
            val resolver = Isrc.Resolver({ url, timeout ->
                assertEquals(10_000, timeout)
                assertTrue("Unexpected request: $url", requests.isNotEmpty())
                assertEquals(requests.removeFirst(), url)
                val index = count++ / 2
                val track = listOf(first, second)[index]
                if (URI(url).path == "/search") JSONObject().put("data", JSONArray().put(
                    JSONObject().put("id", index + 1).put("title", track.first)
                        .put("artist", JSONObject().put("name", track.second)).put("duration", 180)
                )).toString() else """{"isrc":"USAAA000000${index + 1}"}"""
            }) { fail("Unexpected error: $it") }

            listOf(first, second).forEachIndexed { index, track ->
                val query = "track:\"${track.first}\" artist:\"${track.second}\""
                requests += "https://api.deezer.com/search?limit=10&q=${Http.enc(query)}"
                requests += "https://api.deezer.com/track/${index + 1}"
                assertEquals("USAAA000000${index + 1}", resolver.resolve(track.first, track.second, 180))
            }
            repeat(2) {
                assertEquals("USAAA0000001", resolver.resolve(first.first, first.second, 180))
                assertEquals("USAAA0000002", resolver.resolve(second.first, second.second, 180))
            }
            assertTrue(requests.isEmpty())
            assertEquals(4, count)
        }
    }

    @Test fun `video cache keeps title and artist boundaries distinct`() {
        for ((first, second) in collidingTracks) {
            var count = 0
            val searcher = VideoMatch.Searcher({ url, timeout, nullOn ->
                assertEquals(10_000, timeout)
                assertEquals(setOf(403, 404), nullOn)
                val uri = URI(url)
                assertEquals("/youtube/v3/search", uri.path)
                val params = uri.rawQuery.split('&').associate {
                    it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val track = listOf(first, second)[count]
                assertEquals("${track.second} ${track.first} official", params["q"])
                assertEquals("example-key", params["key"])
                val id = listOf("abcdefghijk", "lmnopqrstuv")[count++]
                """{"items":[{"id":{"videoId":"$id"}}]}"""
            }) { _, error -> fail("Unexpected error: $error") }

            assertEquals("abcdefghijk", searcher.search("example-key", first.first, first.second))
            assertEquals("lmnopqrstuv", searcher.search("example-key", second.first, second.second))
            repeat(2) {
                // A successful public video result remains reusable when the API key changes.
                assertEquals("abcdefghijk", searcher.search("changed-example-key", first.first, first.second))
                assertEquals("lmnopqrstuv", searcher.search("changed-example-key", second.first, second.second))
            }
            assertEquals(2, count)
        }
    }

    companion object {
        private val collidingTracks = listOf(
            ("Song|Live" to "Singer") to ("Song" to "Live|Singer"),
            ("가|나" to "다") to ("가" to "나|다"),
            ("A||B" to "C") to ("A" to "|B|C"),
        )
    }
}
