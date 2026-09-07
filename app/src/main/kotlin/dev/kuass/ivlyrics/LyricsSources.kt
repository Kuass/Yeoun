package dev.kuass.ivlyrics

/** Runs the enabled providers in the configured order; first synced result wins, else the first plain one. */
object LyricsSources {
    val ALL = listOf(LrcLib.ID, Lyrically.ID, LyricsPlus.ID)

    fun order(first: String, enabled: Set<String>): List<String> =
        (listOf(first) + ALL.filter { it != first }).filter { it in enabled && it in ALL }

    /** Decision per fetched result: a synced result is final unless we still want syllables or it is romanized Korean. */
    fun isFinal(got: Lyrics, wantSyllables: Boolean, deferRomanized: Boolean): Boolean =
        got.synced && (!wantSyllables || got.hasSyllables) && !(deferRomanized && isRomanized(got))

    fun isRomanized(got: Lyrics) = Lang.looksRomanizedKorean(got.lines.map { it.text })

    /** Best of the non-final results: synced original text, then romanized synced, then plain. */
    fun best(candidates: List<Lyrics>, deferRomanized: Boolean): Lyrics =
        candidates.firstOrNull { it.synced && !(deferRomanized && isRomanized(it)) }
            ?: candidates.firstOrNull { it.synced }
            ?: candidates.firstOrNull { !it.isEmpty }
            ?: Lyrics.NONE

    /**
     * Providers run in order and the first final result wins ([isFinal]). Otherwise the best fallback is used:
     * with [deferRomanized] (target language is Korean) a romanized Korean result yields to any other provider's
     * original-script text, as ivLyrics desktop does.
     */
    fun fetch(order: List<String>, title: String, artist: String, album: String, durationSec: Long,
              wantSyllables: Boolean = false, deferRomanized: Boolean = false): Lyrics {
        val candidates = mutableListOf<Lyrics>()
        for (id in order) {
            val got = when (id) {
                LrcLib.ID -> LrcLib.fetch(title, artist, album, durationSec)
                Lyrically.ID -> Lyrically.fetch(title, artist, durationSec)
                LyricsPlus.ID -> LyricsPlus.fetch(title, artist, album, durationSec)
                else -> Lyrics.NONE
            }
            if (got.isEmpty) continue
            if (isFinal(got, wantSyllables, deferRomanized)) return got
            candidates += got
        }
        return best(candidates, deferRomanized)
    }
}
