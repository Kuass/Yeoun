package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AiErrorBodyTest {
    @Test fun `missing and empty error bodies produce an empty diagnostic`() {
        assertEquals("", Ai.readErrorText(null))
        val stream = Counting(ByteArray(0))
        assertEquals("", Ai.readErrorText(stream))
        assertTrue(stream.closed)
    }

    @Test fun `short error bodies retain exact whitespace and Unicode`() {
        for (body in listOf("error", "  error\nline\r\n", "실패 🎵")) {
            val stream = Counting(body.toByteArray())
            assertEquals(body, Ai.readErrorText(stream))
            assertTrue(stream.closed)
        }
    }

    @Test fun `long error bodies stop after the visible prefix without reading the whole tail`() {
        val bytes = "x".repeat(1024 * 1024).toByteArray()
        val stream = Counting(bytes)
        assertEquals("x".repeat(300), Ai.readErrorText(stream))
        assertTrue("The discarded tail should not be read", stream.bytesRead < bytes.size)
        assertTrue(stream.closed)
    }

    @Test fun `fragmented UTF-8 input returns the same 300 UTF-16 units as the former truncation`() {
        val body = "a😀한글\n".repeat(100)
        val stream = Counting(body.toByteArray(), chunkSize = 1)
        assertEquals(body.take(300), Ai.readErrorText(stream))
        assertTrue(stream.closed)
    }

    @Test fun `a surrogate pair at the prefix boundary retains existing String take semantics`() {
        val body = "a".repeat(299) + "😀tail"
        val stream = Counting(body.toByteArray())
        assertEquals(body.take(300), Ai.readErrorText(stream))
        assertTrue(stream.closed)
    }

    @Test fun `read failures still close the body`() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("read failed")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { Ai.readErrorText(stream) }
        assertTrue(closed)
    }

    @Test fun `an exact prefix does not require another read to find the end of the body`() {
        var remaining = 300
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("Bulk reader expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) throw IOException("The unused tail is unavailable")
                val count = minOf(length, remaining)
                bytes.fill('x'.code.toByte(), offset, offset + count)
                remaining -= count
                return count
            }
            override fun close() { closed = true }
        }
        assertEquals("x".repeat(300), Ai.readErrorText(stream))
        assertTrue(closed)
    }

    @Test fun `a close-only failure does not replace a successfully read diagnostic`() {
        var closeAttempts = 0
        val stream = object : ByteArrayInputStream(" useful diagnostic\n".toByteArray()) {
            override fun close() { closeAttempts++; throw IOException("close failed") }
        }
        assertEquals(" useful diagnostic\n", Ai.readErrorText(stream))
        assertEquals(1, closeAttempts)
    }

    @Test fun `a read failure remains primary when cleanup also fails`() {
        val failure = IOException("read failed")
        var closeAttempts = 0
        val stream = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closeAttempts++; throw IOException("close failed") }
        }
        assertSame(failure, assertThrows(IOException::class.java) { Ai.readErrorText(stream) })
        assertEquals(1, closeAttempts)
    }

    private class Counting(bytes: ByteArray, private val chunkSize: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
        var closed = false
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, minOf(length, chunkSize)).also { if (it > 0) bytesRead += it }
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        override fun close() { closed = true; super.close() }
    }
}
