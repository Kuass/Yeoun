package dev.kuass.ivlyrics

/** Main-thread artwork selection; loading happens on [execute] and results return through [post]. */
internal class ArtworkLoader<T : Any>(
    private val execute: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val load: (String) -> T?,
    private val show: (T?) -> Unit,
) {
    private var uri: String? = null
    private var revision = 0L
    private var closed = false

    fun update(bitmap: T?, source: String?) {
        if (closed) return
        if (bitmap != null || source == null) {
            invalidate()
            show(bitmap)
            return
        }
        if (source == uri) return
        uri = source
        val ticket = ++revision
        execute {
            val bitmap = runCatching { load(source) }.getOrNull() ?: return@execute
            post { if (!closed && revision == ticket) show(bitmap) }
        }
    }

    fun close() {
        closed = true
        invalidate()
    }

    private fun invalidate() {
        uri = null
        revision++
    }
}
