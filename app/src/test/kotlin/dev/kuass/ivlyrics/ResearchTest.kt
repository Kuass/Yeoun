package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ResearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = Ai.Config("https://example.invalid", "example-key", "example-model")

    @Test fun `missing or unreadable cache entries are recoverable misses`() {
        val root = temporary.newFolder()
        val errors = mutableListOf<String>()
        val store = Research(root, { _, _, _ -> error("No AI request during a cache read") }) { message, _ -> errors += message }
        assertNull(store.cached("track"))
        assertTrue(errors.isEmpty())
        cacheFile(root).mkdirs()
        assertNull(store.cached("track"))
        assertEquals(listOf("research cache read failed"), errors)
    }

    @Test fun `blank cache entries do not suppress regeneration`() {
        for (blank in listOf("", " \n\t")) {
            val root = temporary.newFolder()
            var calls = 0
            val store = Research(root, { _, _, _ -> calls++; "notes" }) { _, e -> throw e }
            cacheFile(root).writeText(blank)
            assertNull(store.cached("track"))
            assertEquals("notes", generate(store))
            assertEquals(1, calls)
            assertEquals("notes", store.cached("track"))
        }
    }

    @Test fun `valid cached notes are returned without an AI request or rewrite`() {
        val root = temporary.newFolder()
        val store = Research(root, { _, _, _ -> error("Cached response should be reused") }) { _, e -> throw e }
        cacheFile(root).writeText("existing notes\n")
        assertEquals("existing notes\n", generate(store))
        assertEquals("existing notes\n", cacheFile(root).readText())
    }

    @Test fun `successful completion is trimmed cached and reused`() {
        val root = temporary.newFolder()
        var calls = 0
        val store = Research(root, { actual, system, user ->
            calls++
            assertEquals(config, actual)
            assertTrue(system.contains("English"))
            assertTrue(user.contains("title: Song\nartist: Artist\nalbum: Album"))
            assertTrue(user.contains("lyrics:\nline\n"))
            "  finished notes\n"
        }) { _, e -> throw e }
        repeat(2) { assertEquals("finished notes", generate(store)) }
        assertEquals(1, calls)
        assertEquals("finished notes", cacheFile(root).readText())
    }

    @Test fun `a cache write failure does not discard a completed response or retry the request`() {
        val root = temporary.newFolder()
        var calls = 0
        val errors = mutableListOf<String>()
        val store = Research(root, { _, _, _ -> calls++; cacheFile(root).mkdirs(); "finished notes" }) { message, _ -> errors += message }
        assertEquals("finished notes", generate(store))
        assertEquals(1, calls)
        assertEquals(listOf("research cache write failed"), errors)
    }

    @Test fun `an unreadable existing cache does not prevent returning newly generated notes`() {
        val root = temporary.newFolder()
        var calls = 0
        val errors = mutableListOf<String>()
        val store = Research(root, { _, _, _ -> calls++; "new notes" }) { message, _ -> errors += message }
        cacheFile(root).mkdirs()
        assertEquals("new notes", generate(store))
        assertEquals(1, calls)
        assertEquals(listOf("research cache read failed", "research cache write failed"), errors)
    }

    @Test fun `failed and empty completions are not cached`() {
        for (answer in listOf(null, "", " \n")) {
            val root = temporary.newFolder()
            val store = Research(root, { _, _, _ -> answer }) { _, e -> throw e }
            assertNull(generate(store))
            assertFalse(cacheFile(root).exists())
        }
        val root = temporary.newFolder()
        val errors = mutableListOf<Exception>()
        val failure = IOException("offline")
        val store = Research(root, { _, _, _ -> throw failure }) { _, error -> errors += error }
        assertNull(generate(store))
        assertEquals(listOf(failure), errors)
        assertFalse(cacheFile(root).exists())
    }

    private fun generate(store: Research) = store.generate(config, "track", "Song", "Artist", "Album", listOf("line"), Lang.target("en"))

    private fun cacheFile(root: File): File {
        val hex = MessageDigest.getInstance("SHA-1").digest("track".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(root, "research"), "$hex.txt")
    }
}
