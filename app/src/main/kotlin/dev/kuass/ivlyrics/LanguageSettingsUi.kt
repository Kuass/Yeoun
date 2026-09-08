package dev.kuass.ivlyrics

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

object LanguageSettingsUi {
    fun show(ctx: Context, source: String, onChanged: () -> Unit = {}) {
        val preferences = LanguagePrefs(ctx)
        val existing = preferences.get(source)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        content.addView(TextView(ctx).apply { setText(R.string.language_choice_hint) })
        val translation = MaterialSwitch(ctx).apply { setText(R.string.language_translation); isChecked = existing?.translation ?: false }
        val phonetic = MaterialSwitch(ctx).apply { setText(R.string.language_phonetic); isChecked = existing?.phonetic ?: false }
        content.addView(translation); content.addView(phonetic)
        MaterialAlertDialogBuilder(ctx).setTitle(SourceLanguage.label(source, ctx)).setView(content)
            .setPositiveButton(R.string.save_action) { _, _ ->
                preferences.set(source, LanguageDisplay.Choice(translation.isChecked, phonetic.isChecked)); onChanged()
            }.setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.language_reset) { _, _ -> preferences.reset(source); onChanged() }.show()
    }

    fun select(ctx: Context, onChanged: () -> Unit = {}) {
        val prefs = LanguagePrefs(ctx)
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.language_settings)
            .setItems(SourceLanguage.CODES.map { source ->
                val choice = prefs.get(source)
                val mode = when {
                    choice == null -> R.string.language_unset
                    choice.translation && choice.phonetic -> R.string.language_both
                    choice.translation -> R.string.language_translation
                    choice.phonetic -> R.string.language_phonetic
                    else -> R.string.language_original
                }
                "${SourceLanguage.label(source, ctx)}: ${ctx.getString(mode)}"
            }.toTypedArray()) { _, index -> show(ctx, SourceLanguage.CODES[index], onChanged) }.show()
    }
}
