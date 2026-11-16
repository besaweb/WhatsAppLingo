package com.chatlingo.app.translation

import com.chatlingo.app.model.LanguagePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kvalitní překlad přes vlastní proxy (LLM). Text zprávy opouští telefon a jde na adresu [url],
 * proto jen přes HTTPS a jen po výslovném zapnutí v nastavení.
 */
class RemoteEngine(private val url: String, private val token: String) : TranslationEngine {
    override val id = ID

    override suspend fun translate(text: String, pair: LanguagePair): TranslationResult =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) return@withContext TranslationResult.Failure("insecure_url")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Auth-Token", token)
                }
                val body = JSONObject()
                    .put("text", text)
                    .put("source", pair.source)
                    .put("target", pair.target)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code !in 200..299) return@withContext TranslationResult.Failure("http$code")
                val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val out = JSONObject(response).optString("translation", "")
                if (out.isBlank()) TranslationResult.Failure("empty") else TranslationResult.Success(out, ID)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult.Failure(e.javaClass.simpleName)
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        const val ID = "llm"
    }
}

/** Zkusí hlavní engine, a když selže, použije záložní (překlad v telefonu). */
class FallbackEngine(
    private val primary: TranslationEngine,
    private val secondary: TranslationEngine,
) : TranslationEngine {
    override val id = primary.id

    override suspend fun translate(text: String, pair: LanguagePair): TranslationResult {
        val r = primary.translate(text, pair)
        return if (r is TranslationResult.Success) r else secondary.translate(text, pair)
    }
}
