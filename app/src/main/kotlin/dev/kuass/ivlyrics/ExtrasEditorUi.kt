package dev.kuass.ivlyrics

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ExtrasEditorUi {
    fun show(activity: AppCompatActivity, snapshot: LyricsState.Snapshot) {
        val key = snapshot.extrasKey ?: return
        val lines = snapshot.lyrics?.lines ?: return
        if (lines.isEmpty()) return
        val store = LyricsEdits(activity)
        val cache = LyricsCache(activity)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.extras_editor)
            .setItems(lines.mapIndexed { i, line -> "${i + 1}. ${line.text.ifBlank { LyricsView.BLANK_LINE }}" }.toTypedArray()) { _, index ->
                val edits = store.get(key)
                val values = edits.apply(cache.get(key), lines.size)
                val body = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (24 * resources.displayMetrics.density).toInt(); setPadding(pad, 0, pad, 0)
                }
                body.addView(TextView(activity).apply { text = lines[index].text; setTextIsSelectable(true) })
                body.addView(TextView(activity).apply { setText(R.string.extras_editor_hint) })
                val translation = EditText(activity).apply {
                    setHint(R.string.language_translation); setText(values.translation?.getOrNull(index)); minLines = 2
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val phonetic = EditText(activity).apply {
                    setHint(R.string.language_phonetic); setText(values.phonetic?.getOrNull(index)); minLines = 2
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                body.addView(translation); body.addView(phonetic)
                val dialog = MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.extras_editor_line, index + 1))
                    .setView(ScrollView(activity).apply { addView(body) }).setPositiveButton(R.string.save_action, null)
                    .setNeutralButton(R.string.extras_restore, null).setNegativeButton(android.R.string.cancel, null).create()
                fun bump() = Prefs(activity).putString(Prefs.EXTRAS_VERSION, java.util.UUID.randomUUID().toString())
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val result = runCatching {
                            val latest = store.get(key)
                            store.set(key, latest.copy(translation = latest.translation + (index to translation.text.toString().trim()),
                                phonetic = latest.phonetic + (index to phonetic.text.toString().trim())))
                        }
                        if (result.isSuccess) { bump(); dialog.dismiss() }
                        else translation.error = activity.getString(R.string.extras_save_failed)
                    }
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val result = runCatching {
                            val latest = store.get(key)
                            store.set(key, latest.copy(translation = latest.translation - index, phonetic = latest.phonetic - index))
                        }
                        if (result.isSuccess) { bump(); dialog.dismiss() }
                        else translation.error = activity.getString(R.string.extras_save_failed)
                    }
                }
                dialog.show()
            }.show()
    }
}
