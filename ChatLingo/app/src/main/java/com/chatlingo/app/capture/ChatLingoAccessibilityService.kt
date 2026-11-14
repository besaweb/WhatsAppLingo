package com.chatlingo.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.chatlingo.app.AppSettings
import com.chatlingo.app.adapter.WhatsAppAdapter
import com.chatlingo.app.adapter.WhatsAppSelectors
import com.chatlingo.app.model.Direction
import com.chatlingo.app.model.ExtractionResult
import com.chatlingo.app.model.LanguagePair
import com.chatlingo.app.overlay.Panel
import com.chatlingo.app.overlay.TranslationOverlay
import com.chatlingo.app.translation.AppGraph
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
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var lastResult: ExtractionResult? = null
    private var lastScanAt = 0L
    private var lastScrollAt = 0L
    private val scanRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(this)
        overlay = TranslationOverlay(this)
        // Změna nastavení (jazyky, režim, pozastavení) se projeví hned, ne až po další události.
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule(0) }
            .also { settings.prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        if (::settings.isInitialized) prefsListener?.let { settings.prefs.unregisterOnSharedPreferenceChangeListener(it) }
        if (::overlay.isInitialized) overlay.detach()
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
        if (!settings.consent || !settings.enabled) {
            overlay.clear()
            return
        }
        val root = findWhatsAppRoot()
        val result = root?.let { runCatching { adapter.extract(it) }.getOrNull() }
        if (result == null || !result.isConversation) {
            lastResult = null
            overlay.clear()
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

        val panels = ArrayList<Panel>()
        for (m in candidates) {
            val translated = repo.cached(pair, m.text)
            if (translated != null) panels += Panel(m.bounds, translated) else request(pair, m.text)
        }
        overlay.show(panels, result.viewport)
    }

    private fun request(pair: LanguagePair, text: String) {
        scope.launch {
            val added = repo.translate(pair, text)
            // Po dokončení překladu překreslit, ale ne uprostřed scrollu (tam se panely stejně skrývají).
            if (added && SystemClock.uptimeMillis() - lastScrollAt > SCROLL_IDLE_MS) {
                lastResult?.let { render(it) }
            }
        }
    }

    private companion object {
        const val CONTENT_DEBOUNCE_MS = 40L
        const val SCROLL_IDLE_MS = 60L
        const val MAX_WAIT_MS = 250L
    }
}
