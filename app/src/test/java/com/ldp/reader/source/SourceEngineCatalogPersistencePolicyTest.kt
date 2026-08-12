package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.utils.Base64Compat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineCatalogPersistencePolicyTest {
    private val sourceUrl = "http://source.example/"
    private val bookUrl = "http://site.example/book/1/"

    @Test
    fun refreshedCatalogWhoseLinksOnlyDifferByVolatileBookMetadataAppends() {
        // Regression: the persisted generation embedded book-level metadata (lastChapter etc.)
        // in every chapter link, so a source publishing new chapters changed every link and the
        // whole refreshed catalog was rejected as an incompatible rewrite (RETAINED_CURRENT).
        val persisted = (1..4).map { ordinal ->
            bean(legacyFatLink(ordinal, lastChapter = "第4章 旧末章"), "第${ordinal}章 正文")
        }
        val candidate = (1..6).map { ordinal ->
            bean(slimLink(ordinal), "第${ordinal}章 正文")
        }

        val evaluation = SourceEngineCatalogPersistencePolicy.evaluate(persisted, candidate)

        assertTrue(evaluation.compatible)
        assertTrue(evaluation.changed)
        assertEquals(2, evaluation.appendedChapters)
        assertEquals(4, evaluation.refreshedRoutes)
        assertEquals(0, evaluation.safeTitleUpgrades)
    }

    @Test
    fun identicalLinksWithAppendedTailDoNotCountAsRouteRefreshes() {
        val persisted = (1..3).map { ordinal -> bean(slimLink(ordinal), "第${ordinal}章 正文") }
        val candidate = (1..5).map { ordinal -> bean(slimLink(ordinal), "第${ordinal}章 正文") }

        val evaluation = SourceEngineCatalogPersistencePolicy.evaluate(persisted, candidate)

        assertTrue(evaluation.compatible)
        assertTrue(evaluation.changed)
        assertEquals(2, evaluation.appendedChapters)
        assertEquals(0, evaluation.refreshedRoutes)
    }

    @Test
    fun candidateFromAnotherBookPageIsRejected() {
        val persisted = (1..3).map { ordinal -> bean(slimLink(ordinal), "第${ordinal}章 正文") }
        val candidate = (1..3).map { ordinal ->
            bean(slimLink(ordinal, bookUrl = "http://site.example/book/2/"), "第${ordinal}章 正文")
        }

        assertFalse(SourceEngineCatalogPersistencePolicy.evaluate(persisted, candidate).compatible)
    }

    @Test
    fun unsafeTitleChangeIsRejectedEvenWhenRoutesMatch() {
        val persisted = listOf(bean(slimLink(1), "第1章 正文"))
        val candidate = listOf(bean(slimLink(1), "第2章 换了内容"))

        assertFalse(SourceEngineCatalogPersistencePolicy.evaluate(persisted, candidate).compatible)
    }

    @Test
    fun plainLinkRewriteIsStillRejected() {
        val persisted = listOf(bean("http://site.example/1.html", "第1章 正文"))
        val candidate = listOf(bean("http://site.example/other.html", "第1章 正文"))

        assertFalse(SourceEngineCatalogPersistencePolicy.evaluate(persisted, candidate).compatible)
    }

    @Test
    fun unchangedCatalogIsCompatibleAndUnchanged() {
        val catalog = (1..3).map { ordinal -> bean(slimLink(ordinal), "第${ordinal}章 正文") }

        val evaluation = SourceEngineCatalogPersistencePolicy.evaluate(catalog, catalog)

        assertTrue(evaluation.compatible)
        assertFalse(evaluation.changed)
    }

    private fun bean(link: String, title: String): BookChapterBean {
        return BookChapterBean().apply {
            this.link = link
            this.title = title
        }
    }

    private fun slimLink(
        ordinal: Int,
        bookUrl: String = this.bookUrl
    ): String {
        val source = BookSource(
            sourceName = "测试源",
            sourceUrl = sourceUrl,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "$sourceUrl/search",
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
        val book = SourceBook(
            source = source,
            name = "测试书",
            author = "测试作者",
            bookUrl = bookUrl,
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        return SourceEngineBookRoute.chapterId(
            SourceChapter(
                source = source,
                book = book,
                index = ordinal - 1,
                name = "第${ordinal}章 正文",
                chapterUrl = "$bookUrl$ordinal.html"
            )
        )
    }

    /** A chapter link in the pre-fix on-device format with volatile book metadata embedded. */
    private fun legacyFatLink(ordinal: Int, lastChapter: String): String {
        val json = """
            {"author":"测试作者","bookName":"测试书","bookUrl":"$bookUrl",
            "chapterName":"第${ordinal}章 正文","chapterUrl":"$bookUrl$ordinal.html",
            "coverCandidates":["http://site.example/cover.jpg"],
            "coverUrl":"http://site.example/cover.jpg","index":${ordinal - 1},
            "intro":"简介","kind":"玄幻小说","lastChapter":"$lastChapter","sourceUrl":"$sourceUrl"}
        """.trimIndent().replace("\n", "")
        return "source_engine_chapter_" +
            Base64Compat.encodeUrlWithoutPadding(json.toByteArray(Charsets.UTF_8))
    }
}
