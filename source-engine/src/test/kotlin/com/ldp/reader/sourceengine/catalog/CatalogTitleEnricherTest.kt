package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTitleEnricherTest {
    @Test
    fun detectsOnlySameOrdinalPrefixCompletionAsSafeTitleUpgrade() {
        val enricher = CatalogTitleEnricher()

        assertTrue(
            enricher.isSafeTitleUpgrade(
                currentTitle = "第四十二章 融合...",
                candidateTitle = "第四十二章 融合成功！"
            )
        )
        assertFalse(
            enricher.isSafeTitleUpgrade(
                currentTitle = "第四十二章 融合...",
                candidateTitle = "第四十三章 融合成功！"
            )
        )
        assertFalse(
            enricher.isSafeTitleUpgrade(
                currentTitle = "第四十二章 融合...",
                candidateTitle = "第四十二章 灵魂融合成功！"
            )
        )
        assertFalse(
            enricher.isSafeTitleUpgrade(
                currentTitle = "第四十二章 融合...",
                candidateTitle = "第四十二章 融合成功……"
            )
        )
    }

    @Test
    fun detectsSafeWritebackOnlyWhenTheStableRouteAlsoMatches() {
        val enricher = CatalogTitleEnricher()
        val persisted = mapOf("route://chapter-42" to "第四十二章 融合...")

        assertTrue(
            enricher.hasSafeTitleUpgradeForSameKey(
                currentTitlesByKey = persisted,
                candidateTitlesByKey = mapOf("route://chapter-42" to "第四十二章 融合成功！")
            )
        )
        assertFalse(
            enricher.hasSafeTitleUpgradeForSameKey(
                currentTitlesByKey = persisted,
                candidateTitlesByKey = mapOf("route://another-chapter-42" to "第四十二章 融合成功！")
            )
        )
    }

    @Test
    fun treatsRemovingAKnownUpdateDecorationAsASafeSameOrdinalWriteback() {
        val enricher = CatalogTitleEnricher()

        assertTrue(
            enricher.isSafeTitleUpgrade(
                currentTitle = "第五百九十一章 融合成功！【第三更！】",
                candidateTitle = "第五百九十一章 融合成功！"
            )
        )
        assertFalse(
            enricher.isSafeTitleUpgrade(
                currentTitle = "第五百九十一章 融合成功！【番外】",
                candidateTitle = "第五百九十一章 融合成功！"
            )
        )
    }

    @Test
    fun upgradesTruncatedTitleFromMatchingOrdinalWithoutChangingPrimaryRoute() {
        val primaryChapter = chapter(
            sourceName = "primary",
            ordinal = 42,
            title = "第四十二章 融合..."
        )
        val completeChapter = chapter(
            sourceName = "complete-title",
            ordinal = 42,
            title = "第四十二章 融合成功！"
        )
        val fusion = ChapterListFusion()
        val primaryCatalog = fusion.fuse(listOf(listOf(primaryChapter)))
        val completeCatalog = fusion.fuse(listOf(listOf(completeChapter)))

        val result = CatalogTitleEnricher().enrich(primaryCatalog, listOf(completeCatalog))

        assertEquals("第四十二章 融合成功！", result.catalog.chapters.single().displayTitle)
        assertEquals(primaryChapter.chapterUrl, result.catalog.chapters.single().sourceChapters.single().chapterUrl)
        assertEquals(1, result.upgradedCount)
    }

    @Test
    fun treatsKnownUpdateDecorationAsTheSameCompletionWithoutChangingPrimaryRoute() {
        val primaryChapter = chapter(
            sourceName = "primary",
            ordinal = 591,
            title = "第五百九十一章 融合..."
        )
        val cleanCandidate = chapter(
            sourceName = "clean-title",
            ordinal = 591,
            title = "第五百九十一章 融合成功！"
        )
        val decoratedCandidate = chapter(
            sourceName = "decorated-title",
            ordinal = 591,
            title = "第五百九十一章 融合成功！【第三更！】"
        )
        val fusion = ChapterListFusion()
        val primaryCatalog = fusion.fuse(listOf(listOf(primaryChapter)))
        val candidateCatalogs = listOf(cleanCandidate, decoratedCandidate).map { candidate ->
            fusion.fuse(listOf(listOf(candidate)))
        }

        val result = CatalogTitleEnricher().enrich(primaryCatalog, candidateCatalogs)

        assertEquals("第五百九十一章 融合成功！", result.catalog.chapters.single().displayTitle)
        assertEquals(primaryChapter.chapterUrl, result.catalog.chapters.single().sourceChapters.single().chapterUrl)
        assertEquals(1, result.upgradedCount)
    }

    @Test
    fun canonicalizesKnownUpdateDecorationAlreadyPresentOnPrimaryTitle() {
        val primaryChapter = chapter(
            sourceName = "primary",
            ordinal = 591,
            title = "第五百九十一章 融合成功！【第三更！】"
        )
        val primaryCatalog = ChapterListFusion().fuse(listOf(listOf(primaryChapter)))

        val result = CatalogTitleEnricher().enrich(primaryCatalog, candidateCatalogs = emptyList())

        assertEquals("第五百九十一章 融合成功！", result.catalog.chapters.single().displayTitle)
        assertEquals(primaryChapter.chapterUrl, result.catalog.chapters.single().sourceChapters.single().chapterUrl)
        assertEquals(1, result.upgradedCount)
    }

    @Test
    fun matchesACompletionByParsedOrdinalWhenCatalogPositionsDiffer() {
        val primaryChapter = chapter(
            sourceName = "primary-with-front-matter",
            ordinal = 591,
            title = "第五百九十一章 融合...",
            sourceIndex = 617
        )
        val candidateChapter = chapter(
            sourceName = "candidate-without-front-matter",
            ordinal = 591,
            title = "第五百九十一章 融合成功！",
            sourceIndex = 590
        )
        val fusion = ChapterListFusion()

        val result = CatalogTitleEnricher().enrich(
            primaryCatalog = fusion.fuse(listOf(listOf(primaryChapter))),
            candidateCatalogs = listOf(fusion.fuse(listOf(listOf(candidateChapter))))
        )

        assertEquals("第五百九十一章 融合成功！", result.catalog.chapters.single().displayTitle)
        assertEquals(primaryChapter.chapterUrl, result.catalog.chapters.single().sourceChapters.single().chapterUrl)
    }

    @Test
    fun keepsTruncatedTitleWhenMatchingCandidatesDisagreeOnTheCompletion() {
        val primaryChapter = chapter(
            sourceName = "primary",
            ordinal = 42,
            title = "第四十二章 归来..."
        )
        val firstCandidate = chapter(
            sourceName = "candidate-a",
            ordinal = 42,
            title = "第四十二章 归来之后"
        )
        val secondCandidate = chapter(
            sourceName = "candidate-b",
            ordinal = 42,
            title = "第四十二章 归来之前"
        )
        val fusion = ChapterListFusion()
        val primaryCatalog = fusion.fuse(listOf(listOf(primaryChapter)))
        val candidateCatalogs = listOf(firstCandidate, secondCandidate).map { candidate ->
            fusion.fuse(listOf(listOf(candidate)))
        }

        val result = CatalogTitleEnricher().enrich(primaryCatalog, candidateCatalogs)

        assertEquals("第四十二章 归来...", result.catalog.chapters.single().displayTitle)
        assertEquals(0, result.upgradedCount)
    }

    @Test
    fun keepsTruncatedTitleWhenCandidatesAreARealSemanticConflict() {
        val primaryChapter = chapter("primary", 591, "第五百九十一章 融合...")
        val successful = chapter("candidate-success", 591, "第五百九十一章 融合成功！")
        val failed = chapter("candidate-failure", 591, "第五百九十一章 融合失败！")
        val fusion = ChapterListFusion()

        val result = CatalogTitleEnricher().enrich(
            primaryCatalog = fusion.fuse(listOf(listOf(primaryChapter))),
            candidateCatalogs = listOf(successful, failed).map { candidate ->
                fusion.fuse(listOf(listOf(candidate)))
            }
        )

        assertEquals("第五百九十一章 融合...", result.catalog.chapters.single().displayTitle)
        assertEquals(0, result.upgradedCount)
    }

    @Test
    fun doesNotCollapseAnUnknownBracketedPartOfTheTitle() {
        val primaryChapter = chapter("primary", 591, "第五百九十一章 融合...")
        val clean = chapter("candidate-clean", 591, "第五百九十一章 融合成功！")
        val differentTitle = chapter("candidate-different", 591, "第五百九十一章 融合成功！【番外】")
        val fusion = ChapterListFusion()

        val result = CatalogTitleEnricher().enrich(
            primaryCatalog = fusion.fuse(listOf(listOf(primaryChapter))),
            candidateCatalogs = listOf(clean, differentTitle).map { candidate ->
                fusion.fuse(listOf(listOf(candidate)))
            }
        )

        assertEquals("第五百九十一章 融合...", result.catalog.chapters.single().displayTitle)
        assertEquals(0, result.upgradedCount)
    }

    @Test
    fun keepsAuthorEllipsisWhenNoCompleteCandidateExists() {
        val primaryChapter = chapter(
            sourceName = "primary",
            ordinal = 42,
            title = "第四十二章 未完待续……"
        )
        val primaryCatalog = ChapterListFusion().fuse(listOf(listOf(primaryChapter)))

        val result = CatalogTitleEnricher().enrich(primaryCatalog, candidateCatalogs = emptyList())

        assertEquals("第四十二章 未完待续……", result.catalog.chapters.single().displayTitle)
        assertEquals(0, result.upgradedCount)
    }

    private fun chapter(
        sourceName: String,
        ordinal: Int,
        title: String,
        sourceIndex: Int = ordinal - 1
    ): SourceChapter {
        val source = BookSource(
            sourceName = sourceName,
            sourceUrl = "https://example.com/$sourceName",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
        val book = SourceBook(
            source = source,
            name = "测试书籍",
            author = "测试作者",
            bookUrl = source.sourceUrl,
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        return SourceChapter(
            source = source,
            book = book,
            index = sourceIndex,
            name = title,
            chapterUrl = "${source.sourceUrl}/chapter-$ordinal.html"
        )
    }
}
