package com.chatlingo.app

import android.content.Context
import android.content.SharedPreferences
import com.chatlingo.app.model.LanguagePair

class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Souhlas uživatele s tím, co služba čte a dělá. Bez něj služba nic nezobrazuje. */
    var consent: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(v) = prefs.edit().putBoolean(KEY_CONSENT, v).apply()

    /** Hlavní přepínač „Pozastavit překlady“. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var source: String
        get() = prefs.getString(KEY_SOURCE, "sq") ?: "sq"
        set(v) = prefs.edit().putString(KEY_SOURCE, v).apply()

    var target: String
        get() = prefs.getString(KEY_TARGET, "cs") ?: "cs"
        set(v) = prefs.edit().putString(KEY_TARGET, v).apply()

    /** MODE_LATEST = jen poslední příchozí zpráva, MODE_ALL = všechny viditelné příchozí. */
    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_LATEST) ?: MODE_LATEST
        set(v) = prefs.edit().putString(KEY_MODE, v).apply()

    val pair: LanguagePair get() = LanguagePair(source, target)

    companion object {
        const val PREFS = "chatlingo"
        const val KEY_CONSENT = "consent"
        const val KEY_ENABLED = "enabled"
        const val KEY_SOURCE = "source"
        const val KEY_TARGET = "target"
        const val KEY_MODE = "mode"
        const val MODE_LATEST = "C"
        const val MODE_ALL = "A"
    }
}
