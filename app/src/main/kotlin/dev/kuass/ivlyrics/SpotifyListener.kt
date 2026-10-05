package dev.kuass.ivlyrics

import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/**
 * Notification-listener access is only used to read Spotify's media session:
 * track metadata and playback position drive the lyrics overlay.
 */
class SpotifyListener : NotificationListenerService() {
    private companion object {
        val INACTIVE_STATES = setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ai = Executors.newSingleThreadExecutor() // slow AI calls must never delay the next track's lyrics fetch
    private val prefs by lazy { Prefs(this) }
    private val languagePrefs by lazy { LanguagePrefs(this) }
    private val edits by lazy { LyricsEdits(this) }
    private val archive by lazy { SongArchive(this) }
    private val cache by lazy { LyricsCache(this) }
    private val community by lazy { CommunitySync(this) }
    private val trackPrefs by lazy { TrackPrefs(this) }
    private val localLyrics by lazy { LocalLyrics(this) }
    private var current: Triple<Lyrics, String, String>? = null // lyrics, title, artist of the shown track
    private var currentLang: String? = null
    private var currentLocal: String? = null
    private var currentSource: String? = null
    private var currentSourceLanguage: String? = null
    private val requests = LyricsRequestGate()
    private val loads = LyricsRequestGate()
    private val controller: MediaController? get() = sessions.controller
    private var overlay: LyricsOverlay? = null
    private var trackKey: Triple<String, String, Long>? = null
    private var dismissedFor: Triple<String, String, Long>? = null
    private var lastState: PlaybackState? = null
    private val hideOnPause = Runnable { overlay?.hide() }

    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith(LanguagePrefs.PREFIX) == true) refreshEnrichment()
        else when (key) {
            Prefs.TRANSLATE, Prefs.PHONETIC, Prefs.TARGET_LANG, Prefs.STYLE, Prefs.INSTRUCTION,
            Prefs.NOTATION, Prefs.BASE_URL, Prefs.API_KEY, Prefs.MODEL, Prefs.EXTRAS_VERSION -> refreshEnrichment()
            Prefs.UI_OPEN -> updateVisibility()
            Prefs.TRACK_PREFS_VERSION -> applyTrackPrefs()
            Prefs.SRC_FIRST, Prefs.SRC_LRCLIB, Prefs.SRC_LYRICALLY, Prefs.SRC_LYRICSPLUS,
            Prefs.SRC_COMMUNITY, Prefs.KARAOKE -> {
                overlay?.applyPrefs()
                trackKey?.let { loadLyrics(it, controller?.metadata) }
            }
            else -> overlay?.applyPrefs()
        }
    }

    /** Re-reads the current track's overrides; a language change re-runs translation with the new target. */
    private fun applyTrackPrefs() {
        val key = trackKey ?: return
        val entry = trackPrefs.get(TrackPrefs.key(key.first, key.second, key.third))
        overlay?.setTrackOffset(entry.offsetMs)
        LyricsState.update { it.copy(trackOffsetMs = entry.offsetMs) }
        val localNow = localLyrics.get(TrackPrefs.key(key.first, key.second, key.third))
        val localChanged = localNow != currentLocal
        val languageChanged = entry.lang != currentLang || entry.sourceLanguage != currentSourceLanguage
        currentSourceLanguage = entry.sourceLanguage
        val sourceChanged = entry.source != currentSource
        currentSource = entry.source
        currentLocal = localNow
        currentLang = entry.lang
        if (localChanged || sourceChanged) {
            loadLyrics(key, controller?.metadata)
        } else if (languageChanged) refreshEnrichment()
    }

    private fun refreshEnrichment() {
        val key = trackKey ?: return
        val stored = current ?: return // The pending provider load will capture current preferences when it publishes.
        val ticket = requests.begin(key)
        overlay?.clearExtras()
        LyricsState.update { it.copy(translation = null, phonetic = null) }
        queueEnrichment(ticket, stored.first, stored.second, stored.third)
    }

    private val sessions: SpotifySessionBinding by lazy {
        SpotifySessionBinding(this, main, ::onSessionChanged, ::onMetadata, ::onState)
    }

    override fun onListenerConnected() {
        // requestRebind() from MainActivity can reconnect without a disconnect: drop the old session and overlay first.
        sessions.stop()
        overlay = LyricsOverlay(this, prefs, onDismissed = { dismissedFor = trackKey }, onTap = {
            startActivity(android.content.Intent(this, FullscreenActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsChanged)
        sessions.start()
    }

    override fun onListenerDisconnected() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsChanged)
        sessions.stop()
        overlay = null
    }

    private fun onSessionChanged(spotify: MediaController?) {
        if (spotify == null) {
            requests.invalidate(); loads.invalidate()
            current = null
            LyricsState.update { LyricsState.Snapshot() }
            trackKey = null
            lastState = null
            main.removeCallbacks(hideOnPause)
            overlay?.hide()
            return
        }
        onMetadata(spotify.metadata)
        onState(spotify.playbackState)
    }

    private fun onMetadata(md: MediaMetadata?) {
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val durationSec = md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000
        val key = Triple(title, artist, durationSec)
        if (key == trackKey) return
        trackKey = key
        val entry = trackPrefs.get(TrackPrefs.key(title, artist, durationSec))
        currentLang = entry.lang
        currentSource = entry.source
        currentSourceLanguage = entry.sourceLanguage
        currentLocal = localLyrics.get(TrackPrefs.key(title, artist, durationSec))
        overlay?.setTrackKey(TrackPrefs.key(title, artist, durationSec))
        overlay?.setTrackOffset(entry.offsetMs)
        LyricsState.update { LyricsState.Snapshot(key, title, artist, null, null, null, entry.offsetMs) }
        loadLyrics(key, md)
        updateVisibility()
    }

    /** Each reload invalidates both provider and AI responses from all earlier revisions. */
    private fun loadLyrics(key: Triple<String, String, Long>, md: MediaMetadata?) {
        val loadTicket = loads.begin(key)
        val ticket = requests.begin(key)
        val (title, artist, durationSec) = key
        current = null
        overlay?.setTrack(title)
        LyricsState.update { it.copy(lyrics = null, translation = null, phonetic = null, sourceLanguages = emptyList(), extrasKey = null, extrasProgress = null) }
        val local = localLyrics.lyrics(TrackPrefs.key(title, artist, durationSec), durationSec)
        if (local != null) {
            publishLyrics(ticket, local)
            io.execute { runCatching { archive.save(key, local) } }
            return
        }
        val trackId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.removePrefix("spotify:track:")?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val selectedSource = trackPrefs.get(TrackPrefs.key(title, artist, durationSec)).source
        val selection = LyricsSources.select(selectedSource, prefs.sourceOrder, prefs.srcCommunity)
        val karaoke = prefs.karaoke
        val deferRomanized = prefs.targetLang == "ko"
        io.execute {
            if (!loads.accepts(loadTicket)) return@execute
            val cached = archive.get(key)?.takeIf { selection.acceptsCached(it.lyrics.source) }
            if (cached != null) main.post { publishLyrics(ticket, cached.lyrics) }
            val lyrics = runCatching {
                (if (selection.community) community.lookup(trackId, title, artist, durationSec) else null)
                    ?: LyricsSources.fetch(selection.order, title, artist, album, durationSec, karaoke, deferRomanized)
            }.getOrDefault(Lyrics.NONE)
            if (!loads.accepts(loadTicket)) return@execute
            if (!lyrics.isEmpty) runCatching { archive.save(key, lyrics) }
            else if (cached != null) return@execute
            main.post {
                if (loads.accepts(loadTicket)) publishLyrics(requests.begin(key), lyrics)
            }
        }
    }

    private fun publishLyrics(ticket: LyricsRequestGate.Ticket, lyrics: Lyrics) {
        if (!requests.accepts(ticket)) return
        val (title, artist) = ticket.key
        overlay?.clearExtras()
        overlay?.setLyrics(lyrics)
        current = Triple(lyrics, title, artist)
        LyricsState.update { it.copy(lyrics = lyrics, translation = null, phonetic = null) }
        if (!lyrics.isEmpty) queueEnrichment(ticket, lyrics, title, artist)
    }

    private fun queueEnrichment(ticket: LyricsRequestGate.Ticket, lyrics: Lyrics, title: String, artist: String) {
        val cfg = prefs.aiConfig
        val opt = prefs.aiOptions.let { base -> currentLang?.let { base.copy(target = Lang.target(it)) } ?: base }
        val texts = lyrics.lines.map { it.text }
        val languages = SourceLanguage.detectLines(texts, currentSourceLanguage)
        val plan = EnrichmentPlan.create(texts, languages, opt.target.code,
            languages.distinct().associateWith { languagePrefs.get(it) }, prefs.translate, prefs.phonetic)
        overlay?.setLanguagePrompt(plan.pendingLanguages.firstOrNull())
        val cacheKey = ExtrasPolicy.key(ticket.key, texts, opt)
        LyricsState.update { it.copy(sourceLanguages = plan.languages, extrasKey = cacheKey) }
        ai.execute {
            if (!requests.accepts(ticket)) return@execute
            val work = plan.prepare(cache.get(cacheKey), edits.get(cacheKey), cfg != null)
            val hit = work.cached
            fun publish(result: LyricsCache.Extras, finished: Boolean = false) {
                val (tr, ph, progress) = work.presentation(result, finished)
                main.post {
                    if (requests.accepts(ticket)) {
                        overlay?.setExtrasProgress(progress)
                        overlay?.clearExtras()
                        overlay?.setExtras(tr, ph)
                        LyricsState.update { it.copy(translation = tr, phonetic = ph, extrasProgress = progress) }
                    }
                }
            }
            publish(hit)
            if (cfg == null || !requests.accepts(ticket)) return@execute
            val translationInput = work.translationInput
            val translated = if (translationInput.any(String::isNotBlank)) Ai.translate(cfg, translationInput, title, artist, opt) else null
            val translation = ExtrasPolicy.merge(hit.translation, translated, texts.size)
            if (!requests.accepts(ticket)) return@execute
            if (translated != null) {
                cache.put(cacheKey, LyricsCache.Extras(translation, hit.phonetic))
                publish(LyricsCache.Extras(translation, hit.phonetic))
            }
            val phoneticInput = work.phoneticInput
            val pronounced = if (phoneticInput.any(String::isNotBlank)) Ai.pronounce(cfg, phoneticInput, opt) else null
            val result = LyricsCache.Extras(translation, ExtrasPolicy.merge(hit.phonetic, pronounced, texts.size))
            if (!requests.accepts(ticket)) return@execute
            if (translated != null || pronounced != null) cache.put(cacheKey, result)
            publish(result, finished = true)
        }
    }

    private fun onState(state: PlaybackState?) {
        lastState = state
        overlay?.setPlayback(state)
        updateVisibility()
    }

    /** Single place deciding whether the overlay is on screen. */
    private fun updateVisibility() {
        val o = overlay ?: return
        val state = lastState
        main.removeCallbacks(hideOnPause)
        val blocked = prefs.uiOpen || trackKey == null || (dismissedFor != null && dismissedFor == trackKey)
        when {
            blocked || state == null || state.state in INACTIVE_STATES -> o.hide()
            state.state == PlaybackState.STATE_PAUSED -> {
                o.show()
                val sec = prefs.hideOnPauseSec
                if (sec > 0) main.postDelayed(hideOnPause, sec * 1000L)
            }
            else -> o.show()
        }
    }
}
