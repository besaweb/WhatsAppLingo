package com.chatlingo.spike

import org.json.JSONArray
import org.json.JSONObject

object SpikeStats {
    @Volatile var serviceConnected = false
    var scans = 0
    var totalScanMs = 0L
    var maxScanMs = 0L
    var scrollEvents = 0
    var scrollWithDelta = 0
    val settleMs = mutableListOf<Long>()
    var bothKnown = 0
    var bothAgree = 0
    var dirUnknown = 0
    var dirTotal = 0
    @Volatile var markerEverSeen = false
    var lastResult: ScanResult? = null
    var lastSetText: String? = null
    var lastDump: String? = null
    var mode: String = "SLEDUJ"

    @Synchronized
    fun reset() {
        scans = 0; totalScanMs = 0; maxScanMs = 0
        scrollEvents = 0; scrollWithDelta = 0
        settleMs.clear()
        bothKnown = 0; bothAgree = 0
        dirUnknown = 0; dirTotal = 0
        lastResult = null; lastSetText = null; lastDump = null
    }

    @Synchronized
    fun record(r: ScanResult) {
        scans++
        totalScanMs += r.durationMs
        if (r.durationMs > maxScanMs) maxScanMs = r.durationMs
        lastResult = r
        for (b in r.bubbles) {
            if (!b.partial) {
                dirTotal++
                if (b.direction == Direction.UNKNOWN) dirUnknown++
            }
            if (b.dirByMarker != Direction.UNKNOWN && b.dirByPosition != Direction.UNKNOWN) {
                bothKnown++
                if (b.dirByMarker == b.dirByPosition) bothAgree++
            }
        }
    }

    @Synchronized
    fun addSettle(ms: Long) {
        settleMs += ms
        if (settleMs.size > 500) settleMs.removeAt(0)
    }

    private fun percentile(p: Double): Long? {
        if (settleMs.isEmpty()) return null
        val s = settleMs.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    @Synchronized
    fun summaryText(): String = buildString {
        appendLine("Služba připojena: ${if (serviceConnected) "ANO" else "NE"}")
        appendLine("Režim scrollu: $mode")
        appendLine("Průchodů: $scans, průměr ${if (scans > 0) totalScanMs / scans else 0} ms, max $maxScanMs ms")
        val r = lastResult
        if (r == null) {
            appendLine("Zatím žádný průchod – otevři chat ve WhatsAppu.")
        } else {
            appendLine("--- poslední průchod ---")
            appendLine("Chat otevřen: ${r.isConversation}  ${r.note ?: ""}")
            appendLine("Vstupní pole: ${r.entryHow ?: "-"}")
            appendLine("Seznam: ${r.listId ?: "(bez id)"} / ${r.listClass}")
            appendLine("Položek v seznamu: ${r.itemCount}, rozpoznaných bublin: ${r.bubbles.size}")
            val byDir = r.bubbles.groupingBy { it.direction }.eachCount()
            appendLine("IN=${byDir[Direction.IN] ?: 0} OUT=${byDir[Direction.OUT] ?: 0} UNKNOWN=${byDir[Direction.UNKNOWN] ?: 0}")
            val fb = r.bubbles.count { it.textSource != "id" }
            appendLine("Text přes id message_text: ${r.bubbles.size - fb}, fallback: $fb")
            appendLine("Značka odchozích (status) nalezena: ${r.markerSeen}")
            appendLine("Částečně viditelné: ${r.bubbles.count { it.partial }}")
        }
        appendLine("--- kumulativně ---")
        val agree = if (bothKnown > 0) "%.1f %%".format(bothAgree * 100.0 / bothKnown) else "-"
        appendLine("Shoda směru (fajfky vs. poloha): $agree ze $bothKnown")
        appendLine("Směr neurčen (bez uříznutých bublin): $dirUnknown ze $dirTotal")
        appendLine("Scroll událostí: $scrollEvents, z toho se scrollDeltaY: $scrollWithDelta")
        val p50 = percentile(0.5); val p90 = percentile(0.9)
        appendLine("Ustálení po scrollu: medián ${p50 ?: "-"} ms, p90 ${p90 ?: "-"} ms, max ${settleMs.maxOrNull() ?: "-"} ms (n=${settleMs.size})")
        appendLine("SET TEXT: ${lastSetText ?: "-"}")
        appendLine("Poslední dump: ${lastDump ?: "-"}")
    }

    @Synchronized
    fun toJson(): JSONObject = JSONObject().apply {
        put("scans", scans)
        put("avgScanMs", if (scans > 0) totalScanMs / scans else 0)
        put("maxScanMs", maxScanMs)
        put("scrollEvents", scrollEvents)
        put("scrollWithDelta", scrollWithDelta)
        put("settleMs", JSONArray(settleMs))
        put("directionBothKnown", bothKnown)
        put("directionAgree", bothAgree)
        put("directionUnknown", dirUnknown)
        put("directionTotalNonPartial", dirTotal)
        put("lastSetText", lastSetText)
        put("mode", mode)
    }
}
