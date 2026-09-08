package dev.kuass.ivlyrics

/** Manual reading pauses tracking until release plus a short grace period, or explicit resume. */
class ScrollFollow(private val delayMs: Long = 3000) {
    private var until = 0L
    private var touching = false
    fun begin() { touching = true }
    fun end(now: Long) { touching = false; until = now + delayMs }
    fun resume() { touching = false; until = 0 }
    fun paused(now: Long): Boolean = touching || now < until
}
