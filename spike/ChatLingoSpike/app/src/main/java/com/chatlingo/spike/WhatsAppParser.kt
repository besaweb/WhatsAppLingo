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

    private val TEXT_IDS = setOf("message_text", "caption")
    private val OUT_MARKER_IDS = setOf("status")

    /** Skupinový chat: jméno odesílatele / kolečko s iniciálou se zobrazují jen u příchozích zpráv. */
    private val IN_CUE_IDS = setOf("name_in_group", "name_in_group_tv", "group_profile_initials", "group_profile_pic")

    /** Podstromy, které nenesou text zprávy ani značku; nevstupujeme do nich kvůli rychlosti. */
    private val SKIP_SUBTREE_IDS = setOf(
        "reactions_bubble_layout", "media_container_wrapper", "action_button",
    )
    private val ENTRY_IDS = listOf("entry")
    private val META_IDS = setOf(
        "date", "status", "name_in_group", "name_in_group_tv", "secondary_name_in_group_tv",
        "forwarded", "edited", "url",
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
        // Rychlá cesta: jedno vyhledání podle id místo průchodu celým stromem.
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/list")
            .filter { it.isVisibleToUser }
            .maxByOrNull { val r = it.bounds(); r.width().toLong() * r.height() }
        if (byId != null) return byId
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
        // Bez vstupního pole nejde o chat; seznam pak vůbec nehledáme (šetří stovky ms na seznamu chatů).
        val list = if (entry != null) findConversationList(root) else null
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
        if (markerSeen) SpikeStats.markerEverSeen = true
        val markerKnown = markerSeen || SpikeStats.markerEverSeen
        val bubbles = raw.map { r ->
            // Bublina uříznutá o okraj seznamu: horní/dolní hrana leží na hraně seznamu.
            val partial = !viewport.contains(r.bubbleBounds) ||
                r.bubbleBounds.top <= viewport.top + 1 || r.bubbleBounds.bottom >= viewport.bottom - 1
            val byMarker = when {
                r.hasMarker -> Direction.OUT
                r.hasInCue -> Direction.IN
                // Značka odchozích se může skrývat v odříznuté části, proto u částečných bublin nehádáme.
                markerKnown && !partial -> Direction.IN
                else -> Direction.UNKNOWN
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
                partial = partial,
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
        val hasInCue: Boolean,
        val byPosition: Direction,
    )

    private fun parseItem(item: AccessibilityNodeInfo, viewport: Rect): RawItem? {
        val byId = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var hasMarker = false
        var hasInCue = false
        var mainLayout: AccessibilityNodeInfo? = null
        walk(item, maxDepth = 20) { n, _ ->
            val id = n.shortId()
            if (id != null && id.contains("quoted")) return@walk false
            if (id != null && id in SKIP_SUBTREE_IDS) return@walk false
            if (id == "main_layout" && mainLayout == null) mainLayout = n
            if (id != null && id in OUT_MARKER_IDS) hasMarker = true
            if (id != null && id in IN_CUE_IDS) hasInCue = true
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
        // Rychlá cesta: main_layout je přesná bublina (bez dalších volání nadřazených uzlů).
        val ml = mainLayout?.bounds()
        var cur = if (ml != null && ml.contains(textBounds) && ml.width() <= viewport.width() * 0.92f) {
            bubble = ml
            bubbleSource = "main_layout"
            null
        } else {
            textNode.parent
        }
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

        // Odchozí bubliny jsou přilepené k pravému okraji (~3 % šířky). Příchozí začínají vlevo
        // (1:1 ~3 %, skupina ~13 % kvůli avatarům) a vpravo nechávají víc místa než odchozí.
        val w = viewport.width().toFloat()
        val leftGap = bubble.left - viewport.left
        val rightGap = viewport.right - bubble.right
        val byPosition = when {
            rightGap <= w * 0.07f -> Direction.OUT
            leftGap <= w * 0.20f -> Direction.IN
            else -> Direction.UNKNOWN
        }
        return RawItem(
            text = textNode.text.toString(),
            textBounds = textBounds,
            bubbleBounds = bubble,
            bubbleSource = bubbleSource,
            textSource = source,
            hasMarker = hasMarker,
            hasInCue = hasInCue,
            byPosition = byPosition,
        )
    }
}
