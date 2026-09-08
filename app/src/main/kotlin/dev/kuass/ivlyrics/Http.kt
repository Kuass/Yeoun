package dev.kuass.ivlyrics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Tiny GET helper shared by the lyrics providers. Returns null on 404. */
object Http {
    const val UA = "Yeoun-Android/0.4.0"

    fun get(url: String, timeoutMs: Int = 8000, headers: Map<String, String> = emptyMap(), nullOn: Set<Int> = setOf(404)): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return try {
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().readText()
                in nullOn -> null
                else -> throw IOException("HTTP $code from ${conn.url.host}${conn.url.path}")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
