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

    /** Plovoucí tlačítko ve WhatsAppu pro rychlé zapnutí a vypnutí překladu. */
    var showToggle: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOGGLE, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_TOGGLE, v).apply()

    /** Poloha plovoucího tlačítka v pixelech (-1 = výchozí). */
    var toggleX: Int
        get() = prefs.getInt(KEY_TOGGLE_X, -1)
        set(v) = prefs.edit().putInt(KEY_TOGGLE_X, v).apply()

    var toggleY: Int
        get() = prefs.getInt(KEY_TOGGLE_Y, -1)
        set(v) = prefs.edit().putInt(KEY_TOGGLE_Y, v).apply()

    /** Překlad zprávy, kterou uživatel píše (z jeho jazyka do jazyka příchozích). Vkládá se jen po klepnutí na Vložit. */
    var outgoing: Boolean
        get() = prefs.getBoolean(KEY_OUTGOING, true)
        set(v) = prefs.edit().putBoolean(KEY_OUTGOING, v).apply()

    /** Kvalitní překlad přes LLM (text zprávy se posílá na vlastní proxy). Vypnuto = vše zůstává v telefonu. */
    var useLlm: Boolean
        get() = prefs.getBoolean(KEY_USE_LLM, false)
        set(v) = prefs.edit().putBoolean(KEY_USE_LLM, v).apply()

    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROXY_URL, v).apply()

    var proxyToken: String
        get() = prefs.getString(KEY_PROXY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROXY_TOKEN, v).apply()

    val pair: LanguagePair get() = LanguagePair(source, target)

    companion object {
        const val PREFS = "chatlingo"
        const val KEY_CONSENT = "consent"
        const val KEY_ENABLED = "enabled"
        const val KEY_SOURCE = "source"
        const val KEY_TARGET = "target"
        const val KEY_MODE = "mode"
        const val KEY_OUTGOING = "outgoing"
        const val KEY_SHOW_TOGGLE = "show_toggle"
        const val KEY_TOGGLE_X = "toggle_x"
        const val KEY_TOGGLE_Y = "toggle_y"
        const val KEY_USE_LLM = "use_llm"
        const val KEY_PROXY_URL = "proxy_url"
        const val KEY_PROXY_TOKEN = "proxy_token"
        const val MODE_LATEST = "C"
        const val MODE_ALL = "A"
    }
}
