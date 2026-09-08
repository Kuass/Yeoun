package dev.kuass.ivlyrics

import android.content.ComponentName
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

/**
 * Tap-to-time editor: while the song plays, each tap of "start of this line" stamps the current position on the
 * next lyric line. The result is saved as local LRC for this track and used before any online source.
 */
class SyncCreatorActivity : AppCompatActivity() {
    private companion object { const val TICK_MS = 200L; const val BACK_MS = 3000L; const val SPOTIFY = "com.spotify.music" }

    private lateinit var prefs: Prefs
    private lateinit var local: LocalLyrics
    private lateinit var list: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var position: TextView
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var state: PlaybackState? = null
    private val draft = SyncDraft()
    private val key get() = draft.key
    private val lines get() = draft.lines
    private val times get() = draft.times
    private val cursor get() = draft.cursor
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) { state = s; updatePlayPause() }
        override fun onMetadataChanged(metadata: MediaMetadata?) = adoptTrack(metadata)
    }
    /** Fills the line list as soon as the service has lyrics for the track being edited. */
    private val stateListener: (LyricsState.Snapshot) -> Unit = { snap ->
        val snapKey = snap.key?.let { TrackPrefs.key(it.first, it.second, it.third) }
        val typing = !findViewById<TextInputEditText>(R.id.scPaste).text.isNullOrBlank()
        if (lines.isEmpty() && !typing && snapKey != null && snapKey == key) {
            snap.lyrics?.lines?.map { it.text }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { texts ->
                setLines(texts, null)
                findViewById<View>(R.id.scPaste).visibility = View.GONE
                findViewById<View>(R.id.scUsePaste).visibility = View.GONE
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() { position.text = LrcWriter.stamp(positionMs()); main.postDelayed(this, TICK_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); local = LocalLyrics(this)
        setContentView(R.layout.activity_sync_creator)
        list = findViewById(R.id.scLines); scroll = findViewById(R.id.scScroll); position = findViewById(R.id.scPosition)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        val snap = LyricsState.snapshot
        draft.selectTrack(snap.key?.let { TrackPrefs.key(it.first, it.second, it.third) })
        findViewById<TextView>(R.id.scTitle).text = listOf(snap.title, snap.artist).filter { it.isNotBlank() }.joinToString(" · ")
        val existing = key?.let { local.lyrics(it, snap.key?.third ?: 0) }
        val source = existing?.lines?.map { it.text } ?: snap.lyrics?.lines?.map { it.text }?.filter { it.isNotBlank() } ?: emptyList()
        val restored = savedInstanceState?.takeIf { it.getString("draft_key") == key }
        val restoredLines = restored?.getStringArrayList("draft_lines")
        val restoredTimes = restored?.getLongArray("draft_times")?.map { if (it < 0) null else it }
        setLines(restoredLines ?: source, restoredTimes ?: existing?.takeIf { it.synced }?.lines?.map { it.timeMs })
        restored?.let { draft.select(it.getInt("draft_cursor")); refresh() }
        findViewById<TextInputEditText>(R.id.scPaste).visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.scUsePaste).apply {
            visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
            setOnClickListener {
                val pasted = findViewById<TextInputEditText>(R.id.scPaste).text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (pasted.isNotEmpty()) { setLines(pasted, null); visibility = View.GONE; findViewById<View>(R.id.scPaste).visibility = View.GONE }
            }
        }
        findViewById<MaterialButton>(R.id.scStamp).setOnClickListener { stamp() }
        findViewById<MaterialButton>(R.id.scUndo).setOnClickListener { undo() }
        findViewById<MaterialButton>(R.id.scBack).setOnClickListener { controller?.transportControls?.seekTo((positionMs() - BACK_MS).coerceAtLeast(0)) }
        findViewById<MaterialButton>(R.id.scPlayPause).setOnClickListener {
            if (state?.state == PlaybackState.STATE_PLAYING) controller?.transportControls?.pause() else controller?.transportControls?.play()
        }
        findViewById<MaterialButton>(R.id.scSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.scDelete).setOnClickListener {
            key?.let {
                local.remove(it)
                setLines(emptyList(), null)
                showPaste(true)
                prefs.bumpTrackPrefs()
                Snackbar.make(list, R.string.sync_deleted, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft_key", key)
        outState.putStringArrayList("draft_lines", ArrayList(lines))
        outState.putLongArray("draft_times", times.map { it ?: -1L }.toLongArray())
        outState.putInt("draft_cursor", cursor)
        super.onSaveInstanceState(outState)
    }

    private fun showPaste(visible: Boolean) {
        findViewById<View>(R.id.scPaste).visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scUsePaste).visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        prefs.putBoolean(Prefs.UI_OPEN, true)
        val manager = getSystemService(MediaSessionManager::class.java)
        val self = ComponentName(this, SpotifyListener::class.java)
        runCatching { manager.addOnActiveSessionsChangedListener(sessionsChanged, self, main); attach(manager.getActiveSessions(self)) }
        LyricsState.addListener(stateListener)
        main.post(tick)
    }

    override fun onPause() {
        LyricsState.removeListener(stateListener)
        main.removeCallbacks(tick)
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback); controller = null
        prefs.putBoolean(Prefs.UI_OPEN, false)
        super.onPause()
    }

    private fun attach(sessions: List<MediaController>?) {
        val spotify = sessions?.firstOrNull { it.packageName == SPOTIFY }
        if (spotify?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = spotify?.also { it.registerCallback(callback, main) }
        state = spotify?.playbackState
        updatePlayPause()
        adoptTrack(spotify?.metadata)
    }

    /** Uses the session's own metadata when the service has not published the track yet (e.g. right after install). */
    private fun adoptTrack(md: MediaMetadata?) {
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title == null) {
            // Keep the draft while disconnected, but never stamp using a missing playback session.
            findViewById<MaterialButton>(R.id.scStamp).isEnabled = false
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val k = TrackPrefs.key(title, artist, md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000)
        if (k == key) return
        draft.selectTrack(k)
        setLines(emptyList(), null)
        findViewById<TextInputEditText>(R.id.scPaste).text?.clear()
        showPaste(true)
        findViewById<TextView>(R.id.scTitle).text = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")
        local.lyrics(k, md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000)?.let { setLines(it.lines.map { l -> l.text }, it.takeIf { l -> l.synced }?.lines?.map { l -> l.timeMs }); showPaste(false) }
            ?: stateListener(LyricsState.snapshot)
    }

    private fun positionMs(): Long {
        val s = state ?: return 0
        return if (s.state != PlaybackState.STATE_PLAYING) s.position
            else s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }

    private fun setLines(texts: List<String>, existingTimes: List<Long?>?) {
        draft.replace(texts, existingTimes)
        list.removeAllViews()
        texts.forEachIndexed { i, _ ->
            list.addView(TextView(this).apply {
                textSize = 16f
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { draft.select(i); refresh() }
                isFocusable = true
                minimumHeight = dp(48)
                tag = i
            })
        }
        refresh()
    }

    private fun stamp() {
        if (state == null || controller == null || cursor >= lines.size) return
        if (!draft.stamp(positionMs())) {
            Snackbar.make(list, R.string.sync_order_error, Snackbar.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun undo() { draft.undo(); refresh() }

    private fun refresh() {
        findViewById<MaterialButton>(R.id.scStamp).isEnabled = key != null && state != null && cursor < lines.size
        findViewById<MaterialButton>(R.id.scSave).isEnabled = key != null && times.any { it != null }
        for (i in lines.indices) {
            val row = list.getChildAt(i) as TextView
            val t = times[i]
            row.text = (t?.let { "[${LrcWriter.stamp(it)}]  " } ?: "[ --:--.-- ]  ") + lines[i]
            row.setTypeface(null, if (i == cursor) Typeface.BOLD else Typeface.NORMAL)
            row.setTextColor(getColor(when { i == cursor -> R.color.amber; t != null -> R.color.paper; else -> R.color.paper_dim }))
        }
        findViewById<TextView>(R.id.scProgress).text = getString(R.string.sync_progress, times.count { it != null }, lines.size)
        list.getChildAt(cursor.coerceIn(0, maxOf(0, lines.size - 1)))?.let { row ->
            scroll.post { scroll.smoothScrollTo(0, (row.top - scroll.height / 2).coerceAtLeast(0)) }
        }
    }

    private fun save() {
        val k = key ?: return
        val lrc = LrcWriter.write(lines, times)
        if (lrc.isEmpty()) { Snackbar.make(list, R.string.sync_nothing, Snackbar.LENGTH_SHORT).show(); return }
        local.set(k, lrc)
        prefs.bumpTrackPrefs()
        Snackbar.make(list, R.string.sync_saved, Snackbar.LENGTH_SHORT).show()
    }

    private fun updatePlayPause() {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        findViewById<MaterialButton>(R.id.scPlayPause).apply {
            setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = getString(if (playing) R.string.pause else R.string.play)
            isEnabled = controller != null
        }
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
