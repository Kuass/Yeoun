package dev.kuass.ivlyrics

/** Pure media-session position calculation; callers supply the monotonic clock and display offsets. */
internal object PlaybackPosition {
    fun at(
        positionMs: Long,
        playing: Boolean,
        lastPositionUpdateTimeMs: Long,
        playbackSpeed: Float,
        elapsedRealtimeMs: Long,
        offsetMs: Int = 0,
        trackOffsetMs: Int = 0,
    ): Long {
        // Preserve the session's Float arithmetic and truncation, including negative positions/deltas.
        val base = if (!playing) positionMs
            else positionMs + ((elapsedRealtimeMs - lastPositionUpdateTimeMs) * playbackSpeed).toLong()
        return base + offsetMs + trackOffsetMs
    }
}
