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
    private var current: Triple<Lyrics, String, String>? = null // lyrics, title, artist of the shown track
    private var currentLang: String? = null
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
        if (entry.lang != currentLang) {
            currentLang = entry.lang
            overlay?.clearExtras()
            LyricsState.update { it.copy(translation = null, phonetic = null) }
            current?.let { (lyrics, title, artist) -> ai.execute { enrich(key, lyrics, title, artist) } }
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
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationSec = md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000
        val key = Triple(title, artist, durationSec)
        if (key == trackKey) return
        trackKey = key
        val trackId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.removePrefix("spotify:track:")?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
        overlay?.setTrack(title)
        current = null
        LyricsState.update { LyricsState.Snapshot(key, title, artist, null, null, null, 0) }
        val entry = trackPrefs.get(TrackPrefs.key(title, artist, durationSec))
        currentLang = entry.lang
        overlay?.setTrackOffset(entry.offsetMs)
        LyricsState.update { it.copy(trackOffsetMs = entry.offsetMs) }
        updateVisibility()
        io.execute {
            // Community sync data carries hand-made character timing on top of an LRCLIB text, so it wins when present.
            val lyrics = (if (prefs.srcCommunity) community.lookup(trackId, title, artist, durationSec) else null)
                ?: LyricsSources.fetch(prefs.sourceOrder, title, artist, album, durationSec, prefs.karaoke, deferRomanized = prefs.targetLang == "ko")
            main.post { if (trackKey == key) { overlay?.setLyrics(lyrics); current = Triple(lyrics, title, artist); LyricsState.update { it.copy(lyrics = lyrics) } } }
            if (!lyrics.isEmpty) ai.execute { enrich(key, lyrics, title, artist) }
        }
    }

    /** Runs on the ai thread: cached or freshly generated translation and pronunciation for [key]. */
    private fun enrich(key: Triple<String, String, Long>, lyrics: Lyrics, title: String, artist: String) {
        val cfg = prefs.aiConfig ?: return
        val override = trackPrefs.get(TrackPrefs.key(key.first, key.second, key.third)).lang
        val opt = prefs.aiOptions.let { if (override != null) it.copy(target = Lang.target(override)) else it }
        val wantTranslate = prefs.translate
        val wantPhonetic = prefs.phonetic
        if (!wantTranslate && !wantPhonetic) return
        val texts = lyrics.lines.map { it.text }
        if (Lang.isAlreadyIn(texts, opt.target)) return
        val cacheKey = "$key|${lyrics.synced}|${texts.hashCode()}|${opt.fingerprint}"
        val hit = cache.get(cacheKey)
        if (hit != null) {
            main.post { if (trackKey == key) { overlay?.setExtras(hit.translation, hit.phonetic); LyricsState.update { it.copy(translation = hit.translation ?: it.translation, phonetic = hit.phonetic ?: it.phonetic) } } }
            if ((hit.translation != null || !wantTranslate) && (hit.phonetic != null || !wantPhonetic)) return
        }
        if (trackKey != key) return
        val translation = hit?.translation ?: if (wantTranslate) Ai.translate(cfg, texts, title, artist, opt) else null
        if (translation != null) main.post { if (trackKey == key) { overlay?.setExtras(translation, null); LyricsState.update { it.copy(translation = translation) } } }
        if (trackKey != key) return
        val phonetic = hit?.phonetic ?: if (wantPhonetic) Ai.pronounce(cfg, texts, opt) else null
        if (phonetic != null) main.post { if (trackKey == key) { overlay?.setExtras(null, phonetic); LyricsState.update { it.copy(phonetic = phonetic) } } }
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
