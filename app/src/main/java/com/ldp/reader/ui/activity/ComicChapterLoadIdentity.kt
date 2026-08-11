package com.ldp.reader.ui.activity

internal data class ComicChapterLoadIdentity(
    val token: Int,
    val bookRouteId: String?,
    val chapterRouteId: String
) {
    fun isCurrent(currentToken: Int, currentBookRouteId: String?, currentChapterRouteId: String): Boolean {
        return token == currentToken &&
            bookRouteId == currentBookRouteId &&
            chapterRouteId == currentChapterRouteId
    }
}
