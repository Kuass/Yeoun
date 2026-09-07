package dev.kuass.ivlyrics

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnPreDraw

/**
 * Renders a window of lyric lines around the current one: previous lines, the current line with optional
 * pronunciation and translation, then upcoming lines. Used by both the overlay and the settings preview.
 * Shared lines keep their screen baselines across a transition, so the next line flows into focus.
 */
class LyricsView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    data class Style(val fontSp: Int, val prevLines: Int, val nextLines: Int, val animate: Boolean, val bgPercent: Int, val karaoke: Boolean = true, val rowGapDp: Int = 2)

    data class Content(
        val lines: List<String>,
        val index: Int,
        val phonetic: List<String>? = null,
        val translation: List<String>? = null,
        val synced: Boolean = true,
        /** Timing of the current line's sung units; enables karaoke highlighting when present. */
        val syllables: List<Syl>? = null,
    )

    companion object {
        const val BLANK_LINE = "♪"
        private const val UNSYNCED_MARK = "≈ "
        private const val ANIM_MS = 440L

        /** Indices to render for [index] given how many neighbours are wanted; clamped to the list. */
        fun window(index: Int, prev: Int, next: Int, size: Int): IntRange =
            maxOf(0, index - prev)..minOf(size - 1, index + next)
    }

    private val dp = resources.displayMetrics.density
    private val regular: Typeface = ResourcesCompat.getFont(ctx, R.font.pretendard) ?: Typeface.DEFAULT
    private val bold: Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(regular, 650, false) else Typeface.create(regular, Typeface.BOLD)
    private val panel = GradientDrawable().apply { cornerRadius = 20 * dp }
    private var style = Style(Prefs.DEFAULT_FONT_SP, Prefs.DEFAULT_PREV_LINES, Prefs.DEFAULT_NEXT_LINES, true, Prefs.DEFAULT_BG_PERCENT)
    private var column: LinearLayout? = null
    private var shownIndex = Int.MIN_VALUE
    private var currentView: TextView? = null
    private var currentSyllables: List<Syl>? = null
    private var sungChars = -1
    private var shownLines: List<String>? = null
    private var motion: ValueAnimator? = null
    private var outgoing: LinearLayout? = null
    private var transitionVersion = 0
    private data class LineKey(val index: Int, val role: Int)
    private data class LinePosition(val baseline: Float, val size: Float)
    private val unsungColor = ContextCompat.getColor(ctx, R.color.overlay_unsung)

    init {
        val pad = (14 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = panel
        applyBackground()
    }

    fun setStyle(s: Style) {
        if (style == s) return
        style = s
        shownIndex = Int.MIN_VALUE
        shownLines = null
        applyBackground()
    }

    /** Title-style status, e.g. while searching or when nothing was found. */
    fun showStatus(title: String, detail: String) {
        shownIndex = Int.MIN_VALUE
        currentView = null
        currentSyllables = null
        swap(newColumn().apply {
            addView(line(title, 1f, bold, R.color.overlay_current))
            if (detail.isNotEmpty()) addView(line(detail, 0.8f, regular, R.color.overlay_neighbor))
        }, animate = false)
    }

    fun show(content: Content) {
        if (content.lines.isEmpty()) return
        val previousIndex = shownIndex
        val scroll = LyricsMotion.shouldScroll(previousIndex, content.index, shownLines == content.lines, style.animate)
        shownIndex = content.index
        shownLines = content.lines
        val i = content.index
        val next = newColumn()
        for (j in window(i, style.prevLines, style.nextLines, content.lines.size)) {
            when {
                j == i -> {
                    val mark = if (content.synced) "" else UNSYNCED_MARK
                    val current = line(mark + text(content.lines[i]), 1f, bold, R.color.overlay_current) as TextView
                    current.tag = LineKey(j, 0)
                    currentView = current
                    currentSyllables = content.syllables?.takeIf { style.karaoke && it.isNotEmpty() && mark.isEmpty() }
                    sungChars = -1
                    next.addView(current)
                    extra(content.phonetic?.getOrNull(i))?.let { next.addView(line(it, 0.8f, regular, R.color.overlay_phonetic).apply { tag = LineKey(j, 1) }) }
                    extra(content.translation?.getOrNull(i))?.let { next.addView(line(it, 0.85f, regular, R.color.overlay_translation).apply { tag = LineKey(j, 2) }) }
                }
                else -> next.addView(line(text(content.lines[j]), 0.75f, regular, R.color.overlay_neighbor).apply { tag = LineKey(j, 0) })
            }
        }
        if (i < 0) next.addView(line(BLANK_LINE, 1f, bold, R.color.overlay_current), 0)
        swap(next, animate = scroll, direction = if (i > previousIndex) 1 else -1)
    }

    /**
     * Karaoke: characters whose syllable has started by [posMs] stay bright, the rest dim.
     * Cheap when nothing changed, so it is safe to call on every playback tick.
     */
    fun setPosition(posMs: Long) {
        val view = currentView ?: return
        val syls = currentSyllables ?: return
        val sung = Lrc.sungChars(syls, posMs).coerceAtMost(view.text.length)
        if (sung == sungChars) return
        sungChars = sung
        val span = SpannableString(view.text.toString())
        if (sung < span.length) span.setSpan(ForegroundColorSpan(unsungColor), sung, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.setText(span, TextView.BufferType.SPANNABLE)
    }

    private fun textViews(parent: LinearLayout) = (0 until parent.childCount).mapNotNull { parent.getChildAt(it) as? TextView }

    private fun baselineOnScreen(view: TextView): Float {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        // Scaling pivots around the baseline, so it does not move during emphasis changes.
        return location[1] + view.baseline * view.scaleY
    }

    /** FLIP each retained line, keeping it fully visible; only entering/exiting rows fade at the edges. */
    private fun swap(next: LinearLayout, animate: Boolean, direction: Int = 1) {
        val old = column
        val positions = old?.let { textViews(it).associate { view ->
            view.tag to LinePosition(baselineOnScreen(view), view.textSize * view.scaleY)
        } }.orEmpty()
        motion?.cancel()
        motion = null
        outgoing?.let { overlay.remove(it) }
        outgoing = null
        val version = ++transitionVersion
        column = next
        if (old != null) removeView(old)
        addView(next)
        val motionEnabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (old == null || !animate || !isLaidOut || !motionEnabled) return
        overlay.add(old)
        outgoing = old
        next.doOnPreDraw {
            if (version != transitionVersion) return@doOnPreDraw
            val rows = textViews(next)
            val shared = rows.filter { positions.containsKey(it.tag) }
            val travel = shared.firstOrNull()?.let { view ->
                positions.getValue(view.tag).baseline - baselineOnScreen(view)
            } ?: ((rows.firstOrNull()?.textSize ?: (style.fontSp * dp)) * 1.5f * direction)
            val offsets = rows.associateWith { view ->
                positions[view.tag]?.let { it.baseline - baselineOnScreen(view) } ?: run {
                    // New translation/reading lines travel with their own original line, not the outgoing one.
                    val key = view.tag as? LineKey
                    val original = rows.firstOrNull { it.tag == key?.let { LineKey(it.index, 0) } }
                    val previous = original?.let { positions[it.tag] }
                    if (previous != null) previous.baseline - baselineOnScreen(original) else travel
                }
            }
            val scales = rows.associateWith { view -> positions[view.tag]?.let { it.size / view.textSize } ?: 1f }
            val keys = rows.map { it.tag }.toSet()
            val exiting = textViews(old).filter { it.tag !in keys }
            textViews(old).filter { it.tag in keys }.forEach { it.alpha = 0f }
            val exitOffsets = exiting.associateWith { it.translationY }
            val exitAlphas = exiting.associateWith { it.alpha }
            rows.forEach { view ->
                view.pivotX = view.width / 2f
                view.pivotY = view.baseline.toFloat()
            }
            fun frame(fraction: Float) {
                rows.forEach { view ->
                    view.translationY = offsets.getValue(view) * (1f - fraction)
                    val scale = 1f + (scales.getValue(view) - 1f) * (1f - fraction)
                    view.scaleX = scale
                    view.scaleY = scale
                    view.alpha = when {
                        positions.containsKey(view.tag) -> 1f
                        (view.tag as? LineKey)?.role != 0 -> ((fraction - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        else -> fraction
                    }
                }
                exiting.forEach { view ->
                    view.translationY = exitOffsets.getValue(view) - travel * fraction
                    val fade = if ((view.tag as? LineKey)?.role != 0) (1f - fraction * 4f).coerceAtLeast(0f) else 1f - fraction
                    view.alpha = exitAlphas.getValue(view) * fade
                }
            }
            frame(0f)
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIM_MS
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    frame(it.animatedValue as Float)
                    if (it.animatedFraction == 1f) {
                        overlay.remove(old)
                        if (outgoing === old) outgoing = null
                    }
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        ++transitionVersion
        motion?.cancel()
        motion = null
        outgoing?.let { overlay.remove(it) }
        outgoing = null
        column?.let { textViews(it).forEach { row -> row.translationY = 0f; row.scaleX = 1f; row.scaleY = 1f; row.alpha = 1f } }
        super.onDetachedFromWindow()
    }

    private fun newColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun applyBackground() {
        val alpha = (255 * style.bgPercent / 100f).toInt().coerceIn(0, 255)
        panel.setColor((alpha shl 24) or (ContextCompat.getColor(context, R.color.ink) and 0x00FFFFFF))
    }

    private fun text(s: String) = s.ifBlank { BLANK_LINE }
    private fun extra(s: String?) = s?.takeIf { it.isNotBlank() && it != BLANK_LINE }

    private fun line(text: String, scale: Float, face: Typeface, colorRes: Int): View = TextView(context).apply {
        this.text = text
        textSize = style.fontSp * scale
        typeface = face
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, colorRes))
        val v = (style.rowGapDp * dp).toInt()
        setPadding(0, v, 0, v)
        setShadowLayer(3 * dp, 0f, 1 * dp, 0x99000000.toInt())
    }
}
