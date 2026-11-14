package com.chatlingo.app.model

import android.graphics.Rect

enum class Direction { IN, OUT, UNKNOWN }

/** Zpráva viditelná na obrazovce. [bounds] = hranice bubliny v souřadnicích obrazovky. */
data class VisibleMessage(
    val text: String,
    val bounds: Rect,
    val direction: Direction,
    /** Bublina je uříznutá o okraj seznamu, překlad se u ní nezobrazuje. */
    val partial: Boolean,
)

data class ExtractionResult(
    val isConversation: Boolean,
    val viewport: Rect?,
    val messages: List<VisibleMessage>,
)

data class LanguagePair(val source: String, val target: String) {
    override fun toString() = "$source>$target"
}
