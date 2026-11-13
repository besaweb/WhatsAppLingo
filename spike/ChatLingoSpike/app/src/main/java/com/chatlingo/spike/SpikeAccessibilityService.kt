package com.chatlingo.spike

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Diagnostická služba (spike). Pouze čte strukturu WhatsAppu, kreslí rámečky
 * a na pokyn vkládá testovací text. Nikdy neklikne na Odeslat.
 */
class SpikeAccessibilityService : AccessibilityService(), OverlayController.Listener {

    private enum class Mode(val label: String) { FOLLOW("SLEDUJ"), HIDE_ON_SCROLL("SKRYJ") }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayController
    private var mode = Mode.FOLLOW
    private var framesOn = true
    private var inWhatsApp = false
    private var lastScanAt = 0L
    private var lastScrollAt = 0L
    private var pendingSettle = false
    private val scanRunnable = Runnable { doScan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this, this)
        SpikeStats.reset()
        SpikeStats.mode = mode.label
        SpikeStats.serviceConnected = true
        toast("ChatLingo Spike: služba běží")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.detach()
        SpikeStats.serviceConnected = false
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString()
        val isWa = pkg != null && pkg in WhatsAppParser.PACKAGES
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (isWa) onScroll(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (isWa) schedule(CONTENT_DEBOUNCE_MS, allowMaxWait = mode == Mode.FOLLOW)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (pkg != packageName) schedule(CONTENT_DEBOUNCE_MS, allowMaxWait = true)
        }
    }

    private fun onScroll(event: AccessibilityEvent) {
        lastScrollAt = SystemClock.uptimeMillis()
        pendingSettle = true
        SpikeStats.scrollEvents++
        when (mode) {
            Mode.FOLLOW -> {
                val dy = event.scrollDeltaY
                if (dy != -1 && dy != 0) {
                    SpikeStats.scrollWithDelta++
                    overlay.shiftBy(-dy)
                }
                schedule(SCROLL_DEBOUNCE_MS, allowMaxWait = true)
            }
            Mode.HIDE_ON_SCROLL -> {
                overlay.hideFramesTemporarily()
                schedule(HIDE_IDLE_MS, allowMaxWait = false)
            }
        }
    }

    private fun schedule(delayMs: Long, allowMaxWait: Boolean) {
        main.removeCallbacks(scanRunnable)
        val since = SystemClock.uptimeMillis() - lastScanAt
        if (allowMaxWait && since > MAX_WAIT_MS) main.post(scanRunnable) else main.postDelayed(scanRunnable, delayMs)
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
                    val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                    if (r.height() > screenH / 2) return null
                }
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val root = w.root ?: continue
                    return if ((root.packageName?.toString() ?: "") in WhatsAppParser.PACKAGES) root else null
                }
            }
        }
        val r = rootInActiveWindow
        return if ((r?.packageName?.toString() ?: "") in WhatsAppParser.PACKAGES) r else null
    }

    private fun doScan() {
        lastScanAt = SystemClock.uptimeMillis()
        val root = findWhatsAppRoot()
        if (root == null) {
            if (inWhatsApp) {
                inWhatsApp = false
                overlay.clearFrames()
                overlay.setVisible(false)
            }
            return
        }
        if (!inWhatsApp) {
            inWhatsApp = true
            overlay.ensureAttached()
            overlay.setVisible(true)
        }
        val result = runCatching { WhatsAppParser.scan(root) }.getOrElse { e ->
            ScanResult(false, null, null, null, null, 0, emptyList(), false, 0, "Chyba: ${e.javaClass.simpleName}: ${e.message}")
        }
        SpikeStats.record(result)
        if (pendingSettle && result.isConversation) {
            SpikeStats.addSettle(SystemClock.uptimeMillis() - lastScrollAt)
            pendingSettle = false
        }
        overlay.render(result, framesOn)
    }

    override fun onDumpClicked() {
        val root = findWhatsAppRoot() ?: rootInActiveWindow ?: return toast("Žádné okno k uložení")
        val ws = runCatching { windows }.getOrDefault(emptyList())
        val scan = SpikeStats.lastResult
        val mask = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(MainActivity.KEY_MASK, true)
        toast("Ukládám strukturu…")
        Thread {
            val msg = runCatching {
                val dumper = TreeDumper(this, mask)
                val path = dumper.save(dumper.buildDump(root, ws, scan))
                SpikeStats.lastDump = path
                "Uloženo: $path"
            }.getOrElse { e -> "Dump selhal: ${e.message}" }
            main.post { toast(msg) }
        }.start()
    }

    override fun onSetTextClicked() {
        val root = findWhatsAppRoot() ?: return toast("WhatsApp není v popředí")
        val found = WhatsAppParser.findEntry(root) ?: run {
            SpikeStats.lastSetText = "vstupní pole nenalezeno"
            return toast("Vstupní pole nenalezeno")
        }
        val (node, how) = found
        if (node.isPassword) return toast("Pole je typu heslo – ignoruji")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TEST_TEXT)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        SpikeStats.lastSetText = "${if (ok) "OK" else "SELHALO"} ($how)"
        toast(if (ok) "Text vložen (neodesláno)" else "ACTION_SET_TEXT selhalo")
    }

    override fun onModeClicked() {
        mode = if (mode == Mode.FOLLOW) Mode.HIDE_ON_SCROLL else Mode.FOLLOW
        SpikeStats.mode = mode.label
        overlay.updateStatus(SpikeStats.lastResult)
        toast("Režim scrollu: ${mode.label}")
    }

    override fun onFramesClicked() {
        framesOn = !framesOn
        SpikeStats.lastResult?.let { overlay.render(it, framesOn) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CONTENT_DEBOUNCE_MS = 60L
        private const val SCROLL_DEBOUNCE_MS = 80L
        private const val HIDE_IDLE_MS = 150L
        private const val MAX_WAIT_MS = 250L
        private const val TEST_TEXT = "ChatLingo test ✓ Mirëdita! 👋"
    }
}
