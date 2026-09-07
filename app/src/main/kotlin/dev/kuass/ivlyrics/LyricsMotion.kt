package dev.kuass.ivlyrics

/** Only adjacent lines in the same track share a continuous scroll transition. */
internal object LyricsMotion {
    fun shouldScroll(previous: Int, next: Int, sameTrack: Boolean, enabled: Boolean): Boolean =
        enabled && sameTrack && previous >= 0 && next >= 0 && kotlin.math.abs(next - previous) == 1

}
