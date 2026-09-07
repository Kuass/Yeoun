package dev.kuass.ivlyrics

/** OpenAI-compatible endpoints the settings screen offers as presets. */
object Providers {
    data class Preset(val name: String, val baseUrl: String)

    /** Sentinel for a hand-typed address; the UI shows a localized label for it. */
    const val CUSTOM = "custom"

    val PRESETS = listOf(
        Preset("OpenAI", "https://api.openai.com/v1"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1"),
        Preset("Groq", "https://api.groq.com/openai/v1"),
        Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Preset("Anthropic", "https://api.anthropic.com/v1"),
    )

    val NAMES = PRESETS.map { it.name } + CUSTOM

    fun nameFor(baseUrl: String): String =
        PRESETS.firstOrNull { it.baseUrl == baseUrl.trim().trimEnd('/') }?.name ?: CUSTOM

    fun baseUrlFor(name: String): String? = PRESETS.firstOrNull { it.name == name }?.baseUrl
}
