package dev.kuass.ivlyrics

import java.util.Collections

/** Pure, immutable decisions for one enrichment revision; callers own storage, execution and rendering. */
internal class EnrichmentPlan private constructor(
    private val texts: List<String>,
    val languages: List<String>,
    private val translation: List<Boolean>,
    private val phonetic: List<Boolean>,
    val pendingLanguages: List<String>,
) {
    val size: Int get() = texts.size

    /** Bind the disk snapshots on the AI queue, without doing any storage or provider work here. */
    fun prepare(cached: LyricsCache.Extras?, edits: ExtrasEdits, configured: Boolean): Work = Work(this, cached, edits, configured)

    class Work internal constructor(
        private val plan: EnrichmentPlan,
        cached: LyricsCache.Extras?,
        edits: ExtrasEdits,
        private val configured: Boolean,
    ) {
        val cached = LyricsCache.Extras(cached?.translation?.snapshot(), cached?.phonetic?.snapshot())
        private val edits = ExtrasEdits(edits.translation.toMap(), edits.phonetic.toMap())
        val translationInput: List<String> = input(plan.translation, this.edits.translation, this.cached.translation)
        val phoneticInput: List<String> = input(plan.phonetic, this.edits.phonetic, this.cached.phonetic)

        private fun input(allowed: List<Boolean>, edits: Map<Int, String>, stored: List<String>?): List<String> =
            ExtrasPolicy.missing(plan.texts, allowed.mapIndexed { i, on -> on && i !in edits }, stored).snapshot()

        fun presentation(result: LyricsCache.Extras, finished: Boolean = false): Presentation {
            val visible = edits.apply(result, plan.size)
            val needsChoice = plan.pendingLanguages.isNotEmpty()
            return Presentation(
                ExtrasPolicy.visible(visible.translation, plan.translation),
                ExtrasPolicy.visible(visible.phonetic, plan.phonetic),
                ExtrasProgress.Status(
                    ExtrasProgress.channel(plan.translation, result.translation, edits.translation.keys, configured, finished, needsChoice),
                    ExtrasProgress.channel(plan.phonetic, result.phonetic, edits.phonetic.keys, configured, finished, needsChoice),
                ),
            )
        }
    }

    data class Presentation(val translation: List<String>?, val phonetic: List<String>?, val progress: ExtrasProgress.Status)

    companion object {
        fun create(
            texts: List<String>,
            languages: List<String>,
            target: String,
            choices: Map<String, LanguageDisplay.Choice?>,
            translationEnabled: Boolean,
            phoneticEnabled: Boolean,
        ): EnrichmentPlan {
            require(texts.size == languages.size) { "Every lyric line must have a source-language decision" }
            val decisions = languages.map { LanguageDisplay.resolve(it, target, choices[it]) }
            return EnrichmentPlan(
                texts.snapshot(),
                languages.snapshot(),
                decisions.map { it.translation && translationEnabled }.snapshot(),
                decisions.map { it.phonetic && phoneticEnabled }.snapshot(),
                languages.indices.filter { decisions[it].needsChoice }.map { languages[it] }.distinct().snapshot(),
            )
        }
    }
}

private fun <T> List<T>.snapshot(): List<T> = Collections.unmodifiableList(ArrayList(this))
