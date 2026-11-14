package com.chatlingo.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Překlad, který se má zobrazit pod bublinou s hranicemi [anchor]. */
data class Panel(val anchor: Rect, val text: String)

/**
 * Jedno celoobrazovkové okno bez dotyků (TYPE_ACCESSIBILITY_OVERLAY). Panel je jen překryv:
 * zakrývá to, co je pod bublinou. Dotyky propadají do WhatsAppu.
 */
class TranslationOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: PanelsView? = null

    fun ensureAttached() {
        if (view != null) return
        val v = PanelsView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        wm.addView(v, lp)
        view = v
    }

    fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** Zobrazí panely. Prázdný seznam = nic se nekreslí. */
    fun show(panels: List<Panel>, viewport: Rect?) {
        val v = view ?: return
        v.setPanels(panels, viewport)
        v.visibility = View.VISIBLE
    }

    /** Skrytí během scrollu (výchozí a spolehlivé chování, viz Etapa 1b). */
    fun hide() {
        view?.visibility = View.INVISIBLE
    }

    fun clear() {
        view?.setPanels(emptyList(), null)
    }
}

private class PanelsView(ctx: Context) : View(ctx) {

    private class Drawn(val rect: RectF, val layout: StaticLayout)

    private var drawn: List<Drawn> = emptyList()
    private var viewport: Rect? = null
    private val loc = IntArray(2)

    private val density = ctx.resources.displayMetrics.density
    private val pad = 8f * density
    private val gap = 3f * density
    private val radius = 8f * density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF01F2C34.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF25D366.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9EDEF.toInt()
        textSize = 14f * ctx.resources.displayMetrics.scaledDensity
    }

    fun setPanels(panels: List<Panel>, vp: Rect?) {
        viewport = vp
        drawn = panels.mapNotNull { p ->
            val width = (p.anchor.width() - 2 * pad).toInt()
            if (width < 40 * density) return@mapNotNull null
            val layout = StaticLayout.Builder
                .obtain(p.text, 0, p.text.length, textPaint, width)
                .setMaxLines(MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            val top = p.anchor.bottom + gap
            val rect = RectF(
                p.anchor.left.toFloat(),
                top,
                p.anchor.right.toFloat(),
                top + layout.height + 2 * pad,
            )
            Drawn(rect, layout)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        viewport?.let { canvas.clipRect(it) }
        for (d in drawn) {
            canvas.drawRoundRect(d.rect, radius, radius, bg)
            canvas.drawRoundRect(d.rect, radius, radius, border)
            canvas.save()
            canvas.translate(d.rect.left + pad, d.rect.top + pad)
            d.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private companion object {
        const val MAX_LINES = 3
    }
}
