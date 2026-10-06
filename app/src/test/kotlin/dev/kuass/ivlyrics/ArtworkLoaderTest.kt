package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ArtworkLoaderTest {
    @Test fun `a newer embedded bitmap replaces pending URI artwork`() {
        val fixture = Fixture()
        fixture.loader.update(null, "old")
        fixture.loader.update("embedded", "ignored")
        fixture.finish()
        assertEquals(listOf("embedded"), fixture.shown)
        assertEquals(listOf("old"), fixture.loaded)
    }

    @Test fun `clearing artwork invalidates a download before it completes`() {
        val fixture = Fixture()
        fixture.loader.update(null, "old")
        fixture.loader.update(null, null)
        fixture.finish()
        assertEquals(listOf<String?>(null), fixture.shown)
    }

    @Test fun `clearing artwork rejects an already posted result`() {
        val fixture = Fixture()
        fixture.loader.update(null, "old")
        fixture.work.removeFirst()()
        fixture.loader.update(null, null)
        fixture.main.removeFirst()()
        assertEquals(listOf<String?>(null), fixture.shown)
    }

    @Test fun `the same URI reloads after artwork was cleared or replaced by a bitmap`() {
        for (replacement in listOf(null, "embedded")) {
            val fixture = Fixture()
            fixture.loader.update(null, "same")
            fixture.finish()
            fixture.loader.update(replacement, null)
            fixture.loader.update(null, "same")
            fixture.finish()
            assertEquals(listOf("same", replacement, "same"), fixture.shown)
            assertEquals(listOf("same", "same"), fixture.loaded)
        }
    }

    @Test fun `unchanged URI metadata does not load again`() {
        val fixture = Fixture()
        repeat(3) { fixture.loader.update(null, "same") }
        fixture.finish()
        fixture.loader.update(null, "same")
        assertEquals(listOf("same"), fixture.shown)
        assertEquals(listOf("same"), fixture.loaded)
        assertTrue(fixture.work.isEmpty())
    }

    @Test fun `returning to a previous URI never reactivates its older request`() {
        val fixture = Fixture()
        fixture.loader.update(null, "first")
        fixture.loader.update(null, "second")
        fixture.loader.update(null, "first")
        repeat(2) { fixture.work.removeFirst()(); fixture.main.removeFirst()() }
        assertTrue(fixture.shown.isEmpty())
        fixture.finish()
        assertEquals(listOf("first"), fixture.shown)
    }

    @Test fun `closing rejects downloaded and queued artwork without changing the displayed image`() {
        for (alreadyPosted in listOf(false, true)) {
            val fixture = Fixture()
            fixture.loader.update("existing", null)
            fixture.loader.update(null, "pending")
            if (alreadyPosted) fixture.work.removeFirst()()
            fixture.loader.close()
            fixture.loader.close()
            fixture.finish()
            fixture.loader.update("later", null)
            fixture.loader.update(null, "later")
            assertEquals(listOf("existing"), fixture.shown)
            assertTrue(fixture.work.isEmpty())
        }
    }

    @Test fun `decode failure leaves existing artwork intact and still permits another URI`() {
        for (throws in listOf(false, true)) {
            val fixture = Fixture { uri ->
                if (uri != "broken") uri else if (throws) throw IOException("offline") else null
            }
            fixture.loader.update("existing", null)
            fixture.loader.update(null, "broken")
            fixture.finish()
            assertEquals(listOf("existing"), fixture.shown)
            fixture.loader.update(null, "recovered")
            fixture.finish()
            assertEquals(listOf("existing", "recovered"), fixture.shown)
        }
    }

    private class Fixture(load: (String) -> String? = { it }) {
        val work = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val shown = mutableListOf<String?>()
        val loaded = mutableListOf<String>()
        val loader = ArtworkLoader<String>(
            execute = { work.addLast(it) }, post = { main.addLast(it) },
            load = { loaded += it; load(it) }, show = { shown += it },
        )
        fun finish() {
            while (work.isNotEmpty()) work.removeFirst()()
            while (main.isNotEmpty()) main.removeFirst()()
        }
    }
}
