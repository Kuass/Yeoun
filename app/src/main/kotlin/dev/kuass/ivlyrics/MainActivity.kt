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
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) io.execute {
            val result = runCatching {
                val data = BackupCodec.encode(BackupStore(this).snapshot())
                require(data.toByteArray().size <= BackupCodec.MAX_BYTES)
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                output.bufferedWriter().use { it.write(data) }
            }
            main.post { if (!isDestroyed) documentMessage(if (result.isSuccess) R.string.backup_saved else R.string.backup_failed) }
        }
    }
    private val backupImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io.execute {
            val result = runCatching { requireNotNull(contentResolver.openInputStream(uri)).use(BackupCodec::read) }
            main.post {
                if (isDestroyed || isFinishing) return@post
                result.onSuccess { data ->
                    MaterialAlertDialogBuilder(this).setTitle(R.string.backup_restore)
                        .setMessage(getString(R.string.backup_preview, data.tracks.size, data.local.size, data.presets.size, data.edits.size))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.backup_restore) { _, _ -> io.execute {
                            val restored = runCatching { BackupStore(this).restore(data) }
                            main.post {
                                if (isDestroyed || isFinishing) return@post
                                if (restored.isSuccess) {
                                    val code = prefs.appLang
                                    val locales = if (code == Prefs.APP_LANG_SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(code)
                                    if (AppCompatDelegate.getApplicationLocales() == locales) recreate()
                                    else AppCompatDelegate.setApplicationLocales(locales)
                                } else documentMessage(R.string.backup_partial_failure)
                            }
                        } }.show()
                }.onFailure { documentMessage(R.string.backup_invalid) }
            }
        }
    }
    private var importKey: String? = null
    private var exportFile: String? = null
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = importKey
        importKey = null
        if (uri != null && key != null) io.execute {
            val result = runCatching {
                val text = contentResolver.openInputStream(uri)?.use(LyricsDocument::read)
                    ?: error("Cannot read document")
                LocalLyrics(this).set(key, text)
                prefs.bumpTrackPrefs()
            }
            main.post {
                if (!isDestroyed && !isFinishing) documentMessage(if (result.isSuccess) R.string.lrc_imported else R.string.lrc_import_error)
            }
        }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = exportFile?.let { java.io.File(cacheDir, it) }
        exportFile = null
        if (file != null) io.execute {
            if (uri == null) { file.delete(); return@execute }
            val result = runCatching {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write document")
                output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
            }
            file.delete()
            main.post {
                if (!isDestroyed && !isFinishing) documentMessage(if (result.isSuccess) R.string.lrc_exported else R.string.lrc_export_error)
            }
        }
    }

    private fun documentMessage(id: Int) = Snackbar.make(findViewById(R.id.scroll), id, Snackbar.LENGTH_LONG).show()

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
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        prefs = Prefs(this)
        importKey = savedInstanceState?.getString("import_key")
        exportFile = savedInstanceState?.getString("export_file")
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
        showNowPlaying(null)
        bindAi()
        bindApp()
        bindSections(savedInstanceState?.getInt("settings_section") ?: intent.getIntExtra("settings_section", 0))
        if (savedInstanceState == null) handleSettingsIntent()
        refreshPreview()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        findViewById<TabLayout>(R.id.settingsTabs).getTabAt(intent.getIntExtra("settings_section", 0).coerceIn(0, 3))?.select()
        handleSettingsIntent()
    }

    private fun handleSettingsIntent() {
        val language = intent.getStringExtra("source_language")?.takeIf { it in SourceLanguage.CODES } ?: return
        intent.removeExtra("source_language")
        LanguageSettingsUi.show(this, language) { refreshPreview() }
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
        outState.putString("import_key", importKey)
        outState.putString("export_file", exportFile)
        super.onSaveInstanceState(outState)
    }

    private fun bindApp() {
        AboutUi.bind(this)
        findViewById<View>(R.id.btnBackup).setOnClickListener { backupExport.launch("yeoun-backup.json") }
        findViewById<View>(R.id.btnRestore).setOnClickListener { backupImport.launch(arrayOf("application/json", "text/plain")) }
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
        LyricsState.addListener(extrasListener)
        prefs.putBoolean(Prefs.UI_OPEN, true)
        findViewById<MaterialSwitch>(R.id.switchPeek).isChecked = false
        refreshPermissions()
        startSessionWatch()
        demoStartedAt = System.currentTimeMillis()
        main.postDelayed(demo, DEMO_STEP_MS)
        main.post(demoTick)
    }

    override fun onPause() {
        LyricsState.removeListener(extrasListener)
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
        switch(R.id.switchInlinePronunciation, prefs.inlinePronunciation, Prefs.INLINE_PRONUNCIATION)
        findViewById<View>(R.id.btnPresets).setOnClickListener { PresetUi.show(this) }
        slider(R.id.sliderTranslationPrev, R.id.valueTranslationPrev, prefs.translationPrev, Prefs.TRANSLATION_PREV) { getString(R.string.lines_format, it) }
        slider(R.id.sliderTranslationNext, R.id.valueTranslationNext, prefs.translationNext, Prefs.TRANSLATION_NEXT) { getString(R.string.lines_format, it) }
        slider(R.id.sliderPhoneticPrev, R.id.valuePhoneticPrev, prefs.phoneticPrev, Prefs.PHONETIC_PREV) { getString(R.string.lines_format, it) }
        slider(R.id.sliderPhoneticNext, R.id.valuePhoneticNext, prefs.phoneticNext, Prefs.PHONETIC_NEXT) { getString(R.string.lines_format, it) }
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
        findViewById<View>(R.id.btnRetryExtras).setOnClickListener { prefs.putString(Prefs.EXTRAS_VERSION, java.util.UUID.randomUUID().toString()) }
        dropdown(R.id.sourceLanguage, sourceLanguageOptions(), "") { source ->
            nowKey?.let { key ->
                trackPrefs.set(key, trackPrefs.get(key).copy(sourceLanguage = source.ifEmpty { null }))
                prefs.bumpTrackPrefs()
            }
        }
        findViewById<View>(R.id.btnLyricsSearch).setOnClickListener {
            val key = nowKey ?: return@setOnClickListener
            val md = controller?.metadata
            LyricsSearchUi.show(this, key, md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
        }
        findViewById<View>(R.id.btnExtrasEditor).setOnClickListener {
            val snap = LyricsState.snapshot
            if (snap.key?.let { TrackPrefs.key(it.first, it.second, it.third) } == nowKey) ExtrasEditorUi.show(this, snap)
        }
        findViewById<MaterialButton>(R.id.btnLocalLyrics).setOnClickListener { showLocalLyrics() }
        dropdown(R.id.trackSource, sourceOptions(), "") { source ->
            nowKey?.let { key ->
                trackPrefs.set(key, trackPrefs.get(key).copy(source = source.ifEmpty { null }))
                prefs.bumpTrackPrefs()
            }
        }
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
        findViewById<MaterialButton>(R.id.btnSync).setOnClickListener { startActivity(Intent(this, SyncCreatorActivity::class.java)) }
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
        findViewById<View>(R.id.btnLocalLyrics).isEnabled = enabled
        findViewById<View>(R.id.btnLyricsSearch).isEnabled = enabled
        findViewById<View>(R.id.sourceLanguage).isEnabled = enabled
        findViewById<AutoCompleteTextView>(R.id.trackSource).isEnabled = enabled
        val entry = nowKey?.let { trackPrefs.get(it) } ?: TrackPrefs.Entry()
        findViewById<AutoCompleteTextView>(R.id.sourceLanguage).setText(sourceLanguageOptions().firstOrNull { it.first == (entry.sourceLanguage ?: "") }?.second, false)
        showExtrasState(LyricsState.snapshot)
        findViewById<AutoCompleteTextView>(R.id.trackSource).setText(
            sourceOptions().firstOrNull { it.first == (entry.source ?: "") }?.second, false)
        slider.value = entry.offsetMs.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        val label = entry.lang?.let { code -> Lang.TARGETS.firstOrNull { it.code == code } }?.let { "${it.native} · ${it.name}" }
            ?: getString(R.string.track_lang_default)
        lang.setText(label, false)
        findViewById<TextInputEditText>(R.id.trackVideo).apply {
            val shown = entry.videoId?.let { "https://youtu.be/$it" } ?: ""
            if (text.toString() != shown) setText(shown)
        }
    }

    private val extrasListener: (LyricsState.Snapshot) -> Unit = { showExtrasState(it) }

    private fun showExtrasState(snapshot: LyricsState.Snapshot) {
        val matches = nowKey != null && snapshot.key?.let { TrackPrefs.key(it.first, it.second, it.third) } == nowKey
        findViewById<View>(R.id.btnExtrasEditor).isEnabled = matches && snapshot.extrasKey != null
        val languages = if (matches) snapshot.sourceLanguages.filter { it.isNotEmpty() }.distinct() else emptyList()
        findViewById<TextView>(R.id.extrasProgress).text = if (matches) snapshot.extrasProgress?.label(this).orEmpty() else ""
        findViewById<View>(R.id.btnRetryExtras).isEnabled = matches && snapshot.extrasProgress?.canRetry == true
        findViewById<TextView>(R.id.detectedLanguages).text = getString(R.string.detected_languages,
            languages.joinToString(", ") { SourceLanguage.label(it, this) }.ifEmpty { getString(R.string.language_unset) })
    }

    private fun sourceLanguageOptions() = listOf("" to getString(R.string.source_language_auto)) +
        SourceLanguage.CODES.map { it to SourceLanguage.label(it, this) }

    private fun sourceOptions() = listOf(
        "" to getString(R.string.track_source_default),
        LrcLib.ID to getString(R.string.source_lrclib),
        Lyrically.ID to getString(R.string.source_lyrically),
        LyricsPlus.ID to getString(R.string.source_lyricsplus),
    )

    private fun showLocalLyrics() {
        val key = nowKey ?: return
        val local = LocalLyrics(this)
        val stored = local.get(key)
        val snapshot = LyricsState.snapshot
        val lyrics = snapshot.lyrics?.takeIf {
            snapshot.key?.let { TrackPrefs.key(it.first, it.second, it.third) } == key && it.synced && !it.isEmpty
        }
        val export = stored?.takeUnless { it.startsWith(LocalLyrics.PLAIN_HEADER) } ?: lyrics?.let { LrcWriter.write(it.lines.map { line -> line.text }, it.lines.map { line -> line.timeMs }) }
        val actions = mutableListOf(R.string.lrc_import)
        if (export != null) actions += R.string.lrc_export
        if (stored != null) actions += R.string.lrc_remove
        MaterialAlertDialogBuilder(this).setTitle(R.string.local_lyrics)
            .setItems(actions.map { getString(it) }.toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.lrc_import -> MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.lrc_import).setMessage(R.string.lrc_import_hint)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            importKey = key
                            importDocument.launch(arrayOf("*/*"))
                        }.show()
                    R.string.lrc_export -> {
                        if (export != null) io.execute {
                            val result = runCatching {
                                java.io.File.createTempFile("lyrics-export-", ".lrc", cacheDir).also { it.writeText(export, Charsets.UTF_8) }
                            }
                            main.post {
                                if (isDestroyed || isFinishing) { result.getOrNull()?.delete(); return@post }
                                result.onSuccess { file ->
                                    exportFile = file.name
                                    exportDocument.launch("lyrics.lrc")
                                }.onFailure { documentMessage(R.string.lrc_export_error) }
                            }
                        }
                    }
                    R.string.lrc_remove -> MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.lrc_remove).setMessage(R.string.lrc_remove_hint)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            local.remove(key)
                            prefs.bumpTrackPrefs()
                            documentMessage(R.string.lrc_removed)
                        }.show()
                }
            }.show()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    private fun isListenerEnabled() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    // ---- lyrics sources ----------------------------------------------------------------------

    private fun bindLyrics() {
        findViewById<View>(R.id.btnRecentSongs).setOnClickListener { io.execute {
            val songs = SongArchive(this).recent()
            main.post { if (!isDestroyed && !isFinishing) RecentSongsUi.show(this, songs) }
        } }
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
        findViewById<View>(R.id.btnLanguageSettings).setOnClickListener { LanguageSettingsUi.select(this) { refreshPreview() } }
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
                    if (isDestroyed || isFinishing) return@runOnUiThread
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
