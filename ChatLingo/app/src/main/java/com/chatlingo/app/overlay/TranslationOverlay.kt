package com.chatlingo.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/** Překlad, který se má zobrazit pod bublinou s hranicemi [anchor]. */
data class Panel(val anchor: Rect, val text: String)

/**
 * Panely s překladem příchozích zpráv. Každý panel je malé okno (TYPE_ACCESSIBILITY_OVERLAY)
 * s nejvýše [VISIBLE_LINES] řádky; delší překlad se v panelu posouvá prstem.
 * Panel je jen překryv: zakrývá to, co je pod bublinou. Krátký panel dotyky nepřijímá
 * (propadají do WhatsAppu), posuvný panel je přijímá jen ve své ploše.
 */
class TranslationOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val pad = (8 * density).toInt()
    private val gap = (3 * density).toInt()
    private val arrowH = (8 * density).toInt()
    private val pool = ArrayList<PanelWindow>()

    /** Rozdíl mezi požadovanou a skutečnou polohou okna (např. stavový řádek), zjistí se po prvním zobrazení. */
    private var yCorrection = 0

    private inner class PanelWindow {
        val text = TextView(service).apply {
            setTextColor(0xFFE9EDEF.toInt())
            textSize = 14f
        }
        val scroll = ScrollView(service).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((16 * density).toInt())
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xF01F2C34.toInt())
                cornerRadius = 8 * density
                setStroke((1.5f * density).toInt().coerceAtLeast(1), 0xFF25D366.toInt())
            }
            addView(text)
        }

        /** Šipka dolů pod panelem, když je panel nad bublinou (ukazuje na zprávu, ke které patří). */
        val arrow = ArrowView(service, density).apply { visibility = View.GONE }
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(arrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, arrowH))
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags(touchable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        var attached = false
        var shownText: String? = null
    }

    private fun flags(touchable: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    /** Ponecháno kvůli kompatibilitě se službou; okna se vytvářejí až v [show]. */
    fun ensureAttached() = Unit

    fun detach() {
        for (pw in pool) if (pw.attached) runCatching { wm.removeView(pw.root) }
        pool.clear()
    }

    /** Zobrazí panely. Okna navíc se odeberou. */
    fun show(panels: List<Panel>, viewport: Rect?) {
        while (pool.size < panels.size) pool += PanelWindow()
        panels.forEachIndexed { i, p -> place(pool[i], p, viewport) }
        for (i in panels.size until pool.size) remove(pool[i])
    }

    /** Skrytí během scrollu: obsah se schová a okna přestanou přijímat dotyky, aby nebránila scrollu. */
    fun hide() {
        for (pw in pool) {
            if (!pw.attached) continue
            pw.root.visibility = View.INVISIBLE
            setFlags(pw, touchable = false)
        }
    }

    fun clear() {
        for (pw in pool) remove(pw)
    }

    private fun remove(pw: PanelWindow) {
        if (pw.attached) {
            runCatching { wm.removeView(pw.root) }
            pw.attached = false
            pw.shownText = null
        }
    }

    private fun setFlags(pw: PanelWindow, touchable: Boolean) {
        val f = flags(touchable)
        if (pw.params.flags != f) {
            pw.params.flags = f
            if (pw.attached) runCatching { wm.updateViewLayout(pw.root, pw.params) }
        }
    }

    private fun place(pw: PanelWindow, p: Panel, vp: Rect?) {
        val width = p.anchor.width()
        val textWidth = width - 2 * pad
        if (textWidth < 40 * density) return remove(pw)

        if (pw.shownText != p.text) {
            pw.text.text = p.text
            pw.scroll.scrollTo(0, 0)
            pw.shownText = p.text
        }
        pw.text.measure(
            View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullHeight = pw.text.measuredHeight
        val maxHeight = pw.text.lineHeight * VISIBLE_LINES
        val overflow = fullHeight > maxHeight + pw.text.lineHeight / 2
        var height = min(fullHeight, maxHeight) + 2 * pad

        // Pod bublinou, a když tam není místo (poslední zpráva nad polem pro psaní), nad ní se šipkou dolů.
        val minHeight = pw.text.lineHeight + 2 * pad
        var top = p.anchor.bottom + gap
        var above = false
        if (vp != null) {
            val spaceBelow = vp.bottom - top
            val spaceAbove = p.anchor.top - gap - vp.top
            if (height > spaceBelow) {
                if (height + arrowH <= spaceAbove || spaceAbove > spaceBelow) {
                    above = true
                    height = min(height, (spaceAbove - arrowH).coerceAtLeast(minHeight))
                    top = p.anchor.top - gap - height - arrowH
                } else {
                    height = min(height, spaceBelow.coerceAtLeast(minHeight))
                }
            }
        }
        val windowHeight = height + if (above) arrowH else 0
        if (vp != null) top = top.coerceIn(vp.top, (vp.bottom - windowHeight).coerceAtLeast(vp.top))

        val lp = pw.params
        val newY = top + yCorrection
        val changed = lp.x != p.anchor.left || lp.y != newY || lp.width != width || lp.height != windowHeight
        lp.x = p.anchor.left
        lp.y = newY
        lp.width = width
        lp.height = windowHeight
        pw.arrow.visibility = if (above) View.VISIBLE else View.GONE
        pw.root.visibility = View.VISIBLE

        if (!pw.attached) {
            lp.flags = flags(overflow)
            runCatching { wm.addView(pw.root, lp) }.onSuccess { pw.attached = true }
            if (pw.attached) correctPosition(pw, top)
        } else {
            val f = flags(overflow)
            if (changed || lp.flags != f) {
                lp.flags = f
                runCatching { wm.updateViewLayout(pw.root, lp) }
            }
        }
    }

    /** Po prvním rozložení porovná skutečnou polohu okna s požadovanou a rozdíl si zapamatuje. */
    private fun correctPosition(pw: PanelWindow, desiredTop: Int) {
        pw.root.post {
            if (!pw.attached) return@post
            val loc = IntArray(2)
            pw.root.getLocationOnScreen(loc)
            val diff = desiredTop - loc[1]
            if (abs(diff) > 2) {
                yCorrection += diff
                pw.params.y += diff
                runCatching { wm.updateViewLayout(pw.root, pw.params) }
            }
        }
    }

    private companion object {
        const val VISIBLE_LINES = 5
    }
}

/** Zelený trojúhelník mířící dolů, 24 dp od levého okraje panelu. */
private class ArrowView(ctx: Context, private val density: Float) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF25D366.toInt() }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val x = 24f * density
        val s = 8f * density
        path.reset()
        path.moveTo(x - s, 0f)
        path.lineTo(x + s, 0f)
        path.lineTo(x, height.toFloat())
        path.close()
        canvas.drawPath(path, paint)
    }
}
