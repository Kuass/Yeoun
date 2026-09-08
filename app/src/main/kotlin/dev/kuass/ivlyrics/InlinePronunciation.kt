package dev.kuass.ivlyrics

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan

object InlinePronunciation {
    data class Part(val start: Int, val end: Int, val reading: String)
    private val words = Regex("\\S+")
    private val joiningScript = Regex("[\\u0600-\\u08ff\\u0900-\\u0dff]")

    fun align(original: String, reading: String): List<Part>? {
        if (original.isBlank() || reading.isBlank() || joiningScript.containsMatchIn(original)) return null
        val tokens = words.findAll(original).toList()
        val parts = if ('｜' in reading) reading.split('｜').map(String::trim)
            else words.findAll(reading).map { it.value }.toList()
        if (tokens.size != parts.size || parts.any(String::isBlank)) return null
        return tokens.mapIndexed { i, word -> Part(word.range.first, word.range.last + 1, parts[i]) }
    }

    fun plain(reading: String) = reading.replace('｜', ' ').trim()

    class Ruby(val part: Part, private val readingColor: Int, private val dimColor: Int) : ReplacementSpan() {
        var sung: Int? = null
        private val scale = 0.72f
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val small = Paint(paint).apply { textSize *= scale }
            fm?.let {
                val metrics = paint.fontMetricsInt
                it.ascent = metrics.ascent; it.top = metrics.top
                it.descent = metrics.descent + (small.fontMetrics.descent - small.fontMetrics.ascent).toInt()
                it.bottom = it.descent
            }
            return kotlin.math.ceil(maxOf(paint.measureText(text, start, end), small.measureText(part.reading)).toDouble()).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val originalPaint = Paint(paint)
            val readingPaint = Paint(paint).apply { textSize *= scale; color = readingColor }
            val width = getSize(paint, text, start, end, null).toFloat()
            val left = x + (width - paint.measureText(text, start, end)) / 2
            val split = sung?.coerceIn(start, end) ?: end
            canvas.drawText(text, start, split, left, y.toFloat(), originalPaint)
            if (split < end) {
                originalPaint.color = dimColor
                canvas.drawText(text, split, end, left + paint.measureText(text, start, split), y.toFloat(), originalPaint)
            }
            val readingY = y + paint.fontMetrics.descent - readingPaint.fontMetrics.ascent
            canvas.drawText(part.reading, x + (width - readingPaint.measureText(part.reading)) / 2, readingY, readingPaint)
        }
    }

    fun styled(original: String, reading: String, paint: Paint, maxWidth: Int, readingColor: Int, dimColor: Int): SpannableString? {
        val parts = align(original, reading) ?: return null
        val spans = parts.map { Ruby(it, readingColor, dimColor) }
        if (spans.any { it.getSize(paint, original, it.part.start, it.part.end, null) > maxWidth }) return null
        return SpannableString(original).apply {
            spans.forEach { setSpan(it, it.part.start, it.part.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
}
