package com.chatlingo.app.translation

import android.os.SystemClock
import com.chatlingo.app.model.LanguagePair

/**
 * Cache překladů jen v paměti (nic se neukládá na disk), slučování stejných požadavků
 * a přeskočení textů, které se překládat nemají. Používá se jen z hlavního vlákna.
 */
class TranslationRepository(
    private val engine: TranslationEngine,
    private val gate: LanguageGate,
) {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_CACHE
    }
    private val inFlight = HashSet<String>()
    private val skipped = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    private fun key(pair: LanguagePair, text: String) = "$pair|$text"

    fun cached(pair: LanguagePair, text: String): String? = cache[key(pair, text)]

    /** Přeloží text. Vrací true, pokud přibyl nový překlad do cache. */
    suspend fun translate(pair: LanguagePair, rawText: String): Boolean {
        val text = rawText.take(MAX_CHARS)
        val k = key(pair, rawText)
        if (k in cache || k in skipped) return false
        val failed = failedAt[k]
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_AFTER_MS) return false
        if (!inFlight.add(k)) return false
        try {
            if (text.none { it.isLetter() }) {
                skipped += k
                return false
            }
            val lang = gate.detect(text)
            if (lang == pair.target) {
                skipped += k
                return false
            }
            return when (val r = engine.translate(text, pair)) {
                is TranslationResult.Success -> {
                    failedAt.remove(k)
                    cache[k] = r.text
                    true
                }
                is TranslationResult.Failure -> {
                    failedAt[k] = SystemClock.uptimeMillis()
                    false
                }
            }
        } finally {
            inFlight.remove(k)
        }
    }

    private companion object {
        const val MAX_CACHE = 300
        const val MAX_CHARS = 1500
        const val RETRY_AFTER_MS = 15_000L
    }
}

/** Sdílené instance pro službu i obrazovku nastavení (jeden proces). */
object AppGraph {
    val engine by lazy { MlKitEngine() }
    val repository by lazy { TranslationRepository(engine, LanguageGate()) }
}
