package dev.kuass.ivlyrics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Immersive lyrics screen: blurred album art behind a large [LyricsView], with transport controls.
 * Reads the media session directly for position and controls; lyrics come from [LyricsState].
 */
class FullscreenActivity : AppCompatActivity() {
    private companion object {
        const val TICK_MS = 100L
        const val CONTROLS_HIDE_MS = 5000L
        const val FONT_SCALE = 1.7f
        const val ROW_GAP_DP = 10
        const val VIDEO_SYNC_MS = 2000L
        const val VIDEO_DRIFT_SEC = 1.5
    }

    private lateinit var prefs: Prefs
    private lateinit var lyricsView: LyricsView
    private lateinit var background: ImageView
    private lateinit var art: ImageView
    private lateinit var seek: Slider
    private lateinit var playPause: MaterialButton
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val controller: MediaController? get() = sessions.controller
    private var state: PlaybackState? = null
    private var durationMs = 0L
    private val follow = ScrollFollow()
    private lateinit var followButton: MaterialButton
    private var seeking = false
    private var shownIndex = Int.MIN_VALUE
    private var shownKey: Triple<String, String, Long>? = null
    private val artwork by lazy {
        ArtworkLoader<Bitmap>(
            execute = { io.execute(it) },
            post = { main.post(it) },
            load = { uri -> URL(uri).openStream().use(BitmapFactory::decodeStream) },
            show = { bitmap ->
                if (bitmap != null) showArt(bitmap)
                else { background.setImageDrawable(null); art.setImageDrawable(null) }
            },
        )
    }
    private var snapshot = LyricsState.snapshot
    private var video: WebView? = null
    private var videoId: String? = null
    private var videoRevision = 0L
    private var videoReady = false
    private var videoActive = false
    private var videoKey: Triple<String, String, Long>? = null
    private val research by lazy { Research(this) }
    private val trackPrefs by lazy { TrackPrefs(this) }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val stateListener: (LyricsState.Snapshot) -> Unit = { snapshot = it; shownIndex = Int.MIN_VALUE; render(force = true) }
    private val sessions: SpotifySessionBinding by lazy {
        SpotifySessionBinding(this, main, ::onSessionChanged, ::showMetadata, { state = it; updatePlayPause() })
    }
    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, TICK_MS) }
    }
    private val hideControls = Runnable { setControlsVisible(false) }
    private val videoSync = object : Runnable {
        override fun run() { syncVideo(); main.postDelayed(this, VIDEO_SYNC_MS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_fullscreen)
        lyricsView = findViewById(R.id.fsLyrics)
        lyricsView.setOnClickListener { findViewById<View>(R.id.fsRoot).performClick() }
        followButton = MaterialButton(this).apply {
            setText(R.string.follow_current); visibility = View.GONE
            setOnClickListener { follow.resume(); visibility = View.GONE; shownIndex = Int.MIN_VALUE; render() }
        }
        findViewById<android.widget.FrameLayout>(R.id.fsRoot).addView(followButton,
            android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(190) })
        findViewById<ManualScrollView>(R.id.fsLyricsScroll).onManualTouch = { touching ->
            if (touching) { follow.begin(); followButton.visibility = View.VISIBLE }
            else follow.end(SystemClock.elapsedRealtime())
        }
        background = findViewById(R.id.fsBackground)
        art = findViewById(R.id.fsArt)
        seek = findViewById(R.id.fsSeek)
        playPause = findViewById(R.id.fsPlayPause)
        val chrome = listOf<View>(findViewById(R.id.fsHeader), findViewById(R.id.fsBottom))
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = maxOf(bars.left, bars.right) + dp(20) // symmetric, so centered rows stay centered next to a cutout
            chrome[0].updatePadding(top = bars.top + dp(12), left = side, right = side)
            chrome[1].updatePadding(bottom = bars.bottom + dp(12), left = side, right = side)
            insets
        }
        findViewById<MaterialButton>(R.id.fsInfo).setOnClickListener { showResearch(); scheduleHide() }
        findViewById<MaterialButton>(R.id.fsSync).setOnClickListener { startActivity(android.content.Intent(this, SyncCreatorActivity::class.java)) }
        setUpVideo()
        findViewById<View>(R.id.fsRoot).setOnClickListener { setControlsVisible(!findViewById<View>(R.id.fsHeader).isShown) }
        findViewById<MaterialButton>(R.id.fsPrev).setOnClickListener { controller?.transportControls?.skipToPrevious(); scheduleHide() }
        findViewById<MaterialButton>(R.id.fsNext).setOnClickListener { controller?.transportControls?.skipToNext(); scheduleHide() }
        playPause.setOnClickListener {
            if (state?.state == PlaybackState.STATE_PLAYING) controller?.transportControls?.pause() else controller?.transportControls?.play()
            scheduleHide()
        }
        findViewById<MaterialButton>(R.id.fsClose).setOnClickListener { finish() }
        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { seeking = true; main.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
                controller?.transportControls?.seekTo((slider.value / 1000f * durationMs).toLong())
                scheduleHide()
            }
        })
        seek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val position = (value / 1000f * durationMs).toLong()
                showTime(position)
                if (!seeking) {
                    controller?.transportControls?.seekTo(position)
                    scheduleHide()
                }
            }
        }
        applyLyricsStyle()
    }

    private fun applyLyricsStyle() {
        val style = prefs.lyricsStyle
        lyricsView.setStyle(style.copy(fontSp = (style.fontSp * FONT_SCALE).toInt().coerceIn(20, 48), bgPercent = 0, rowGapDp = ROW_GAP_DP))
    }

    override fun onResume() {
        super.onResume()
        applyLyricsStyle()
        prefs.putBoolean(Prefs.UI_OPEN, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        LyricsState.addListener(stateListener)
        runCatching { sessions.start() }
        videoActive = true
        video?.onResume()
        main.post(tick)
        main.post(videoSync)
        setControlsVisible(true)
    }

    override fun onDestroy() {
        artwork.close()
        video?.destroy(); video = null
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        main.removeCallbacks(tick); main.removeCallbacks(hideControls); main.removeCallbacks(videoSync)
        videoActive = false
        video?.evaluateJavascript("pause()", null)
        video?.onPause()
        LyricsState.removeListener(stateListener)
        runCatching { sessions.stop(notifyDisconnected = false) }
        prefs.putBoolean(Prefs.UI_OPEN, false)
        super.onPause()
    }

    private fun onSessionChanged(spotify: MediaController?) {
        state = spotify?.playbackState
        showMetadata(spotify?.metadata)
        updatePlayPause()
    }

    private fun showMetadata(md: MediaMetadata?) {
        findViewById<TextView>(R.id.fsTitle).text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        findViewById<TextView>(R.id.fsArtist).text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        resolveVideo(md)
        val bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val uri = md?.getString("com.spotify.music.extra.ART_HTTPS_URI") ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        artwork.update(bitmap, uri)
    }

    private fun showArt(bitmap: Bitmap) {
        art.setImageBitmap(bitmap)
        background.setImageBitmap(bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            background.setRenderEffect(RenderEffect.createBlurEffect(dp(28).toFloat(), dp(28).toFloat(), Shader.TileMode.CLAMP))
        }
    }

    // ---- music video background -------------------------------------------------------------------

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setUpVideo() {
        if (!prefs.videoBg) return
        val w = findViewById<WebView>(R.id.fsVideo)
        w.settings.javaScriptEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(0)
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(object {
            @JavascriptInterface fun ready(revision: String) {
                main.post {
                    if (isDestroyed || video !== w || revision != videoRevision.toString() || videoKey == null || videoId == null) return@post
                    videoReady = true
                    w.visibility = View.VISIBLE
                    background.visibility = View.INVISIBLE
                    syncVideo(force = true)
                }
            }
        }, "Android")
        // A 16:9 player scaled up to cover a portrait screen; cropping the sides is intended.
        val m = resources.displayMetrics
        val scale = maxOf(1f, m.heightPixels / (m.widthPixels * 9f / 16f))
        w.scaleX = scale; w.scaleY = scale
        video = w
    }

    private fun resolveVideo(md: MediaMetadata?) {
        val w = video ?: return
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: run { retireVideo(); return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val key = Triple(title, artist, (md.getLong(MediaMetadata.METADATA_KEY_DURATION)) / 1000)
        if (key == videoKey) return
        val revision = ++videoRevision
        videoKey = key
        videoReady = false
        videoId = null
        w.visibility = View.GONE; background.visibility = View.VISIBLE
        val manual = trackPrefs.get(TrackPrefs.key(title, artist, key.third)).videoId
        val apiKey = prefs.ytApiKey
        io.execute {
            val id = manual ?: VideoMatch.search(apiKey, title, artist)
            main.post { if (videoKey == key && videoRevision == revision) loadVideo(id) }
        }
    }

    private fun retireVideo() {
        videoRevision++
        videoKey = null
        videoReady = false
        videoId = null
        video?.let { it.visibility = View.GONE; it.loadUrl("about:blank") }
        background.visibility = View.VISIBLE
    }

    private fun loadVideo(id: String?) {
        val w = video ?: return
        videoId = id
        if (id == null) { w.loadUrl("about:blank"); return }
        val start = (positionMs() / 1000).coerceAtLeast(0)
        val html = """<html><body style="margin:0;background:#000;overflow:hidden"><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
 playerVars:{autoplay:1,controls:0,mute:1,playsinline:1,rel:0,iv_load_policy:3,disablekb:1,fs:0,start:$start,loop:1,playlist:'$id'},
 events:{onReady:function(e){e.target.mute();e.target.playVideo();Android.ready('$videoRevision');}}});}
function seek(t){if(player&&player.seekTo){player.seekTo(t,true);}}
function play(){if(player&&player.playVideo){player.playVideo();}}
function pause(){if(player&&player.pauseVideo){player.pauseVideo();}}
function now(){return (player&&player.getCurrentTime)?player.getCurrentTime():-1;}
</script></body></html>"""
        w.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    /** Keeps the muted video within [VIDEO_DRIFT_SEC] of the song position and mirrors play/pause. */
    private fun syncVideo(force: Boolean = false) {
        val w = video ?: return
        if (!videoActive || !videoReady || videoId == null) return
        val playing = state?.state == PlaybackState.STATE_PLAYING
        w.evaluateJavascript(if (playing) "play()" else "pause()", null)
        val target = positionMs() / 1000.0
        if (force) { w.evaluateJavascript("seek($target)", null); return }
        w.evaluateJavascript("now()") { raw ->
            if (isDestroyed || !videoActive || video !== w) return@evaluateJavascript // the WebView may be gone by the time JS answers
            val current = raw?.toDoubleOrNull() ?: return@evaluateJavascript
            if (current >= 0 && kotlin.math.abs(current - target) > VIDEO_DRIFT_SEC) w.evaluateJavascript("seek(${positionMs() / 1000.0})", null)
        }
    }

    // ---- song notes ------------------------------------------------------------------------------

    private fun showResearch() {
        val snap = snapshot
        val key = snap.key ?: return
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_research, null)
        val body = view.findViewById<TextView>(R.id.researchBody)
        view.findViewById<TextView>(R.id.researchTitle).text = listOf(snap.title, snap.artist).filter { it.isNotBlank() }.joinToString(" · ")
        sheet.setContentView(view)
        sheet.show()
        val cfg = prefs.aiConfig
        val target = Lang.target(prefs.targetLang)
        val cacheKey = TrackPrefs.key(key.first, key.second, key.third) + "|" + target.code
        val cached = research.cached(cacheKey)
        when {
            cached != null -> body.text = cached
            cfg == null -> body.text = getString(R.string.research_unavailable)
            else -> {
                body.text = getString(R.string.research_loading)
                val album = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                Research.executor.execute {
                    // Lyrics may still be loading when the sheet opens; use whatever the service has by now.
                    val latest = LyricsState.snapshot.takeIf { it.key == key }?.lyrics ?: snap.lyrics
                    val lyrics = latest?.lines?.map { it.text } ?: emptyList()
                    val text = research.generate(cfg, cacheKey, snap.title, snap.artist, album, lyrics, target)
                    main.post { if (sheet.isShowing && !isDestroyed) body.text = text ?: getString(R.string.research_failed) }
                }
            }
        }
    }

    private fun positionMs(): Long {
        val s = state ?: return 0
        return PlaybackPosition.at(
            positionMs = s.position,
            playing = s.state == PlaybackState.STATE_PLAYING,
            lastPositionUpdateTimeMs = s.lastPositionUpdateTime,
            playbackSpeed = s.playbackSpeed,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            offsetMs = prefs.offsetMs,
            trackOffsetMs = snapshot.trackOffsetMs,
        )
    }

    private fun scrollToCurrent() {
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.fsLyricsScroll)
        lyricsView.post {
            if (follow.paused(SystemClock.elapsedRealtime())) return@post
            lyricsView.currentLineCenter()?.let { center ->
                scroll.smoothScrollTo(0, (lyricsView.top + center - scroll.height / 2).coerceAtLeast(0))
            }
        }
    }

    private fun render(force: Boolean = false) {
        if (::followButton.isInitialized && followButton.visibility == View.VISIBLE && !follow.paused(SystemClock.elapsedRealtime())) {
            followButton.visibility = View.GONE; shownIndex = Int.MIN_VALUE
        }
        findViewById<TextView>(R.id.fsClock).text = clockFormat.format(Date())
        val pos = positionMs()
        if (!seeking && durationMs > 0) { seek.value = (pos.coerceIn(0, durationMs) * 1000f / durationMs).coerceIn(0f, 1000f); showTime(pos) }
        if (shownKey != snapshot.key) { follow.resume(); followButton.visibility = View.GONE }
        val lyrics = snapshot.lyrics
        if (lyrics == null || lyrics.isEmpty) {
            if (force || shownKey != snapshot.key || shownIndex != Int.MIN_VALUE) {
                shownKey = snapshot.key; shownIndex = Int.MIN_VALUE
                lyricsView.showStatus(snapshot.title.ifEmpty { getString(R.string.now_playing_none) },
                    if (lyrics == null) getString(R.string.lyrics_loading) else getString(R.string.lyrics_none))
            }
            return
        }
        if (follow.paused(SystemClock.elapsedRealtime())) { lyricsView.setPosition(pos); return }
        val i = Lrc.indexAt(lyrics.lines, pos)
        if (i != shownIndex || shownKey != snapshot.key) {
            shownIndex = i; shownKey = snapshot.key
            lyricsView.show(LyricsView.Content(lyrics.lines.map { it.text }, i, snapshot.phonetic, snapshot.translation, lyrics.synced, lyrics.lines.getOrNull(i)?.syllables))
            if (!follow.paused(SystemClock.elapsedRealtime())) scrollToCurrent()
        }
        lyricsView.setPosition(pos)
    }

    private fun showTime(pos: Long) {
        findViewById<TextView>(R.id.fsElapsed).text = clock(pos)
        findViewById<TextView>(R.id.fsRemaining).text = "-" + clock((durationMs - pos).coerceAtLeast(0))
    }

    private fun clock(ms: Long): String = String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

    private fun updatePlayPause() {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        playPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = getString(if (playing) R.string.pause else R.string.play)
    }

    private fun setControlsVisible(visible: Boolean) {
        listOf<View>(findViewById(R.id.fsHeader), findViewById(R.id.fsBottom)).forEach { v ->
            v.animate().alpha(if (visible) 1f else 0f).setDuration(220).withStartAction { if (visible) v.visibility = View.VISIBLE }
                .withEndAction { if (!visible) v.visibility = View.INVISIBLE }.start()
        }
        if (visible) scheduleHide() else main.removeCallbacks(hideControls)
    }

    private fun scheduleHide() { main.removeCallbacks(hideControls); main.postDelayed(hideControls, CONTROLS_HIDE_MS) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
