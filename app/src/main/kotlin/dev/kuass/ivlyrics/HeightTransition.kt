package dev.kuass.ivlyrics

/** Retarget from the currently displayed height, including rapid lyric/translation changes. */
internal class HeightTransition(private val durationMs: Long = 350) {
    private var from = 0f
    private var target = 0f
    private var startedAt = 0L
    private var initialized = false

    fun value(now: Long): Float {
        val fraction = ((now - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
        val remainder = 1f - fraction
        return from + (target - from) * (1f - remainder * remainder * remainder)
    }
    fun update(height: Int, now: Long, animate: Boolean): Int {
        val desired = height.toFloat()
        if (!initialized || !animate) {
            initialized = true; from = desired; target = desired; startedAt = now - durationMs
        } else if (desired != target) {
            from = value(now); target = desired; startedAt = now
        }
        return value(now).toInt()
    }
    fun running(now: Long) = initialized && from != target && now - startedAt < durationMs
    fun reset() { initialized = false }
}
