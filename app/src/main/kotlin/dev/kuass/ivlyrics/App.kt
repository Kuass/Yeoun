package dev.kuass.ivlyrics

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val lang = Prefs(this).appLang
        if (lang == Prefs.APP_LANG_SYSTEM) return
        // AppCompatDelegate must not be called before an Activity exists on API 33+; the framework API works from here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.toLanguageTags() != lang) manager.applicationLocales = LocaleList.forLanguageTags(lang)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
    }
}
