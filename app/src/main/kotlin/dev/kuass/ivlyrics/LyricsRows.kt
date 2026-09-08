package dev.kuass.ivlyrics

/** Independent windows, merged in lyric order without forcing original neighbours to be visible. */
object LyricsRows {
    data class Row(val index: Int, val original: Boolean, val translation: Boolean, val phonetic: Boolean)
    fun build(index: Int, size: Int, style: LyricsView.Style, hasTranslation: Boolean, hasPhonetic: Boolean): List<Row> {
        val originals = LyricsView.window(index, style.prevLines, style.nextLines, size)
        val translations = if (hasTranslation) LyricsView.window(index, style.translationPrev, style.translationNext, size) else IntRange.EMPTY
        val phonetics = if (hasPhonetic) LyricsView.window(index, style.phoneticPrev, style.phoneticNext, size) else IntRange.EMPTY
        return (originals.toList() + translations.toList() + phonetics.toList()).distinct().sorted()
            .map { Row(it, it in originals, it in translations, it in phonetics) }
    }
}
