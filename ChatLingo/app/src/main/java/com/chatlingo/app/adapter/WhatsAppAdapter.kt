package com.chatlingo.app.adapter

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.chatlingo.app.model.Direction
import com.chatlingo.app.model.ExtractionResult
import com.chatlingo.app.model.VisibleMessage

/**
 * Selektory ověřené spikem (WhatsApp 2.26.44.90). Do budoucna patří do konfigurace
 * (assets/adapters/whatsapp.json), aby šly aktualizovat bez nové verze aplikace.
 */
object WhatsAppSelectors {
    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    const val LIST_ID = "android:id/list"
    const val ENTRY = "entry"
    const val BUBBLE = "main_layout"
    const val OUT_MARKER = "status"
    const val QUOTED_PREFIX = "quoted"
    val TEXT_IDS = setOf("message_text", "caption")

    /** Jméno odesílatele nebo avatar ve skupině se zobrazují jen u příchozích zpráv. */
    val IN_CUES = setOf("name_in_group", "name_in_group_tv", "group_profile_initials", "group_profile_pic")

    /** Podstromy bez textu zprávy; do nich se nevstupuje (rychlost). */
    val SKIP_SUBTREE = setOf("reactions_bubble_layout", "media_container_wrapper", "action_button")

    /** Texty, které nejsou obsahem zprávy (použito u záložního hledání textu). */
    val META_IDS = setOf(
        "date", "status", "name_in_group", "name_in_group_tv", "secondary_name_in_group_tv",
        "forwarded", "edited", "url", "conversation_row_date_divider", "unread_divider_tv",
    )
}

private fun AccessibilityNodeInfo.shortId(): String? =
    viewIdResourceName?.substringAfter(":id/", "")?.takeIf { it.isNotEmpty() }

private fun AccessibilityNodeInfo.bounds(): Rect = Rect().also { getBoundsInScreen(it) }

private fun walk(
    node: AccessibilityNodeInfo,
    depth: Int = 0,
    maxDepth: Int = 20,
    visit: (AccessibilityNodeInfo) -> Boolean,
) {
    if (!visit(node) || depth >= maxDepth) return
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        walk(child, depth + 1, maxDepth, visit)
    }
}

/**
 * Čtení zpráv z okna WhatsAppu. Nikdy nic neklikne a nic nemění.
 * Instance drží stav „značka odchozích už byla někdy viděna“ (pomáhá rozpoznat příchozí).
 */
class WhatsAppAdapter {

    private var markerEverSeen = false

    private class Raw(
        val text: String,
        val bubble: Rect,
        val hasMarker: Boolean,
        val hasInCue: Boolean,
    )

    fun extract(root: AccessibilityNodeInfo): ExtractionResult {
        if (!hasVisibleEntry(root)) return NOT_A_CHAT
        val list = findList(root) ?: return NOT_A_CHAT
        val viewport = list.bounds()

        val raw = ArrayList<Raw>()
        for (i in 0 until list.childCount) {
            val item = list.getChild(i) ?: continue
            parseRow(item, viewport)?.let { raw += it }
        }

        val markerSeen = raw.any { it.hasMarker }
        if (markerSeen) markerEverSeen = true
        val markerKnown = markerSeen || markerEverSeen

        val messages = raw.map { r ->
            val partial = !viewport.contains(r.bubble) ||
                r.bubble.top <= viewport.top + 1 || r.bubble.bottom >= viewport.bottom - 1
            val byMarker = when {
                r.hasMarker -> Direction.OUT
                r.hasInCue -> Direction.IN
                // U uříznuté bubliny se značka odchozích mohla ztratit, proto nehádáme.
                markerKnown && !partial -> Direction.IN
                else -> Direction.UNKNOWN
            }
            val direction = if (byMarker != Direction.UNKNOWN) byMarker else byPosition(r.bubble, viewport)
            VisibleMessage(r.text, r.bubble, direction, partial)
        }
        return ExtractionResult(true, viewport, messages)
    }

    private fun hasVisibleEntry(root: AccessibilityNodeInfo): Boolean {
        val pkgs = (listOfNotNull(root.packageName?.toString()) + WhatsAppSelectors.PACKAGES).distinct()
        for (pkg in pkgs) {
            val found = root.findAccessibilityNodeInfosByViewId("$pkg:id/${WhatsAppSelectors.ENTRY}")
            if (found.any { it.isVisibleToUser }) return true
        }
        return false
    }

    private fun findList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(WhatsAppSelectors.LIST_ID)
            .filter { it.isVisibleToUser }
            .maxByOrNull { val r = it.bounds(); r.width().toLong() * r.height() }

    /**
     * Odchozí bubliny jsou přilepené k pravému okraji (~3 % šířky), příchozí začínají vlevo
     * (1:1 ~3 %, skupina ~13 % kvůli avatarům).
     */
    private fun byPosition(bubble: Rect, viewport: Rect): Direction {
        val w = viewport.width().toFloat()
        val leftGap = bubble.left - viewport.left
        val rightGap = viewport.right - bubble.right
        return when {
            rightGap <= w * 0.07f -> Direction.OUT
            leftGap <= w * 0.20f -> Direction.IN
            else -> Direction.UNKNOWN
        }
    }

    private fun parseRow(item: AccessibilityNodeInfo, viewport: Rect): Raw? {
        val byId = ArrayList<AccessibilityNodeInfo>()
        val candidates = ArrayList<AccessibilityNodeInfo>()
        var hasMarker = false
        var hasInCue = false
        var bubbleNode: AccessibilityNodeInfo? = null

        walk(item) { n ->
            val id = n.shortId()
            if (id != null) {
                if (id.startsWith(WhatsAppSelectors.QUOTED_PREFIX)) return@walk false
                if (id in WhatsAppSelectors.SKIP_SUBTREE) return@walk false
                if (id == WhatsAppSelectors.BUBBLE && bubbleNode == null) bubbleNode = n
                if (id == WhatsAppSelectors.OUT_MARKER) hasMarker = true
                if (id in WhatsAppSelectors.IN_CUES) hasInCue = true
            }
            val txt = n.text
            if (!txt.isNullOrBlank()) {
                if (id != null && id in WhatsAppSelectors.TEXT_IDS) {
                    byId += n
                } else if (n.className?.toString()?.contains("TextView") == true &&
                    (id == null || id !in WhatsAppSelectors.META_IDS)
                ) {
                    candidates += n
                }
            }
            true
        }

        val textNode = byId.firstOrNull() ?: candidates.maxByOrNull { it.text?.length ?: 0 } ?: return null
        val textBounds = textNode.bounds()
        if (textBounds.width() <= 0 || textBounds.height() <= 0) return null

        val bubble = bubbleNode?.bounds()
            ?.takeIf { it.contains(textBounds) && it.width() <= viewport.width() * 0.92f }
            ?: Rect(textBounds)

        return Raw(textNode.text.toString(), bubble, hasMarker, hasInCue)
    }

    private companion object {
        val NOT_A_CHAT = ExtractionResult(false, null, emptyList())
    }
}
