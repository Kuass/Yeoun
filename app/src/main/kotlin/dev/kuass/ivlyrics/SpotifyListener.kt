package dev.kuass.ivlyrics

import android.content.ComponentName
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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
        const val SPOTIFY = "com.spotify.music"
        val INACTIVE_STATES = setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ai = Executors.newSingleThreadExecutor() // slow AI calls must never delay the next track's lyrics fetch
    private val prefs by lazy { Prefs(this) }
    private val cache by lazy { LyricsCache(this) }
    private val community by lazy { CommunitySync(this) }
    private val trackPrefs by lazy { TrackPrefs(this) }
    private val localLyrics by lazy { LocalLyrics(this) }
    private var current: Triple<Lyrics, String, String>? = null // lyrics, title, artist of the shown track
    private var currentLang: String? = null
    private var currentLocal: String? = null
    private val requests = LyricsRequestGate()
    private var controller: MediaController? = null
    private var overlay: LyricsOverlay? = null
    private var trackKey: Triple<String, String, Long>? = null
    private var dismissedFor: Triple<String, String, Long>? = null
    private var lastState: PlaybackState? = null
    private val hideOnPause = Runnable { overlay?.hide() }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }
    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.UI_OPEN -> updateVisibility()
            Prefs.TRACK_PREFS_VERSION -> applyTrackPrefs()
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
        val languageChanged = entry.lang != currentLang
        currentLocal = localNow
        currentLang = entry.lang
        if (localChanged) {
            loadLyrics(key, controller?.metadata)
        } else if (languageChanged) {
            val stored = current
            if (stored == null) loadLyrics(key, controller?.metadata)
            else {
                val ticket = requests.begin(key)
                overlay?.clearExtras()
                LyricsState.update { it.copy(translation = null, phonetic = null) }
                queueEnrichment(ticket, stored.first, stored.second, stored.third)
            }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = onState(state)
        override fun onSessionDestroyed() = attach(emptyList())
    }

    override fun onListenerConnected() {
        // requestRebind() from MainActivity can reconnect without a disconnect: drop the old session and overlay first.
        attach(emptyList())
        overlay = LyricsOverlay(this, prefs, onDismissed = { dismissedFor = trackKey }, onTap = {
            startActivity(android.content.Intent(this, FullscreenActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsChanged)
        val manager = getSystemService(MediaSessionManager::class.java)
        val self = ComponentName(this, SpotifyListener::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsChanged, self, main)
        attach(manager.getActiveSessions(self))
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsChanged)
        attach(emptyList())
        overlay = null
    }

    private fun attach(sessions: List<MediaController>?) {
        val spotify = sessions?.firstOrNull { it.packageName == SPOTIFY }
        if (spotify?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = spotify
        if (spotify == null) {
            requests.invalidate()
            current = null
            LyricsState.update { LyricsState.Snapshot() }
            trackKey = null
            lastState = null
            main.removeCallbacks(hideOnPause)
            overlay?.hide()
            return
        }
        spotify.registerCallback(callback, main)
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
        currentLocal = localLyrics.get(TrackPrefs.key(title, artist, durationSec))
        overlay?.setTrackOffset(entry.offsetMs)
        LyricsState.update { LyricsState.Snapshot(key, title, artist, null, null, null, entry.offsetMs) }
        loadLyrics(key, md)
        updateVisibility()
    }

    /** Each reload invalidates both provider and AI responses from all earlier revisions. */
    private fun loadLyrics(key: Triple<String, String, Long>, md: MediaMetadata?) {
        val ticket = requests.begin(key)
        val (title, artist, durationSec) = key
        current = null
        overlay?.setTrack(title)
        LyricsState.update { it.copy(lyrics = null, translation = null, phonetic = null) }
        val local = localLyrics.lyrics(TrackPrefs.key(title, artist, durationSec))
        if (local != null) {
            publishLyrics(ticket, local)
            return
        }
        val trackId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.removePrefix("spotify:track:")?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val communityEnabled = prefs.srcCommunity
        val order = prefs.sourceOrder
        val karaoke = prefs.karaoke
        val deferRomanized = prefs.targetLang == "ko"
        io.execute {
            if (!requests.accepts(ticket)) return@execute
            val lyrics = (if (communityEnabled) community.lookup(trackId, title, artist, durationSec) else null)
                ?: LyricsSources.fetch(order, title, artist, album, durationSec, karaoke, deferRomanized)
            main.post { publishLyrics(ticket, lyrics) }
        }
    }

    private fun publishLyrics(ticket: LyricsRequestGate.Ticket, lyrics: Lyrics) {
        if (!requests.accepts(ticket)) return
        val (title, artist) = ticket.key
        overlay?.setLyrics(lyrics)
        current = Triple(lyrics, title, artist)
        LyricsState.update { it.copy(lyrics = lyrics, translation = null, phonetic = null) }
        if (!lyrics.isEmpty) queueEnrichment(ticket, lyrics, title, artist)
    }

    private fun queueEnrichment(ticket: LyricsRequestGate.Ticket, lyrics: Lyrics, title: String, artist: String) {
        val cfg = prefs.aiConfig ?: return
        val opt = prefs.aiOptions.let { base -> currentLang?.let { base.copy(target = Lang.target(it)) } ?: base }
        val translate = prefs.translate
        val phonetic = prefs.phonetic
        ai.execute { enrich(ticket, lyrics, title, artist, cfg, opt, translate, phonetic) }
    }

    /** Uses immutable settings captured when this revision was queued. */
    private fun enrich(ticket: LyricsRequestGate.Ticket, lyrics: Lyrics, title: String, artist: String,
                       cfg: Ai.Config, opt: Ai.Options, wantTranslate: Boolean, wantPhonetic: Boolean) {
        if (!requests.accepts(ticket)) return
        val key = ticket.key
        if (!wantTranslate && !wantPhonetic) return
        val texts = lyrics.lines.map { it.text }
        if (Lang.isAlreadyIn(texts, opt.target)) return
        val cacheKey = "$key|${lyrics.synced}|${texts.hashCode()}|${opt.fingerprint}"
        val hit = cache.get(cacheKey)
        if (hit != null) {
            main.post { if (requests.accepts(ticket)) { overlay?.setExtras(hit.translation, hit.phonetic); LyricsState.update { it.copy(translation = hit.translation ?: it.translation, phonetic = hit.phonetic ?: it.phonetic) } } }
            if ((hit.translation != null || !wantTranslate) && (hit.phonetic != null || !wantPhonetic)) return
        }
        if (!requests.accepts(ticket)) return
        val translation = hit?.translation ?: if (wantTranslate) Ai.translate(cfg, texts, title, artist, opt) else null
        if (translation != null) main.post { if (requests.accepts(ticket)) { overlay?.setExtras(translation, null); LyricsState.update { it.copy(translation = translation) } } }
        if (!requests.accepts(ticket)) return
        val phonetic = hit?.phonetic ?: if (wantPhonetic) Ai.pronounce(cfg, texts, opt) else null
        if (phonetic != null) main.post { if (requests.accepts(ticket)) { overlay?.setExtras(null, phonetic); LyricsState.update { it.copy(phonetic = phonetic) } } }
        if (translation != null || phonetic != null) cache.put(cacheKey, LyricsCache.Extras(translation, phonetic))
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
