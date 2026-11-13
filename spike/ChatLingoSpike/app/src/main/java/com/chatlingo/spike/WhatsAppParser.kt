package com.chatlingo.spike

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

enum class Direction { IN, OUT, UNKNOWN }

data class DetectedBubble(
    val text: String,
    val textBounds: Rect,
    val bubbleBounds: Rect,
    val direction: Direction,
    val dirByMarker: Direction,
    val dirByPosition: Direction,
    val textSource: String,
    val bubbleSource: String,
    val partial: Boolean,
)

data class ScanResult(
    val isConversation: Boolean,
    val entryHow: String?,
    val viewport: Rect?,
    val listId: String?,
    val listClass: String?,
    val itemCount: Int,
    val bubbles: List<DetectedBubble>,
    val markerSeen: Boolean,
    val durationMs: Long,
    val note: String?,
)

fun AccessibilityNodeInfo.shortId(): String? =
    viewIdResourceName?.substringAfter(":id/", "")?.takeIf { it.isNotEmpty() }

fun AccessibilityNodeInfo.bounds(): Rect = Rect().also { getBoundsInScreen(it) }

/** Projde podstrom; visit vrací false = nevstupovat do potomků daného uzlu. */
fun walk(
    node: AccessibilityNodeInfo,
    depth: Int = 0,
    maxDepth: Int = 40,
    visit: (AccessibilityNodeInfo, Int) -> Boolean,
) {
    if (!visit(node, depth) || depth >= maxDepth) return
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        walk(child, depth + 1, maxDepth, visit)
    }
}

object WhatsAppParser {
    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private val TEXT_IDS = setOf("message_text")
    private val OUT_MARKER_IDS = setOf("status")
    private val ENTRY_IDS = listOf("entry")
    private val META_IDS = setOf(
        "date", "status", "name_in_group", "forwarded", "edited",
        "conversation_row_date_divider", "unread_divider_tv",
    )

    fun findEntry(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        val pkgs = buildList {
            root.packageName?.toString()?.let { add(it) }
            addAll(PACKAGES)
        }.distinct()
        for (pkg in pkgs) for (id in ENTRY_IDS) {
            val found = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
            found.firstOrNull { it.isVisibleToUser }?.let { return it to "id:$pkg:id/$id" }
        }
        var fallback: AccessibilityNodeInfo? = null
        walk(root) { n, _ ->
            if (fallback == null && n.isEditable && !n.isPassword && n.isVisibleToUser) fallback = n
            fallback == null
        }
        return fallback?.let { it to "fallback:editable(${it.viewIdResourceName})" }
    }

    private fun findConversationList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0L
        walk(root, maxDepth = 30) { n, _ ->
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("ListView") || cls.contains("RecyclerView")) {
                val r = n.bounds()
                var score = r.width().toLong() * r.height()
                if (n.isScrollable) score *= 2
                if (score > bestScore) {
                    best = n
                    bestScore = score
                }
                false
            } else {
                true
            }
        }
        return best
    }

    fun scan(root: AccessibilityNodeInfo): ScanResult {
        val t0 = SystemClock.uptimeMillis()
        val entry = findEntry(root)
        val list = findConversationList(root)
        if (entry == null || list == null) {
            return ScanResult(
                isConversation = false,
                entryHow = entry?.second,
                viewport = null,
                listId = list?.viewIdResourceName,
                listClass = list?.className?.toString(),
                itemCount = 0,
                bubbles = emptyList(),
                markerSeen = false,
                durationMs = SystemClock.uptimeMillis() - t0,
                note = if (entry == null) "Není otevřený chat (vstupní pole nenalezeno)" else "Seznam zpráv nenalezen",
            )
        }
        val viewport = list.bounds()
        val raw = mutableListOf<RawItem>()
        var markerSeen = false
        for (i in 0 until list.childCount) {
            val item = list.getChild(i) ?: continue
            val parsed = parseItem(item, viewport) ?: continue
            if (parsed.hasMarker) markerSeen = true
            raw += parsed
        }
        val bubbles = raw.map { r ->
            val byMarker = when {
                !markerSeen -> Direction.UNKNOWN
                r.hasMarker -> Direction.OUT
                else -> Direction.IN
            }
            val final = if (byMarker != Direction.UNKNOWN) byMarker else r.byPosition
            DetectedBubble(
                text = r.text,
                textBounds = r.textBounds,
                bubbleBounds = r.bubbleBounds,
                direction = final,
                dirByMarker = byMarker,
                dirByPosition = r.byPosition,
                textSource = r.textSource,
                bubbleSource = r.bubbleSource,
                partial = !viewport.contains(r.bubbleBounds),
            )
        }
        return ScanResult(
            isConversation = true,
            entryHow = entry.second,
            viewport = viewport,
            listId = list.viewIdResourceName,
            listClass = list.className?.toString(),
            itemCount = list.childCount,
            bubbles = bubbles,
            markerSeen = markerSeen,
            durationMs = SystemClock.uptimeMillis() - t0,
            note = null,
        )
    }

    private class RawItem(
        val text: String,
        val textBounds: Rect,
        val bubbleBounds: Rect,
        val bubbleSource: String,
        val textSource: String,
        val hasMarker: Boolean,
        val byPosition: Direction,
    )

    private fun parseItem(item: AccessibilityNodeInfo, viewport: Rect): RawItem? {
        val byId = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var hasMarker = false
        walk(item, maxDepth = 20) { n, _ ->
            val id = n.shortId()
            if (id != null && id.contains("quoted")) return@walk false
            if (id != null && id in OUT_MARKER_IDS) hasMarker = true
            val txt = n.text
            if (!txt.isNullOrBlank()) {
                if (id != null && id in TEXT_IDS) {
                    byId += n
                } else if (n.className?.toString()?.contains("TextView") == true && (id == null || id !in META_IDS)) {
                    candidates += n
                }
            }
            true
        }
        val (textNode, source) = when {
            byId.isNotEmpty() -> byId.first() to "id"
            candidates.isNotEmpty() -> candidates.maxBy { it.text?.length ?: 0 } to "fallback"
            else -> return null
        }
        val textBounds = textNode.bounds()
        if (textBounds.width() <= 0 || textBounds.height() <= 0) return null

        var bubble = Rect(textBounds)
        var bubbleSource = "text"
        var cur = textNode.parent
        var steps = 0
        while (cur != null && steps < 8) {
            val r = cur.bounds()
            if (r.width() > viewport.width() * 0.92f) break
            if (r.contains(textBounds)) {
                bubble = r
                bubbleSource = "ancestor+$steps(${cur.shortId() ?: cur.className})"
            }
            if (cur == item) break
            cur = cur.parent
            steps++
        }

        val leftGap = bubble.left - viewport.left
        val rightGap = viewport.right - bubble.right
        val byPosition = when {
            abs(leftGap - rightGap) < viewport.width() * 0.08f -> Direction.UNKNOWN
            leftGap < rightGap -> Direction.IN
            else -> Direction.OUT
        }
        return RawItem(
            text = textNode.text.toString(),
            textBounds = textBounds,
            bubbleBounds = bubble,
            bubbleSource = bubbleSource,
            textSource = source,
            hasMarker = hasMarker,
            byPosition = byPosition,
        )
    }
}
