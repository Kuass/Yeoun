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
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import android.content.Intent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Floating window hosting a [LyricsView]. Drag vertically to move (position is remembered). The gesture axis is
 * locked by the first movement; only a deliberate sideways fling (fast, or past most of the width) dismisses the
 * panel until the next track. Anything else snaps back.
 */
class LyricsOverlay(context: Context, private val prefs: Prefs, private val onDismissed: () -> Unit, private val onTap: () -> Unit = {}) {
    private companion object {
        const val TICK_MS = 100L
        const val DISMISS_FRACTION = 0.6f
        const val DISMISS_VELOCITY_DP_S = 1800f
        const val DISMISS_ANIM_MS = 180L
        const val CONTROLS_HIDE_MS = 5000L
    }

    // NotificationListenerService does not inherit the Activity's Material theme.
    private val ctx = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_IvLyrics)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLongPress: Runnable? = null
    private val dp = ctx.resources.displayMetrics.density
    private val view = LyricsView(ctx).apply { setStyle(prefs.lyricsStyle); setOnTouchListener(Drag()); setOnClickListener { onTap() }; setOnLongClickListener { toggleQuick(); true } }
    private val follow = ScrollFollow()
    private lateinit var lyricsScroll: android.widget.ScrollView
    private val resumeFollow = MaterialButton(ctx).apply {
        setText(R.string.follow_current); visibility = View.GONE
        setOnClickListener { follow.resume(); visibility = View.GONE; shownIndex = Int.MIN_VALUE; render(force = true) }
    }
    private var currentTrackKey: String? = null
    private val retry = MaterialButton(ctx).apply {
        setText(R.string.extras_retry); visibility = View.GONE
        setOnClickListener { prefs.putString(Prefs.EXTRAS_VERSION, java.util.UUID.randomUUID().toString()) }
    }
    private val hideControls = Runnable { quick.visibility = View.GONE; quickLauncher.visibility = View.GONE }
    private val quickLauncher = MaterialButton(ctx).apply {
        setText(R.string.quick_settings); textSize = 11f; isAllCaps = false; visibility = View.GONE
        backgroundTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.ink_raised))
        setTextColor(ctx.getColor(R.color.paper_dim))
        setOnClickListener { toggleQuick() }
    }
    private val offsetLabel = TextView(ctx).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(ctx.getColor(R.color.paper)) }
    private val quick = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val quickToggleRefreshers = mutableListOf<() -> Unit>()
    private val footer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(resumeFollow)
        val row = LinearLayout(ctx).apply { gravity = Gravity.END }
        row.addView(quickLauncher, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(retry, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
        val toggles = LinearLayout(ctx)
        fun toggle(label: Int, key: String, read: () -> Boolean) {
            toggles.addView(MaterialButton(ctx).apply {
                quickToggleRefreshers += {
                    val enabled = read()
                    isSelected = enabled
                    alpha = if (enabled) 1f else 0.5f
                }
                setText(label); textSize = 11f; isAllCaps = false
                alpha = if (read()) 1f else 0.5f
                setOnClickListener { prefs.putBoolean(key, !read()); isSelected = read(); alpha = if (read()) 1f else 0.5f; scheduleControlsHide() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        toggle(R.string.quick_translation, Prefs.TRANSLATE) { prefs.translate }
        toggle(R.string.quick_phonetic, Prefs.PHONETIC) { prefs.phonetic }
        quick.addView(toggles)
        quick.addView(offsetLabel)
        val sync = LinearLayout(ctx)
        listOf(R.string.offset_earlier to 100, R.string.offset_reset to 0, R.string.offset_later to -100).forEach { (label, delta) ->
            sync.addView(MaterialButton(ctx).apply {
                setText(label); textSize = 11f; isAllCaps = false
                setOnClickListener {
                    val key = currentTrackKey ?: return@setOnClickListener
                    val tracks = TrackPrefs(ctx); val entry = tracks.get(key)
                    tracks.set(key, entry.copy(offsetMs = if (delta == 0) 0 else (entry.offsetMs + delta).coerceIn(-3000, 3000)))
                    prefs.bumpTrackPrefs()
                    scheduleControlsHide()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        quick.addView(sync)
        quick.addView(MaterialButton(ctx).apply { setText(R.string.close); setOnClickListener { quick.visibility = View.GONE; scheduleControlsHide() } })
        addView(quick)
    }
    private var promptLanguage: String? = null
    private val prompt = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        background = android.graphics.drawable.GradientDrawable().apply { setColor(ctx.getColor(R.color.ink_raised)); cornerRadius = 16 * dp }
    }
    /** Full-width host with side margins, so the panel never needs horizontal positioning. */
    private val host = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val side = (10 * dp).toInt()
        setPadding(side, 0, side, 0)
        lyricsScroll = object : android.widget.ScrollView(ctx) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                val intercept = super.onInterceptTouchEvent(event)
                if (intercept) { follow.begin(); resumeFollow.visibility = View.VISIBLE }
                return intercept
            }
            override fun performClick(): Boolean = super.performClick()
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) follow.end(SystemClock.elapsedRealtime())
                return super.onTouchEvent(event)
            }
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.6f).toInt(), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(view) }
        addView(lyricsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(prompt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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

    private fun revealControls() {
        handler.removeCallbacks(hideControls)
        quickLauncher.visibility = View.VISIBLE
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    private fun toggleQuick() {
        if (quick.visibility != View.VISIBLE) quickToggleRefreshers.forEach { it() }
        revealControls()
        quick.visibility = if (quick.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        scheduleControlsHide()
    }

    fun setTrackKey(key: String) { currentTrackKey = key }
    fun setExtrasProgress(status: ExtrasProgress.Status) {
        retry.visibility = if (status.canRetry) View.VISIBLE else View.GONE
    }

    fun setTrackOffset(ms: Int) { trackOffsetMs = ms; offsetLabel.text = ctx.getString(R.string.ms_format, ms); render(force = true) }

    fun clearExtras() { translations = null; phonetics = null; render(force = true) }

    fun applyPrefs() {
        view.setStyle(prefs.lyricsStyle)
        offsetMs = prefs.offsetMs
        render(force = true)
    }

    fun setLanguagePrompt(language: String?) {
        if (promptLanguage == language) return
        promptLanguage = language
        prompt.removeAllViews()
        prompt.visibility = if (language == null) View.GONE else View.VISIBLE
        if (language == null) return
        prompt.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.language_first_prompt, SourceLanguage.label(language, ctx))
            setTextColor(ctx.getColor(R.color.paper)); textSize = 14f
        })
        fun row(vararg choices: Pair<Int, LanguageDisplay.Choice?>) {
            val row = LinearLayout(ctx)
            choices.forEach { (label, choice) ->
                row.addView(MaterialButton(ctx).apply {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.amber_deep))
                    setTextColor(ctx.getColor(R.color.paper))
                    cornerRadius = (12 * dp).toInt()
                    setText(label); textSize = 12f; isAllCaps = false
                    minHeight = (48 * dp).toInt()
                    setOnClickListener {
                        if (choice != null) {
                            LanguagePrefs(ctx).set(language, choice)
                            if ((choice.translation || choice.phonetic) && !prefs.isAiConfigured) openSettings(language)
                        } else openSettings(language)
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((2 * dp).toInt(), 0, (2 * dp).toInt(), 0) })
            }
            prompt.addView(row)
        }
        row(R.string.language_translation to LanguageDisplay.Choice(true, false),
            R.string.language_phonetic to LanguageDisplay.Choice(false, true),
            R.string.language_both to LanguageDisplay.Choice(true, true))
        row(R.string.language_original to LanguageDisplay.Choice(false, false), R.string.language_settings to null)
    }

    private fun openSettings(language: String) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("settings_section", 1).putExtra("source_language", language))
    }

    fun setTrack(title: String) {
        setLanguagePrompt(null)
        follow.resume(); resumeFollow.visibility = View.GONE
        retry.visibility = View.GONE
        handler.removeCallbacks(hideControls)
        quick.visibility = View.GONE
        quickLauncher.visibility = View.GONE
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
        host.measure(View.MeasureSpec.makeMeasureSpec(ctx.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        params.y = params.y.coerceIn(0, (ctx.resources.displayMetrics.heightPixels - host.measuredHeight).coerceAtLeast(0))
        wm.addView(host, params)
        attached = true
        handler.post(tick)
    }

    fun hide() {
        pendingLongPress?.let(handler::removeCallbacks)
        pendingLongPress = null
        handler.removeCallbacks(hideControls)
        quick.visibility = View.GONE
        quickLauncher.visibility = View.GONE
        if (!attached) return
        handler.removeCallbacks(tick)
        wm.removeView(host)
        attached = false
    }

    private fun positionMs(): Long {
        val s = state ?: return 0
        return PlaybackPosition.at(
            positionMs = s.position,
            playing = s.state == PlaybackState.STATE_PLAYING,
            lastPositionUpdateTimeMs = s.lastPositionUpdateTime,
            playbackSpeed = s.playbackSpeed,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            offsetMs = offsetMs,
            trackOffsetMs = trackOffsetMs,
        )
    }

    private fun followCurrent() {
        view.post { view.currentLineCenter()?.let { center -> lyricsScroll.smoothScrollTo(0, (center - lyricsScroll.height / 2).coerceAtLeast(0)) } }
    }

    private fun render(force: Boolean) {
        if (resumeFollow.visibility == View.VISIBLE && !follow.paused(SystemClock.elapsedRealtime())) {
            resumeFollow.visibility = View.GONE; shownIndex = Int.MIN_VALUE
        }
        val lines = lyrics.lines
        if (lines.isEmpty()) return
        val pos = positionMs()
        if (follow.paused(SystemClock.elapsedRealtime())) { view.setPosition(pos); return }
        val i = Lrc.indexAt(lines, pos)
        if (!force && i == shownIndex) { view.setPosition(pos); return }
        shownIndex = i
        view.show(LyricsView.Content(lines.map { it.text }, i, phonetics, translations, lyrics.synced, lines.getOrNull(i)?.syllables))
        view.setPosition(pos)
        if (!follow.paused(SystemClock.elapsedRealtime())) followCurrent()
    }

    private enum class Axis { NONE, VERTICAL, HORIZONTAL }

    private inner class Drag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var axis = Axis.NONE
        private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var tracker: VelocityTracker? = null
        private var longPressed = false
        private val longPress = Runnable { longPressed = true; view.performLongClick() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    handler.removeCallbacks(longPress)
                    pendingLongPress = longPress
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
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
                        handler.removeCallbacks(longPress)
                        axis = if (abs(dx) > abs(dy) * 1.5f) Axis.HORIZONTAL else Axis.VERTICAL
                    }
                    when (axis) {
                        Axis.VERTICAL -> {
                            revealControls()
                            params.y = (startY + dy.toInt()).coerceIn(0, (ctx.resources.displayMetrics.heightPixels - host.height).coerceAtLeast(0))
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
                    handler.removeCallbacks(longPress)
                    pendingLongPress = null
                    val dx = e.rawX - touchX
                    val velocityX = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    tracker?.recycle(); tracker = null
                    when (axis) {
                        Axis.VERTICAL -> { prefs.saveOverlayPosition(0, params.y); scheduleControlsHide() }
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
                        Axis.NONE -> if (e.actionMasked == MotionEvent.ACTION_UP && !longPressed) v.performClick()
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
