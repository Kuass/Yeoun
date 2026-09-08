package dev.kuass.ivlyrics

import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object RecentSongsUi {
    fun show(activity: AppCompatActivity, songs: List<SongArchiveCodec.Song>) {
        if (songs.isEmpty()) {
            MaterialAlertDialogBuilder(activity).setTitle(R.string.recent_songs).setMessage(R.string.recent_empty)
                .setPositiveButton(android.R.string.ok, null).show(); return
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.recent_songs)
            .setItems(songs.map { "${it.track.first} · ${it.track.second}" }.toTypedArray()) { _, index ->
                val song = songs[index]
                val prefs = Prefs(activity)
                val trackPrefs = TrackPrefs(activity).get(TrackPrefs.key(song.track.first, song.track.second, song.track.third))
                val options = prefs.aiOptions.copy(target = Lang.target(trackPrefs.lang ?: prefs.targetLang))
                val texts = song.lyrics.lines.map { it.text }
                val key = ExtrasPolicy.key(song.track, texts, options)
                val values = LyricsEdits(activity).get(key).apply(LyricsCache(activity).get(key), texts.size)
                val languagePrefs = LanguagePrefs(activity)
                val languages = SourceLanguage.detectLines(texts, trackPrefs.sourceLanguage)
                val choices = languages.map { LanguageDisplay.resolve(it, options.target.code, languagePrefs.get(it)) }
                val translation = ExtrasPolicy.visible(values.translation, choices.map { it.translation && prefs.translate })
                val phonetic = ExtrasPolicy.visible(values.phonetic, choices.map { it.phonetic && prefs.phonetic })
                val view = LyricsView(activity).apply {
                    setStyle(prefs.lyricsStyle.copy(prevLines = 0, nextLines = texts.lastIndex, translationNext = texts.lastIndex, phoneticNext = texts.lastIndex, animate = false))
                    show(LyricsView.Content(texts, 0, phonetic, translation, song.lyrics.synced))
                }
                val snapshot = LyricsState.Snapshot(song.track, song.track.first, song.track.second, song.lyrics, translation, phonetic,
                    extrasKey = key, sourceLanguages = languages)
                MaterialAlertDialogBuilder(activity).setTitle(song.track.first).setMessage(R.string.recent_readonly)
                    .setView(ScrollView(activity).apply { addView(view) })
                    .setPositiveButton(R.string.extras_editor) { _, _ -> ExtrasEditorUi.show(activity, snapshot) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.delete_action) { _, _ ->
                        MaterialAlertDialogBuilder(activity).setTitle(R.string.delete_action).setMessage(song.track.first)
                            .setPositiveButton(R.string.delete_action) { _, _ -> SongArchive(activity).remove(song.track) }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }.show()
            }.show()
    }
}
