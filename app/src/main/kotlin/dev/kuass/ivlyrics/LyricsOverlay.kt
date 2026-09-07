package dev.kuass.ivlyrics

import android.content.Context
import android.graphics.PixelFormat
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Floating window hosting a [LyricsView]. Drag vertically to move (position is remembered). The gesture axis is
 * locked by the first movement; only a deliberate sideways fling (fast, or past most of the width) dismisses the
 * panel until the next track. Anything else snaps back.
 */
class LyricsOverlay(private val ctx: Context, private val prefs: Prefs, private val onDismissed: () -> Unit, private val onTap: () -> Unit = {}) {
    private companion object {
        const val TICK_MS = 100L
        const val DISMISS_FRACTION = 0.6f
        const val DISMISS_VELOCITY_DP_S = 1800f
        const val DISMISS_ANIM_MS = 180L
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val dp = ctx.resources.displayMetrics.density
    private val view = LyricsView(ctx).apply { setStyle(prefs.lyricsStyle); setOnTouchListener(Drag()); setOnClickListener { onTap() } }
    /** Full-width host with side margins, so the panel never needs horizontal positioning. */
    private val host = FrameLayout(ctx).apply {
        val side = (10 * dp).toInt()
        setPadding(side, 0, side, 0)
        addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = if (prefs.overlayY == Int.MIN_VALUE) (80 * dp).toInt() else prefs.overlayY
    }

    private var attached = false
    private var lyrics: Lyrics = Lyrics.NONE
    private var translations: List<String>? = null
    private var phonetics: List<String>? = null
    private var state: PlaybackState? = null
    private var shownIndex = Int.MIN_VALUE

    private val tick = object : Runnable {
        override fun run() {
            render(force = false)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private var offsetMs = prefs.offsetMs
    private var trackOffsetMs = 0

    fun setTrackOffset(ms: Int) { trackOffsetMs = ms; render(force = true) }

    fun clearExtras() { translations = null; phonetics = null; render(force = true) }

    fun applyPrefs() {
        view.setStyle(prefs.lyricsStyle)
        offsetMs = prefs.offsetMs
        render(force = true)
    }

    fun setTrack(title: String) {
        lyrics = Lyrics.NONE
        translations = null
        phonetics = null
        shownIndex = Int.MIN_VALUE
        view.showStatus(title, ctx.getString(R.string.lyrics_loading))
    }

    fun setLyrics(found: Lyrics) {
        lyrics = found
        if (found.isEmpty) view.showStatus(view.context.getString(R.string.lyrics_none), "") else render(force = true)
    }

    fun setExtras(translation: List<String>?, phonetic: List<String>?) {
        if (translation != null) translations = translation
        if (phonetic != null) phonetics = phonetic
        render(force = true)
    }

    fun setPlayback(s: PlaybackState?) { state = s }

    fun show() {
        if (attached || !Settings.canDrawOverlays(ctx)) return
        view.alpha = 1f
        view.translationX = 0f
        wm.addView(host, params)
        attached = true
        handler.post(tick)
    }

    fun hide() {
        if (!attached) return
        handler.removeCallbacks(tick)
        wm.removeView(host)
        attached = false
    }

    private fun positionMs(): Long {
        val s = state ?: return 0
        val base = if (s.state != PlaybackState.STATE_PLAYING) s.position
            else s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        return base + offsetMs + trackOffsetMs
    }

    private fun render(force: Boolean) {
        val lines = lyrics.lines
        if (lines.isEmpty()) return
        val pos = positionMs()
        val i = Lrc.indexAt(lines, pos)
        if (!force && i == shownIndex) { view.setPosition(pos); return }
        shownIndex = i
        view.show(LyricsView.Content(lines.map { it.text }, i, phonetics, translations, lyrics.synced, lines.getOrNull(i)?.syllables))
        view.setPosition(pos)
    }

    private enum class Axis { NONE, VERTICAL, HORIZONTAL }

    private inner class Drag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var axis = Axis.NONE
        private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var tracker: VelocityTracker? = null

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    axis = Axis.NONE
                    startY = params.y; touchX = e.rawX; touchY = e.rawY
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (axis == Axis.NONE) {
                        if (abs(dx) < slop && abs(dy) < slop) return true
                        axis = if (abs(dx) > abs(dy) * 1.5f) Axis.HORIZONTAL else Axis.VERTICAL
                    }
                    when (axis) {
                        Axis.VERTICAL -> {
                            params.y = startY + dy.toInt()
                            if (attached) wm.updateViewLayout(host, params)
                        }
                        Axis.HORIZONTAL -> {
                            view.translationX = dx
                            view.alpha = (1 - abs(dx) / view.width * 0.6f).coerceIn(0.4f, 1f)
                        }
                        Axis.NONE -> Unit
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - touchX
                    val velocityX = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    tracker?.recycle(); tracker = null
                    when (axis) {
                        Axis.VERTICAL -> prefs.saveOverlayPosition(0, params.y)
                        Axis.HORIZONTAL -> {
                            val fling = e.actionMasked == MotionEvent.ACTION_UP &&
                                abs(velocityX) > DISMISS_VELOCITY_DP_S * dp && velocityX * dx > 0
                            val far = abs(dx) > view.width * DISMISS_FRACTION
                            if (fling || far) {
                                onDismissed() // decided now, so a track change during the exit animation cannot be blamed
                                dismiss(dx)
                            } else {
                                view.animate().translationX(0f).alpha(1f).setDuration(DISMISS_ANIM_MS).start()
                            }
                        }
                        Axis.NONE -> if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                else -> return false
            }
            return true
        }

        private fun dismiss(dx: Float) {
            val target = if (dx > 0) view.width.toFloat() else -view.width.toFloat()
            view.animate().translationX(target).alpha(0f).setDuration(DISMISS_ANIM_MS).withEndAction { hide() }.start()
        }
    }
}
