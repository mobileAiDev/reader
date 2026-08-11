package com.ldp.reader.document

internal interface LocalDocumentPageProgressStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)
}

internal class LocalDocumentPageProgressStore(
    private val storage: LocalDocumentPageProgressStorage
) {
    fun save(documentId: String, pageIndex: Int) {
        if (documentId.isBlank()) return
        storage.write(documentId, "$FORMAT_VERSION$SEPARATOR${pageIndex.coerceAtLeast(0)}")
    }

    fun load(documentId: String): Int? {
        if (documentId.isBlank()) return null
        val fields = storage.read(documentId)?.split(SEPARATOR) ?: return null
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return null
        return fields[1].toIntOrNull()?.coerceAtLeast(0)
    }

    private companion object {
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 2
    }
}

internal fun restoredLocalDocumentPageIndex(savedPageIndex: Int?, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    return (savedPageIndex ?: 0).coerceIn(0, pageCount - 1)
}

internal data class LocalDocumentVisiblePage(
    val index: Int,
    val visiblePixels: Int
)

internal fun dominantLocalDocumentPageIndex(
    visiblePages: Iterable<LocalDocumentVisiblePage>,
    fallbackIndex: Int,
    pageCount: Int
): Int {
    var bestIndex: Int? = null
    var bestVisiblePixels = 0
    visiblePages.forEach { page ->
        if (page.index in 0 until pageCount && page.visiblePixels > bestVisiblePixels) {
            bestIndex = page.index
            bestVisiblePixels = page.visiblePixels
        }
    }
    return restoredLocalDocumentPageIndex(bestIndex ?: fallbackIndex, pageCount)
}

internal fun localDocumentPageLabel(pageIndex: Int, pageCount: Int): String {
    if (pageCount <= 0) return ""
    return "${restoredLocalDocumentPageIndex(pageIndex, pageCount) + 1} / $pageCount"
}
