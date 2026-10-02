package dev.kuass.ivlyrics

import java.io.IOException
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class LrcLibFallbackTest {
    private val plain = LrcLib.Candidate(120, null, "first\nsecond")
    private val synced = LrcLib.Candidate(120, "[00:01]first\n[00:02]second", null)

    @Test fun `search transport failures retain exact plain lyrics`() {
        val failure = IOException("search unavailable")
        val errors = mutableListOf<Exception>()
        val found = LrcLib.resolve({ plain }, { throw failure }, errors::add)

        assertEquals(plain, found)
        assertEquals(listOf(failure), errors)
        val lyrics = LrcLib.toLyrics(found, 120)
        assertFalse(lyrics.synced)
        assertEquals(listOf(LrcLine(0, "first"), LrcLine(60_000, "second")), lyrics.lines)
    }

    @Test fun `malformed search responses also retain exact plain lyrics`() {
        val found = LrcLib.resolve({ plain }, { throw JSONException("invalid search JSON") })

        assertEquals(plain, found)
    }

    @Test fun `exact lookup failures still try the search fallback`() {
        for (failure in listOf(IOException("exact lookup unavailable"), JSONException("invalid exact JSON"))) {
            var searches = 0
            val found = LrcLib.resolve({ throw failure }, { searches++; synced })

            assertEquals(synced, found)
            assertEquals(1, searches)
            assertTrue(LrcLib.toLyrics(found, 120).synced)
        }
    }

    @Test fun `an exact synchronized result avoids the optional search`() {
        val found = LrcLib.resolve({ synced }, { fail("Search should not run"); null })

        assertEquals(synced, found)
    }

    @Test fun `search synchronization still upgrades an exact plain result`() {
        assertEquals(synced, LrcLib.resolve({ plain }, { synced }))
    }

    @Test fun `exact plain lyrics retain priority over plain search lyrics`() {
        val searched = plain.copy(plain = "different rendition")
        assertEquals(plain, LrcLib.resolve({ plain }, { searched }))
    }

    @Test fun `an exact miss can fall back to plain search lyrics`() {
        assertEquals(plain, LrcLib.resolve({ null }, { plain }))
    }

    @Test fun `failed or empty lookups yield no lyrics without throwing`() {
        val errors = mutableListOf<Exception>()
        val found = LrcLib.resolve({ throw IOException("exact failed") }, { throw IOException("search failed") }, errors::add)

        assertEquals(2, errors.size)
        assertEquals(Lyrics.NONE, LrcLib.toLyrics(found, 120))
        assertNull(LrcLib.resolve({ null }, { null }))
        assertNull(LrcLib.resolve({ null }, { throw IOException("search failed") }))
        assertNull(LrcLib.resolve({ throw IOException("exact failed") }, { null }))
    }
}
