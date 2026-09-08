package dev.kuass.ivlyrics

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object LyricsSearchUi {
    fun show(activity: AppCompatActivity, trackKey: String, title: String, artist: String) {
        val worker = Executors.newSingleThreadExecutor()
        val revision = AtomicInteger()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt(); setPadding(pad, 0, pad, 0)
        }
        val query = EditText(activity).apply { setText(activity.getString(R.string.search_initial_query, title, artist).trim()); setHint(R.string.search_query); isSingleLine = true }
        val search = MaterialButton(activity).apply { setText(R.string.search_action) }
        val status = TextView(activity)
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(query); body.addView(search); body.addView(status)
        body.addView(ScrollView(activity).apply { addView(results) }, LinearLayout.LayoutParams(-1, (300 * activity.resources.displayMetrics.density).toInt()))
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.lyrics_search).setView(body)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { revision.incrementAndGet(); worker.shutdownNow() }
        search.setOnClickListener {
            val text = query.text.toString().trim()
            if (text.isEmpty()) { query.error = activity.getString(R.string.search_query); return@setOnClickListener }
            val ticket = revision.incrementAndGet()
            search.isEnabled = false; results.removeAllViews(); status.setText(R.string.search_loading)
            worker.execute {
                val result = runCatching { LrcLib.searchQuery(text) }
                activity.runOnUiThread {
                    if (revision.get() != ticket || !dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                    search.isEnabled = true
                    result.onSuccess { hits ->
                        status.setText(if (hits.isEmpty()) R.string.search_none else R.string.search_select)
                        hits.forEach { hit ->
                            val timed = hit.candidate.synced != null
                            val label = activity.getString(if (timed) R.string.search_synced else R.string.search_plain)
                            results.addView(MaterialButton(activity).apply {
                                isAllCaps = false
                                this.text = activity.getString(R.string.search_result_format, hit.title, hit.artist, hit.album, hit.candidate.durationSec / 60, hit.candidate.durationSec % 60, label)
                                setOnClickListener {
                                    val preview = hit.candidate.synced?.let { Lrc.parse(it).joinToString("\n") { line -> line.text } } ?: hit.candidate.plain.orEmpty()
                                    val content = TextView(activity).apply { this.text = preview; setPadding(32, 16, 32, 16); setTextIsSelectable(true) }
                                    MaterialAlertDialogBuilder(activity).setTitle("${hit.title} · $label")
                                        .setMessage(activity.getString(R.string.search_apply_hint, title, artist))
                                        .setView(ScrollView(activity).apply { addView(content) })
                                        .setPositiveButton(R.string.search_apply) { _, _ ->
                                            val local = LocalLyrics(activity)
                                            if (timed) local.set(trackKey, requireNotNull(hit.candidate.synced))
                                            else local.setPlain(trackKey, requireNotNull(hit.candidate.plain))
                                            Prefs(activity).bumpTrackPrefs()
                                            android.widget.Toast.makeText(activity, R.string.search_applied, android.widget.Toast.LENGTH_SHORT).show()
                                            dialog.dismiss()
                                        }.setNegativeButton(android.R.string.cancel, null).show()
                                }
                            })
                        }
                    }.onFailure { status.setText(R.string.search_failed) }
                }
            }
        }
        dialog.show()
    }
}
