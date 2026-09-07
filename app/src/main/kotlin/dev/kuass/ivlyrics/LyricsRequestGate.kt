package dev.kuass.ivlyrics

/** Identifies a particular load, even when a track or its local lyrics are selected again. */
internal class LyricsRequestGate {
    data class Ticket(val key: Triple<String, String, Long>, val revision: Long)
    @Volatile private var active: Ticket? = null
    private var revision = 0L

    @Synchronized fun begin(key: Triple<String, String, Long>): Ticket =
        Ticket(key, ++revision).also { active = it }

    fun accepts(ticket: Ticket): Boolean = active == ticket
    fun invalidate() { active = null }
}
