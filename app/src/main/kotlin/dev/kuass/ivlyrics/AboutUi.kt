package dev.kuass.ivlyrics

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.snackbar.Snackbar

internal object AboutUi {
    fun bind(activity: AppCompatActivity) {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        activity.findViewById<TextView>(R.id.aboutVersion).text = activity.getString(
            R.string.about_version_value, info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info)
        )
        activity.findViewById<View>(R.id.btnAboutAuthor).setOnClickListener {
            open(activity, "https://github.com/Kuass")
        }
        activity.findViewById<View>(R.id.btnAboutRepository).setOnClickListener {
            open(activity, "https://github.com/Kuass/Yeoun")
        }
        activity.findViewById<View>(R.id.btnAboutReleases).setOnClickListener {
            open(activity, "https://github.com/Kuass/Yeoun/releases")
        }
    }

    private fun open(activity: AppCompatActivity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(activity.findViewById(R.id.scroll), R.string.about_link_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }
}
