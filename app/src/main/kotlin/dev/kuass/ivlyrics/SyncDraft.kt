package dev.kuass.ivlyrics

/** Editing state independent of the Android view lifecycle. A track switch never reuses another track's text. */
internal class SyncDraft {
    var key: String? = null
        private set
    var lines: List<String> = emptyList()
        private set
    var times: List<Long?> = emptyList()
        private set
    var cursor = 0
        private set
    private val history = java.util.ArrayDeque<Pair<List<Long?>, Int>>()

    fun selectTrack(track: String?, texts: List<String> = emptyList(), marks: List<Long?>? = null): Boolean {
        if (track == key) return false
        key = track
        replace(texts, marks)
        return true
    }

    fun replace(texts: List<String>, marks: List<Long?>? = null) {
        lines = texts.toList()
        times = texts.indices.map { marks?.getOrNull(it) }
        cursor = times.indexOfFirst { it == null }.let { if (it < 0) lines.size else it }
        history.clear()
    }

    fun select(index: Int) { cursor = index.coerceIn(0, lines.size) }

    fun canStamp(track: String?): Boolean = key != null && key == track && cursor < lines.size

    fun stamp(ms: Long, track: String?): Boolean {
        if (!canStamp(track) || ms < 0 || times.take(cursor).any { it != null && it > ms }) return false
        history.addLast(times to cursor)
        times = times.mapIndexed { index, time ->
            when {
                index == cursor -> ms
                index > cursor && time != null && time < ms -> null
                else -> time
            }
        }
        cursor++
        return true
    }

    fun undo() {
        val previous = history.pollLast() ?: return
        times = previous.first
        cursor = previous.second
    }
}
