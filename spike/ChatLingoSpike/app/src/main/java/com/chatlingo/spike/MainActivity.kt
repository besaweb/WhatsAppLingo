package com.chatlingo.spike

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var summary: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
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
            text = "ChatLingo Spike"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Diagnostika pro Etapu 1b. Nic neodesílá na internet, nikdy neodešle zprávu."
            textSize = 13f
        }, lp())

        status = TextView(this).apply { textSize = 16f }
        col.addView(status, lp())

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
        col.addView(CheckBox(this).apply {
            text = "Maskovat texty zpráv v dumpu (doporučeno)"
            isChecked = prefs.getBoolean(KEY_MASK, true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_MASK, checked).apply() }
        }, lp())
        col.addView(Button(this).apply {
            text = "Obnovit výsledky"
            setOnClickListener { refresh() }
        }, lp())
        col.addView(Button(this).apply {
            text = "Vynulovat statistiky"
            setOnClickListener {
                SpikeStats.reset()
                refresh()
            }
        }, lp())

        summary = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        col.addView(summary, lp())

        col.addView(TextView(this).apply {
            textSize = 13f
            text = INSTRUCTIONS
        }, lp())

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, SpikeAccessibilityService::class.java)
        val names = setOf(cn.flattenToString(), cn.flattenToShortString())
        return enabled.split(':').any { e -> names.any { it.equals(e, ignoreCase = true) } }
    }

    private fun refresh() {
        val on = isServiceEnabled()
        status.text = if (on) "✅ Služba přístupnosti je zapnutá" else "❌ Služba přístupnosti je vypnutá"
        summary.text = SpikeStats.summaryText()
    }

    companion object {
        const val PREFS = "spike"
        const val KEY_MASK = "mask_text"

        private val INSTRUCTIONS = """
            Postup testu:
            1) Zapni službu: Nastavení přístupnosti → Nainstalované aplikace → ChatLingo Spike. Pokud je zašedlá, otevři Info o aplikaci → ⋮ → Povolit omezená nastavení a zkus to znovu.
            2) Otevři WhatsApp a chat (ideálně 1:1 s příchozími i odchozími zprávami a jednu skupinu).
            3) Zkontroluj rámečky: zelená = příchozí, modrá = odchozí, oranžová = neurčeno, čárkovaná = částečně viditelná. Fialový čárkovaný rámeček = oblast seznamu.
            4) Panel „≡ CL“ vpravo: klepnutím sbalíš nebo rozbalíš, tažením posuneš.
               DUMP = uloží strukturu do Download/ChatLingoSpike
               SET TEXT = vloží testovací text do pole (neodesílá)
               REŽIM = SLEDUJ (rámečky jedou se scrollem) / SKRYJ (během scrollu skryté)
               RÁMY = zapnout nebo vypnout rámečky
            5) Scrolluj pomalu i rychle v obou režimech, napiš si zprávu, otevři klávesnici, otoč telefon.
            6) Udělej DUMP v chatu 1:1, ve skupině a na seznamu chatů. Pošli mi JSON soubory a screenshot téhle obrazovky.
        """.trimIndent()
    }
}
