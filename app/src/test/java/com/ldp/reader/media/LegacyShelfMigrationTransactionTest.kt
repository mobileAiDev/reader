package com.ldp.reader.media

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyShelfMigrationTransactionTest {
    @Test
    fun validLegacyShelfIsDeletedOnlyAfterVerifiedWrite() {
        val events = mutableListOf<String>()

        val migrated = LegacyShelfMigrationTransaction.migrate(
            legacyPayload = "old shelf",
            decode = { listOf("legacy-comic") },
            currentItems = { listOf("current-audio") },
            merge = { current, legacy -> current + legacy },
            persistAndVerify = { items ->
                events += "persist:${items.joinToString()}"
                true
            },
            deleteLegacy = {
                events += "delete"
                true
            }
        )

        assertTrue(migrated)
        assertTrue(events == listOf("persist:current-audio, legacy-comic", "delete"))
    }

    @Test
    fun failedWriteKeepsLegacyShelfForNextStartup() {
        var deleted = false

        val migrated = LegacyShelfMigrationTransaction.migrate(
            legacyPayload = "old shelf",
            decode = { listOf("legacy-comic") },
            currentItems = { emptyList() },
            merge = { _, legacy -> legacy },
            persistAndVerify = { false },
            deleteLegacy = {
                deleted = true
                true
            }
        )

        assertFalse(migrated)
        assertFalse(deleted)
    }

    @Test
    fun malformedLegacyShelfIsPreservedInsteadOfClearingCurrentShelf() {
        var persisted = false
        var deleted = false

        val migrated = LegacyShelfMigrationTransaction.migrate(
            legacyPayload = "not-json",
            decode = { null },
            currentItems = { listOf("current-comic") },
            merge = { current, _ -> current },
            persistAndVerify = {
                persisted = true
                true
            },
            deleteLegacy = {
                deleted = true
                true
            }
        )

        assertFalse(migrated)
        assertFalse(persisted)
        assertFalse(deleted)
    }

    @Test
    fun legacyDecoderFailureDoesNotCrashStartupOrDeleteShelf() {
        var deleted = false

        val migrated = LegacyShelfMigrationTransaction.migrate<String>(
            legacyPayload = "unexpected legacy value",
            decode = { error("legacy decoder failed") },
            currentItems = { listOf("current-comic") },
            merge = { current, _ -> current },
            persistAndVerify = { true },
            deleteLegacy = {
                deleted = true
                true
            }
        )

        assertFalse(migrated)
        assertFalse(deleted)
    }

    @Test
    fun oldSharedPreferencesItemShapeKeepsComicIdentityAndChapter() {
        val expected = shelfItem(id = "comic|source|book", updatedAtMs = 123L)
        val legacyPayload = Gson().toJson(listOf(expected)).replace(
            "\"routeSnapshot\"",
            "\"comicPageIndex\":7," +
                "\"audioPositionMs\":0," +
                "\"audioDurationMs\":0," +
                "\"comicPageIndexByChapter\":{\"chapter-route\":7}," +
                "\"audioPositionMsByChapter\":{}," +
                "\"audioDurationMsByChapter\":{}," +
                "\"routeSnapshot\""
        )

        val decoded = MediaShelfStore.decodeLegacyItems(legacyPayload)

        assertNotNull(decoded)
        assertEquals(1, decoded?.size)
        assertEquals(expected.id, decoded?.single()?.id)
        assertEquals(expected.currentChapterRouteId, decoded?.single()?.currentChapterRouteId)
        assertEquals(expected.routeSnapshot.book.bookUrl, decoded?.single()?.routeSnapshot?.book?.bookUrl)
    }

    @Test
    fun migrationMergePreservesDistinctBooksAndKeepsNewestDuplicate() {
        val current = shelfItem(id = "same", updatedAtMs = 200L).copy(title = "current title")
        val oldDuplicate = shelfItem(id = "same", updatedAtMs = 100L).copy(title = "old title")
        val legacyOnly = shelfItem(id = "legacy-only", updatedAtMs = 150L)

        val merged = MediaShelfStore.mergeForMigration(
            currentItems = listOf(current),
            legacyItems = listOf(oldDuplicate, legacyOnly)
        )

        assertEquals(2, merged.size)
        assertEquals("current title", merged.single { it.id == "same" }.title)
        assertTrue(merged.any { it.id == "legacy-only" })
    }

    private fun shelfItem(id: String, updatedAtMs: Long): MediaShelfItem {
        val rules = MediaLegadoRuleSet("", emptyMap())
        val source = MediaSourceDefinition(
            sourceName = "漫画源",
            sourceUrl = "https://source.example",
            sourceType = MediaSourceType.COMIC,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = rules,
            ruleBookInfo = rules,
            ruleToc = rules,
            ruleContent = rules,
            diagnostics = emptyList()
        )
        val book = MediaSourceBook(
            source = source,
            name = "测试漫画",
            author = "作者",
            bookUrl = "https://source.example/book",
            coverUrl = "https://source.example/cover.jpg",
            intro = "简介",
            kind = ReaderMediaKind.COMIC.seedKey,
            lastChapter = "第十话"
        )
        val chapter = MediaSourceChapter(
            source = source,
            book = book,
            index = 9,
            name = "第十话",
            chapterUrl = "https://source.example/chapter/10"
        )
        val snapshot = MediaRouteSnapshot(
            kind = ReaderMediaKind.COMIC,
            bookRouteId = "book-route",
            book = book,
            chapters = listOf(MediaRouteChapterSnapshot("chapter-route", chapter))
        )
        return MediaShelfItem(
            id = id,
            kindKey = ReaderMediaKind.COMIC.seedKey,
            bookRouteId = "book-route",
            title = book.name,
            author = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            latest = book.lastChapter,
            sourceName = source.sourceName,
            currentChapterRouteId = "chapter-route",
            currentChapterTitle = chapter.name,
            currentChapterIndex = chapter.index,
            routeSnapshot = snapshot,
            updatedAtMs = updatedAtMs
        )
    }
}
