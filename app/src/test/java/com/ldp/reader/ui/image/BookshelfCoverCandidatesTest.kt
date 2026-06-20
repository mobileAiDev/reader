package com.ldp.reader.ui.image

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfCoverCandidatesTest {
    @Test
    fun sourceEngineShelfBookUsesCoverCandidatesFromRoute() {
        val routeId = SourceEngineBookRoute.bookId(
            book("https://primary.example/a.jpg"),
            listOf(" https://backup.example/b.jpg ", "https://primary.example/a.jpg")
        )
        val shelfBook = CollBookBean().apply {
            set_id(SourceEngineBookRoute.shelfBookId(book("https://ignored.example/shelf.jpg")))
            bookIdInBiquge = routeId
            cover = "https://primary.example/a.jpg"
        }

        assertEquals(
            listOf("https://primary.example/a.jpg", "https://backup.example/b.jpg"),
            BookshelfCoverCandidates.forBook(shelfBook)
        )
    }

    @Test
    fun loadedFallbackCoverBecomesPrimaryCandidate() {
        val routeId = SourceEngineBookRoute.bookId(
            book("https://broken.example/a.jpg"),
            listOf("https://ok.example/b.jpg", "https://backup.example/c.jpg")
        )
        val shelfBook = CollBookBean().apply {
            set_id(SourceEngineBookRoute.shelfBookId(book("https://ignored.example/shelf.jpg")))
            bookIdInBiquge = routeId
            cover = "https://broken.example/a.jpg"
        }

        assertEquals(
            true,
            BookshelfCoverCandidates.promoteLoadedCover(shelfBook, " https://ok.example/b.jpg ")
        )
        assertEquals("https://ok.example/b.jpg", shelfBook.cover)
        assertEquals(
            listOf("https://ok.example/b.jpg", "https://broken.example/a.jpg", "https://backup.example/c.jpg"),
            BookshelfCoverCandidates.forBook(shelfBook)
        )
    }

    @Test
    fun unknownLoadedCoverDoesNotMutateShelfBook() {
        val shelfBook = CollBookBean().apply {
            set_id("backend-id")
            cover = "https://cover.example/a.jpg"
        }

        assertEquals(
            false,
            BookshelfCoverCandidates.promoteLoadedCover(shelfBook, "https://other.example/b.jpg")
        )
        assertEquals("https://cover.example/a.jpg", shelfBook.cover)
    }

    @Test
    fun nonSourceEngineShelfBookKeepsSingleCover() {
        val shelfBook = CollBookBean().apply {
            set_id("backend-id")
            cover = "https://cover.example/a.jpg"
        }

        assertEquals(
            listOf("https://cover.example/a.jpg"),
            BookshelfCoverCandidates.forBook(shelfBook)
        )
    }

    private fun book(coverUrl: String): SourceBook {
        return SourceBook(
            source = source(),
            name = "Test Book",
            author = "Test Author",
            bookUrl = "https://book.example/xuanjian",
            coverUrl = coverUrl,
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun source(): BookSource {
        val emptyRule = LegadoRuleSet("test", emptyMap())
        return BookSource(
            sourceName = "test",
            sourceUrl = "https://source.example",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = emptyRule,
            ruleBookInfo = emptyRule,
            ruleToc = emptyRule,
            ruleContent = emptyRule,
            diagnostics = emptyList()
        )
    }
}
