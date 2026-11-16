package com.chatlingo.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Panel s překladem psané zprávy nad polem pro psaní. Má tlačítka „Vložit překlad“ a „Zavřít“.
 * Okno není fokusovatelné, takže klávesnice zůstává otevřená. Služba nikdy neodesílá zprávu:
 * překlad se jen vloží do pole a odeslat musí uživatel sám.
 */
class OutgoingPanel(
    private val service: AccessibilityService,
    private val onInsert: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var header: TextView? = null
    private var body: TextView? = null
    private var attached = false

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun build(): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(0xF01F2C34.toInt())
            cornerRadius = 10f * density
            setStroke(dp(2), 0xFF25D366.toInt())
        }
        val col = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val h = TextView(service).apply {
            textSize = 11f
            setTextColor(0xFF8696A0.toInt())
        }
        val b = TextView(service).apply {
            textSize = 16f
            setTextColor(0xFFE9EDEF.toInt())
            maxLines = 6
            setPadding(0, dp(2), 0, dp(6))
        }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, bgColor: Int, action: () -> Unit) = TextView(service).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
        }
        row.addView(button("Vložit překlad", 0xFF25D366.toInt()) { onInsert() })
        row.addView(button("Zavřít", 0xFF3C4043.toInt()) { onClose() })
        col.addView(h)
        col.addView(b)
        col.addView(row)
        header = h
        body = b
        return col
    }

    /** Zobrazí překlad nad polem pro psaní ([entryBounds] = hranice pole v souřadnicích obrazovky). */
    fun show(translation: String, label: String, entryBounds: Rect) {
        val view = root ?: build().also { root = it }
        header?.text = label
        body?.text = translation

        val margin = dp(12)
        val width = service.resources.displayMetrics.widthPixels - 2 * margin
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val desiredTop = (entryBounds.top - dp(10) - view.measuredHeight).coerceAtLeast(dp(90))

        val lp = params ?: WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }.also { params = it }
        lp.width = width
        lp.x = margin
        lp.y = desiredTop

        if (attached) wm.updateViewLayout(view, lp) else {
            wm.addView(view, lp)
            attached = true
        }
        // Souřadnice okna se mohou lišit o výšku stavového řádku: po rozložení polohu dorovnáme.
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val diff = desiredTop - loc[1]
            if (abs(diff) > 2 && attached) {
                lp.y += diff
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
    }

    fun hide() {
        val view = root ?: return
        if (attached) {
            runCatching { wm.removeView(view) }
            attached = false
        }
    }

    fun detach() = hide()
}
