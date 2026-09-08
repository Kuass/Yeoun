package dev.kuass.ivlyrics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Device checks using the platform instrumentation protocol; no external services or AI credentials. */
class QualityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val checks = listOf("languageAndPresets", "renderSpacingAndRanges", "localAndEdits", "floatingPrompt", "inlineLayout", "backupMerge", "offlineArchive", "overlayFromUnthemedContext", "animatedHeight")
        var failures = 0
        checks.forEachIndexed { index, name ->
            val status = Bundle().apply { putString("class", this@QualityInstrumentation.javaClass.name); putString("test", name); putInt("numtests", checks.size); putInt("current", index + 1) }
            sendStatus(1, status)
            try {
                when (name) {
                    "languageAndPresets" -> languageAndPresets()
                    "renderSpacingAndRanges" -> renderSpacingAndRanges()
                    "localAndEdits" -> localAndEdits()
                    "floatingPrompt" -> floatingPrompt()
                    "inlineLayout" -> inlineLayout()
                    "backupMerge" -> backupMerge()
                    "offlineArchive" -> offlineArchive()
                    "overlayFromUnthemedContext" -> overlayFromUnthemedContext()
                    "animatedHeight" -> animatedHeight()
                }
                sendStatus(0, status)
            } catch (error: Throwable) {
                failures++
                status.putString("stack", error.stackTraceToString())
                sendStatus(-2, status)
            }
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "${checks.size} checks, $failures failures\n") })
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        runOnMainSync { result = runCatching(action) }
        requireNotNull(result).getOrThrow()
    }

    private fun languageAndPresets() {
        val prefs = Prefs(targetContext)
        val languages = LanguagePrefs(targetContext)
        val oldChoice = languages.get("ja")
        val oldPrev = prefs.translationPrev
        val oldNext = prefs.phoneticNext
        val presets = SettingsPresets(targetContext)
        val name = "__instrumentation__"
        try {
            languages.set("ja", LanguageDisplay.Choice(false, true))
            prefs.putInt(Prefs.TRANSLATION_PREV, 2)
            prefs.putInt(Prefs.PHONETIC_NEXT, 4)
            presets.save(name)
            languages.set("ja", LanguageDisplay.Choice(true, false))
            prefs.putInt(Prefs.TRANSLATION_PREV, 0)
            presets.apply(name)
            check(languages.get("ja") == LanguageDisplay.Choice(false, true))
            check(prefs.translationPrev == 2 && prefs.phoneticNext == 4)
            languages.reset("ja")
            check(languages.get("ja") == null)
        } finally {
            presets.remove(name)
            if (oldChoice == null) languages.reset("ja") else languages.set("ja", oldChoice)
            prefs.putInt(Prefs.TRANSLATION_PREV, oldPrev); prefs.putInt(Prefs.PHONETIC_NEXT, oldNext)
        }
    }

    private fun renderSpacingAndRanges() {
        onMain {
            val ctx = ContextThemeWrapper(targetContext, R.style.Theme_IvLyrics)
            val view = LyricsView(ctx)
            val lines = listOf("previous", "current original", "next")
            view.setStyle(LyricsView.Style(18, 0, 0, false, 80, translationPrev = 1, translationNext = 1, phoneticPrev = 0, phoneticNext = 1, inlinePronunciation = false))
            view.show(LyricsView.Content(lines, 1, listOf("reading before", "current reading", "reading after"), listOf("meaning before", "current meaning", "meaning after")))
            view.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, 900, view.measuredHeight)
            val column = view.getChildAt(0) as LinearLayout
            val rows = (0 until column.childCount).map { column.getChildAt(it) as TextView }
            check(rows.map { it.text.toString() } == listOf("meaning before", "current original", "current reading", "current meaning", "reading after", "meaning after"))
            val reading = rows.first { it.text == "current reading" }
            val meaning = rows.first { it.text == "current meaning" }
            check(reading.paddingTop == 0)
            check(meaning.paddingTop > reading.paddingTop)
            check(!reading.includeFontPadding)
        }
    }

    private fun localAndEdits() {
        val key = TrackPrefs.key("Quality fixture", "Yeoun", 120)
        val local = LocalLyrics(targetContext)
        val editsKey = "a".repeat(64)
        val edits = LyricsEdits(targetContext)
        try {
            local.setPlain(key, "first\nsecond")
            val lyrics = requireNotNull(local.lyrics(key, 120))
            check(!lyrics.synced && lyrics.lines.last().timeMs == 60000L)
            local.set(key, "[00:01]first\n[00:02]second")
            check(local.lyrics(key, 120)?.synced == true)
            edits.set(editsKey, ExtrasEdits(mapOf(0 to "", 1 to "corrected"), mapOf(0 to "reading")))
            check(LyricsEdits(targetContext).get(editsKey).translation == mapOf(0 to "", 1 to "corrected"))
        } finally { local.remove(key); edits.remove(editsKey) }
    }

    private fun inlineLayout() = onMain {
        val ctx = ContextThemeWrapper(targetContext, R.style.Theme_IvLyrics)
        val original = "The room remembers whispers"
        val reading = "더｜룸｜리멤버즈｜위스퍼스"
        val view = LyricsView(ctx)
        view.setStyle(LyricsView.Style(18, 0, 0, false, 80))
        view.show(LyricsView.Content(listOf(original), 0, listOf(reading), listOf("방 안에 속삭임이 남아요"),
            syllables = original.mapIndexed { index, char -> Syl(index * 100L, index * 100L + 100, char.toString()) }))
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 480, view.measuredHeight)
        val column = view.getChildAt(0) as LinearLayout
        check(column.childCount == 2)
        val text = column.getChildAt(0) as TextView
        check(text.text.toString() == original)
        check(text.layout.lineCount >= 2)
        val spans = (text.text as android.text.Spanned).getSpans(0, original.length, InlinePronunciation.Ruby::class.java)
        check(spans.size == 4)
        view.setPosition(0)
        check(spans.all { it.sung == 1 })
    }

    private fun isolatedContext(): android.content.Context {
        val token = "quality-" + java.util.UUID.randomUUID().toString()
        return object : android.content.ContextWrapper(targetContext) {
            override fun getFilesDir(): File = File(targetContext.cacheDir, token).apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = targetContext.getSharedPreferences("$token-$name", mode)
        }
    }

    private fun backupMerge() {
        val ctx = isolatedContext()
        val prefs = Prefs(ctx)
        prefs.putString(Prefs.API_KEY, "never-export-this")
        prefs.putInt(Prefs.FONT_SP, 24)
        val local = LocalLyrics(ctx)
        local.set("saved", "[00:01]saved lyric")
        val store = BackupStore(ctx)
        val encoded = BackupCodec.encode(store.snapshot())
        check(!encoded.contains("never-export-this"))
        local.set("saved", "[00:02]modified")
        local.set("unrelated", "[00:03]keep")
        prefs.putInt(Prefs.FONT_SP, 18)
        store.restore(BackupCodec.decode(encoded))
        check(local.get("saved") == "[00:01]saved lyric")
        check(local.get("unrelated") == "[00:03]keep")
        check(prefs.fontSp == 24 && prefs.apiKey == "never-export-this")
        ctx.filesDir.deleteRecursively()
    }

    private fun offlineArchive() {
        val ctx = isolatedContext()
        val archive = SongArchive(ctx)
        val lyrics = Lyrics(listOf(LrcLine(1000, "stored offline", listOf(Syl(1000, 2000, "stored offline")))), true, LrcLib.ID)
        val key = Triple("Quality offline", "Yeoun", 100L)
        archive.save(key, lyrics)
        check(SongArchive(ctx).get(key)?.lyrics == lyrics)
        check(archive.recent().single().track == key)
        archive.remove(key)
        check(archive.get(key) == null)
        ctx.filesDir.deleteRecursively()
    }

    private fun animatedHeight() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view: LyricsView
        try {
            onMain {
                view = LyricsView(activity)
                view.setStyle(LyricsView.Style(18, 0, 0, true, 80))
                activity.addContentView(view, android.widget.FrameLayout.LayoutParams(600, -2))
                view.show(LyricsView.Content(listOf("Short line"), 0))
            }
            Thread.sleep(150)
            val short = view.height
            onMain { view.show(LyricsView.Content(listOf("A longer line that wraps across several rows. ".repeat(6)), 0)) }
            Thread.sleep(90)
            val growing = view.height
            Thread.sleep(450)
            val tall = view.height
            check(growing in (short + 1) until tall) { "Growth jumped: $short -> $growing -> $tall" }
            onMain { view.show(LyricsView.Content(listOf("Short line"), 0)) }
            Thread.sleep(90)
            val shrinking = view.height
            Thread.sleep(450)
            check(shrinking in (view.height + 1) until tall) { "Shrink jumped: $tall -> $shrinking -> ${view.height}" }
            check(kotlin.math.abs(view.height - short) <= 1)
        } finally { onMain { activity.finish() } }
    }

    private fun overlayFromUnthemedContext() = onMain {
        val serviceLike = object : android.content.ContextWrapper(targetContext) {
            private val platformTheme = resources.newTheme().apply { applyStyle(android.R.style.Theme_DeviceDefault, true) }
            override fun getTheme(): android.content.res.Resources.Theme = platformTheme
        }
        check(runCatching { com.google.android.material.button.MaterialButton(serviceLike) }.exceptionOrNull() is IllegalArgumentException)
        val overlay = LyricsOverlay(serviceLike, Prefs(serviceLike), {})
        overlay.setTrack("Service context regression")
        overlay.setLanguagePrompt("en")
        overlay.setExtrasProgress(ExtrasProgress.Status(
            ExtrasProgress.Channel(ExtrasProgress.State.SETUP), ExtrasProgress.Channel(ExtrasProgress.State.SETUP)))
        overlay.show()
        overlay.hide()
    }

    private fun floatingPrompt() {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var overlay: LyricsOverlay? = null
        val languagePrefs = LanguagePrefs(targetContext)
        val previousChoice = languagePrefs.get("en")
        val floatingKey = TrackPrefs.key("__quality_floating__", "Yeoun", 120)
        val previousTrack = TrackPrefs(targetContext).get(floatingKey)
        val overlayPrefs = Prefs(targetContext)
        val oldX = overlayPrefs.overlayX; val oldY = overlayPrefs.overlayY
        try {
            onMain {
                val ctx = ContextThemeWrapper(targetContext, R.style.Theme_IvLyrics)
                overlay = LyricsOverlay(ctx, Prefs(ctx), {})
                overlay.setTrackKey(floatingKey)
                overlay.setTrackOffset(0)
                overlay.setTrack("Yeoun quality preview")
                overlay.setLyrics(Lyrics(listOf(LrcLine(0, "The room remembers every whisper"), LrcLine(3000, "Until the morning finds our song")), true))
                overlay.setExtras(listOf("방 안에는 속삭임이 남아 있어요", "아침이 우리 노래를 찾아올 때까지"), listOf("더 룸 리멤버즈 에브리 위스퍼", "언틸 더 모닝 파인즈 아워 송"))
                overlay.setLanguagePrompt("en")
                overlay.show()
            }
            Thread.sleep(400)
            val image = requireNotNull(uiAutomation.takeScreenshot())
            val dir = File(targetContext.getExternalFilesDir(null), "quality").apply { mkdirs() }
            File(dir, "language-overlay.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val roots = uiAutomation.windows.mapNotNull { it.root }
            fun hasText(node: android.view.accessibility.AccessibilityNodeInfo, text: String): Boolean {
                if (node.text?.toString() == text) return true
                return (0 until node.childCount).any { node.getChild(it)?.let { child -> hasText(child, text) } == true }
            }
            check(roots.any { hasText(it, targetContext.getString(R.string.language_settings)) })
            check(roots.any { hasText(it, targetContext.getString(R.string.language_both)) })
            fun find(node: android.view.accessibility.AccessibilityNodeInfo, text: String): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.toString() == text) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child, text)?.let { return it } }
                return null
            }
            val original = roots.firstNotNullOfOrNull { find(it, targetContext.getString(R.string.language_original)) }
            check(requireNotNull(original).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(200)
            check(languagePrefs.get("en") == LanguageDisplay.Choice(false, false))
            fun click(label: Int) {
                val button = uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull { find(it, targetContext.getString(label)) }
                check(requireNotNull(button).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(150)
            }
            check(roots.none { hasText(it, targetContext.getString(R.string.quick_settings)) })
            fun longClickable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.isLongClickable && node.className?.toString() in setOf(LyricsView::class.java.name, "android.widget.FrameLayout")) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> longClickable(child)?.let { return it } }
                return null
            }
            val lyricsNode = uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::longClickable)
            val bounds = android.graphics.Rect()
            requireNotNull(lyricsNode).getBoundsInScreen(bounds)
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, y: Float) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), y, 0)
                check(uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, bounds.centerY().toFloat())
            for (step in 1..5) {
                Thread.sleep(32)
                touch(android.view.MotionEvent.ACTION_MOVE, bounds.centerY() + step * 24f)
            }
            touch(android.view.MotionEvent.ACTION_UP, bounds.centerY() + 120f)
            Thread.sleep(150)
            click(R.string.quick_settings)
            click(R.string.offset_reset)
            click(R.string.offset_earlier)
            check(TrackPrefs(targetContext).get(floatingKey).offsetMs == 100)
            click(R.string.offset_reset)
            check(TrackPrefs(targetContext).get(floatingKey).offsetMs == 0)
            Thread.sleep(5200)
            val settledRoots = uiAutomation.windows.mapNotNull { it.root }
            check(settledRoots.none { hasText(it, targetContext.getString(R.string.quick_settings)) })
            check(settledRoots.none { hasText(it, targetContext.getString(R.string.offset_earlier)) })
        } finally {
            onMain { overlay?.hide(); activity.finish() }
            if (previousChoice == null) languagePrefs.reset("en") else languagePrefs.set("en", previousChoice)
            TrackPrefs(targetContext).set(floatingKey, previousTrack)
            overlayPrefs.saveOverlayPosition(oldX, oldY)
        }
    }
}
