package com.chatlingo.app.translation

import com.chatlingo.app.model.LanguagePair

sealed interface TranslationResult {
    data class Success(val text: String, val engineId: String) : TranslationResult
    data class Failure(val reason: String) : TranslationResult
}

interface TranslationEngine {
    val id: String
    suspend fun translate(text: String, pair: LanguagePair): TranslationResult
}
