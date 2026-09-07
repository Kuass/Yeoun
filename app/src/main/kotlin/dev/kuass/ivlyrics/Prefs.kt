package dev.kuass.ivlyrics

import android.content.Context
import android.content.SharedPreferences

/** User settings. Written by MainActivity, read by the service and overlay. */
class Prefs(ctx: Context) {
    companion object {
        const val NAME = "settings"
        const val BASE_URL = "ai_base_url"
        const val API_KEY = "ai_api_key"
        const val MODEL = "ai_model"
        const val TRANSLATE = "translate"
        const val PHONETIC = "phonetic"
        const val TARGET_LANG = "target_lang"
        const val STYLE = "style"
        const val INSTRUCTION = "instruction"
        const val NOTATION = "notation"
        const val FONT_SP = "font_sp"
        const val PREV_LINES = "prev_lines"
        const val NEXT_LINES = "next_lines"
        const val ANIMATE = "animate"
        const val KARAOKE = "karaoke"
        const val VIDEO_BG = "video_bg"
        const val YT_API_KEY = "yt_api_key"
        const val BG_PERCENT = "bg_percent"
        const val HIDE_ON_PAUSE_SEC = "hide_on_pause_sec"
        const val OFFSET_MS = "offset_ms"
        const val SRC_FIRST = "src_first"
        const val SRC_LRCLIB = "src_lrclib"
        const val SRC_LYRICALLY = "src_lyrically"
        const val SRC_LYRICSPLUS = "src_lyricsplus"
        const val SRC_COMMUNITY = "src_community"
        const val OVERLAY_X = "overlay_x"
        const val OVERLAY_Y = "overlay_y"
        /** True while MainActivity is in the foreground; the overlay stays hidden then. */
        const val UI_OPEN = "ui_open"
        const val APP_LANG = "app_lang"
        /** Bumped whenever a per-track override changes so the service re-reads it. */
        const val TRACK_PREFS_VERSION = "track_prefs_version"
        const val APP_LANG_SYSTEM = "system"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_FONT_SP = 18
        const val DEFAULT_PREV_LINES = 1
        const val DEFAULT_NEXT_LINES = 2
        const val DEFAULT_BG_PERCENT = 80
        const val DEFAULT_HIDE_ON_PAUSE_SEC = 15
    }

    val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val baseUrl: String get() = (sp.getString(BASE_URL, null)?.trim()?.ifEmpty { null } ?: DEFAULT_BASE_URL).trimEnd('/')
    val apiKey: String get() = sp.getString(API_KEY, "")!!.trim()
    val model: String get() = sp.getString(MODEL, "")!!.trim()
    val translate: Boolean get() = sp.getBoolean(TRANSLATE, true)
    val phonetic: Boolean get() = sp.getBoolean(PHONETIC, true)
    val targetLang: String get() = sp.getString(TARGET_LANG, "ko")!!
    val style: String get() = sp.getString(STYLE, Lang.Style.NATURAL.code)!!
    val instruction: String get() = sp.getString(INSTRUCTION, "")!!
    val notation: String get() = sp.getString(NOTATION, Lang.Notation.SCRIPT.code)!!
    val fontSp: Int get() = sp.getInt(FONT_SP, DEFAULT_FONT_SP)
    val prevLines: Int get() = sp.getInt(PREV_LINES, DEFAULT_PREV_LINES)
    val nextLines: Int get() = sp.getInt(NEXT_LINES, DEFAULT_NEXT_LINES)
    val animate: Boolean get() = sp.getBoolean(ANIMATE, true)
    val karaoke: Boolean get() = sp.getBoolean(KARAOKE, true)
    val videoBg: Boolean get() = sp.getBoolean(VIDEO_BG, true)
    val ytApiKey: String get() = sp.getString(YT_API_KEY, "")!!.trim()
    val bgPercent: Int get() = sp.getInt(BG_PERCENT, DEFAULT_BG_PERCENT)
    val hideOnPauseSec: Int get() = sp.getInt(HIDE_ON_PAUSE_SEC, DEFAULT_HIDE_ON_PAUSE_SEC)
    val offsetMs: Int get() = sp.getInt(OFFSET_MS, 0)
    val srcFirst: String get() = sp.getString(SRC_FIRST, LrcLib.ID)!!
    val srcLrclib: Boolean get() = sp.getBoolean(SRC_LRCLIB, true)
    val srcLyrically: Boolean get() = sp.getBoolean(SRC_LYRICALLY, true)
    val srcLyricsPlus: Boolean get() = sp.getBoolean(SRC_LYRICSPLUS, true)
    val srcCommunity: Boolean get() = sp.getBoolean(SRC_COMMUNITY, true)
    val overlayX: Int get() = sp.getInt(OVERLAY_X, 0)
    val overlayY: Int get() = sp.getInt(OVERLAY_Y, Int.MIN_VALUE)
    val uiOpen: Boolean get() = sp.getBoolean(UI_OPEN, false)
    val appLang: String get() = sp.getString(APP_LANG, APP_LANG_SYSTEM)!!

    fun bumpTrackPrefs() = sp.edit().putInt(TRACK_PREFS_VERSION, sp.getInt(TRACK_PREFS_VERSION, 0) + 1).apply()

    val isAiConfigured: Boolean get() = apiKey.isNotEmpty() && model.isNotEmpty()
    val aiConfig: Ai.Config? get() = if (isAiConfigured) Ai.Config(baseUrl, apiKey, model) else null
    val aiOptions: Ai.Options get() = Ai.Options(Lang.target(targetLang), Lang.Style.of(style), instruction, Lang.Notation.of(notation))
    val lyricsStyle: LyricsView.Style get() = LyricsView.Style(fontSp, prevLines, nextLines, animate, bgPercent, karaoke)
    val sourceOrder: List<String> get() = LyricsSources.order(srcFirst, buildSet {
        if (srcLrclib) add(LrcLib.ID); if (srcLyrically) add(Lyrically.ID); if (srcLyricsPlus) add(LyricsPlus.ID)
    })

    fun putString(key: String, value: String) = sp.edit().putString(key, value.trim()).apply()
    fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun saveOverlayPosition(x: Int, y: Int) = sp.edit().putInt(OVERLAY_X, x).putInt(OVERLAY_Y, y).apply()
}
