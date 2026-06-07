package com.ldp.reader.ui.image

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.source.SourceEngineBookRoute

object BookshelfCoverCandidates {
    fun forBook(book: CollBookBean): List<String> {
        val routeCandidates = listOf(book.bookIdInBiquge, book.get_id())
            .asSequence()
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .distinct()
            .flatMap { routeId ->
                val payload = runCatching { SourceEngineBookRoute.decodeBookId(requireNotNull(routeId)) }
                    .getOrNull()
                SourceEngineBookRoute.coverCandidates(payload ?: return@flatMap emptySequence())
                    .asSequence()
            }

        return (sequenceOf(book.cover) + routeCandidates)
            .map { cover -> cover?.trim().orEmpty() }
            .filter { cover -> cover.isNotBlank() }
            .distinct()
            .toList()
    }
}
