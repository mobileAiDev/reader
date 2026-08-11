package com.ldp.reader.document.epub

import kotlin.math.roundToLong

internal data class EpubReadingProgress(
    val scrollY: Int,
    val maxScrollY: Int,
    val textZoom: Int
)

internal fun restoredEpubScrollY(savedScrollY: Int, currentMaxScrollY: Int): Int {
    return savedScrollY.coerceIn(0, currentMaxScrollY.coerceAtLeast(0))
}

internal fun restoredEpubScrollY(
    savedScrollY: Int,
    savedMaxScrollY: Int,
    currentMaxScrollY: Int
): Int {
    val boundedCurrentMax = currentMaxScrollY.coerceAtLeast(0)
    if (boundedCurrentMax == 0) return 0
    if (savedMaxScrollY <= 0) {
        return restoredEpubScrollY(savedScrollY, boundedCurrentMax)
    }
    val boundedSavedScroll = savedScrollY.coerceIn(0, savedMaxScrollY)
    return (
        boundedSavedScroll.toLong() * boundedCurrentMax.toLong() /
            savedMaxScrollY.toLong()
        )
        .coerceIn(0L, boundedCurrentMax.toLong())
        .toInt()
}

internal fun epubMaxScrollY(
    contentHeightCss: Int,
    webViewScale: Float,
    viewportHeight: Int
): Int {
    if (contentHeightCss <= 0 || !webViewScale.isFinite() || webViewScale <= 0f) return 0
    val contentHeightPx = (contentHeightCss.toDouble() * webViewScale.toDouble())
        .coerceAtMost(Int.MAX_VALUE.toDouble())
        .roundToLong()
    return (contentHeightPx - viewportHeight.coerceAtLeast(0).toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal interface EpubReadingProgressStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)
}

internal class EpubReadingProgressStore(
    private val storage: EpubReadingProgressStorage
) {
    fun save(documentId: String, progress: EpubReadingProgress) {
        if (documentId.isBlank()) return
        storage.write(
            documentId,
            listOf(
                FORMAT_VERSION,
                progress.scrollY,
                progress.maxScrollY,
                progress.textZoom
            ).joinToString(SEPARATOR)
        )
    }

    fun load(documentId: String): EpubReadingProgress? {
        if (documentId.isBlank()) return null
        val fields = storage.read(documentId)?.split(SEPARATOR) ?: return null
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return null
        return EpubReadingProgress(
            scrollY = fields[1].toIntOrNull() ?: return null,
            maxScrollY = fields[2].toIntOrNull() ?: return null,
            textZoom = fields[3].toIntOrNull() ?: return null
        )
    }

    private companion object {
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 4
    }
}
