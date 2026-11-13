package com.chatlingo.spike

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayController(
    private val service: AccessibilityService,
    private val listener: Listener,
) {
    interface Listener {
        fun onDumpClicked()
        fun onSetTextClicked()
        fun onModeClicked()
        fun onFramesClicked()
    }

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var frames: FramesView? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var buttonsBox: LinearLayout? = null
    private var visible = false

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    fun ensureAttached() {
        if (frames != null) return
        val fv = FramesView(service)
        val fp = WindowManager.LayoutParams(
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
        wm.addView(fv, fp)
        frames = fv

        val p = buildPanel()
        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (service.resources.displayMetrics.heightPixels * 0.30f).toInt()
        }
        wm.addView(p, pp)
        panel = p
        panelParams = pp
        visible = true
    }

    fun detach() {
        frames?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        frames = null
        panel = null
        visible = false
    }

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        val vis = if (v) View.VISIBLE else View.GONE
        frames?.visibility = vis
        panel?.visibility = vis
    }

    fun render(result: ScanResult, framesOn: Boolean) {
        val fv = frames ?: return
        fv.bubbles = result.bubbles
        fv.viewport = result.viewport
        fv.shiftY = 0f
        fv.visibility = if (framesOn && visible) View.VISIBLE else View.INVISIBLE
        fv.invalidate()
        updateStatus(result)
    }

    fun shiftBy(dy: Int) {
        val fv = frames ?: return
        fv.shiftY += dy
        fv.invalidate()
    }

    fun hideFramesTemporarily() {
        frames?.visibility = View.INVISIBLE
    }

    fun clearFrames() {
        frames?.let {
            it.bubbles = emptyList()
            it.viewport = null
            it.invalidate()
        }
    }

    fun updateStatus(result: ScanResult?) {
        val sv = statusView ?: return
        sv.text = if (result == null) {
            "—\n${SpikeStats.mode}"
        } else if (!result.isConversation) {
            "mimo chat\n${result.durationMs} ms\n${SpikeStats.mode}"
        } else {
            val o = result.bubbles.count { it.direction == Direction.OUT }
            val i = result.bubbles.count { it.direction == Direction.IN }
            "${result.bubbles.size} bub\nIN $i OUT $o\n${result.durationMs} ms\n${SpikeStats.mode}"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC202124.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val handle = TextView(service).apply {
            text = "≡ CL"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(handle)

        val box = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(service).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        box.addView(status)
        fun btn(label: String, action: () -> Unit) = TextView(service).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF3C4043.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        box.addView(btn("DUMP") { listener.onDumpClicked() })
        box.addView(btn("SET TEXT") { listener.onSetTextClicked() })
        box.addView(btn("REŽIM") { listener.onModeClicked() })
        box.addView(btn("RÁMY") { listener.onFramesClicked() })
        root.addView(box)

        statusView = status
        buttonsBox = box
        updateStatus(null)

        var downRawY = 0f
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, ev ->
            val params = panelParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = ev.rawY
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downRawY
                    if (abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        panel?.let { wm.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }
        return root
    }
}

class FramesView(ctx: Context) : View(ctx) {
    var bubbles: List<DetectedBubble> = emptyList()
    var viewport: Rect? = null
    var shiftY = 0f

    private val loc = IntArray(2)
    private val dash = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    private fun stroke(c: Int, w: Float = 5f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w
        color = c
    }
    private val pIn = stroke(Color.rgb(0, 200, 83))
    private val pOut = stroke(Color.rgb(41, 121, 255))
    private val pUnknown = stroke(Color.rgb(255, 145, 0))
    private val pViewport = stroke(Color.argb(160, 255, 0, 255), 3f).apply { pathEffect = dash }
    private val labelBg = Paint().apply { color = 0xB0000000.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val vp = viewport
        if (vp != null) {
            canvas.drawRect(vp, pViewport)
            canvas.clipRect(vp)
        }
        canvas.translate(0f, shiftY)
        for (b in bubbles) {
            val p = when (b.direction) {
                Direction.IN -> pIn
                Direction.OUT -> pOut
                Direction.UNKNOWN -> pUnknown
            }
            p.pathEffect = if (b.partial) dash else null
            canvas.drawRect(b.bubbleBounds, p)
            val label = buildString {
                append(b.direction.name)
                append(" m:").append(b.dirByMarker.name.first())
                append(" p:").append(b.dirByPosition.name.first())
                if (b.textSource != "id") append(" FB")
            }
            val w = labelText.measureText(label)
            val x = b.bubbleBounds.left.toFloat()
            val y = b.bubbleBounds.top.toFloat()
            canvas.drawRect(x, y, x + w + 12f, y + 30f, labelBg)
            canvas.drawText(label, x + 6f, y + 23f, labelText)
        }
        canvas.restore()
    }
}
