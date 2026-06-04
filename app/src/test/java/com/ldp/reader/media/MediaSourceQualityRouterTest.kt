package com.ldp.reader.media

import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourceQualityRouterTest {
    @Test
    fun seedTierPlacesTrustedComicSourcesFirst() {
        val fast = source("快漫画", "https://fast.example", MediaSourceType.COMIC)
        val slow = source("慢漫画", "https://slow.example", MediaSourceType.COMIC)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = mediaSeed(
                seedRow("comic", fast.sourceUrl, fast.sourceName, tier = 1, score = 8_000),
                seedRow("comic", slow.sourceUrl, slow.sourceName, tier = 3, score = 3_500)
            )
        )

        val ordered = router.waterfallSources(ReaderMediaKind.COMIC, listOf(slow, fast))

        assertEquals("快漫画", ordered.first().sourceName)
    }

    @Test
    fun comicConsensusRequiresAtLeastOneCover() {
        val sourceA = source("漫画A", "https://a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://b.example", MediaSourceType.COMIC)
        val sourceC = source("漫画C", "https://c.example", MediaSourceType.COMIC)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.COMIC,
            keyword = "海贼王",
            books = listOf(
                book(sourceC, "海贼王外传"),
                book(sourceA, "海贼王"),
                book(sourceB, "海贼王", coverUrl = "https://img.example/one.jpg")
            ),
            limit = 10
        )

        assertEquals("海贼王", ranked.first().book.name)
        assertEquals(2, ranked.first().sourceCount)
    }

    @Test
    fun comicConsensusWithoutCoverCanDisplay() {
        val sourceA = source("漫画A", "https://a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://b.example", MediaSourceType.COMIC)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.COMIC,
            keyword = "海贼王",
            books = listOf(
                book(sourceA, "海贼王"),
                book(sourceB, "海贼王")
            ),
            limit = 10
        )

        assertEquals("海贼王", ranked.single().book.name)
        assertEquals(2, ranked.single().sourceCount)
    }

    @Test
    fun mediaConsensusDeduplicatesSameSearchHostMirrors() {
        val mirrorA = source("野蛮漫画", "https://mhbao.colacomic.com", MediaSourceType.COMIC)
            .copy(searchUrl = "https://yemancomic.com/search?searchkey={{key}}")
        val mirrorB = source("野蛮漫画", "https://yemancomic.com", MediaSourceType.COMIC)
            .copy(searchUrl = "https://yemancomic.com/search?searchkey={{key}}")
        val other = source("我的漫神", "https://m.mhkami.com", MediaSourceType.COMIC)
            .copy(searchUrl = "https://m.mhkami.com/search?keyword={{key}}")
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.COMIC,
            keyword = "凡人修仙传",
            books = listOf(
                book(mirrorA, "凡人修仙传", coverUrl = "https://img.example/a.jpg"),
                book(mirrorB, "凡人修仙传"),
                book(other, "凡人修仙传")
            ),
            limit = 10
        )

        assertEquals(2, ranked.single().sourceCount)
    }

    @Test
    fun audioConsensusOutranksSingleSourceResult() {
        val sourceA = source("听书A", "https://audio-a.example", MediaSourceType.AUDIO)
        val sourceB = source("听书B", "https://audio-b.example", MediaSourceType.AUDIO)
        val sourceC = source("听书C", "https://audio-c.example", MediaSourceType.AUDIO)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.AUDIO,
            keyword = "凡人修仙传",
            books = listOf(
                book(sourceC, "凡人修仙传外传"),
                book(sourceA, "凡人修仙传", coverUrl = "https://img.example/audio.jpg"),
                book(sourceB, "凡人修仙传")
            ),
            limit = 10
        )

        assertEquals("凡人修仙传", ranked.first().book.name)
        assertEquals(2, ranked.first().sourceCount)
    }

    @Test
    fun higherReadableConsensusCountMovesResultForward() {
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )
        val fourMediaSourceBooks = (1..4).map { index ->
            book(
                source("四源$index", "https://four-$index.example", MediaSourceType.COMIC),
                "凡人修仙传甲",
                coverUrl = if (index == 1) "https://img.example/four.jpg" else ""
            )
        }
        val fiveMediaSourceBooks = (1..5).map { index ->
            book(
                source("五源$index", "https://five-$index.example", MediaSourceType.COMIC),
                "凡人修仙传乙",
                coverUrl = if (index == 1) "https://img.example/five.jpg" else ""
            )
        }

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.COMIC,
            keyword = "凡人修仙传",
            books = fourMediaSourceBooks + fiveMediaSourceBooks,
            limit = 10
        )

        assertEquals("凡人修仙传乙", ranked.first().book.name)
        assertEquals(5, ranked.first().sourceCount)
    }

    @Test
    fun mediaSearchDoesNotMatchAuthorOnlyConsensus() {
        val sourceA = source("漫画A", "https://author-a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://author-b.example", MediaSourceType.COMIC)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.COMIC,
            keyword = "岸本齐史",
            books = listOf(
                book(sourceA, "火影忍者", author = "岸本齐史", coverUrl = "https://img.example/a.jpg"),
                book(sourceB, "火影忍者", author = "岸本齐史")
            ),
            limit = 10
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun displayGateKeepsOnlyReadableMediaSources() {
        val sourceA = source("漫画A", "https://read-a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://read-b.example", MediaSourceType.COMIC)
        val books = listOf(
            book(sourceA, "凡人修仙传", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "凡人修仙传")
        )
        var readableCalls = 0

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.COMIC,
            keyword = "凡人修仙传",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, _ ->
            readableCalls += 1
            readableCalls == 1
        }

        assertEquals(1, displayable.size)
        assertEquals(sourceA, displayable.single().source)
        assertEquals(2, readableCalls)
    }

    @Test
    fun comicDisplayGateHidesCandidatesWhenEverySourceIsUnreadable() {
        val sourceA = source("漫画A", "https://empty-a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://empty-b.example", MediaSourceType.COMIC)
        val books = listOf(
            book(sourceA, "凡人修仙传", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "凡人修仙传")
        )

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.COMIC,
            keyword = "凡人修仙传",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, _ -> false }

        assertTrue(displayable.isEmpty())
    }

    @Test
    fun comicDisplayGateRejectsLooseCharacterCoverageMatches() {
        val sourceA = source("漫画A", "https://loose-a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://loose-b.example", MediaSourceType.COMIC)
        val books = listOf(
            book(sourceA, "箱子之下、一粒", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "箱子之下、一粒")
        )

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.COMIC,
            keyword = "一人之下",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, _ -> true }

        assertTrue(displayable.isEmpty())
    }

    @Test
    fun comicRankerRejectsLooseCharacterCoverageMatches() {
        val sourceA = source("漫画A", "https://rank-loose-a.example", MediaSourceType.COMIC)
        val sourceB = source("漫画B", "https://rank-loose-b.example", MediaSourceType.COMIC)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.COMIC,
            keyword = "一人之下",
            books = listOf(
                book(sourceA, "箱子之下、一粒", coverUrl = "https://img.example/a.jpg"),
                book(sourceB, "箱子之下、一粒")
            ),
            limit = 10
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun audioDisplayGateGroupsReadableQueryMatchedTitleVariants() {
        val sourceA = source("听书A", "https://audio-a.example", MediaSourceType.AUDIO)
        val sourceB = source("听书B", "https://audio-b.example", MediaSourceType.AUDIO)
        val sourceC = source("听书C", "https://audio-c.example", MediaSourceType.AUDIO)
        val books = listOf(
            book(sourceA, "《凡人修仙传之仙界篇》", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "凡人修仙传之仙界篇 | 多人经典仙侠巨作 | 忘语"),
            book(sourceC, "凡人修仙传之仙界篇免费有声小说")
        )

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.AUDIO,
            keyword = "凡人修仙传",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, _ -> true }

        assertEquals(3, displayable.size)
    }

    @Test
    fun audioDisplayGateGroupsEditionTitlesButKeepsSequelsSeparate() {
        val sourceA = source("听书A", "https://audio-a.example", MediaSourceType.AUDIO)
        val sourceB = source("听书B", "https://audio-b.example", MediaSourceType.AUDIO)
        val sequel = source("听书C", "https://audio-c.example", MediaSourceType.AUDIO)
        val books = listOf(
            book(sourceA, "凡人修仙传（大灰狼版）", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "凡人修仙传 粤语版"),
            book(sequel, "凡人修仙传之仙界篇（北冥版）", coverUrl = "https://img.example/c.jpg")
        )

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.AUDIO,
            keyword = "凡人修仙传",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, book -> book.source != sequel }

        assertEquals(2, displayable.size)
        assertTrue(displayable.none { it.source == sequel })
    }

    @Test
    fun audioDisplayGateRejectsKeywordEmbeddedInAnotherBookTitle() {
        val sourceA = source("听书A", "https://audio-a.example", MediaSourceType.AUDIO)
        val sourceB = source("听书B", "https://audio-b.example", MediaSourceType.AUDIO)
        val books = listOf(
            book(sourceA, "剑噬大地|爆爽流|废柴逆袭|斗破苍穹|多人有声剧", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "剑噬大地 斗破苍穹特别篇")
        )

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.AUDIO,
            keyword = "斗破苍穹",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, _ -> true }

        assertTrue(displayable.isEmpty())
    }

    @Test
    fun audioDisplayGateRejectsBaseTitleForSequelKeyword() {
        val sourceA = source("听书A", "https://audio-a.example", MediaSourceType.AUDIO)
        val sourceB = source("听书B", "https://audio-b.example", MediaSourceType.AUDIO)
        val books = listOf(
            book(sourceA, "凡人修仙传", coverUrl = "https://img.example/a.jpg"),
            book(sourceB, "凡人修仙传")
        )

        val displayable = MediaSearchDisplayGate.displayableBooks(
            kind = ReaderMediaKind.AUDIO,
            keyword = "凡人修仙传之仙界篇",
            books = books,
            maxGroups = 10,
            maxSourcesPerGroup = 10
        ) { _, _ -> true }

        assertTrue(displayable.isEmpty())
    }

    @Test
    fun audioRankerRejectsBaseTitleConsensusForSequelKeyword() {
        val sourceA = source("听书A", "https://audio-rank-a.example", MediaSourceType.AUDIO)
        val sourceB = source("听书B", "https://audio-rank-b.example", MediaSourceType.AUDIO)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = MediaSourceQualitySeed.empty()
        )

        val ranked = router.rankSearchResults(
            kind = ReaderMediaKind.AUDIO,
            keyword = "凡人修仙传之仙界篇",
            books = listOf(
                book(sourceA, "凡人修仙传", coverUrl = "https://img.example/a.jpg"),
                book(sourceB, "凡人修仙传")
            ),
            limit = 10
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun successfulBookRuntimeSignalsPromotePersonalTier() {
        val global = source("通用漫画", "https://global.example", MediaSourceType.COMIC)
        val personal = source("本书强源", "https://personal.example", MediaSourceType.COMIC)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = mediaSeed(
                seedRow("comic", global.sourceUrl, global.sourceName, tier = 1, score = 8_000),
                seedRow("comic", personal.sourceUrl, personal.sourceName, tier = 3, score = 4_200)
            )
        )
        val detail = detail(book(personal, "一人之下"))

        router.recordDetailResolved(ReaderMediaKind.COMIC, detail)
        router.recordChapterListResolved(ReaderMediaKind.COMIC, detail, chapterCount = 120)
        router.recordContentResolved(ReaderMediaKind.COMIC, chapter(detail.book), resolvedItemCount = 8)

        val ordered = router.waterfallSourcesForQuery(
            ReaderMediaKind.COMIC,
            listOf(global, personal),
            "一人之下"
        )

        assertEquals("本书强源", ordered.first().sourceName)
        assertEquals("通用漫画", ordered[1].sourceName)
    }

    @Test
    fun failedBookRuntimeSignalsDoNotEnterPersonalTier() {
        val global = source("通用听书", "https://global-audio.example", MediaSourceType.AUDIO)
        val failing = source("失败听书", "https://bad-audio.example", MediaSourceType.AUDIO)
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = mediaSeed(
                seedRow("audio", global.sourceUrl, global.sourceName, tier = 1, score = 8_000),
                seedRow("audio", failing.sourceUrl, failing.sourceName, tier = 2, score = 5_000)
            )
        )
        val failedBook = book(failing, "凡人修仙传")

        router.recordDetailFailed(ReaderMediaKind.AUDIO, failedBook)
        router.recordContentResolved(ReaderMediaKind.AUDIO, chapter(failedBook), resolvedItemCount = 0)

        val ordered = router.waterfallSourcesForQuery(
            ReaderMediaKind.AUDIO,
            listOf(failing, global),
            "凡人修仙传"
        )

        assertEquals("通用听书", ordered.first().sourceName)
    }

    @Test
    fun userImportedMediaSourceStartsNearTierTwoFront() {
        val trusted = source("内置强漫画", "https://trusted.example", MediaSourceType.COMIC)
        val ordinary = source("普通漫画", "https://ordinary.example", MediaSourceType.COMIC)
        val imported = source(
            name = "用户导入漫画",
            url = "https://imported.example",
            type = MediaSourceType.COMIC,
            comment = "reader:user-imported-source"
        )
        val router = MediaSourceQualityRouter(
            storage = InMemoryMediaSourceQualityStorage(),
            seed = mediaSeed(
                seedRow("comic", trusted.sourceUrl, trusted.sourceName, tier = 1, score = 8_000),
                seedRow("comic", ordinary.sourceUrl, ordinary.sourceName, tier = 2, score = 5_000)
            )
        )

        val ordered = router.waterfallSources(ReaderMediaKind.COMIC, listOf(ordinary, imported, trusted))

        assertEquals("内置强漫画", ordered[0].sourceName)
        assertEquals("用户导入漫画", ordered[1].sourceName)
        assertEquals("普通漫画", ordered[2].sourceName)
    }

    @Test
    fun parsesMediaSeedTsvByKind() {
        val seed = MediaSourceQualitySeed.fromTsv(
            """
            kind	sourceUrl	sourceName	tier	score	note
            comic	https://comic.example	漫画源	1	8123	fixture
            audio	https://audio.example	听书源	2	6123	fixture
            """.trimIndent()
        )

        assertEquals(1, seed.recordFor(ReaderMediaKind.COMIC, source("漫画源", "https://comic.example", MediaSourceType.COMIC))?.tier)
        assertEquals(6_123, seed.recordFor(ReaderMediaKind.AUDIO, source("听书源", "https://audio.example", MediaSourceType.AUDIO))?.score)
    }

    private fun mediaSeed(vararg rows: String): MediaSourceQualitySeed {
        return MediaSourceQualitySeed.fromTsv(
            """
            kind	sourceUrl	sourceName	tier	score	note
            ${rows.joinToString("\n")}
            """.trimIndent()
        )
    }

    private fun seedRow(kind: String, url: String, name: String, tier: Int, score: Int): String {
        return listOf(kind, url, name, tier.toString(), score.toString(), "test").joinToString("\t")
    }

    private fun detail(book: MediaSourceBook): MediaSourceBookDetail {
        return MediaSourceBookDetail(
            book = book,
            name = book.name,
            author = book.author,
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = "",
            tocUrl = "${book.bookUrl}/toc"
        )
    }

    private fun book(source: MediaSourceDefinition, name: String, author: String = "", coverUrl: String = ""): MediaSourceBook {
        return MediaSourceBook(
            source = source,
            name = name,
            author = author,
            bookUrl = "${source.sourceUrl}/book",
            coverUrl = coverUrl,
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun chapter(book: MediaSourceBook): MediaSourceChapter {
        return MediaSourceChapter(
            source = book.source,
            book = book,
            index = 0,
            name = "第一话",
            chapterUrl = "${book.bookUrl}/1"
        )
    }

    private fun source(name: String, url: String, type: Int, comment: String? = null): MediaSourceDefinition {
        return MediaSourceDefinition(
            sourceName = name,
            sourceUrl = url,
            sourceType = type,
            sourceGroup = null,
            sourceComment = comment,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search?q={{key}}",
            ruleSearch = MediaLegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = MediaLegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = MediaLegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = MediaLegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
    }
}
