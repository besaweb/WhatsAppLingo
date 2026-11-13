package com.chatlingo.spike

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TreeDumper(private val context: Context, private val maskText: Boolean) {

    private fun mask(s: CharSequence?): String? {
        if (s == null) return null
        val str = s.toString().take(500)
        if (!maskText) return str
        return buildString(str.length) {
            str.forEach { c -> append(if (c.isLetterOrDigit()) 'x' else c) }
        }
    }

    private fun Rect.str() = "$left,$top,$right,$bottom"

    private fun flags(n: AccessibilityNodeInfo) = buildString {
        if (n.isClickable) append('c')
        if (n.isLongClickable) append('l')
        if (n.isScrollable) append('s')
        if (n.isVisibleToUser) append('v')
        if (n.isEditable) append('e')
        if (n.isPassword) append('p')
        if (n.isFocusable) append('f')
        if (n.isImportantForAccessibility) append('i')
        if (Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive) append('D')
    }

    private fun nodeJson(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): JSONObject {
        budget[0]--
        val o = JSONObject()
        o.put("cls", n.className?.toString())
        n.viewIdResourceName?.let { o.put("id", it) }
        mask(n.text)?.let { o.put("text", it) }
        mask(n.contentDescription)?.let { o.put("desc", it) }
        o.put("b", n.bounds().str())
        o.put("f", flags(n))
        if (n.childCount > 0) {
            o.put("n", n.childCount)
            if (depth < 60 && budget[0] > 0) {
                val arr = JSONArray()
                for (i in 0 until n.childCount) {
                    if (budget[0] <= 0) break
                    val ch = n.getChild(i) ?: continue
                    arr.put(nodeJson(ch, depth + 1, budget))
                }
                o.put("ch", arr)
            }
        }
        return o
    }

    @Suppress("DEPRECATION")
    private fun appVersion(pkg: String): String? = runCatching {
        val pi = context.packageManager.getPackageInfo(pkg, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrNull()

    private fun windowType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
        else -> "TYPE_$t"
    }

    fun buildDump(
        root: AccessibilityNodeInfo,
        windows: List<AccessibilityWindowInfo>,
        scan: ScanResult?,
    ): JSONObject {
        val dm = context.resources.displayMetrics
        val meta = JSONObject().apply {
            put("time", Date().toString())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("sdk", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("screen", "${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
            put("rootPackage", root.packageName?.toString())
            WhatsAppParser.PACKAGES.forEach { p -> appVersion(p)?.let { put("version_$p", it) } }
            put("textMasked", maskText)
        }
        val wins = JSONArray()
        windows.forEach { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            wins.put(JSONObject().apply {
                put("type", windowType(w.type))
                put("layer", w.layer)
                put("active", w.isActive)
                put("focused", w.isFocused)
                put("b", r.str())
                put("title", w.title?.toString())
                put("pkg", runCatching { w.root?.packageName?.toString() }.getOrNull())
            })
        }
        val bubbles = JSONArray()
        scan?.bubbles?.forEach { b ->
            bubbles.put(JSONObject().apply {
                put("text", mask(b.text))
                put("dir", b.direction.name)
                put("dirMarker", b.dirByMarker.name)
                put("dirPosition", b.dirByPosition.name)
                put("textSource", b.textSource)
                put("bubbleSource", b.bubbleSource)
                put("textBounds", b.textBounds.str())
                put("bubbleBounds", b.bubbleBounds.str())
                put("partial", b.partial)
            })
        }
        val scanJson = JSONObject().apply {
            put("isConversation", scan?.isConversation)
            put("entryHow", scan?.entryHow)
            put("listId", scan?.listId)
            put("listClass", scan?.listClass)
            put("viewport", scan?.viewport?.str())
            put("itemCount", scan?.itemCount)
            put("markerSeen", scan?.markerSeen)
            put("durationMs", scan?.durationMs)
            put("note", scan?.note)
            put("bubbles", bubbles)
        }
        val budget = intArrayOf(4000)
        return JSONObject().apply {
            put("meta", meta)
            put("stats", SpikeStats.toJson())
            put("windows", wins)
            put("scan", scanJson)
            put("tree", nodeJson(root, 0, budget))
            put("treeTruncated", budget[0] <= 0)
        }
    }

    fun save(json: JSONObject): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "chatlingo_dump_$ts.json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ChatLingoSpike")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Nelze vytvořit soubor v Downloads")
        resolver.openOutputStream(uri)?.use { it.write(json.toString(1).toByteArray()) }
            ?: error("Nelze zapsat soubor")
        return "Download/ChatLingoSpike/$name"
    }
}
