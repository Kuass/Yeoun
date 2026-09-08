package dev.kuass.ivlyrics

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** User corrections are durable data, independent of the disposable generated-result cache. */
class LyricsEdits(ctx: Context) {
    private val dir = File(ctx.filesDir, "lyrics-edits").apply { mkdirs() }
    fun get(key: String): ExtrasEdits = runCatching { ExtrasEdits.decode(file(key).openRead().bufferedReader().use { it.readText() }) }.getOrDefault(ExtrasEdits())
    fun set(key: String, edits: ExtrasEdits) {
        val file = file(key)
        val out = file.startWrite()
        try { out.write(edits.encode().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun remove(key: String) = file(key).delete()
    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(dir, "$key.json"))
    }
}
