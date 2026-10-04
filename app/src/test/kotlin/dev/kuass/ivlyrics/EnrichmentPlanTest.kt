package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class EnrichmentPlanTest {
    private val both = LanguageDisplay.Choice(true, true)
    private val empty = LyricsCache.Extras(null, null)

    private fun plan(
        texts: List<String> = listOf("one", "two", "three"),
        languages: List<String> = List(texts.size) { "en" },
        target: String = "ko",
        choices: Map<String, LanguageDisplay.Choice?> = mapOf("en" to both),
        translationEnabled: Boolean = true,
        phoneticEnabled: Boolean = true,
    ) = EnrichmentPlan.create(texts, languages, target, choices, translationEnabled, phoneticEnabled)

    @Test fun `language choices keep line indices and independent channels`() {
        val plan = plan(listOf("section", "own", "translate", "pronounce", "pending"),
            listOf("", "ko", "en", "ja", "fr"), choices = mapOf(
                "en" to LanguageDisplay.Choice(true, false), "ja" to LanguageDisplay.Choice(false, true)))
        val work = plan.prepare(empty, ExtrasEdits(), true)
        assertEquals(listOf("", "", "translate", "", ""), work.translationInput)
        assertEquals(listOf("", "", "", "pronounce", ""), work.phoneticInput)
        assertEquals(listOf("fr"), plan.pendingLanguages)
    }

    @Test fun `explicit choice overrides same target suppression`() {
        val work = plan(listOf("own"), listOf("ko"), choices = mapOf("ko" to both)).prepare(null, ExtrasEdits(), true)
        assertEquals(listOf("own"), work.translationInput)
        assertEquals(listOf("own"), work.phoneticInput)
    }

    @Test fun `pending languages remain unique and ordered even when channels are globally disabled`() {
        val plan = plan(listOf("a", "b", "c", "d", "e"), listOf("ja", "en", "ja", "ko", ""),
            choices = emptyMap(), translationEnabled = false, phoneticEnabled = false)
        assertEquals(listOf("ja", "en"), plan.pendingLanguages)
        val work = plan.prepare(null, ExtrasEdits(), true)
        assertEquals(List(5) { "" }, work.translationInput)
        assertEquals(ExtrasProgress.State.CHOICE, work.presentation(empty).progress.translation.state)
        assertEquals(ExtrasProgress.State.CHOICE, work.presentation(empty).progress.phonetic.state)
    }

    @Test fun `global switches hide cached and corrected values without generating them`() {
        for (translation in listOf(false, true)) for (phonetic in listOf(false, true)) {
            val work = plan(translationEnabled = translation, phoneticEnabled = phonetic)
                .prepare(LyricsCache.Extras(listOf("cached", "", ""), listOf("cached", "", "")),
                    ExtrasEdits(mapOf(1 to "edited"), mapOf(1 to "edited")), true)
            assertEquals(listOf("", "", if (translation) "three" else ""), work.translationInput)
            assertEquals(listOf("", "", if (phonetic) "three" else ""), work.phoneticInput)
            val presentation = work.presentation(work.cached)
            assertEquals(if (translation) listOf("cached", "edited", "") else null, presentation.translation)
            assertEquals(if (phonetic) listOf("cached", "edited", "") else null, presentation.phonetic)
        }
    }

    @Test fun `intentionally empty edits suppress generation and count as complete`() {
        val work = plan().prepare(LyricsCache.Extras(listOf("old", "", ""), null),
            ExtrasEdits(mapOf(0 to "", 1 to "replacement", 2 to ""), mapOf(0 to "")), true)
        assertEquals(listOf("", "", ""), work.translationInput)
        assertEquals(listOf("", "two", "three"), work.phoneticInput)
        val presentation = work.presentation(work.cached)
        assertEquals(listOf("", "replacement", ""), presentation.translation)
        assertNull(presentation.phonetic)
        assertEquals(ExtrasProgress.Channel(ExtrasProgress.State.READY, 3, 3), presentation.progress.translation)
        assertEquals(ExtrasProgress.Channel(ExtrasProgress.State.RUNNING, 1, 3), presentation.progress.phonetic)
        assertEquals(listOf("old", "", ""), work.cached.translation)
    }

    @Test fun `cached translation does not suppress missing pronunciation or blank line positions`() {
        val work = plan(listOf("one", " ", "three")).prepare(
            LyricsCache.Extras(listOf("translated", "", ""), listOf("", "", "pronounced")), ExtrasEdits(), true)
        assertEquals(listOf("", " ", "three"), work.translationInput)
        assertEquals(listOf("one", " ", ""), work.phoneticInput)
    }

    @Test fun `setup running failed and ready progress derive from each snapshot`() {
        val selected = plan()
        val partial = LyricsCache.Extras(listOf("one", "", ""), listOf("one", "two", "three"))
        val noConfig = selected.prepare(partial, ExtrasEdits(), false)
        assertEquals(ExtrasProgress.Channel(ExtrasProgress.State.SETUP, 1, 3), noConfig.presentation(partial).progress.translation)
        assertEquals(ExtrasProgress.State.READY, noConfig.presentation(partial).progress.phonetic.state)
        val configured = selected.prepare(partial, ExtrasEdits(), true)
        assertEquals(ExtrasProgress.State.RUNNING, configured.presentation(partial).progress.translation.state)
        assertEquals(ExtrasProgress.State.FAILED, configured.presentation(partial, finished = true).progress.translation.state)
        assertEquals(ExtrasProgress.State.READY, configured.presentation(partial, finished = true).progress.phonetic.state)
        assertEquals(ExtrasProgress.State.SETUP, noConfig.presentation(partial, finished = true).progress.translation.state)
    }

    @Test fun `cached translated and pronounced publications share fixed edits and eligibility`() {
        val work = plan().prepare(LyricsCache.Extras(listOf("cached", "", ""), null),
            ExtrasEdits(mapOf(2 to ""), mapOf(1 to "edited reading")), true)
        val translated = LyricsCache.Extras(ExtrasPolicy.merge(work.cached.translation, listOf("", "new", ""), 3), null)
        val pronounced = LyricsCache.Extras(translated.translation, listOf("new reading", "", "last reading"))
        assertEquals(listOf("cached", "", ""), work.presentation(work.cached).translation)
        assertEquals(listOf("cached", "new", ""), work.presentation(translated).translation)
        assertEquals(ExtrasProgress.State.READY, work.presentation(translated).progress.translation.state)
        val final = work.presentation(pronounced, finished = true)
        assertEquals(listOf("new reading", "edited reading", "last reading"), final.phonetic)
        assertEquals(ExtrasProgress.State.READY, final.progress.phonetic.state)
        assertEquals(listOf("", "two", ""), work.translationInput)
        assertEquals(listOf("one", "", "three"), work.phoneticInput)
    }

    @Test fun `short and overlong cache lists retain original missing and display rules`() {
        val work = plan().prepare(LyricsCache.Extras(listOf("cached"), listOf("a", "b", "c", "extra")),
            ExtrasEdits(mapOf(8 to "outside"), mapOf(-1 to "outside")), true)
        assertEquals(listOf("", "two", "three"), work.translationInput)
        assertEquals(listOf("", "", ""), work.phoneticInput)
        val display = work.presentation(work.cached)
        assertEquals(listOf("cached", "", ""), display.translation)
        assertEquals(listOf("a", "b", "c"), display.phonetic)
        assertEquals(1, display.progress.translation.done)
        assertEquals(3, display.progress.phonetic.done)
    }

    @Test fun `plan and prepared work snapshot all mutable inputs`() {
        val texts = mutableListOf("one", "two", "three")
        val languages = mutableListOf("en", "en", "en")
        val choices = mutableMapOf("en" to both)
        val selected = plan(texts, languages, choices = choices)
        texts[1] = "changed"; languages[1] = "ko"; choices["en"] = LanguageDisplay.Choice(false, false)
        val cachedTranslation = mutableListOf("cached", "", "")
        val cachedPhonetic = mutableListOf("", "reading", "")
        val trEdits = mutableMapOf(2 to "")
        val phEdits = mutableMapOf(2 to "edited")
        val work = selected.prepare(LyricsCache.Extras(cachedTranslation, cachedPhonetic), ExtrasEdits(trEdits, phEdits), true)
        cachedTranslation[0] = "changed"; cachedPhonetic[1] = "changed"; trEdits.clear(); phEdits[2] = "changed"
        assertEquals(listOf("en", "en", "en"), selected.languages)
        assertEquals(listOf("", "two", ""), work.translationInput)
        assertEquals(listOf("one", "", ""), work.phoneticInput)
        assertEquals(listOf("cached", "", ""), work.presentation(work.cached).translation)
        assertEquals(listOf("", "reading", "edited"), work.presentation(work.cached).phonetic)
        assertEquals(2, work.presentation(work.cached).progress.translation.done)
    }

    @Test fun `exposed snapshot lists cannot mutate the plan or cache`() {
        val selected = plan(choices = emptyMap())
        val work = selected.prepare(LyricsCache.Extras(listOf("one"), listOf("reading")), ExtrasEdits(), true)
        for (values in listOf(selected.languages, selected.pendingLanguages, work.translationInput,
            work.phoneticInput, work.cached.translation!!, work.cached.phonetic!!)) {
            assertThrows(UnsupportedOperationException::class.java) { (values as MutableList<String>).add("changed") }
        }
    }

    @Test fun `empty plan has no work and hidden channels`() {
        val selected = plan(emptyList())
        val work = selected.prepare(null, ExtrasEdits(), false)
        assertEquals(0, selected.size)
        assertTrue(selected.pendingLanguages.isEmpty())
        assertTrue(work.translationInput.isEmpty())
        assertTrue(work.phoneticInput.isEmpty())
        assertNull(work.presentation(empty).translation)
        assertEquals(ExtrasProgress.State.HIDDEN, work.presentation(empty).progress.translation.state)
        assertEquals(ExtrasProgress.State.HIDDEN, work.presentation(empty).progress.phonetic.state)
    }

    @Test fun `mismatched source-language decisions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { plan(languages = listOf("en")) }
    }
}
