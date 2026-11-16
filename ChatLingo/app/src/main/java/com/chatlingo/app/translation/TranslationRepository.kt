package com.chatlingo.app.translation

import android.os.SystemClock
import com.chatlingo.app.AppSettings
import com.chatlingo.app.model.LanguagePair

/**
 * Cache překladů jen v paměti (nic se neukládá na disk), slučování stejných požadavků
 * a přeskočení textů, které se překládat nemají. Používá se jen z hlavního vlákna.
 */
class TranslationRepository(private val gate: LanguageGate) {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_CACHE
    }
    private val inFlight = HashSet<String>()
    private val skipped = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    private fun key(engineId: String, pair: LanguagePair, text: String) = "$engineId|$pair|$text"

    /** Překlad od preferovaného engine, jinak záložní (z telefonu), pokud už ho máme. */
    fun cached(preferred: String, pair: LanguagePair, text: String): String? =
        cache[key(preferred, pair, text)] ?: cache[key(MlKitEngine.ID, pair, text)]

    /**
     * Přeloží text enginem [engine] (preferovaný [preferred]). Vrací true, pokud přibyl nový překlad.
     * Když preferovaný engine selže a zabere záložní, preferovaný se zkusí znovu až po pauze.
     */
    suspend fun translate(
        preferred: String,
        engine: TranslationEngine,
        pair: LanguagePair,
        rawText: String,
    ): Boolean {
        val text = rawText.take(MAX_CHARS)
        val k = key(preferred, pair, rawText)
        if (k in cache || k in skipped) return false
        val failed = failedAt[k]
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_AFTER_MS) return false
        if (!inFlight.add(k)) return false
        try {
            if (text.none { it.isLetter() }) {
                skipped += k
                return false
            }
            if (!gate.shouldTranslate(text, pair.source, pair.target)) {
                skipped += k
                return false
            }
            return when (val r = engine.translate(text, pair)) {
                is TranslationResult.Success -> {
                    if (r.engineId == preferred) {
                        failedAt.remove(k)
                    } else {
                        failedAt[k] = SystemClock.uptimeMillis()
                    }
                    cache[key(r.engineId, pair, rawText)] = r.text
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
    val mlKit by lazy { MlKitEngine() }
    val repository by lazy { TranslationRepository(LanguageGate()) }

    /** Dvojice (id preferovaného enginu, engine). LLM jen když je zapnutý a nastavená adresa proxy. */
    fun engineFor(settings: AppSettings): Pair<String, TranslationEngine> {
        val url = settings.proxyUrl.trim()
        return if (settings.useLlm && url.isNotEmpty()) {
            RemoteEngine.ID to FallbackEngine(RemoteEngine(url, settings.proxyToken.trim()), mlKit)
        } else {
            MlKitEngine.ID to mlKit
        }
    }
}
