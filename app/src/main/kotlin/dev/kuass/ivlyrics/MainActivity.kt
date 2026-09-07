package dev.kuass.ivlyrics

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private companion object {
        const val DEMO_STEP_MS = 2600L
        const val DEMO_TICK_MS = 80L
        var rebindRequested = false
    }

    private lateinit var prefs: Prefs
    private lateinit var preview: LyricsView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var demoIndex = 2
    private var demoStartedAt = 0L
    private val demo = object : Runnable {
        override fun run() {
            demoIndex = (demoIndex + 1) % DEMO_LINES
            demoStartedAt = System.currentTimeMillis()
            refreshPreview()
            main.postDelayed(this, DEMO_STEP_MS)
        }
    }
    private val demoTick = object : Runnable {
        override fun run() {
            preview.setPosition(System.currentTimeMillis() - demoStartedAt)
            main.postDelayed(this, DEMO_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        preview = findViewById(R.id.preview)
        bindPermissions()
        bindDisplay()
        bindLyrics()
        bindNowPlaying()
        bindAi()
        bindApp()
        bindSections(savedInstanceState?.getInt("settings_section") ?: 0)
        refreshPreview()
    }

    private fun bindSections(selected: Int) {
        val panels = listOf(R.id.panelDisplay, R.id.panelTranslation, R.id.panelSources, R.id.panelApp)
        val labels = listOf(R.string.tab_display, R.string.tab_translation, R.string.tab_sources, R.string.tab_app)
        val tabs = findViewById<TabLayout>(R.id.settingsTabs)
        labels.forEach { tabs.addTab(tabs.newTab().setText(it), false) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFocus?.clearFocus()
                WindowCompat.getInsetsController(window, tabs).hide(WindowInsetsCompat.Type.ime())
                panels.forEachIndexed { index, id ->
                    findViewById<View>(id).visibility = if (index == tab.position) View.VISIBLE else View.GONE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(selected.coerceIn(0, panels.lastIndex))?.select()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settings_section", findViewById<TabLayout>(R.id.settingsTabs).selectedTabPosition)
        super.onSaveInstanceState(outState)
    }

    private fun bindApp() {
        dropdown(R.id.appLang, listOf(
            Prefs.APP_LANG_SYSTEM to getString(R.string.lang_system),
            "ko" to getString(R.string.lang_ko),
            "en" to getString(R.string.lang_en),
        ), prefs.appLang) { code ->
            prefs.putString(Prefs.APP_LANG, code)
            AppCompatDelegate.setApplicationLocales(
                if (code == Prefs.APP_LANG_SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(code)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.putBoolean(Prefs.UI_OPEN, true)
        findViewById<MaterialSwitch>(R.id.switchPeek).isChecked = false
        refreshPermissions()
        startSessionWatch()
        demoStartedAt = System.currentTimeMillis()
        main.postDelayed(demo, DEMO_STEP_MS)
        main.post(demoTick)
    }

    override fun onPause() {
        stopSessionWatch()
        main.removeCallbacks(demo)
        main.removeCallbacks(demoTick)
        prefs.putBoolean(Prefs.UI_OPEN, false)
        super.onPause()
    }

    // ---- permissions -------------------------------------------------------------------------

    private fun bindPermissions() {
        findViewById<View>(R.id.rowNotification).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<View>(R.id.rowOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun refreshPermissions() {
        val listener = isListenerEnabled()
        val overlay = Settings.canDrawOverlays(this)
        state(R.id.stateNotification, listener)
        state(R.id.stateOverlay, overlay)
        val missing = listOf(listener, overlay).count { !it }
        findViewById<TextView>(R.id.status).apply {
            text = if (missing == 0) getString(R.string.status_ready) else getString(R.string.status_blocked, missing)
            setTextColor(getColor(if (missing == 0) R.color.paper_dim else R.color.amber))
            isClickable = missing > 0
            isFocusable = missing > 0
            setOnClickListener {
                if (missing > 0) {
                    findViewById<TabLayout>(R.id.settingsTabs).getTabAt(3)?.select()
                    val connection = findViewById<View>(R.id.connection)
                    connection.post {
                        connection.requestRectangleOnScreen(android.graphics.Rect(0, 0, connection.width, connection.height), false)
                    }
                }
            }
        }
        // Rebind once per process (after the user grants access); every resume would reset the service and refetch lyrics.
        if (listener && !rebindRequested) {
            rebindRequested = true
            NotificationListenerService.requestRebind(ComponentName(this, SpotifyListener::class.java))
        }
    }

    private fun state(id: Int, granted: Boolean) = findViewById<TextView>(id).apply {
        setText(if (granted) R.string.granted else R.string.denied)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (granted) R.drawable.ic_check else R.drawable.ic_chevron, 0)
    }

    // ---- display -----------------------------------------------------------------------------

    private fun bindDisplay() {
        slider(R.id.sliderFont, R.id.valueFont, prefs.fontSp, Prefs.FONT_SP) { getString(R.string.sp_format, it) }
        slider(R.id.sliderPrev, R.id.valuePrev, prefs.prevLines, Prefs.PREV_LINES) { getString(R.string.lines_format, it) }
        slider(R.id.sliderNext, R.id.valueNext, prefs.nextLines, Prefs.NEXT_LINES) { getString(R.string.lines_format, it) }
        slider(R.id.sliderBg, R.id.valueBg, prefs.bgPercent, Prefs.BG_PERCENT) { if (it == 0) getString(R.string.background_off) else "$it%" }
        slider(R.id.sliderHide, R.id.valueHide, prefs.hideOnPauseSec, Prefs.HIDE_ON_PAUSE_SEC) {
            if (it == 0) getString(R.string.hide_never) else getString(R.string.seconds_format, it)
        }
        switch(R.id.switchAnimate, prefs.animate, Prefs.ANIMATE)
        switch(R.id.switchKaraoke, prefs.karaoke, Prefs.KARAOKE)
    }

    private fun slider(sliderId: Int, valueId: Int, initial: Int, key: String, label: (Int) -> String) {
        val slider = findViewById<Slider>(sliderId)
        val value = findViewById<TextView>(valueId)
        slider.value = initial.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        value.text = label(slider.value.toInt())
        slider.addOnChangeListener { _, v, fromUser ->
            value.text = label(v.toInt())
            if (fromUser) { prefs.putInt(key, v.toInt()); refreshPreview() }
        }
    }

    private fun switch(id: Int, initial: Boolean, key: String) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> prefs.putBoolean(key, checked); refreshPreview() }
        }
    }

    private val DEMO_LINES = 6

    private fun refreshPreview() {
        preview.setStyle(prefs.lyricsStyle)
        val lines = listOf(
            getString(R.string.sample_prev2), getString(R.string.sample_prev1), getString(R.string.sample_current),
            getString(R.string.sample_next1), getString(R.string.sample_next2), getString(R.string.sample_next3),
        )
        val readings = listOf(
            getString(R.string.sample_prev2_ph), getString(R.string.sample_prev1_ph), getString(R.string.sample_phonetic),
            getString(R.string.sample_next1_ph), getString(R.string.sample_next2_ph), getString(R.string.sample_next3_ph),
        )
        val meanings = listOf(
            getString(R.string.sample_prev2_tr), getString(R.string.sample_prev1_tr), getString(R.string.sample_translation),
            getString(R.string.sample_next1_tr), getString(R.string.sample_next2_tr), getString(R.string.sample_next3_tr),
        )
        preview.show(LyricsView.Content(lines, demoIndex, if (prefs.phonetic) readings else null, if (prefs.translate) meanings else null,
            syllables = demoSyllables(lines[demoIndex])))
        preview.setPosition(System.currentTimeMillis() - demoStartedAt)
    }

    /** Words of the sample line spread evenly over the demo step so the preview shows karaoke filling. */
    private fun demoSyllables(line: String): List<Syl> {
        val words = line.split(" ")
        val step = (DEMO_STEP_MS - 600) / words.size
        return words.mapIndexed { i, w -> Syl(200 + i * step, 200 + (i + 1) * step, w + if (i == words.lastIndex) "" else " ") }
    }


    // ---- now playing: per-track overrides -----------------------------------------------------

    private val trackPrefs by lazy { TrackPrefs(this) }
    private var nowKey: String? = null
    private var controller: MediaController? = null
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { attachSession(it) }
    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showNowPlaying(metadata)
    }

    private fun bindNowPlaying() {
        switch(R.id.switchVideo, prefs.videoBg, Prefs.VIDEO_BG)
        findViewById<TextInputEditText>(R.id.ytApiKey).apply {
            setText(prefs.ytApiKey)
            doAfterTextChanged { prefs.putString(Prefs.YT_API_KEY, it.toString()) }
        }
        findViewById<TextInputEditText>(R.id.trackVideo).doAfterTextChanged {
            val key = nowKey ?: return@doAfterTextChanged
            val id = VideoMatch.parseVideoId(it.toString())
            if (id != null || it.isNullOrBlank()) { trackPrefs.set(key, trackPrefs.get(key).copy(videoId = id)); prefs.bumpTrackPrefs() }
        }
        findViewById<MaterialButton>(R.id.btnFullscreen).setOnClickListener { startActivity(Intent(this, FullscreenActivity::class.java)) }
        val peek = findViewById<MaterialSwitch>(R.id.switchPeek)
        peek.isChecked = false
        peek.setOnCheckedChangeListener { _, on -> prefs.putBoolean(Prefs.UI_OPEN, !on) }
        val slider = findViewById<Slider>(R.id.sliderTrackOffset)
        val value = findViewById<TextView>(R.id.valueTrackOffset)
        value.text = getString(R.string.ms_format, 0)
        slider.addOnChangeListener { _, v, fromUser ->
            value.text = getString(R.string.ms_format, v.toInt())
            val key = nowKey ?: return@addOnChangeListener
            if (fromUser) { trackPrefs.set(key, trackPrefs.get(key).copy(offsetMs = v.toInt())); prefs.bumpTrackPrefs() }
        }
        val lang = findViewById<AutoCompleteTextView>(R.id.trackLang)
        val options = listOf("" to getString(R.string.track_lang_default)) + Lang.TARGETS.map { it.code to "${it.native} · ${it.name}" }
        lang.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options.map { it.second }))
        lang.setText(options[0].second, false)
        lang.setOnItemClickListener { _, _, pos, _ ->
            val key = nowKey ?: return@setOnItemClickListener
            trackPrefs.set(key, trackPrefs.get(key).copy(lang = options[pos].first.ifEmpty { null })); prefs.bumpTrackPrefs()
        }
    }

    private fun startSessionWatch() {
        if (!isListenerEnabled()) return
        val manager = getSystemService(MediaSessionManager::class.java)
        val self = ComponentName(this, SpotifyListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionListener, self)
            attachSession(manager.getActiveSessions(self))
        }
    }

    private fun stopSessionWatch() {
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionListener) }
        controller?.unregisterCallback(sessionCallback); controller = null
    }

    private fun attachSession(sessions: List<MediaController>?) {
        val spotify = sessions?.firstOrNull { it.packageName == "com.spotify.music" }
        if (spotify?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(sessionCallback)
        controller = spotify?.also { it.registerCallback(sessionCallback) }
        showNowPlaying(spotify?.metadata)
    }

    private fun showNowPlaying(md: MediaMetadata?) {
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val durationSec = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000
        val slider = findViewById<Slider>(R.id.sliderTrackOffset)
        val lang = findViewById<AutoCompleteTextView>(R.id.trackLang)
        nowKey = title?.let { TrackPrefs.key(it, artist, durationSec) }
        findViewById<TextView>(R.id.nowTitle).text = title ?: getString(R.string.now_playing_none)
        findViewById<TextView>(R.id.nowArtist).text = artist
        val enabled = nowKey != null
        slider.isEnabled = enabled; lang.isEnabled = enabled
        val entry = nowKey?.let { trackPrefs.get(it) } ?: TrackPrefs.Entry()
        slider.value = entry.offsetMs.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        val label = entry.lang?.let { code -> Lang.TARGETS.firstOrNull { it.code == code } }?.let { "${it.native} · ${it.name}" }
            ?: getString(R.string.track_lang_default)
        lang.setText(label, false)
        findViewById<TextInputEditText>(R.id.trackVideo).apply {
            val shown = entry.videoId?.let { "https://youtu.be/$it" } ?: ""
            if (text.toString() != shown) setText(shown)
        }
    }

    private fun isListenerEnabled() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    // ---- lyrics sources ----------------------------------------------------------------------

    private fun bindLyrics() {
        dropdown(R.id.srcFirst, listOf(
            LrcLib.ID to getString(R.string.source_lrclib),
            Lyrically.ID to getString(R.string.source_lyrically),
            LyricsPlus.ID to getString(R.string.source_lyricsplus),
        ), prefs.srcFirst) { prefs.putString(Prefs.SRC_FIRST, it) }
        switch(R.id.switchLrclib, prefs.srcLrclib, Prefs.SRC_LRCLIB)
        switch(R.id.switchLyrically, prefs.srcLyrically, Prefs.SRC_LYRICALLY)
        switch(R.id.switchLyricsPlus, prefs.srcLyricsPlus, Prefs.SRC_LYRICSPLUS)
        switch(R.id.switchCommunity, prefs.srcCommunity, Prefs.SRC_COMMUNITY)
        slider(R.id.sliderOffset, R.id.valueOffset, prefs.offsetMs, Prefs.OFFSET_MS) { getString(R.string.ms_format, it) }
    }

    /** Exposed dropdown bound to a (code, label) list; stores the code. */
    private fun dropdown(id: Int, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val view = findViewById<AutoCompleteTextView>(id)
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options.map { it.second }))
        view.setText(options.firstOrNull { it.first == current }?.second ?: options.first().second, false)
        view.setOnItemClickListener { _, _, pos, _ -> onPick(options[pos].first); refreshPreview() }
    }

    // ---- AI ----------------------------------------------------------------------------------

    private fun bindAi() {
        val provider = findViewById<AutoCompleteTextView>(R.id.provider)
        val baseUrl = findViewById<TextInputEditText>(R.id.baseUrl)
        val apiKey = findViewById<TextInputEditText>(R.id.apiKey)
        val model = findViewById<AutoCompleteTextView>(R.id.model)

        val providerLabel = { name: String -> if (name == Providers.CUSTOM) getString(R.string.provider_custom) else name }
        provider.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, Providers.NAMES.map(providerLabel)))
        provider.setText(providerLabel(Providers.nameFor(prefs.baseUrl)), false)
        provider.setOnItemClickListener { _, _, pos, _ ->
            Providers.baseUrlFor(Providers.NAMES[pos])?.let { baseUrl.setText(it) }
        }

        dropdown(R.id.targetLang, Lang.TARGETS.map { it.code to "${it.native} · ${it.name}" }, prefs.targetLang) { prefs.putString(Prefs.TARGET_LANG, it) }
        dropdown(R.id.style, listOf(
            Lang.Style.NATURAL.code to getString(R.string.style_natural),
            Lang.Style.LITERAL.code to getString(R.string.style_literal),
            Lang.Style.ADAPTIVE.code to getString(R.string.style_adaptive),
        ), prefs.style) { prefs.putString(Prefs.STYLE, it) }
        dropdown(R.id.notation, listOf(
            Lang.Notation.SCRIPT.code to getString(R.string.notation_script),
            Lang.Notation.LATIN.code to getString(R.string.notation_latin),
            Lang.Notation.IPA.code to getString(R.string.notation_ipa),
        ), prefs.notation) { prefs.putString(Prefs.NOTATION, it) }
        findViewById<TextInputEditText>(R.id.instruction).apply {
            setText(prefs.instruction)
            doAfterTextChanged { prefs.putString(Prefs.INSTRUCTION, it.toString()) }
        }
        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener { v ->
            io.execute { LyricsCache(this).clear() }
            Snackbar.make(v, R.string.cache_cleared, Snackbar.LENGTH_SHORT).show()
        }

        baseUrl.setText(prefs.baseUrl)
        baseUrl.doAfterTextChanged {
            val url = it.toString()
            prefs.putString(Prefs.BASE_URL, url)
            provider.setText(providerLabel(Providers.nameFor(url)), false)
        }
        apiKey.setText(prefs.apiKey)
        apiKey.doAfterTextChanged { prefs.putString(Prefs.API_KEY, it.toString()) }
        model.setText(prefs.model)
        model.doAfterTextChanged { prefs.putString(Prefs.MODEL, it.toString()) }
        model.setOnClickListener { if ((model.adapter?.count ?: 0) > 0) model.showDropDown() }

        val btn = findViewById<MaterialButton>(R.id.btnModels)
        btn.setOnClickListener {
            val cfg = Ai.Config(prefs.baseUrl, prefs.apiKey, prefs.model)
            btn.isEnabled = false
            btn.setText(R.string.loading_models)
            io.execute {
                val result = runCatching { Ai.listModels(cfg) }
                runOnUiThread {
                    btn.isEnabled = true
                    btn.setText(R.string.load_models)
                    result.onSuccess { ids ->
                        model.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, ids))
                        Snackbar.make(model, getString(R.string.models_loaded, ids.size), Snackbar.LENGTH_SHORT).show()
                        if (ids.isNotEmpty()) model.showDropDown()
                    }.onFailure { e ->
                        Snackbar.make(model, getString(R.string.models_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
        switch(R.id.switchTranslate, prefs.translate, Prefs.TRANSLATE)
        switch(R.id.switchPhonetic, prefs.phonetic, Prefs.PHONETIC)
    }
}
