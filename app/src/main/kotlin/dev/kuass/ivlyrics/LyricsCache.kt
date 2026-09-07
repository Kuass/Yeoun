package dev.kuass.ivlyrics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Disk cache of AI results per track so repeated plays cost nothing. */
class LyricsCache(ctx: Context) {
    private companion object { const val TAG = "LyricsCache" }

    data class Extras(val translation: List<String>?, val phonetic: List<String>?)

    private val dir = File(ctx.cacheDir, "ai").apply { mkdirs() }

    fun get(key: String): Extras? = try {
        val f = file(key)
        if (!f.exists()) null else JSONObject(f.readText()).let { Extras(it.optList("translation"), it.optList("phonetic")) }
    } catch (e: Exception) {
        Log.w(TAG, "cache read failed", e); null
    }

    fun put(key: String, extras: Extras) = try {
        file(key).writeText(JSONObject()
            .put("translation", extras.translation?.let(::JSONArray))
            .put("phonetic", extras.phonetic?.let(::JSONArray))
            .toString())
    } catch (e: Exception) {
        Log.w(TAG, "cache write failed", e)
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun file(key: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.json")
    }

    private fun JSONObject.optList(name: String): List<String>? =
        optJSONArray(name)?.let { arr -> List(arr.length()) { arr.getString(it) } }
}
