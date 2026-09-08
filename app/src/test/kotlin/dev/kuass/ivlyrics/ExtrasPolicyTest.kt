package dev.kuass.ivlyrics

import org.junit.Assert.*
import org.junit.Test

class ExtrasPolicyTest {
    private val options = Ai.Options(Lang.target("ko"), Lang.Style.NATURAL, "", Lang.Notation.SCRIPT)

    @Test fun `identity separates colliding Java strings and options while remaining stable`() {
        val track = Triple("song", "artist", 120L)
        val a = ExtrasPolicy.key(track, listOf("Aa"), options)
        assertEquals(a, ExtrasPolicy.key(track, listOf("Aa"), options))
        assertNotEquals(a, ExtrasPolicy.key(track, listOf("BB"), options))
        assertNotEquals(a, ExtrasPolicy.key(track, listOf("Aa"), options.copy(target = Lang.target("ja"))))
    }

    @Test fun `disabled language outputs cannot leak from a previous cached result`() {
        assertEquals(listOf("", "translated"), ExtrasPolicy.visible(listOf("hidden", "translated"), listOf(false, true)))
        assertNull(ExtrasPolicy.visible(listOf("hidden"), listOf(false)))
        assertNull(ExtrasPolicy.visible(listOf("wrong size"), listOf(true, true)))
    }

    @Test fun `only missing enabled lines are generated and all indices survive merge`() {
        assertEquals(listOf("", "b", ""), ExtrasPolicy.missing(listOf("a", "b", "c"), listOf(true, true, false), listOf("existing", "", "")))
        assertEquals(listOf("existing", "new", ""), ExtrasPolicy.merge(listOf("existing", "", ""), listOf("", "new", ""), 3))
    }

    @Test fun `sparse corrections retain empty edits and do not overwrite other channels`() {
        val edits = ExtrasEdits(mapOf(1 to ""), mapOf(0 to "reading"))
        assertEquals(edits, ExtrasEdits.decode(edits.encode()))
        val applied = edits.apply(LyricsCache.Extras(listOf("one", "two"), listOf("a", "b")), 2)
        assertEquals(listOf("one", ""), applied.translation)
        assertEquals(listOf("reading", "b"), applied.phonetic)
        assertEquals(listOf("", ""), edits.apply(null, 2).translation)
    }

    @Test fun `presets include independent ranges and choices but never credentials or track state`() {
        val values = mapOf(Prefs.FONT_SP to 22, Prefs.TRANSLATION_PREV to 3, Prefs.PHONETIC_NEXT to 4,
            Prefs.TRANSLATE to false, LanguagePrefs.PREFIX + "ja" to "01", Prefs.API_KEY to "secret", Prefs.UI_OPEN to true,
            Prefs.BASE_URL to "https://private.example", Prefs.YT_API_KEY to "secret", Prefs.OVERLAY_Y to 100)
        val saved = PresetValues.decode(PresetValues.encode(values))
        assertEquals(setOf(Prefs.FONT_SP, Prefs.TRANSLATION_PREV, Prefs.PHONETIC_NEXT, Prefs.TRANSLATE, LanguagePrefs.PREFIX + "ja"), saved.keys)
        assertEquals(4, saved[Prefs.PHONETIC_NEXT])
        assertTrue(PresetValues.filter(mapOf(Prefs.FONT_SP to 999, Prefs.BG_PERCENT to 7, Prefs.PHONETIC_NEXT to "4", LanguagePrefs.PREFIX + "ja" to "bad")).isEmpty())
    }

    @Test fun `LRCLIB candidates retain plain timing distinction and ignore nulls and instrumentals`() {
        val hits = LrcLib.parseSearch("""[
          {"id":1,"trackName":"Sync","syncedLyrics":"[00:01]one","plainLyrics":null},
          {"id":2,"trackName":"Plain","syncedLyrics":null,"plainLyrics":"one\ntwo"},
          {"id":3,"syncedLyrics":null,"plainLyrics":null},
          {"id":4,"instrumental":true,"plainLyrics":"instrumental"},
          {"id":5,"syncedLyrics":"invalid","plainLyrics":null}
        ]""")
        assertEquals(listOf(1L, 2L), hits.map { it.id })
        assertTrue(LrcLib.toLyrics(hits[0].candidate, 120).synced)
        val plain = LrcLib.toLyrics(hits[1].candidate, 120)
        assertFalse(plain.synced)
        assertEquals(listOf(0L, 60000L), plain.lines.map { it.timeMs })
    }
}
