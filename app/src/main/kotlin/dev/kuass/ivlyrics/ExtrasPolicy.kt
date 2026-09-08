package dev.kuass.ivlyrics

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object ExtrasPolicy {
    fun key(track: Triple<String, String, Long>, texts: List<String>, opt: Ai.Options): String {
        val raw = JSONArray().put(track.first).put(track.second).put(track.third).put(JSONArray(texts))
            .put(opt.target.code).put(opt.style.code).put(opt.notation.code).put(opt.instruction.trim()).toString()
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun visible(values: List<String>?, allowed: List<Boolean>): List<String>? = values?.takeIf { it.size == allowed.size }
        ?.mapIndexed { i, value -> if (allowed[i]) value else "" }?.takeIf { it.any(String::isNotBlank) }

    /** Only missing, enabled lines are sent to AI. Existing values keep their original line indices. */
    fun missing(texts: List<String>, allowed: List<Boolean>, stored: List<String>?): List<String> =
        texts.mapIndexed { i, text -> if (allowed.getOrElse(i) { false } && stored?.getOrNull(i).isNullOrBlank()) text else "" }

    fun merge(stored: List<String>?, generated: List<String>?, size: Int): List<String>? =
        List(size) { i -> stored?.getOrNull(i)?.takeIf(String::isNotBlank) ?: generated?.getOrNull(i).orEmpty() }
            .takeIf { it.any(String::isNotBlank) }
}

/** Sparse edits distinguish an intentionally empty line from an unedited line. */
data class ExtrasEdits(val translation: Map<Int, String> = emptyMap(), val phonetic: Map<Int, String> = emptyMap()) {
    fun apply(extras: LyricsCache.Extras?, size: Int) = LyricsCache.Extras(
        applyLines(extras?.translation, translation, size), applyLines(extras?.phonetic, phonetic, size))

    private fun applyLines(values: List<String>?, edits: Map<Int, String>, size: Int): List<String>? =
        if (values == null && edits.isEmpty()) null else List(size) { edits[it] ?: values?.getOrNull(it).orEmpty() }

    fun encode(): String = JSONObject().put("translation", encodeMap(translation)).put("phonetic", encodeMap(phonetic)).toString()
    private fun encodeMap(values: Map<Int, String>) = JSONObject().apply { values.forEach { (i, text) -> put(i.toString(), text) } }

    companion object {
        fun decode(raw: String): ExtrasEdits {
            val json = JSONObject(raw)
            fun values(name: String): Map<Int, String> {
                val obj = json.optJSONObject(name) ?: return emptyMap()
                return obj.keys().asSequence().mapNotNull { key ->
                    key.toIntOrNull()?.takeIf { it >= 0 }?.let { it to obj.getString(key) }
                }.toMap()
            }
            return ExtrasEdits(values("translation"), values("phonetic"))
        }
    }
}
