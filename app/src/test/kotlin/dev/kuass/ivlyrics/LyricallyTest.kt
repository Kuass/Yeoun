package dev.kuass.ivlyrics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricallyTest {
    @Test fun `null missing and blank plain lyrics are empty results`() {
        for (body in listOf("""{"plain":null}""", "{}", """{"plain":"  "}""")) {
            assertTrue(Lyrically.parse(JSONObject(body), 180).isEmpty)
        }
    }

    @Test fun `literal null text remains a valid lyric`() {
        val plain = Lyrically.parse(JSONObject("""{"plain":"null"}"""), 180)
        assertEquals(listOf("null"), plain.lines.map { it.text })
        assertFalse(plain.synced)
        val timed = Lyrically.parse(JSONObject("""{"lyrics":[{"timestamp":1000,"text":[{"text":"null","timestamp":1000,"endtime":2000}]}]}"""), 180)
        assertEquals(listOf("null"), timed.lines.map { it.text })
        assertTrue(timed.synced)
    }

    @Test fun `null timed token text does not override usable plain lyrics`() {
        val result = Lyrically.parse(JSONObject("""{"plain":"real lyric","lyrics":[{"timestamp":1000,"text":[{"text":null,"timestamp":1000}]}]}"""), 180)
        assertEquals(listOf("real lyric"), result.lines.map { it.text })
        assertFalse(result.synced)
        assertEquals(Lyrically.ID, result.source)
    }

    @Test fun `entirely null content stays empty`() {
        val result = Lyrically.parse(JSONObject("""{"plain":null,"lrc":null,"lyrics":[{"timestamp":1000,"text":[{"text":null}]}]}"""), 180)
        assertTrue(result.isEmpty)
    }

    @Test fun `null and absent token text never add literal words to timed lyrics`() {
        val result = Lyrically.parse(JSONObject("""{"lyrics":[{"timestamp":1000,"text":[
          {"text":null,"timestamp":0},{"text":"hello","timestamp":1000,"endtime":1500},
          {"timestamp":1600},{"text":null,"timestamp":1700},{"text":"world","timestamp":2000,"endtime":2500},
          {"text":null,"timestamp":3000}]}]}"""), 180)
        assertEquals(listOf("hello world"), result.lines.map { it.text })
        assertEquals(listOf(1000L, 2000L), result.lines.single().syllables!!.map { it.startMs })
        assertTrue(result.synced)
    }

    @Test fun `null tokens still allow the LRC fallback before plain lyrics`() {
        val result = Lyrically.parse(JSONObject("""{"plain":"plain fallback","lrc":"[00:02]timed fallback",
          "lyrics":[{"timestamp":1000,"text":[{"text":null}]}]}"""), 180)
        assertEquals(listOf(LrcLine(2000, "timed fallback")), result.lines)
        assertTrue(result.synced)
    }

    @Test fun `real timed lyrics still take precedence over plain text`() {
        val result = Lyrically.parse(JSONObject("""{"plain":"plain fallback","lyrics":[{"timestamp":1000,
          "text":[{"text":"timed","timestamp":1000,"endtime":1200}]}]}"""), 180)
        assertEquals(listOf(LrcLine(1000, "timed", listOf(Syl(1000, 1200, "timed")))), result.lines)
        assertTrue(result.synced)
    }

    @Test fun `removing a null row does not promote placeholder timestamps over plain lyrics`() {
        val result = Lyrically.parse(JSONObject("""{"plain":"first\nsecond","lyrics":[
          {"timestamp":0,"text":[{"text":null,"timestamp":0,"endtime":0}]},
          {"timestamp":0,"text":[{"text":"second","timestamp":0,"endtime":0}]}]}"""), 180)
        assertEquals(listOf(LrcLine(0, "first"), LrcLine(90000, "second")), result.lines)
        assertFalse(result.synced)
    }

    @Test fun `removing a null row does not hide complete LRC fallback`() {
        val result = Lyrically.parse(JSONObject("""{"lrc":"[00:01]first\n[00:02]second","lyrics":[
          {"timestamp":0,"text":[{"text":null,"timestamp":0,"endtime":0}]},
          {"timestamp":0,"text":[{"text":"second","timestamp":0,"endtime":0}]}]}"""), 180)
        assertEquals(listOf(LrcLine(1000, "first"), LrcLine(2000, "second")), result.lines)
        assertTrue(result.synced)
    }
}
