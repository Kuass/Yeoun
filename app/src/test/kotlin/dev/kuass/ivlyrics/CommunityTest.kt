package dev.kuass.ivlyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityTest {
    private val payload = """{"success":true,"data":{"isrc":"KRA000000001","provider":"lrclib","syncData":{"version":3,
      "source":{"provider":"lrclib","lrclibId":42,"preferredLyricsSource":"synced","lineCharCounts":[3,2]},
      "lines":[{"start":0,"end":2,"chars":[1.0,1.5,2.0]},{"start":3,"end":4,"granularity":"word","timing":[[1,4.0]]}]}}}"""

    @Test
    fun `parses character and word granularity and applies them onto matching base lines`() {
        val data = CommunitySyncCodec.parse(JSONObject(payload))!!
        assertEquals(42L, data.lrclibId)
        assertEquals(listOf(1000L, 1500L, 2000L), data.lines[0].charTimesMs)
        assertEquals(listOf(4000L, 4000L), data.lines[1].charTimesMs)
        val lines = CommunitySyncCodec.apply(listOf("가나다", "라마"), data)!!
        assertEquals(listOf(1000L, 4000L), lines.map { it.timeMs })
        assertEquals(listOf("가", "나", "다"), lines[0].syllables!!.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), lines[0].syllables!!.map { it.startMs })
        assertEquals(1500L, lines[0].syllables!![0].endMs)
    }

    @Test
    fun `refuses to apply when the authored line shape differs`() {
        val data = CommunitySyncCodec.parse(JSONObject(payload))!!
        assertNull(CommunitySyncCodec.apply(listOf("가나다라", "마"), data))
    }

    @Test
    fun `Deezer candidate choice prefers matching duration and non-variants`() {
        val c = listOf(
            Isrc.Candidate(1, "Rooftop (Japanese ver.)", "N.Flying", 212),
            Isrc.Candidate(2, "Rooftop", "N.Flying", 210),
            Isrc.Candidate(3, "Other", "N.Flying", 210),
        )
        assertEquals(2L, Isrc.choose(c, "Rooftop", "N.Flying", 210)?.id)
        assertNull(Isrc.choose(c, "Sailing", "Ahn Ye Eun", 180))
    }
}
