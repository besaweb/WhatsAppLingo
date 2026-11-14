package com.chatlingo.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.chatlingo.app.capture.ChatLingoAccessibilityService
import com.chatlingo.app.translation.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Nastavení, souhlas a stav služby. Prototyp bez Compose (Etapa 2). */
class MainActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var serviceStatus: TextView
    private lateinit var modelStatus: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad + dp(24), pad, pad)
        }
        fun lp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

        col.addView(TextView(this).apply {
            text = "ChatLingo"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        // Upozornění a souhlas (požadavek Google Play i dobrá praxe). Zobrazuje se před zapnutím služby.
        col.addView(TextView(this).apply {
            textSize = 13f
            text = DISCLOSURE
        }, lp())
        col.addView(CheckBox(this).apply {
            text = "Rozumím a souhlasím"
            isChecked = settings.consent
            setOnCheckedChangeListener { _, checked -> settings.consent = checked }
        }, lp())

        serviceStatus = TextView(this).apply { textSize = 16f }
        col.addView(serviceStatus, lp())
        col.addView(Button(this).apply {
            text = "Otevřít nastavení přístupnosti"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, lp())
        col.addView(Button(this).apply {
            text = "Info o aplikaci (⋮ → Povolit omezená nastavení)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }
        }, lp())

        col.addView(Switch(this).apply {
            text = "Překlady zapnuté"
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, checked -> settings.enabled = checked }
        }, lp())

        col.addView(label("Jazyk příchozích zpráv"), lp())
        col.addView(languageSpinner(settings.source) { settings.source = it }, lp())
        col.addView(label("Překládat do"), lp())
        col.addView(languageSpinner(settings.target) { settings.target = it }, lp())

        col.addView(label("Zobrazení překladu"), lp())
        col.addView(modeSpinner(), lp())

        col.addView(Button(this).apply {
            text = "Stáhnout jazykové modely"
            setOnClickListener { downloadModels() }
        }, lp())
        modelStatus = TextView(this).apply { textSize = 13f }
        col.addView(modelStatus, lp())

        col.addView(TextView(this).apply {
            textSize = 13f
            text = HINTS
        }, lp())

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        serviceStatus.text =
            if (isServiceEnabled()) "✅ Služba přístupnosti je zapnutá" else "❌ Služba přístupnosti je vypnutá"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
    }

    private fun languageSpinner(selected: String, onSelect: (String) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            LANGUAGES.map { languageLabel(it) },
        )
        setSelection(LANGUAGES.indexOf(selected).coerceAtLeast(0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelect(LANGUAGES[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun modeSpinner(): Spinner = Spinner(this).apply {
        val modes = listOf(
            AppSettings.MODE_LATEST to "Jen poslední příchozí zpráva (doporučeno)",
            AppSettings.MODE_ALL to "Všechny viditelné příchozí zprávy",
        )
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.second },
        )
        setSelection(modes.indexOfFirst { it.first == settings.mode }.coerceAtLeast(0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.mode = modes[position].first
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun languageLabel(code: String): String {
        val name = Locale(code).getDisplayLanguage(Locale("cs")).replaceFirstChar { it.uppercase() }
        return "$name ($code)"
    }

    private fun downloadModels() {
        val pair = settings.pair
        if (pair.source == pair.target) {
            modelStatus.text = "Zdrojový a cílový jazyk jsou stejné."
            return
        }
        modelStatus.text = "Stahuji modely ${pair.source} → ${pair.target} …"
        scope.launch {
            val ok = AppGraph.engine.ensureModel(pair)
            modelStatus.text = if (ok) "✅ Modely jsou připravené." else "❌ Stažení selhalo (zkontroluj internet)."
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, ChatLingoAccessibilityService::class.java)
        val names = setOf(cn.flattenToString(), cn.flattenToShortString())
        return enabled.split(':').any { e -> names.any { it.equals(e, ignoreCase = true) } }
    }

    private companion object {
        val LANGUAGES = listOf("cs", "sq", "en", "de", "sk", "pl", "ru", "uk", "es", "fr", "it", "hr")

        const val DISCLOSURE = """Co ChatLingo dělá: zapnutá služba přístupnosti čte text zpráv, které vidíš ve WhatsAppu, a pod příchozími zprávami zobrazuje překlad. Čtou se jen zprávy na obrazovce.

Kam data jdou: překlad probíhá přímo v telefonu. Texty zpráv se nikam neodesílají a neukládají na disk. Internet se používá jen ke stažení jazykových modelů.

Co ChatLingo nedělá: nikdy neodešle zprávu, nic neklikne a nic ve WhatsAppu nemění."""

        const val HINTS = """Postup: 1) souhlas, 2) zapni službu v nastavení přístupnosti (u instalace mimo obchod nejdřív „Povolit omezená nastavení“), 3) stáhni modely, 4) otevři chat ve WhatsAppu. Překlad se zobrazí pod příchozí zprávou po zastavení scrollu."""
    }
}
