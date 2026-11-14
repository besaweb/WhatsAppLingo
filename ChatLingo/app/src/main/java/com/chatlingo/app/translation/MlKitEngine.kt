package com.chatlingo.app.translation

import com.chatlingo.app.model.LanguagePair
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Překlad v telefonu (ML Kit). Modely se stahují jednou, text zpráv opouští aplikaci nikdy. */
class MlKitEngine : TranslationEngine {
    override val id = "mlkit"

    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    private fun translator(pair: LanguagePair): Translator =
        translators.getOrPut(pair.toString()) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(pair.source)
                    .setTargetLanguage(pair.target)
                    .build(),
            )
        }

    /** Stáhne modely pro dvojici, pokud ještě nejsou. Vrací false při chybě (např. bez sítě). */
    suspend fun ensureModel(pair: LanguagePair): Boolean {
        if (pair.toString() in ready) return true
        return try {
            translator(pair).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready += pair.toString()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun translate(text: String, pair: LanguagePair): TranslationResult {
        if (!ensureModel(pair)) return TranslationResult.Failure("model")
        return try {
            TranslationResult.Success(translator(pair).translate(text).await(), id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslationResult.Failure(e.javaClass.simpleName)
        }
    }
}

/** Rozpoznání jazyka v telefonu. Slouží k tomu, aby se nepřekládaly zprávy, které už jsou v cílovém jazyce. */
class LanguageGate {
    private val client by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build(),
        )
    }

    /** Kód jazyka, nebo "und" když se nedá určit. */
    suspend fun detect(text: String): String = try {
        client.identifyLanguage(text).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "und"
    }
}
