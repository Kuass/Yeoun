package dev.kuass.ivlyrics

/** One sung unit (word or character) with its timing, used for karaoke highlighting. */
data class Syl(val startMs: Long, val endMs: Long, val text: String)

data class LrcLine(val timeMs: Long, val text: String, val syllables: List<Syl>? = null)

object Lrc {
    private val TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** Parses LRC; an `[offset:ms]` header shifts every timestamp (positive = lyrics appear earlier, per the LRC convention). */
    fun parse(lrc: String): List<LrcLine> {
        val offset = OFFSET.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return lrc.lineSequence()
            .flatMap { raw ->
                val tags = TAG.findAll(raw).toList()
                if (tags.isEmpty()) return@flatMap emptySequence()
                val text = raw.substring(tags.last().range.last + 1).trim()
                tags.asSequence().mapNotNull { tag ->
                    val time = toMs(tag) ?: return@mapNotNull null
                    val shifted = if (offset < 0 && time > Long.MAX_VALUE + offset) Long.MAX_VALUE else maxOf(0L, time - offset)
                    LrcLine(shifted, text)
                }
            }
            .sortedBy { it.timeMs }
            .toList()
    }

    /** Index of the line active at [posMs], or -1 before the first line. */
    fun indexAt(lines: List<LrcLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /**
     * Builds a line whose text is exactly the concatenation of its syllables: blank edge syllables are dropped and
     * edge whitespace trimmed inside the syllables themselves, so karaoke offsets can never drift.
     */
    fun lineFromSyllables(timeMs: Long, syllables: List<Syl>): LrcLine? {
        val core = syllables.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }.toMutableList()
        if (core.isEmpty()) return null
        core[0] = core[0].copy(text = core[0].text.trimStart())
        core[core.lastIndex] = core[core.lastIndex].copy(text = core[core.lastIndex].text.trimEnd())
        val kept = core.filter { it.text.isNotEmpty() }
        if (kept.isEmpty()) return null
        return LrcLine(timeMs, kept.joinToString("") { it.text }, kept)
    }

    /** Number of leading characters of the line that have started singing by [posMs]. */
    fun sungChars(syllables: List<Syl>, posMs: Long): Int {
        var count = 0
        for (syllable in syllables) {
            if (syllable.startMs > posMs) break
            count += syllable.text.length
        }
        return count
    }

    private fun toMs(m: MatchResult): Long? {
        val min = m.groupValues[1].toLongOrNull() ?: return null
        val sec = m.groupValues[2].toLong()
        if (sec >= 60 || min > (Long.MAX_VALUE - 59999) / 60000) return null
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return (min * 60 + sec) * 1000 + ms
    }
}
