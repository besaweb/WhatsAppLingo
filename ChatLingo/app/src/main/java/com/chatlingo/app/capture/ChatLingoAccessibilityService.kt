package com.chatlingo.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.chatlingo.app.AppSettings
import com.chatlingo.app.adapter.WhatsAppAdapter
import com.chatlingo.app.adapter.WhatsAppSelectors
import com.chatlingo.app.model.Direction
import com.chatlingo.app.model.ExtractionResult
import com.chatlingo.app.model.LanguagePair
import com.chatlingo.app.overlay.FloatingToggle
import com.chatlingo.app.overlay.OutgoingPanel
import com.chatlingo.app.overlay.Panel
import com.chatlingo.app.overlay.TranslationOverlay
import com.chatlingo.app.translation.AppGraph
import com.chatlingo.app.translation.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Čte viditelné zprávy ve WhatsAppu a zobrazuje pod příchozími překlad.
 *
 * Pravidla (viz dokumentace, kap. 6): služba nikdy neklikne na tlačítko Odeslat, nic nemění
 * v aplikaci, zpracovává jen okno WhatsAppu a bez souhlasu uživatele nedělá nic.
 * Během scrollu se panely skrývají a po zastavení se zobrazí znovu (režim ověřený spikem).
 */
class ChatLingoAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = WhatsAppAdapter()
    private val repo get() = AppGraph.repository

    private lateinit var settings: AppSettings
    private lateinit var overlay: TranslationOverlay
    private lateinit var outgoingPanel: OutgoingPanel
    private lateinit var toggle: FloatingToggle
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var lastResult: ExtractionResult? = null
    private var lastScanAt = 0L
    private var lastScrollAt = 0L
    private val scanRunnable = Runnable { scan() }

    // Odchozí překlad (psaná zpráva)
    private var outgoingTranslation: String? = null
    private var lastInserted: String? = null
    private val outgoingRunnable = Runnable { scanOutgoing() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(this)
        overlay = TranslationOverlay(this)
        outgoingPanel = OutgoingPanel(this, onInsert = { insertTranslation() }, onClose = { dismissOutgoing() })
        toggle = FloatingToggle(this, settings) {
            val on = !settings.enabled
            settings.enabled = on // změna nastavení spustí nový průchod (prefsListener)
            Toast.makeText(this, if (on) "ChatLingo: překlad zapnutý" else "ChatLingo: překlad vypnutý", Toast.LENGTH_SHORT).show()
        }
        // Změna nastavení (jazyky, režim, pozastavení) se projeví hned, ne až po další události.
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule(0) }
            .also { settings.prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        if (::settings.isInitialized) prefsListener?.let { settings.prefs.unregisterOnSharedPreferenceChangeListener(it) }
        if (::overlay.isInitialized) overlay.detach()
        if (::outgoingPanel.isInitialized) outgoingPanel.detach()
        if (::toggle.isInitialized) toggle.detach()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString()
        val fromWhatsApp = pkg != null && pkg in WhatsAppSelectors.PACKAGES
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (fromWhatsApp) {
                lastScrollAt = SystemClock.uptimeMillis()
                overlay.hide()
                schedule(SCROLL_IDLE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (fromWhatsApp) schedule(CONTENT_DEBOUNCE_MS)
            // Psaní do pole zprávy: překlad až po pauze v psaní (šetří výkon i soukromí).
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (fromWhatsApp) {
                main.removeCallbacks(outgoingRunnable)
                main.postDelayed(outgoingRunnable, OUTGOING_DEBOUNCE_MS)
            }
            // Změna oken (klávesnice, jiná aplikace, oznamovací lišta): přepočítat nebo skrýt.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (pkg != packageName) schedule(CONTENT_DEBOUNCE_MS)
        }
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(scanRunnable)
        val sinceLast = SystemClock.uptimeMillis() - lastScanAt
        // Při nepřetržitých událostech nenechat panely skryté déle než MAX_WAIT_MS.
        if (delayMs > 0 && sinceLast > MAX_WAIT_MS) main.post(scanRunnable) else main.postDelayed(scanRunnable, delayMs)
    }

    /** Vrátí root WhatsAppu jen tehdy, když je WhatsApp nejvyšší aplikační okno a nic velkého ho nezakrývá. */
    private fun findWhatsAppRoot(): AccessibilityNodeInfo? {
        val ws = runCatching { windows }.getOrDefault(emptyList())
        val screenH = resources.displayMetrics.heightPixels
        for (w in ws) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> continue
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    val r = Rect().also { w.getBoundsInScreen(it) }
                    if (r.height() > screenH / 2) return null
                }
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val root = w.root ?: continue
                    return if ((root.packageName?.toString() ?: "") in WhatsAppSelectors.PACKAGES) root else null
                }
            }
        }
        val r = rootInActiveWindow
        return if ((r?.packageName?.toString() ?: "") in WhatsAppSelectors.PACKAGES) r else null
    }

    private fun scan() {
        lastScanAt = SystemClock.uptimeMillis()
        if (!settings.consent) {
            overlay.clear()
            dismissOutgoing()
            toggle.hide()
            return
        }
        val root = findWhatsAppRoot()
        if (root == null) {
            // WhatsApp není v popředí: nic nezobrazovat, ani plovoucí tlačítko.
            lastResult = null
            overlay.clear()
            dismissOutgoing()
            toggle.hide()
            return
        }
        // Plovoucí tlačítko je vidět nad WhatsAppem i při vypnutém překladu, aby šel znovu zapnout.
        if (settings.showToggle) toggle.show(settings.enabled) else toggle.hide()
        if (!settings.enabled) {
            lastResult = null
            overlay.clear()
            dismissOutgoing()
            return
        }
        val result = runCatching { adapter.extract(root) }.getOrNull()
        if (result == null || !result.isConversation) {
            lastResult = null
            overlay.clear()
            dismissOutgoing()
            return
        }
        overlay.ensureAttached()
        lastResult = result
        render(result)
    }

    private fun render(result: ExtractionResult) {
        val pair = settings.pair
        // Překládají se jen příchozí, plně viditelné zprávy.
        var candidates = result.messages.filter {
            it.direction == Direction.IN && !it.partial && it.text.isNotBlank()
        }
        if (settings.mode == AppSettings.MODE_LATEST) candidates = candidates.takeLast(1)

        val (preferred, engine) = AppGraph.engineFor(settings)
        val panels = ArrayList<Panel>()
        for (m in candidates) {
            val translated = repo.cached(preferred, pair, m.text)
            if (translated != null) panels += Panel(m.bounds, translated) else request(preferred, engine, pair, m.text)
        }
        overlay.show(panels, result.viewport)
    }

    private fun request(preferred: String, engine: TranslationEngine, pair: LanguagePair, text: String) {
        scope.launch {
            val added = repo.translate(preferred, engine, pair, text)
            // Po dokončení překladu překreslit, ale ne uprostřed scrollu (tam se panely stejně skrývají).
            if (added && SystemClock.uptimeMillis() - lastScrollAt > SCROLL_IDLE_MS) {
                lastResult?.let { render(it) }
            }
        }
    }

    // ---- Odchozí překlad: text, který uživatel píše ----

    private fun dismissOutgoing() {
        main.removeCallbacks(outgoingRunnable)
        outgoingTranslation = null
        if (::outgoingPanel.isInitialized) outgoingPanel.hide()
    }

    private fun scanOutgoing() {
        if (!settings.consent || !settings.enabled || !settings.outgoing) {
            dismissOutgoing()
            return
        }
        val root = findWhatsAppRoot() ?: return dismissOutgoing()
        val entry = adapter.findEntry(root) ?: return dismissOutgoing()
        val text = entry.text?.toString().orEmpty()
        // Prázdné pole ukazuje nápovědu („Zpráva“), ta se nepřekládá. Text, který jsme sami vložili, také ne.
        if (entry.isShowingHintText || text.isBlank() || text == lastInserted) return dismissOutgoing()

        // Píšu ve svém jazyce (cíl příchozích) a překládám do jazyka příchozích: opačný směr než u příchozích.
        val pair = LanguagePair(settings.target, settings.source)
        val (preferred, engine) = AppGraph.engineFor(settings)
        val bounds = Rect().also { entry.getBoundsInScreen(it) }

        val cached = repo.cached(preferred, pair, text)
        if (cached != null) {
            showOutgoing(cached, pair, bounds)
            return
        }
        scope.launch {
            repo.translate(preferred, engine, pair, text)
            // Zobrazit jen když se mezitím text v poli nezměnil.
            val current = findWhatsAppRoot()?.let { adapter.findEntry(it) }?.text?.toString().orEmpty()
            if (current == text) {
                repo.cached(preferred, pair, text)?.let { showOutgoing(it, pair, bounds) } ?: dismissOutgoing()
            }
        }
    }

    private fun showOutgoing(translation: String, pair: LanguagePair, entryBounds: Rect) {
        outgoingTranslation = translation
        outgoingPanel.show(translation, "Překlad ${pair.source} → ${pair.target}", entryBounds)
    }

    /** Vloží překlad do pole zprávy. Nikdy zprávu neodešle, odeslat musí uživatel sám. */
    private fun insertTranslation() {
        val translation = outgoingTranslation ?: return
        val entry = findWhatsAppRoot()?.let { adapter.findEntry(it) } ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, translation)
        }
        if (entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            lastInserted = translation
            dismissOutgoing()
        }
    }

    private companion object {
        const val OUTGOING_DEBOUNCE_MS = 700L
        const val CONTENT_DEBOUNCE_MS = 40L
        const val SCROLL_IDLE_MS = 60L
        const val MAX_WAIT_MS = 250L
    }
}
