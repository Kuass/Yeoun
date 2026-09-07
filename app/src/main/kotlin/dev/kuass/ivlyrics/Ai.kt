package dev.kuass.ivlyrics

// Adapted from ivLyrics for Android; modified 2026-09-08.
// Upstream attribution and LGPL-2.1 text: THIRD_PARTY_NOTICES.md and LICENSE.

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Translation and pronunciation through an OpenAI-compatible chat/completions endpoint.
 * Prompts are ported from ivLyrics desktop (AIAddonManager).
 */
object Ai {
    private const val TAG = "Ai"
    private const val TIMEOUT_MS = 90_000
    private const val TEMPERATURE = 0.3
    private const val CHUNK_LINES = 20
    private const val PARALLEL_REQUESTS = 3

    private val pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS)

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    data class Options(
        val target: Lang.Target,
        val style: Lang.Style,
        val instruction: String,
        val notation: Lang.Notation,
    ) {
        /** Part of the cache key: any of these changes the expected output. */
        val fingerprint get() = "${target.code}|${style.code}|${notation.code}|${instruction.trim().hashCode()}"
    }

    fun translate(cfg: Config, lines: List<String>, title: String, artist: String, opt: Options): List<String>? =
        withContentLines(lines) { sub -> translateLines(cfg, sub, title, artist, opt) }

    fun pronounce(cfg: Config, lines: List<String>, opt: Options): List<String>? =
        withContentLines(lines) { sub -> pronounceLines(cfg, sub, opt) }

    /**
     * Sends only non-blank lines to the model, in chunks of [CHUNK_LINES] requested in parallel, and maps the
     * result back onto the original indices. A failed chunk leaves its lines empty instead of failing the song.
     * ponytail: chunking exists because long single requests stop early on some models (6 of 51 lines observed).
     */
    fun withContentLines(lines: List<String>, call: (List<String>) -> List<String>?): List<String>? {
        val idx = lines.indices.filter { lines[it].isNotBlank() && lines[it].trim() != LyricsView.BLANK_LINE }
        if (idx.isEmpty()) return null
        val chunks = idx.map { lines[it] }.chunked(CHUNK_LINES)
        val out = pool.invokeAll(chunks.map { chunk -> Callable { call(chunk) ?: List(chunk.size) { "" } } })
            .flatMap { it.get() }
        if (out.all { it.isEmpty() }) return null
        val full = MutableList(lines.size) { "" }
        idx.forEachIndexed { j, i -> full[i] = out[j] }
        return full
    }

    private fun translateLines(cfg: Config, lines: List<String>, title: String, artist: String, opt: Options): List<String>? {
        val n = lines.size
        val lang = "${opt.target.name} (${opt.target.native})"
        val preferences = opt.instruction.trim().takeIf { it.isNotEmpty() }
            ?.let { "\nUSER PREFERENCES (follow unless they conflict with the output contract):\n$it\n" } ?: ""
        val system = """You are the lyrics translation system for Yeoun.

Translate song lyrics into $lang.

TRANSLATION STYLE:
${opt.style.instruction}

SONG CONTEXT:
- Title: ${title.ifBlank { "unknown" }}
- Artist: ${artist.ifBlank { "unknown" }}
Use what you know about this song and artist (narrator, addressee, relationships, genre, register, slang) to choose fitting wording. Do not add facts to the lyrics themselves.
$preferences
CRITICAL OUTPUT CONTRACT:
- This is a translation task. Translate the meaning of every non-empty lyric line.
- Write the translated lyrics in $lang only.
- Never return the original lyrics unchanged, romanization, or pronunciation instead of a translation.
- Return exactly $n lines, with one output line for each input line in the same order.
- Never merge multiple input lines or split one input line into multiple output lines.
- You may use surrounding lines only to understand context; output line N must still represent input line N.
- Preserve " / " between simultaneous vocal parts and translate each part separately.
- Preserve empty lines as empty lines.
- Preserve music symbols and structural markers such as ♪, [Chorus], and (Yeah).
- Do not add line numbers, prefixes, explanations, JSON, Markdown, or code fences.
- Return only the translated lyric lines."""
        val user = "Translate the following $n lyric lines. Return exactly $n lines and nothing else.\n\n<lyrics>\n${lines.joinToString("\n")}\n</lyrics>"
        return request(cfg, system, user, n, "translate")
    }

    private fun pronounceLines(cfg: Config, lines: List<String>, opt: Options): List<String>? {
        val n = lines.size
        val t = opt.target
        val script = Lang.scriptFor(t, opt.notation)
        val isIpa = opt.notation == Lang.Notation.IPA
        val audience = if (isIpa)
            "Transcribe the original sung lyric sounds into ${script.name}. Infer the language from the lyrics."
        else
            "Convert lyric sounds for ${t.name} (${t.native}) speakers. The required output writing system is ${script.name}."
        val policy = if (isIpa) """- The user's pronunciation notation is IPA. The translation target language does not change the IPA symbols.
- ${script.instruction}
- Prefer a broad standard-language transcription. Preserve a clearly written dialectal or contracted pronunciation only when the lyric spelling makes it explicit.
- Fully transcribe every pronounceable lyric token. Never copy source orthography merely because it resembles IPA."""
        else """- The target language selected by the user determines the output script. The source lyric language NEVER determines the output script.
- ${script.instruction}
- ${if (opt.notation == Lang.Notation.SCRIPT) "Follow the target convention: ${t.phoneticDesc}." else "Use natural phonetic spelling that a ${t.name} speaker can read aloud."}
- Fully transliterate every pronounceable lyric token into ${script.name}. Do not leave Japanese, Korean, Thai, or any other source-script text mixed into the pronunciation.
- Before answering, inspect every output line character by character. If a pronounceable token uses the source script or any script other than ${script.name}, rewrite that token in ${script.name}."""
        val examples = if (isIpa) """- English: night → naɪt
- Japanese: 夢 → jɯme
- Korean: 사랑해 → saɾaŋɦɛ
- Do not return ordinary romanization such as yume or saranghae when IPA is requested."""
        else """- Target Latin script: 夢ならばどれほどよかったでしょう → yume naraba dorehodo yokatta deshou
  Wrong for a Latin target: ユメナラバ ドレホド ヨカッタ デショウ or ゆめならば どれほど よかった でしょう
- Target Latin script: 사랑해 → saranghae
- Target Korean (Hangul): 夢ならばどれほどよかったでしょう → 유메나라바 도레호도 요캇타 데쇼오
  Wrong for Korean: ユメナラバ ドレホド ヨカッタ デショウ or yume naraba dorehodo yokatta deshou
- Target Korean (Hangul): night → 나이트"""
        val system = """You are the pronunciation conversion system for Yeoun.

$audience

MANDATORY SCRIPT POLICY:
$policy

TASK RULES:
- This is a PRONUNCIATION task, not a translation task. Preserve the sound; do not translate the meaning.
- Return exactly $n lines, with one pronunciation for each input line in the same order.
- Never merge multiple input lines or split one input line into multiple output lines.
- If an input line contains " / " between simultaneous vocal parts, preserve " / " and convert each part separately.
- Keep empty lines empty. Keep music symbols and structural markers such as ♪, [Chorus], and (Yeah).
- Do not add line numbers, prefixes, explanations, JSON, Markdown, or code fences.
- Return only the pronunciation lines.

SCRIPT EXAMPLES:
$examples"""
        val user = (if (isIpa) "Transcribe the following $n lyric lines into broad Unicode IPA."
            else "Convert the following $n lyric lines into pronunciation for ${t.name} speakers.") +
            "\nUse ${script.name} for every pronounceable lyric sound. Do not answer in the source lyric's writing system.\n\n<lyrics>\n${lines.joinToString("\n")}\n</lyrics>\n\nReturn exactly $n pronunciation lines in ${script.name}, and nothing else."
        return request(cfg, system, user, n, "pronounce")
    }

    private fun request(cfg: Config, system: String, user: String, expected: Int, what: String): List<String>? = try {
        val raw = complete(cfg, system, user)
        val aligned = align(raw, expected)
        if (aligned == null) Log.w(TAG, "$what: expected $expected lines, got ${raw.lines().size}: ${raw.take(300)}")
        aligned
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        null
    }

    /** Splits model output into exactly [expected] lines, tolerating code fences and stray blank lines. */
    fun align(output: String, expected: Int): List<String>? {
        var lines = output.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        if (lines.firstOrNull()?.trimStart()?.startsWith("```") == true) lines = lines.drop(1)
        if (lines.lastOrNull()?.trimStart()?.startsWith("```") == true) lines = lines.dropLast(1)
        if (lines.size != expected) lines = lines.filter { it.isNotBlank() }
        return if (lines.size == expected) lines.map { it.trim() } else null
    }

    /** One free-form completion; throws on transport or HTTP errors. */
    fun ask(cfg: Config, system: String, user: String): String? = complete(cfg, system, user)

    /** Model ids from GET /models, sorted. Anthropic's endpoint wants x-api-key + anthropic-version, so both are sent. */
    fun listModels(cfg: Config): List<String> {
        val conn = URL("${cfg.baseUrl}/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        conn.setRequestProperty("x-api-key", cfg.apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return parseModels(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    fun parseModels(body: String): List<String> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }.sorted()
    }

    private fun complete(cfg: Config, system: String, user: String): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("temperature", TEMPERATURE)
        val conn = URL("${cfg.baseUrl}/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                throw IOException("HTTP $code $err")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }
}
