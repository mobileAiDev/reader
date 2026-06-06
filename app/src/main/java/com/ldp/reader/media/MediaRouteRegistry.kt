package com.ldp.reader.media

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object MediaRouteRegistry {
    private val emptyRuleSet = MediaLegadoRuleSet("", emptyMap())
    private val books = ConcurrentHashMap<String, MediaSourceBook>()
    private val bookAlternates = ConcurrentHashMap<String, List<MediaSourceBook>>()
    private val bookKinds = ConcurrentHashMap<String, ReaderMediaKind>()
    private val details = ConcurrentHashMap<String, MediaSourceBookDetail>()
    private val chapters = ConcurrentHashMap<String, MediaSourceChapter>()
    private val chapterKinds = ConcurrentHashMap<String, ReaderMediaKind>()
    private val chapterBookRoutes = ConcurrentHashMap<String, String>()
    private val chapterRouteKeys = ConcurrentHashMap<String, String>()

    fun registerBook(kind: ReaderMediaKind, book: MediaSourceBook, alternates: List<MediaSourceBook> = emptyList()): String {
        val routeId = "media-item:${UUID.randomUUID()}"
        books[routeId] = book
        if (alternates.isNotEmpty()) {
            bookAlternates[routeId] = alternates
        }
        bookKinds[routeId] = kind
        return routeId
    }

    fun book(routeId: String): MediaSourceBook? = books[routeId]

    fun alternates(routeId: String): List<MediaSourceBook> = bookAlternates[routeId].orEmpty()

    fun replaceBook(routeId: String, book: MediaSourceBook, alternates: List<MediaSourceBook>) {
        books[routeId] = book
        if (alternates.isEmpty()) {
            bookAlternates.remove(routeId)
        } else {
            bookAlternates[routeId] = alternates
        }
        details.remove(routeId)
    }

    fun kind(routeId: String): ReaderMediaKind? = bookKinds[routeId] ?: chapterKinds[routeId]

    fun registerDetail(routeId: String, detail: MediaSourceBookDetail) {
        details[routeId] = detail
    }

    fun detail(routeId: String): MediaSourceBookDetail? = details[routeId]

    fun registerChapter(kind: ReaderMediaKind, chapter: MediaSourceChapter, bookRouteId: String): String {
        val key = chapterKey(bookRouteId, chapter)
        chapterRouteKeys[key]?.let { existingRoute ->
            chapters[existingRoute] = chapter
            chapterKinds[existingRoute] = kind
            chapterBookRoutes[existingRoute] = bookRouteId
            return existingRoute
        }
        val routeId = "media-chapter:${UUID.randomUUID()}"
        chapters[routeId] = chapter
        chapterKinds[routeId] = kind
        chapterBookRoutes[routeId] = bookRouteId
        chapterRouteKeys[key] = routeId
        return routeId
    }

    fun chapter(routeId: String): MediaSourceChapter? = chapters[routeId]

    fun bookRouteForChapter(routeId: String): String? = chapterBookRoutes[routeId]

    fun chaptersForBookRoute(bookRouteId: String): List<MediaRouteChapterSnapshot> {
        if (bookRouteId.isBlank()) return emptyList()
        return chapterBookRoutes.entries
            .asSequence()
            .filter { it.value == bookRouteId }
            .mapNotNull { entry ->
                chapters[entry.key]?.let { chapter ->
                    MediaRouteChapterSnapshot(routeId = entry.key, chapter = chapter)
                }
            }
            .sortedWith(compareBy<MediaRouteChapterSnapshot> { it.chapter.index }.thenBy { it.routeId })
            .toList()
    }

    fun snapshotBookRoute(bookRouteId: String): MediaRouteSnapshot? {
        val kind = bookKinds[bookRouteId] ?: return null
        val book = books[bookRouteId] ?: return null
        return MediaRouteSnapshot(
            kind = kind,
            bookRouteId = bookRouteId,
            book = book,
            alternates = bookAlternates[bookRouteId].orEmpty(),
            detail = details[bookRouteId],
            chapters = chaptersForBookRoute(bookRouteId)
        )
    }

    fun compactSnapshot(snapshot: MediaRouteSnapshot, focusedChapterRouteId: String = ""): MediaRouteSnapshot {
        val book = snapshot.book.compactForRouteSnapshot()
        val focusedChapter = snapshot.chapters.firstOrNull { it.routeId == focusedChapterRouteId }
            ?: snapshot.chapters.firstOrNull()
        return snapshot.copy(
            book = book,
            alternates = emptyList(),
            detail = null,
            chapters = listOfNotNull(
                focusedChapter?.let { chapterSnapshot ->
                    chapterSnapshot.copy(chapter = chapterSnapshot.chapter.compactForRouteSnapshot(book))
                }
            )
        )
    }

    fun isCompactSnapshot(snapshot: MediaRouteSnapshot): Boolean {
        return snapshot.alternates.isEmpty() &&
            snapshot.chapters.size <= 1 &&
            snapshot.book.source.isCompactRouteSource() &&
            snapshot.detail?.let { detail ->
                detail.book.source.isCompactRouteSource() && detail.runtimeVariables.isEmpty()
            } != false &&
            snapshot.chapters.all { chapter ->
                chapter.chapter.source.isCompactRouteSource() &&
                    chapter.chapter.book.source.isCompactRouteSource() &&
                    chapter.chapter.runtimeVariables.isEmpty()
            }
    }

    fun restore(snapshot: MediaRouteSnapshot) {
        val bookRouteId = snapshot.bookRouteId
        if (bookRouteId.isBlank()) return
        val compact = isCompactSnapshot(snapshot)
        val source = if (compact) {
            runCatching {
                MediaSourceRuntime.compatibleSourceForUrl(snapshot.kind.sourceType, snapshot.book.source.sourceUrl)
            }.getOrNull() ?: snapshot.book.source
        } else {
            snapshot.book.source
        }
        val book = snapshot.book.copy(source = source)
        books[bookRouteId] = book
        if (snapshot.alternates.isEmpty()) {
            bookAlternates.remove(bookRouteId)
        } else {
            bookAlternates[bookRouteId] = snapshot.alternates
        }
        bookKinds[bookRouteId] = snapshot.kind
        if (compact) {
            details.remove(bookRouteId)
        } else {
            snapshot.detail?.let { detail ->
                details[bookRouteId] = detail.copy(book = detail.book.copy(source = source))
            }
        }
        snapshot.chapters.forEach { chapterSnapshot ->
            val chapterRouteId = chapterSnapshot.routeId
            if (chapterRouteId.isBlank()) return@forEach
            val chapterBook = chapterSnapshot.chapter.book.copy(source = source)
            chapters[chapterRouteId] = chapterSnapshot.chapter.copy(source = source, book = chapterBook)
            chapterKinds[chapterRouteId] = snapshot.kind
            chapterBookRoutes[chapterRouteId] = bookRouteId
            chapterRouteKeys[chapterKey(bookRouteId, chapterSnapshot.chapter)] = chapterRouteId
        }
    }

    fun clearForTest() {
        books.clear()
        bookAlternates.clear()
        bookKinds.clear()
        details.clear()
        chapters.clear()
        chapterKinds.clear()
        chapterBookRoutes.clear()
        chapterRouteKeys.clear()
    }

    private fun chapterKey(bookRouteId: String, chapter: MediaSourceChapter): String {
        return listOf(bookRouteId, chapter.index.toString(), chapter.chapterUrl, chapter.name).joinToString("|")
    }

    private fun MediaSourceBook.compactForRouteSnapshot(): MediaSourceBook {
        return copy(
            source = source.compactForRouteSnapshot(),
            intro = intro.take(MAX_PERSISTED_INTRO_CHARS)
        )
    }

    private fun MediaSourceChapter.compactForRouteSnapshot(book: MediaSourceBook): MediaSourceChapter {
        return copy(
            source = book.source,
            book = book,
            runtimeVariables = emptyMap()
        )
    }

    private fun MediaSourceDefinition.compactForRouteSnapshot(): MediaSourceDefinition {
        return MediaSourceDefinition(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            sourceType = sourceType,
            sourceGroup = sourceGroup,
            sourceComment = null,
            enabled = enabled,
            headers = headers,
            searchUrl = null,
            ruleSearch = emptyRuleSet,
            ruleBookInfo = emptyRuleSet,
            ruleToc = emptyRuleSet,
            ruleContent = emptyRuleSet,
            diagnostics = emptyList()
        )
    }

    private fun MediaSourceDefinition.isCompactRouteSource(): Boolean {
        return sourceComment == null &&
            searchUrl == null &&
            ruleSearch.isEmpty &&
            ruleBookInfo.isEmpty &&
            ruleToc.isEmpty &&
            ruleContent.isEmpty &&
            diagnostics.isEmpty() &&
            jsLib.isBlank() &&
            loginUrl.isBlank()
    }

    private const val MAX_PERSISTED_INTRO_CHARS = 800
}

data class MediaRouteSnapshot(
    val kind: ReaderMediaKind,
    val bookRouteId: String,
    val book: MediaSourceBook,
    val alternates: List<MediaSourceBook> = emptyList(),
    val detail: MediaSourceBookDetail? = null,
    val chapters: List<MediaRouteChapterSnapshot> = emptyList()
)

data class MediaRouteChapterSnapshot(
    val routeId: String,
    val chapter: MediaSourceChapter
)
