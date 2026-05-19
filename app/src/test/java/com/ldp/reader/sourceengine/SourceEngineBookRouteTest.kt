package com.ldp.reader.sourceengine

import com.ldp.reader.source.SourceEngineBookRoute
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineBookRouteTest {
    @Test
    fun shelfIdIsStableAcrossConcreteSources() {
        val first = book("https://a.example", "https://a.example/doupo", "https://a.example/a.jpg")
        val second = book("https://b.example", "https://b.example/doupo", "https://b.example/b.jpg")

        assertNotEquals(first.bookUrl, second.bookUrl)
        assertEquals(SourceEngineBookRoute.shelfBookId(first), SourceEngineBookRoute.shelfBookId(second))
        assertTrue(SourceEngineBookRoute.isShelfBookId(SourceEngineBookRoute.shelfBookId(first)))
    }

    @Test
    fun shelfIdTreatsTitleMarksAsSameBook() {
        val plain = book("https://a.example", "https://a.example/mystery", "https://a.example/plain.jpg", "诡秘之主")
        val marked = book("https://b.example", "https://b.example/mystery", "https://b.example/marked.jpg", "《诡秘之主》")

        assertEquals(SourceEngineBookRoute.shelfBookId(plain), SourceEngineBookRoute.shelfBookId(marked))
    }

    private fun book(
        sourceUrl: String,
        bookUrl: String,
        coverUrl: String,
        title: String = "斗破苍穹"
    ): SourceBook {
        return SourceBook(
            source = source(sourceUrl),
            name = title,
            author = if (title.contains("诡秘")) "爱潜水的乌贼" else "天蚕土豆",
            bookUrl = bookUrl,
            coverUrl = coverUrl,
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun source(sourceUrl: String): BookSource {
        val emptyRule = LegadoRuleSet("test", emptyMap())
        return BookSource(
            sourceName = sourceUrl,
            sourceUrl = sourceUrl,
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
