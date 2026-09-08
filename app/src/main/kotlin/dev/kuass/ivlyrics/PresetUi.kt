package dev.kuass.ivlyrics

import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PresetUi {
    fun show(activity: AppCompatActivity) {
        val store = SettingsPresets(activity)
        val names = store.names()
        MaterialAlertDialogBuilder(activity).setTitle(R.string.settings_presets)
            .setItems((listOf(activity.getString(R.string.preset_save)) + names).toTypedArray()) { _, index ->
                if (index == 0) {
                    val input = EditText(activity).apply { setHint(R.string.preset_name); isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
                    val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.preset_save).setView(input)
                        .setMessage(R.string.preset_scope).setPositiveButton(R.string.save_action, null).setNegativeButton(android.R.string.cancel, null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val name = input.text.toString().trim()
                            if (name.isEmpty()) { input.error = activity.getString(R.string.preset_name); return@setOnClickListener }
                            if (name !in names && names.size >= 20) { input.error = activity.getString(R.string.preset_limit); return@setOnClickListener }
                            if (name in names) MaterialAlertDialogBuilder(activity).setTitle(R.string.preset_replace)
                                .setMessage(name).setPositiveButton(R.string.save_action) { _, _ -> store.save(name); dialog.dismiss() }
                                .setNegativeButton(android.R.string.cancel, null).show()
                            else { store.save(name); dialog.dismiss() }
                        }
                    }
                    dialog.show()
                } else {
                    val name = names[index - 1]
                    MaterialAlertDialogBuilder(activity).setTitle(name).setMessage(R.string.preset_scope)
                        .setPositiveButton(R.string.preset_apply) { _, _ -> store.apply(name); activity.recreate() }
                        .setNeutralButton(R.string.delete_action) { _, _ ->
                            MaterialAlertDialogBuilder(activity).setTitle(R.string.delete_action).setMessage(name)
                                .setPositiveButton(R.string.delete_action) { _, _ -> store.remove(name) }.setNegativeButton(android.R.string.cancel, null).show()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.show()
    }
}
