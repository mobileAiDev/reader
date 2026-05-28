package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.ContentQualityReport
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceQualityRouterTest {
    @Test
    fun seedPlacesFastSourcesInTierOneBeforeGenericSources() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed("https://ordinary.example", "普通源", 3, "misc", 4_200),
                sourceSeed("https://www.changduzw.com", "55读书", 1, "general", 8_300),
                sourceSeed("https://published.example", "出版物源", 2, "published", 6_300)
            )
        )
        val sources = listOf(
            source("普通源", "https://ordinary.example"),
            source("55读书", "https://www.changduzw.com"),
            source("出版物源", "https://published.example")
        )

        val ordered = router.waterfallSources(sources)

        assertEquals("55读书", ordered.first().sourceName)
    }

    @Test
    fun preservesBreadthByInterleavingBucketsInsideTheSameSeedTier() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed("https://a.example", "笔趣A", 1, "general", 8_200),
                sourceSeed("https://b.example", "笔趣B", 1, "general", 8_100),
                sourceSeed("https://po18.example", "海棠成人源", 1, "adult", 7_900)
            )
        )
        val ordered = router.waterfallSources(
            listOf(
                source("笔趣A", "https://a.example"),
                source("笔趣B", "https://b.example"),
                source("海棠成人源", "https://po18.example")
            )
        )

        assertTrue(ordered.take(2).any { it.sourceName == "海棠成人源" })
    }

    @Test
    fun waterfallPullsTierTwoCoverageIntoTheEarlySearchWindow() {
        val tierOneRows = (1..12).map { index ->
            sourceSeed("https://fast$index.example", "快源$index", 1, "general", 8_000 - index)
        }
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                *(tierOneRows + sourceSeed("https://published.example", "出版覆盖源", 2, "published", 6_600)).toTypedArray()
            )
        )
        val sources = (1..12).map { index -> source("快源$index", "https://fast$index.example") } +
            source("出版覆盖源", "https://published.example")

        val ordered = router.waterfallSources(sources)

        assertTrue(ordered.take(8).any { it.sourceName == "出版覆盖源" })
    }

    @Test
    fun seedTierCannotBuryHighScoreSourceBelowItsScoreTier() {
        val tierOneRows = (1..12).map { index ->
            sourceSeed("https://fast$index.example", "快源$index", 1, "general", 8_000 - index)
        }
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                *(tierOneRows + sourceSeed("https://www.lingdxsw.com", "零点小说", 3, "general", 6_241)).toTypedArray()
            )
        )
        val sources = (1..12).map { index -> source("快源$index", "https://fast$index.example") } +
            source("零点小说", "https://www.lingdxsw.com")

        val ordered = router.waterfallSources(sources)

        assertTrue(ordered.take(8).any { it.sourceName == "零点小说" })
    }

    @Test
    fun noisyBucketsDoNotBuryHigherScoreSourcesBehindLowerScoreBreadth() {
        val lowerScoreRows = (1..120).map { index ->
            sourceSeed("https://wide$index.example", "宽源$index", 2, "bucket-$index", 5_000)
        }
        val higherGeneralRows = (1..24).map { index ->
            sourceSeed("https://general$index.example", "通用$index", 2, "general", 6_900 - index)
        }
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                *(lowerScoreRows + higherGeneralRows +
                    sourceSeed("https://www.lingdxsw.org", "零点小说", 3, "general", 6_241)).toTypedArray()
            )
        )
        val sources = (1..120).map { index -> source("宽源$index", "https://wide$index.example") } +
            (1..24).map { index -> source("通用$index", "https://general$index.example") } +
            source("零点小说", "https://www.lingdxsw.org")

        val ordered = router.waterfallSources(sources)

        assertTrue(ordered.take(80).any { it.sourceName == "零点小说" })
    }

    @Test
    fun bookSpecificSuccessRaisesBookSourceScore() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = SourceQualitySeed.empty()
        )
        val book = SourceBook(
            source = source("普通源", "https://ordinary.example"),
            name = "第一序列",
            author = "会说话的肘子",
            bookUrl = "https://ordinary.example/book/1",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        val before = router.bookSourceScore(book)

        router.recordCatalogResolved(book, chapterCount = 1_200, rawChapterCount = 1_200)

        assertTrue(router.bookSourceScore(book) > before)
    }

    @Test
    fun bookPersonalTierRunsBeforeGenericTierWaterfall() {
        val tierOne = source("通用一线源", "https://global-tier1.example")
        val tierThree = source("本书强源", "https://book-tier.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(tierOne.sourceUrl, tierOne.sourceName, 1, "general", 9_000),
                sourceSeed(tierThree.sourceUrl, tierThree.sourceName, 3, "general", 4_200)
            )
        )
        val provenBook = book(tierThree, name = "第一序列")

        router.recordCatalogResolved(provenBook, chapterCount = 1_200, rawChapterCount = 1_200)
        router.recordContentResolved(chapter(provenBook, "第一千二百章 大结局"), readableContent())

        val ordered = router.waterfallSourcesForBook(listOf(tierOne, tierThree), "第一序列")

        assertEquals("本书强源", ordered.first().sourceName)
        assertEquals("通用一线源", ordered[1].sourceName)
    }

    @Test
    fun bookPersonalWaterfallReturnsOnlyProvenBookSources() {
        val global = source("通用一线源", "https://global-tier1.example")
        val personal = source("本书强源", "https://book-tier.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(global.sourceUrl, global.sourceName, 1, "general", 9_000),
                sourceSeed(personal.sourceUrl, personal.sourceName, 3, "general", 4_200)
            )
        )
        val provenBook = book(personal, name = "第一序列")
        router.recordCatalogResolved(provenBook, chapterCount = 1_200, rawChapterCount = 1_200)
        router.recordContentResolved(chapter(provenBook, "第一千二百章 大结局"), readableContent())

        val personalOnly = router.personalWaterfallSourcesForBook(listOf(global, personal), "第一序列")
        val globalOnly = router.globalWaterfallSourcesForBook(listOf(global, personal), "第一序列")

        assertEquals(listOf("本书强源"), personalOnly.map { it.sourceName })
        assertEquals(listOf("通用一线源"), globalOnly.map { it.sourceName })
    }

    @Test
    fun bookPersonalTierDoesNotChangeGenericTierOrOtherBooks() {
        val tierOne = source("通用一线源", "https://global-tier1.example")
        val tierThree = source("本书强源", "https://book-tier.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(tierOne.sourceUrl, tierOne.sourceName, 1, "general", 9_000),
                sourceSeed(tierThree.sourceUrl, tierThree.sourceName, 3, "general", 4_200)
            )
        )
        val provenBook = book(tierThree, name = "第一序列")
        router.recordCatalogResolved(provenBook, chapterCount = 1_200, rawChapterCount = 1_200)
        router.recordContentResolved(chapter(provenBook, "第一千二百章 大结局"), readableContent())

        val genericOrder = router.waterfallSources(listOf(tierOne, tierThree))
        val otherBookOrder = router.waterfallSourcesForBook(listOf(tierOne, tierThree), "诡秘之主")

        assertEquals("通用一线源", genericOrder.first().sourceName)
        assertEquals("通用一线源", otherBookOrder.first().sourceName)
    }

    @Test
    fun bookRuntimeFailuresDoNotDemoteGlobalTierOrder() {
        val originallyFirst = source("通用一线源", "https://global-tier1.example")
        val originallySecond = source("通用二线源", "https://global-tier2.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(originallyFirst.sourceUrl, originallyFirst.sourceName, 1, "general", 7_800),
                sourceSeed(originallySecond.sourceUrl, originallySecond.sourceName, 1, "general", 7_600)
            )
        )
        val failedBook = book(originallyFirst, name = "失败样本书")

        repeat(220) { index ->
            router.recordContentRejected(chapter(failedBook, "坏章$index"))
        }

        val genericOrder = router.waterfallSources(listOf(originallyFirst, originallySecond))
        val otherBookOrder = router.waterfallSourcesForBook(listOf(originallyFirst, originallySecond), "另一本文")

        assertEquals("通用一线源", genericOrder.first().sourceName)
        assertEquals("通用一线源", otherBookOrder.first().sourceName)
    }

    @Test
    fun bookPersonalTierIsSortedByBookSourceScore() {
        val stronger = source("本书高分源", "https://book-strong.example")
        val weaker = source("本书低分源", "https://book-weak.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(stronger.sourceUrl, stronger.sourceName, 3, "general", 4_200),
                sourceSeed(weaker.sourceUrl, weaker.sourceName, 3, "general", 4_200)
            )
        )
        val strongBook = book(stronger, name = "第一序列")
        val weakBook = book(weaker, name = "第一序列")
        router.recordCatalogResolved(strongBook, chapterCount = 1_200, rawChapterCount = 1_200)
        router.recordContentResolved(chapter(strongBook, "第一千二百章 大结局"), readableContent())
        router.recordContentResolved(chapter(strongBook, "第一千一百章 再验证"), readableContent())
        router.recordCatalogResolved(weakBook, chapterCount = 1_200, rawChapterCount = 1_200)
        router.recordContentResolved(chapter(weakBook, "第一千二百章 大结局"), readableContent())

        val ordered = router.waterfallSourcesForBook(listOf(weaker, stronger), "第一序列")

        assertEquals("本书高分源", ordered.first().sourceName)
        assertEquals("本书低分源", ordered[1].sourceName)
    }

    @Test
    fun bookPersonalTierRemovesSourceAfterRepeatedBookFailures() {
        val tierOne = source("通用一线源", "https://global-tier1.example")
        val promoted = source("曾经可用源", "https://book-promoted.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(tierOne.sourceUrl, tierOne.sourceName, 1, "general", 9_000),
                sourceSeed(promoted.sourceUrl, promoted.sourceName, 3, "general", 4_200)
            )
        )
        val book = book(promoted, name = "第一序列")
        router.recordCatalogResolved(book, chapterCount = 1_200, rawChapterCount = 1_200)
        router.recordContentResolved(chapter(book, "第一千二百章 大结局"), readableContent())
        assertEquals("曾经可用源", router.waterfallSourcesForBook(listOf(tierOne, promoted), "第一序列").first().sourceName)

        repeat(4) { index ->
            router.recordContentRejected(chapter(book, "坏章$index"))
        }

        assertEquals("通用一线源", router.waterfallSourcesForBook(listOf(tierOne, promoted), "第一序列").first().sourceName)
    }

    @Test
    fun rejectedBookSourceDoesNotEnterTheBookPersonalTier() {
        val tierOne = source("通用一线源", "https://global-tier1.example")
        val badForBook = source("本书失败源", "https://book-bad.example")
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed(tierOne.sourceUrl, tierOne.sourceName, 1, "general", 9_000),
                sourceSeed(badForBook.sourceUrl, badForBook.sourceName, 3, "general", 4_200)
            )
        )
        val badBook = book(badForBook, name = "第一序列")

        router.recordContentRejected(chapter(badBook, "第一章"))
        router.recordContentRejected(chapter(badBook, "第二章"))

        val ordered = router.waterfallSourcesForBook(listOf(tierOne, badForBook), "第一序列")

        assertEquals("通用一线源", ordered.first().sourceName)
    }

    @Test
    fun fanficSourcesAreDemotedAndAdultSourcesRemainAvailableAsFallback() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = SourceQualitySeed.empty()
        )
        val adult = source("海棠成人源", "https://po18.example")
        val fanfic = source("斗破同人源", "https://fanfic.example")

        assertTrue(router.sourceScore(adult) > 0)
        assertTrue(router.sourceScore(fanfic) < router.sourceScore(source("普通源", "https://general.example")))
    }

    @Test
    fun persistedLocalDeltaIsAppliedOnTopOfSeedAfterRestart() {
        val storage = InMemorySourceQualityStorage()
        val seed = seed(sourceSeed("https://fast.example", "快源", 1, "general", 7_500))
        val book = book(source("快源", "https://fast.example"))
        val before = SourceQualityRouter(storage = storage, seed = seed).bookSourceScore(book)

        SourceQualityRouter(storage = storage, seed = seed).apply {
            recordCatalogResolved(book, chapterCount = 1_200, rawChapterCount = 1_200)
            flush()
        }

        val afterRestart = SourceQualityRouter(storage = storage, seed = seed).bookSourceScore(book)
        assertTrue(afterRestart > before)
    }

    @Test
    fun tailPollutionRewardsAdditionalVerifiedChaptersMoreThanSmallBadTailPenalty() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed("https://fast.example", "快更新源", 1, "general", 7_500),
                sourceSeed("https://stale.example", "慢更新源", 1, "general", 7_500)
            )
        )
        val fast = book(source("快更新源", "https://fast.example"))
        val stale = book(source("慢更新源", "https://stale.example"))
        router.recordCatalogResolved(stale, chapterCount = 1_000, rawChapterCount = 1_000)
        router.recordCatalogResolved(fast, chapterCount = 1_000, rawChapterCount = 1_000)

        router.recordCatalogTailTrimmed(fast, kept = 1_006, rawChapterCount = 1_010)

        assertTrue(router.bookSourceScore(fast) > router.bookSourceScore(stale))
        assertEquals(1_010, router.bookSourceSnapshot(fast).latestObservedOrdinal)
        assertEquals(1_006, router.bookSourceSnapshot(fast).latestVerifiedGoodOrdinal)
        assertEquals(1_007, router.bookSourceSnapshot(fast).badTailStartOrdinal)
    }

    @Test
    fun sameAlignedLatestChapterPrefersSmallerBadTail() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed("https://bad4.example", "尾部错四章", 1, "general", 7_500),
                sourceSeed("https://bad3.example", "尾部错三章", 1, "general", 7_500)
            )
        )
        val bad4 = book(source("尾部错四章", "https://bad4.example"))
        val bad3 = book(source("尾部错三章", "https://bad3.example"))
        router.recordCatalogResolved(bad4, chapterCount = 1_000, rawChapterCount = 1_000)
        router.recordCatalogResolved(bad3, chapterCount = 1_000, rawChapterCount = 1_000)

        router.recordCatalogTailTrimmed(bad4, kept = 1_000, rawChapterCount = 1_004)
        router.recordCatalogTailTrimmed(bad3, kept = 1_000, rawChapterCount = 1_003)

        assertTrue(router.bookSourceScore(bad3) > router.bookSourceScore(bad4))
    }

    @Test
    fun v5ValidTailGainRaisesBookSourceDespiteSmallBadTail() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = seed(
                sourceSeed("https://fast.example", "多一章有效源", 1, "general", 7_500),
                sourceSeed("https://stale.example", "少一章有效源", 1, "general", 7_500)
            )
        )
        val fast = book(source("多一章有效源", "https://fast.example"))
        val stale = book(source("少一章有效源", "https://stale.example"))
        router.recordCatalogResolved(stale, chapterCount = 1_000, rawChapterCount = 1_000)
        router.recordCatalogResolved(fast, chapterCount = 1_000, rawChapterCount = 1_000)

        router.recordV5ChapterMarks(
            book = fast,
            latestObservedOrdinal = 1_002,
            latestNormalOrdinal = 1_001,
            firstBadTailOrdinal = 1_002,
            normalCount = 3,
            wrongCount = 1,
            nonStoryCount = 0,
            badExtractionCount = 0,
            inconclusiveCount = 0
        )

        assertTrue(router.bookSourceScore(fast) > router.bookSourceScore(stale))
        assertEquals(1_002, router.bookSourceSnapshot(fast).latestObservedOrdinal)
        assertEquals(1_001, router.bookSourceSnapshot(fast).latestVerifiedGoodOrdinal)
        assertEquals(1_002, router.bookSourceSnapshot(fast).badTailStartOrdinal)
    }

    @Test
    fun parsesSourceSeedTsv() {
        val router = SourceQualityRouter(
            storage = InMemorySourceQualityStorage(),
            seed = SourceQualitySeed.fromTsv(
                """
                # source-quality-seed-v1
                kind	sourceUrl	sourceName	tier	bucket	score	speed	coverage	freshness	quality	stability	note
                source	https://seed.example	种子源	1	published	8123	90	80	70	60	50	fixture
                """.trimIndent()
            )
        )

        assertEquals(8_123, router.sourceScore(source("种子源", "https://seed.example")))
    }

    private fun book(source: BookSource, name: String = "第一序列"): SourceBook {
        return SourceBook(
            source = source,
            name = name,
            author = "会说话的肘子",
            bookUrl = "${source.sourceUrl}/book/1",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun chapter(book: SourceBook, name: String): SourceChapter {
        return SourceChapter(
            source = book.source,
            book = book,
            index = 0,
            name = name,
            chapterUrl = "${book.bookUrl}/chapter/1"
        )
    }

    private fun readableContent(): CleanContent {
        return CleanContent(
            rawContent = "正文".repeat(600),
            cleanedContent = "正文".repeat(600),
            report = ContentQualityReport(
                qualityScore = 95,
                rawLength = 1_200,
                cleanedLength = 1_200,
                paragraphCount = 20,
                removedLineCount = 0,
                duplicateLineCount = 0,
                pollutionMarkers = emptyList(),
                warnings = emptyList(),
                coherenceScore = 95
            )
        )
    }

    private fun seed(vararg rows: String): SourceQualitySeed {
        return SourceQualitySeed.fromTsv(
            """
            kind	sourceUrl	sourceName	tier	bucket	score	speed	coverage	freshness	quality	stability	note
            ${rows.joinToString("\n")}
            """.trimIndent()
        )
    }

    private fun sourceSeed(
        sourceUrl: String,
        sourceName: String,
        tier: Int,
        bucket: String,
        score: Int
    ): String {
        return listOf(
            "source",
            sourceUrl,
            sourceName,
            tier.toString(),
            bucket,
            score.toString(),
            "0",
            "0",
            "0",
            "0",
            "0",
            "test"
        ).joinToString("\t")
    }

    private fun source(name: String, url: String): BookSource {
        return BookSource(
            sourceName = name,
            sourceUrl = url,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search?q={{key}}",
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
    }
}
