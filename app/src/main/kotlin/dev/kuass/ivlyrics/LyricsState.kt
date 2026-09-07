package dev.kuass.ivlyrics

import java.util.concurrent.CopyOnWriteArraySet

/** What the service currently shows, published for in-app screens (fullscreen). Updated on the main thread. */
object LyricsState {
    data class Snapshot(
        val key: Triple<String, String, Long>? = null,
        val title: String = "",
        val artist: String = "",
        val lyrics: Lyrics? = null,
        val translation: List<String>? = null,
        val phonetic: List<String>? = null,
        val trackOffsetMs: Int = 0,
    )

    @Volatile var snapshot = Snapshot()
        private set
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    fun update(change: (Snapshot) -> Snapshot) {
        snapshot = change(snapshot)
        listeners.forEach { it(snapshot) }
    }

    fun addListener(l: (Snapshot) -> Unit) { listeners += l; l(snapshot) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners -= l }
}
