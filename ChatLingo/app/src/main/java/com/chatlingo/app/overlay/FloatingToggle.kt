package com.chatlingo.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.chatlingo.app.AppSettings
import kotlin.math.hypot

/**
 * Malé plovoucí tlačítko, které je vidět jen nad WhatsAppem. Klepnutí zapne/vypne automatický
 * překlad, tažením se dá přesunout (po puštění se přichytí k bližšímu okraji, poloha se pamatuje).
 */
class FloatingToggle(
    private val service: AccessibilityService,
    private val settings: AppSettings,
    private val onToggle: () -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var shownEnabled: Boolean? = null

    private fun dp(v: Int): Int = (v * density).toInt()
    private val size get() = dp(48)

    fun show(enabled: Boolean) {
        val v = view ?: build().also { view = it }
        if (shownEnabled != enabled) {
            style(v, enabled)
            shownEnabled = enabled
        }
        if (!attached) {
            runCatching { wm.addView(v, layoutParams()) }.onSuccess { attached = true }
        }
    }

    fun hide() {
        val v = view ?: return
        if (attached) {
            runCatching { wm.removeView(v) }
            attached = false
        }
    }

    fun detach() = hide()

    private fun layoutParams(): WindowManager.LayoutParams = params ?: WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val dm = service.resources.displayMetrics
        x = settings.toggleX.takeIf { it >= 0 } ?: (dm.widthPixels - size - dp(8))
        y = settings.toggleY.takeIf { it >= 0 } ?: (dm.heightPixels * 0.35f).toInt()
    }.also { params = it }

    private fun style(v: TextView, enabled: Boolean) {
        (v.background as? GradientDrawable)?.setColor(if (enabled) 0xFF25D366.toInt() else 0xFF5F6368.toInt())
        v.paintFlags = if (enabled) {
            v.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            v.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        v.alpha = if (enabled) 1f else 0.8f
        v.contentDescription = if (enabled) "ChatLingo: překlad zapnutý" else "ChatLingo: překlad vypnutý"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): TextView = TextView(service).apply {
        text = "CL"
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.WHITE)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        setOnTouchListener { v, ev ->
            val p = params ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(8)) moved = true
                    if (moved) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // Přichytit k bližšímu okraji a zapamatovat polohu.
                        val w = service.resources.displayMetrics.widthPixels
                        p.x = if (p.x + size / 2 < w / 2) dp(8) else w - size - dp(8)
                        runCatching { wm.updateViewLayout(v, p) }
                        settings.toggleX = p.x
                        settings.toggleY = p.y
                    } else {
                        onToggle()
                    }
                }
            }
            true
        }
    }
}
