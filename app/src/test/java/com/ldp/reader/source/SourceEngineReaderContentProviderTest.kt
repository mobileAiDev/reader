package com.ldp.reader.source

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.legado.HttpFetcher
import com.ldp.reader.sourceengine.legado.HttpRequest
import com.ldp.reader.sourceengine.legado.HttpResponse
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.ContentQualityReport
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer
import com.ldp.reader.sourceengine.content.v8.V8ChapterInput
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8ContentQualitySignal
import com.ldp.reader.sourceengine.search.RankedSearchBook
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceEngineReaderContentProviderTest {
    @After
    fun tearDown() {
        SourceEngineCatalogMarkRegistry.clearForTest()
    }

    @Test
    fun v8ValidationDigestChangesWhenQualitySignalChanges() {
        val base = listOf(
            V8ChapterInput(
                index = 7,
                title = "Chapter 7",
                content = "same chapter body",
                contentQualitySignal = V8ContentQualitySignal(
                    qualityScore = 100,
                    coherenceScore = 100,
                    cleanedLength = 17,
                    warnings = emptyList()
                )
            )
        )
        val changedSignal = listOf(
            base.single().copy(
                contentQualitySignal = V8ContentQualitySignal(
                    qualityScore = 20,
                    coherenceScore = 0,
                    cleanedLength = 17,
                    warnings = listOf("content-unusable")
                )
            )
        )

        val first = SourceEngineV8ValidationDigest.compute(base, setOf(7))
        val second = SourceEngineV8ValidationDigest.compute(changedSignal, setOf(7))

        assertFalse(first == second)
    }

    @Test
    fun validatesPrioritySourceEvenWhenItIsOutsideTopScoredCandidates() {
        val provider = SourceEngineReaderContentProvider()
        val topScored = (1..5).map { index ->
            rankedBook(
                sourceName = "普通源$index",
                sourceUrl = "https://ordinary$index.example",
                score = 20_000 - index
            )
        }
        val priorityCandidate = rankedBook(
            sourceName = "55读书",
            sourceUrl = "https://www.changduzw.com",
            score = 10_000
        )

        val method = provider.javaClass.getDeclaredMethod("validationCandidatesForTitle", List::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val candidates = method.invoke(provider, topScored + priorityCandidate) as List<RankedSearchBook>

        assertTrue(candidates.any { it.book.source.sourceName == "55读书" })
    }

    @Test
    fun fallbackProbeCollectionKeepsCompletedValuesWhenSlowProbeTimesOut() = runBlocking {
        val provider = SourceEngineReaderContentProvider()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val fast = scope.async {
                delay(20)
                "readable-source"
            }
            val slow = scope.async<String?> {
                delay(5_000)
                "slow-source"
            }

            val values = provider.awaitFinishedValuesWithin(listOf(fast, slow), timeoutMs = 150)

            assertEquals(listOf("readable-source"), values)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fallbackProbeCollectionStopsAfterSuccessLimit() = runBlocking {
        val provider = SourceEngineReaderContentProvider()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = scope.async<String?> {
                delay(20)
                "first-readable"
            }
            val second = scope.async<String?> {
                delay(5_000)
                "second-readable"
            }

            val values = provider.awaitFinishedValuesWithinLimit(listOf(first, second), timeoutMs = 1_000, limit = 1)

            assertEquals(listOf("first-readable"), values)
            assertTrue(second.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun tailShortLengthUsesQuarterOfAverageVerifiedLength() {
        val provider = SourceEngineReaderContentProvider()
        val verifiedTailLengths = listOf(9_779, 8_988, 9_610, 6_071)

        assertFalse(provider.isTailChapterTooShortAgainstAverage(2_950, verifiedTailLengths))
        assertTrue(provider.isTailChapterTooShortAgainstAverage(2_100, verifiedTailLengths))
        assertFalse(provider.isTailChapterTooShortAgainstAverage(2_100, listOf(9_779)))
    }

    @Test
    fun searchResultRequiresValidatedCatalog() {
        val provider = SourceEngineReaderContentProvider()

        assertTrue(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-tail-content"))
        assertTrue(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-tail-content+merged-cover"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-content"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-content-fingerprint"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 1_500, validation = "detail-catalog-unreadable"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 0, validation = "unvalidated"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 1_500, validation = "unvalidated"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 4, validation = "detail-catalog"))
    }

    @Test
    fun matchingChapterRejectsSameOrdinalWithDifferentTitleSuffix() {
        val provider = SourceEngineReaderContentProvider()
        val source = changduSource("目录错位源", "https://chapter-mismatch.example")
        val book = sourceBook("目录错位源", "https://chapter-mismatch.example", "诡秘之主", "爱潜水的乌贼")
        val target = ChapterNormalizer().normalize("第七十三章 那个层次")
        val initialBattle = SourceChapter(
            source = source,
            book = book,
            index = 72,
            name = "第七十三章 初战",
            chapterUrl = "https://chapter-mismatch.example/book/1/73.html"
        )
        val catalog = CanonicalChapterList(
            chapters = listOf(
                CanonicalChapter(
                    key = "c:0:n:73:初战",
                    displayTitle = "第七十三章 初战",
                    ordinal = 73,
                    sourceChapters = listOf(initialBattle)
                )
            ),
            duplicateCount = 0,
            missingOrdinalRanges = emptyList()
        )

        val matched = provider.matchingChapter(catalog, target)

        assertEquals(null, matched)
    }

    @Test
    fun longTitleSearchAddsGenericShortPrefixQuery() {
        val provider = SourceEngineReaderContentProvider()
        val method = provider.javaClass.getDeclaredMethod("searchQueriesFor", String::class.java)
        method.isAccessible = true

        val longTitleQueries = method.invoke(provider, "雪中悍刀行") as List<*>
        val shortTitleQueries = method.invoke(provider, "遮天") as List<*>

        assertEquals("雪中悍刀行", longTitleQueries.first())
        assertTrue(longTitleQueries.contains("雪中"))
        assertFalse(shortTitleQueries.contains("遮"))
    }

    @Test
    fun fallbackMatchingKeepsSameTitleCandidatesWhenInitialAuthorIsWrong() {
        val provider = SourceEngineReaderContentProvider()
        val titleMethod = provider.javaClass.getDeclaredMethod(
            "isSameTitleCandidate",
            SourceBook::class.java,
            SourceBook::class.java
        )
        titleMethod.isAccessible = true
        val authorMethod = provider.javaClass.getDeclaredMethod(
            "authorAgreementScore",
            SourceBook::class.java,
            SourceBook::class.java
        )
        authorMethod.isAccessible = true
        val wrongInitial = sourceBook("错误首源", "https://wrong.example", "步步惊心", "子夜月隐")
        val betterSameTitle = sourceBook("可读源", "https://readable.example", "步步惊心", "桐华")
        val sameAuthor = sourceBook("同作者源", "https://same.example", "步步惊心", "子夜月隐")

        assertTrue(titleMethod.invoke(provider, wrongInitial, betterSameTitle) as Boolean)
        assertEquals(0, authorMethod.invoke(provider, wrongInitial, betterSameTitle))
        assertEquals(2, authorMethod.invoke(provider, wrongInitial, sameAuthor))
    }

    @Test
    fun searchBooksDisplaysOnlyCatalogValidatedResult() = runBlocking {
        val sources = listOf(
            changduSource(),
            changduSource("镜像源", "https://mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://www.changduzw.com", "玄鉴仙族", "季越人", "第1497章 庙语", 1_497) +
                    trustedBookFixture("https://mirror.example", "玄鉴仙族", "季越人", "第1497章 庙语", 1_497)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl.substringBefore("#") == sourceUrl.substringBefore("#") } }
        )

        val books = provider.searchBooks("玄鉴仙族")

        assertEquals(1, books.size)
        assertEquals("玄鉴仙族", books.first().title)
        assertEquals("季越人", books.first().author)
        assertTrue(SourceEngineBookRoute.isBookId(books.first().routeId))
    }

    @Test
    fun searchBooksCleansSpacedAuthorPrefix() = runBlocking {
        val sources = listOf(
            changduSource("带前缀源", "https://author-prefix.example"),
            changduSource("带前缀镜像源", "https://author-prefix-mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://author-prefix.example", "青山", "作 者：会说话的肘子", "第671章 凭姨保驾护航", 671) +
                    trustedBookFixture("https://author-prefix-mirror.example", "青山", "作 者：会说话的肘子", "第671章 凭姨保驾护航", 671)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("青山")

        assertEquals("会说话的肘子", books.first().author)
    }

    @Test
    fun searchBooksMergesAuthorPrefixedSourcesBeforeChoosingReadingSource() = runBlocking {
        val cleanA = changduSource("干净源A", "https://clean-a.example")
        val cleanB = changduSource("干净源B", "https://clean-b.example")
        val prefixedTail = changduSource("前缀长尾源", "https://prefixed-tail.example")
        val sources = listOf(cleanA, cleanB, prefixedTail)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://clean-a.example", "青山", "会说话的肘子", "第671章 正文", 671) +
                    trustedBookFixture("https://clean-b.example", "青山", "会说话的肘子", "第671章 正文", 671) +
                    trustedBookFixture(
                        baseUrl = "https://prefixed-tail.example",
                        title = "青山",
                        author = "会说话的肘子",
                        lastChapter = "第672章 正文",
                        chapterCount = 672,
                        coverUrl = "",
                        searchAuthor = "作者：会说话的肘子"
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("青山")
        val route = SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId))

        assertEquals("会说话的肘子", books.first().author)
        assertEquals("https://prefixed-tail.example", route.sourceUrl)
    }

    @Test
    fun completedSearchKeepsValidatingAfterFirstStrongCandidate() = runBlocking {
        val earlySources = (1..5).map { index ->
            changduSource("早到源$index", "https://early-$index.example")
        }
        val lateTail = changduSource("后到长尾源", "https://late-tail.example")
        val sources = earlySources + lateTail
        val earlyFixtures = earlySources
            .map { source ->
                trustedBookFixture(
                    baseUrl = source.sourceUrl,
                    title = "青山",
                    author = "会说话的肘子",
                    lastChapter = "第671章 正文",
                    chapterCount = 671,
                    searchAuthor = "会说话的肘子"
                )
            }
            .fold(emptyMap<String, String>()) { acc, item -> acc + item }
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = earlyFixtures + trustedBookFixture(
                    baseUrl = lateTail.sourceUrl,
                    title = "青山",
                    author = "会说话的肘子",
                    lastChapter = "第672章 正文",
                    chapterCount = 672,
                    searchAuthor = "作者：会说话的肘子"
                ),
                delays = mapOf(lateTail.sourceUrl to 250L)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("青山")
        val route = SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId))

        assertEquals(lateTail.sourceUrl, route.sourceUrl)
    }

    @Test
    fun searchBooksRejectsCatalogCandidateWithoutReadableContentAnchor() = runBlocking {
        val source = changduSource("不可读目录源", "https://unreadable.example")
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://unreadable.example/modules/article/search.php" to searchHtml(
                        bookUrl = "https://unreadable.example/books/1/",
                        title = "玄鉴仙族",
                        lastChapter = "第1500章 伪更新",
                        author = "季越人"
                    ),
                    "https://unreadable.example/books/1/" to detailHtml(
                        title = "玄鉴仙族",
                        author = "季越人",
                        lastChapter = "第1500章 伪更新"
                    ),
                    "https://unreadable.example/book/1/" to catalogHtml("https://unreadable.example", 5),
                    "https://unreadable.example/book/1/1.html" to unreadableChapterHtml(),
                    "https://unreadable.example/book/1/2.html" to unreadableChapterHtml(),
                    "https://unreadable.example/book/1/3.html" to unreadableChapterHtml()
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("玄鉴仙族")

        assertTrue(books.isEmpty())
    }

    @Test
    fun searchBooksCanUseSameGroupMetadataCoverWithoutCountingItAsReadableTrust() = runBlocking {
        val readableA = changduSource("正文源A", "https://readable-a.example")
        val readableB = changduSource("正文源B", "https://readable-b.example")
        val coverOnly = changduSource("封面目录源", "https://cover-only.example")
        val sources = listOf(readableA, readableB, coverOnly)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    baseUrl = "https://readable-a.example",
                    title = "诡秘之主",
                    author = "爱潜水的乌贼",
                    lastChapter = "第5章 正文",
                    chapterCount = 5,
                    coverUrl = "",
                    searchAuthor = ""
                ) + trustedBookFixture(
                    baseUrl = "https://readable-b.example",
                    title = "诡秘之主",
                    author = "爱潜水的乌贼",
                    lastChapter = "第5章 正文",
                    chapterCount = 5,
                    coverUrl = "",
                    searchAuthor = ""
                ) + unreadableBookFixture(
                    baseUrl = "https://cover-only.example",
                    title = "诡秘之主",
                    author = "爱潜水的乌贼",
                    lastChapter = "第5章 正文",
                    chapterCount = 5,
                    coverUrl = "file:///cover.jpg"
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("诡秘之主")

        assertEquals(1, books.size)
        assertEquals("诡秘之主", books.first().title)
        assertEquals("爱潜水的乌贼", books.first().author)
        assertEquals("file:///cover.jpg", books.first().cover)
        assertEquals(
            "https://readable-a.example",
            SourceEngineBookRoute.decodeBookId(books.first().routeId!!).sourceUrl
        )
    }

    @Test
    fun getBookInfoHidesLatestChapterUntilVerifiedCatalog() = runBlocking {
        val source = changduSource("尾章污染源", "https://tail.example")
        val mirrorSource = changduSource("尾章污染镜像源", "https://tail-mirror.example")
        val sources = listOf(source, mirrorSource)
        val fixture = trustedBookFixture(
            baseUrl = "https://tail.example",
            title = "青山",
            author = "会说话的肘子",
            lastChapter = "第11章 伪更新",
            chapterCount = 10
        ) + trustedBookFixture(
            baseUrl = "https://tail-mirror.example",
            title = "青山",
            author = "会说话的肘子",
            lastChapter = "第11章 伪更新",
            chapterCount = 10
        )
        val engine = LegadoSourceEngine(MapFetcher(fixture))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("青山")
        val detail = provider.getBookInfo(books.first().routeId)
        val chapters = provider.getBookFolder(books.first().routeId, detail.collBookBean)

        assertEquals(null, detail.lastChapter)
        assertEquals(0, detail.chaptersCount)
        assertEquals("第10章 正文", chapters.last().title)
        assertEquals(10, chapters.size)
    }

    @Test
    fun searchBooksWaitsWithinTitleGroupAndSelectsLongerCatalog() = runBlocking {
        val shortSource = changduSource("短目录源", "https://short.example")
        val longSource = changduSource("长目录源", "https://long.example")
        val longMirrorSource = changduSource("长目录镜像源", "https://long-mirror.example")
        val sources = listOf(shortSource, longSource, longMirrorSource)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://short.example", "将夜", "墨客007", "第80章 不是这个", 80) +
                    trustedBookFixture("https://long.example", "将夜", "猫腻", "第一千章 彼岸", 1_000) +
                    trustedBookFixture("https://long-mirror.example", "将夜", "猫腻", "第一千章 彼岸", 1_000),
                delays = mapOf("https://long.example/book/1/" to 250L)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("将夜")

        assertEquals(1, books.size)
        assertEquals("将夜", books.first().title)
        assertEquals("猫腻", books.first().author)
        assertEquals("https://long.example", SourceEngineBookRoute.decodeBookId(books.first().routeId!!).sourceUrl)
    }

    @Test
    fun searchBooksKeepsSameTitleDifferentAuthorsSeparate() = runBlocking {
        val sources = listOf(
            changduSource("竹已源", "https://zhu.example"),
            changduSource("竹已镜像源", "https://zhu-mirror.example"),
            changduSource("糖不甜源", "https://tang.example"),
            changduSource("糖不甜镜像源", "https://tang-mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://zhu.example", "难哄", "竹已", "第50章 上火", 50) +
                    trustedBookFixture("https://zhu-mirror.example", "难哄", "竹已", "第50章 上火", 50) +
                    trustedBookFixture("https://tang.example", "难哄", "糖不甜", "第618章 结局篇下：全文完", 618) +
                    trustedBookFixture("https://tang-mirror.example", "难哄", "糖不甜", "第618章 结局篇下：全文完", 618)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("难哄")

        assertEquals(listOf("糖不甜", "竹已"), books.map { it.author })
    }

    @Test
    fun searchBooksMergesSourceAuthorSuffixNoise() = runBlocking {
        val sources = listOf(
            changduSource("原作者源A", "https://doupo-clean-a.example"),
            changduSource("原作者源B", "https://doupo-clean-b.example"),
            changduSource("作者后缀源A", "https://doupo-qdsuffix-a.example"),
            changduSource("作者后缀源B", "https://doupo-qdsuffix-b.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://doupo-clean-a.example", "斗破苍穹", "天蚕土豆", "第100章 正文", 100) +
                    trustedBookFixture("https://doupo-clean-b.example", "斗破苍穹", "天蚕土豆", "第100章 正文", 100) +
                    trustedBookFixture("https://doupo-qdsuffix-a.example", "斗破苍穹", "天蚕土豆_qd22", "第20章 正文", 20) +
                    trustedBookFixture("https://doupo-qdsuffix-b.example", "斗破苍穹", "天蚕土豆_qd22", "第20章 正文", 20)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("斗破苍穹")

        assertEquals(listOf("天蚕土豆"), books.map { it.author })
    }

    @Test
    fun searchBooksDoesNotUsePageCatalogAsReadingSource() = runBlocking {
        val pageSource = changduSource("分页目录源", "https://page-catalog.example")
        val chapterSourceA = changduSource("章节目录源A", "https://chapter-catalog-a.example")
        val chapterSourceB = changduSource("章节目录源B", "https://chapter-catalog-b.example")
        val sources = listOf(pageSource, chapterSourceA, chapterSourceB)
        val engine = LegadoSourceEngine(
            MapFetcher(
                pageCatalogFixture(
                    baseUrl = "https://page-catalog.example",
                    title = "斗破苍穹",
                    author = "天蚕土豆",
                    pageCount = 120
                ) +
                    trustedBookFixture(
                        "https://chapter-catalog-a.example",
                        "斗破苍穹",
                        "天蚕土豆",
                        "第100章 正文",
                        100
                    ) +
                    trustedBookFixture(
                        "https://chapter-catalog-b.example",
                        "斗破苍穹",
                        "天蚕土豆",
                        "第100章 正文",
                        100
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("斗破苍穹")

        assertEquals(1, books.size)
        assertEquals(
            "https://chapter-catalog-a.example",
            SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId)).sourceUrl
        )
    }

    @Test
    fun searchBooksPrefersHigherReadableOrdinalOverHigherRawCatalogCount() = runBlocking {
        val rawLongIncomplete = changduSource("raw多但漏尾源", "https://raw-long-incomplete.example")
        val completeA = changduSource("尾部完整源A", "https://complete-tail-a.example")
        val completeB = changduSource("尾部完整源B", "https://complete-tail-b.example")
        val sources = listOf(rawLongIncomplete, completeA, completeB)
        val incompleteTitles = (1..159).map { index -> "第${index}章 正文" } +
            (1..31).map { index -> "第159章 正文$index" }
        val completeTitles = (1..162).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://raw-long-incomplete.example",
                    title = "斗破苍穹",
                    author = "天蚕土豆",
                    chapterTitles = incompleteTitles,
                    customChapterHtml = { _, chapterTitle ->
                        readableChapterHtmlWithDisplayTitle("斗破苍穹", "天蚕土豆", chapterTitle)
                    }
                ) +
                    customCatalogFixture(
                        baseUrl = "https://complete-tail-a.example",
                        title = "斗破苍穹",
                        author = "天蚕土豆",
                        chapterTitles = completeTitles
                    ) +
                    customCatalogFixture(
                        baseUrl = "https://complete-tail-b.example",
                        title = "斗破苍穹",
                        author = "天蚕土豆",
                        chapterTitles = completeTitles
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("斗破苍穹")

        assertEquals(1, books.size)
        assertEquals(
            "https://complete-tail-a.example",
            SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId)).sourceUrl
        )
    }

    @Test
    fun getBookFolderKeepsHigherReadableOrdinalOverHigherRawCatalogCount() = runBlocking {
        val rawLongIncomplete = changduSource("raw多但漏尾源", "https://raw-long-incomplete.example")
        val completeA = changduSource("尾部完整源A", "https://complete-tail-a.example")
        val completeB = changduSource("尾部完整源B", "https://complete-tail-b.example")
        val sources = listOf(rawLongIncomplete, completeA, completeB)
        val incompleteTitles = (1..159).map { index -> "第${index}章 正文" } +
            (1..31).map { index -> "番外 错误目录$index" }
        val completeTitles = (1..162).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://raw-long-incomplete.example",
                    title = "斗破苍穹",
                    author = "天蚕土豆",
                    chapterTitles = incompleteTitles
                ) +
                    customCatalogFixture(
                        baseUrl = "https://complete-tail-a.example",
                        title = "斗破苍穹",
                        author = "天蚕土豆",
                        chapterTitles = completeTitles
                    ) +
                    customCatalogFixture(
                        baseUrl = "https://complete-tail-b.example",
                        title = "斗破苍穹",
                        author = "天蚕土豆",
                        chapterTitles = completeTitles
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val books = provider.searchBooks("斗破苍穹")

        val detail = provider.getBookInfo(books.first().routeId)
        val chapters = provider.getBookFolder(books.first().routeId, detail.collBookBean)

        assertEquals("第162章 正文", chapters.last().title)
    }

    @Test
    fun searchBooksPrefersContinuousTailWhenLastReadableOrdinalTies() = runBlocking {
        val gappedTail = changduSource("尾部缺章源", "https://gapped-tail.example")
        val completeA = changduSource("尾部连续源A", "https://continuous-tail-a.example")
        val completeB = changduSource("尾部连续源B", "https://continuous-tail-b.example")
        val sources = listOf(gappedTail, completeA, completeB)
        val gappedTitles = (1..160).map { index -> "第${index}章 正文" } +
            (1..10).map { index -> "第162章 正文$index" }
        val completeTitles = (1..162).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://gapped-tail.example",
                    title = "斗破苍穹",
                    author = "天蚕土豆",
                    chapterTitles = gappedTitles
                ) +
                    customCatalogFixture(
                        baseUrl = "https://continuous-tail-a.example",
                        title = "斗破苍穹",
                        author = "天蚕土豆",
                        chapterTitles = completeTitles
                    ) +
                    customCatalogFixture(
                        baseUrl = "https://continuous-tail-b.example",
                        title = "斗破苍穹",
                        author = "天蚕土豆",
                        chapterTitles = completeTitles
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("斗破苍穹")

        assertEquals(
            "https://continuous-tail-a.example",
            SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId)).sourceUrl
        )
    }

    @Test
    fun searchBooksKeepsMultipleConsensusTitleGroups() = runBlocking {
        val sources = listOf(
            changduSource("青山源", "https://qingshan.example"),
            changduSource("青山镜像源", "https://qingshan-mirror.example"),
            changduSource("青山有幸源", "https://qingshan-youxing.example"),
            changduSource("青山有幸镜像源", "https://qingshan-youxing-mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://qingshan.example", "青山", "会说话的肘子", "第673章 凭姨保驾护航", 673) +
                    trustedBookFixture("https://qingshan-mirror.example", "青山", "会说话的肘子", "第673章 凭姨保驾护航", 673) +
                    trustedBookFixture("https://qingshan-youxing.example", "青山有幸", "更俗", "第120章 归途", 120) +
                    trustedBookFixture("https://qingshan-youxing-mirror.example", "青山有幸", "更俗", "第120章 归途", 120)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("青山")

        assertEquals(listOf("青山" to "会说话的肘子", "青山有幸" to "更俗"), books.map { it.title to it.author })
    }

    @Test
    fun longExactTitleSearchDoesNotPromoteContainingTitleWithLongerCatalog() = runBlocking {
        val sources = listOf(
            changduSource("原书源", "https://doupo.example"),
            changduSource("原书镜像源", "https://doupo-mirror.example"),
            changduSource("衍生源", "https://doupo-derivative.example"),
            changduSource("衍生镜像源", "https://doupo-derivative-mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://doupo.example", "斗破苍穹", "天蚕土豆", "第5章 正文", 5) +
                    trustedBookFixture("https://doupo-mirror.example", "斗破苍穹", "天蚕土豆", "第5章 正文", 5) +
                    trustedBookFixture("https://doupo-derivative.example", "穿越斗破苍穹", "午时一刻", "第1000章 正文", 1_000) +
                    trustedBookFixture("https://doupo-derivative-mirror.example", "穿越斗破苍穹", "午时一刻", "第1000章 正文", 1_000)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("斗破苍穹")

        assertEquals(listOf("斗破苍穹" to "天蚕土豆"), books.map { it.title to it.author })
    }

    @Test
    fun progressiveSearchKeepsSearchingAfterFirstVisibleResult() = runBlocking {
        val sources = listOf(
            changduSource("青山源", "https://progress-qingshan.example"),
            changduSource("青山镜像源", "https://progress-qingshan-mirror.example"),
            changduSource("青山有幸源", "https://progress-qingshan-youxing.example"),
            changduSource("青山有幸镜像源", "https://progress-qingshan-youxing-mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://progress-qingshan.example", "青山", "会说话的肘子", "第673章 凭姨保驾护航", 673) +
                    trustedBookFixture("https://progress-qingshan-mirror.example", "青山", "会说话的肘子", "第673章 凭姨保驾护航", 673) +
                    trustedBookFixture("https://progress-qingshan-youxing.example", "青山有幸", "更俗", "第120章 归途", 120) +
                    trustedBookFixture("https://progress-qingshan-youxing-mirror.example", "青山有幸", "更俗", "第120章 归途", 120),
                delays = mapOf(
                    "https://progress-qingshan-youxing.example/modules/article/search.php" to 2_500L,
                    "https://progress-qingshan-youxing-mirror.example/modules/article/search.php" to 2_500L
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<Pair<String?, String?>>>()

        val books = provider.searchBooksProgressively("青山") { update ->
            updates.add(update.map { it.title to it.author })
        }

        assertTrue(updates.any { update -> update == listOf("青山" to "会说话的肘子") })
        assertEquals(listOf("青山" to "会说话的肘子", "青山有幸" to "更俗"), books.map { it.title to it.author })
        assertTrue(updates.last().contains("青山有幸" to "更俗"))
    }

    @Test
    fun progressiveLongExactTitleWaitsForHigherConsensusAuthorGroup() = runBlocking {
        val sources = listOf(
            changduSource("原书源1", "https://progress-doupo-a.example"),
            changduSource("原书源2", "https://progress-doupo-b.example"),
            changduSource("原书源3", "https://progress-doupo-c.example"),
            changduSource("同名源1", "https://progress-doupo-noise-a.example"),
            changduSource("同名源2", "https://progress-doupo-noise-b.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://progress-doupo-a.example", "斗破苍穹", "天蚕土豆", "第1000章 正文", 1_000) +
                    trustedBookFixture("https://progress-doupo-b.example", "斗破苍穹", "天蚕土豆", "第1000章 正文", 1_000) +
                    trustedBookFixture("https://progress-doupo-c.example", "斗破苍穹", "天蚕土豆", "第1000章 正文", 1_000) +
                    trustedBookFixture("https://progress-doupo-noise-a.example", "斗破苍穹", "九支书竹", "第50章 正文", 50) +
                    trustedBookFixture("https://progress-doupo-noise-b.example", "斗破苍穹", "九支书竹", "第50章 正文", 50),
                delays = mapOf(
                    "https://progress-doupo-a.example/book/1/" to 1_500L,
                    "https://progress-doupo-b.example/book/1/" to 1_500L,
                    "https://progress-doupo-c.example/book/1/" to 1_500L
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<Pair<String?, String?>>>()

        val books = provider.searchBooksProgressively("斗破苍穹") { update ->
            updates.add(update.map { it.title to it.author })
        }

        assertTrue(updates.isNotEmpty())
        assertEquals("天蚕土豆", updates.first().first().second)
        assertEquals("天蚕土豆", books.first().author)
    }

    @Test
    fun progressiveSearchDefersCandidateWhenReadableTailLagsFreshnessHint() = runBlocking {
        val badA = changduSource("尾部断裂源A", "https://progress-tail-bad-a.example")
        val badB = changduSource("尾部断裂源B", "https://progress-tail-bad-b.example")
        val goodA = changduSource("尾部完整源A", "https://progress-tail-good-a.example")
        val goodB = changduSource("尾部完整源B", "https://progress-tail-good-b.example")
        val sources = listOf(badA, badB, goodA, goodB)
        val engine = LegadoSourceEngine(
            MapFetcher(
                qingShanTailFixture(
                    baseUrl = "https://progress-tail-bad-a.example",
                    chapterCount = 102,
                    pollutedChapters = setOf(101)
                ) +
                    qingShanTailFixture(
                        baseUrl = "https://progress-tail-bad-b.example",
                        chapterCount = 102,
                        pollutedChapters = setOf(101)
                    ) +
                    qingShanTailFixture(
                        baseUrl = "https://progress-tail-good-a.example",
                        chapterCount = 103,
                        pollutedChapters = emptySet()
                    ) +
                    qingShanTailFixture(
                        baseUrl = "https://progress-tail-good-b.example",
                        chapterCount = 103,
                        pollutedChapters = emptySet()
                    ),
                delays = mapOf(
                    "https://progress-tail-good-a.example/books/1/" to 350L,
                    "https://progress-tail-good-b.example/books/1/" to 350L
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<String>>()

        val books = provider.searchBooksProgressively("青山") { update ->
            updates.add(update.map { result ->
                SourceEngineBookRoute.decodeBookId(requireNotNull(result.routeId)).sourceUrl
            })
        }

        assertTrue(updates.isNotEmpty())
        assertTrue(updates.first().first() in setOf(
            "https://progress-tail-good-a.example",
            "https://progress-tail-good-b.example"
        ))
        assertTrue(
            SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId)).sourceUrl in setOf(
                "https://progress-tail-good-a.example",
                "https://progress-tail-good-b.example"
            )
        )
    }

    @Test
    fun searchBooksDoesNotStopAfterFirstStrongSameTitleCandidate() = runBlocking {
        val sources = listOf(
            changduSource("先返回强目录源", "https://early.example"),
            changduSource("中等目录源1", "https://middle1.example"),
            changduSource("中等目录源2", "https://middle2.example"),
            changduSource("中等目录源3", "https://middle3.example"),
            changduSource("后返回更强目录源", "https://better.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://early.example", "剑来", "烽火戏诸侯", "第900章 先到", 900) +
                    trustedBookFixture("https://middle1.example", "剑来", "烽火戏诸侯", "第一千章 中等", 1_000) +
                    trustedBookFixture("https://middle2.example", "剑来", "烽火戏诸侯", "第一千零一章 中等", 1_001) +
                    trustedBookFixture("https://middle3.example", "剑来", "烽火戏诸侯", "第一千零二章 中等", 1_002) +
                    trustedBookFixture("https://better.example", "剑来", "烽火戏诸侯", "第三千二百章 后到", 3_200),
                delays = mapOf("https://better.example/book/1/" to 250L)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("剑来")

        assertEquals(1, books.size)
        assertEquals("https://better.example", SourceEngineBookRoute.decodeBookId(books.first().routeId!!).sourceUrl)
    }

    @Test
    fun firstDisplayCatalogPrefersLongerVerifiedTailOverLongerRawPollutedTail() = runBlocking {
        val sources = listOf(
            changduSource("raw长但尾部坏1", "https://raw-long-bad-a.example"),
            changduSource("raw长但尾部坏2", "https://raw-long-bad-b.example"),
            changduSource("短一点但尾部完整", "https://verified-tail.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                qingShanTailFixture(
                    baseUrl = "https://raw-long-bad-a.example",
                    chapterCount = 8,
                    pollutedChapters = setOf(6)
                ) +
                    qingShanTailFixture(
                        baseUrl = "https://raw-long-bad-b.example",
                        chapterCount = 8,
                        pollutedChapters = setOf(6)
                    ) +
                    qingShanTailFixture(
                        baseUrl = "https://verified-tail.example",
                        chapterCount = 6,
                        pollutedChapters = emptySet()
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("青山")
        val folder = provider.getBookFolder(
            books.first().routeId,
            CollBookBean().apply {
                set_id("qingshan-test")
                title = "青山"
                author = "会说话的肘子"
            }
        )

        assertEquals("第6章 正文", folder.last().title)
        assertEquals(6, folder.size)
    }

    @Test
    fun getBookContentDisplaysSingleTrustedSourceWhenNoSecondSourceExists() = runBlocking {
        val source = changduSource("唯一可信源", "https://single-trusted.example")
        val engine = LegadoSourceEngine(
            MapFetcher(
                qingShanTailFixture(
                    baseUrl = "https://single-trusted.example",
                    chapterCount = 12,
                    pollutedChapters = emptySet()
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val book = SourceBook(
            source = source,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://single-trusted.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第12章 正文"
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = 11,
            name = "第12章 正文",
            chapterUrl = "https://single-trusted.example/book/1/12.html"
        )
        val collBook = CollBookBean().apply {
            title = "青山"
            author = "会说话的肘子"
        }
        val txtChapter = TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(book)
            link = SourceEngineBookRoute.chapterId(chapter)
            title = chapter.name
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("陈迹与老耳朵"))
        assertTrue(content.contains("镜城港"))
    }

    @Test
    fun currentReadDisplaysPersonalCandidateBeforeFingerprintTrust() = runBlocking {
        val currentSource = changduSource("当前源", "https://current-fast-display.example")
        val candidateSource = changduSource("专属候选源", "https://candidate-fast-display.example")
        val chapterTitles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://current-fast-display.example",
                    title = "青山",
                    author = "会说话的肘子",
                    chapterTitles = chapterTitles,
                    customChapterHtml = { _, _ -> unreadableChapterHtml() }
                ) + customCatalogFixture(
                    baseUrl = "https://candidate-fast-display.example",
                    title = "青山",
                    author = "会说话的肘子",
                    chapterTitles = chapterTitles,
                    customChapterHtml = { index, title ->
                        if (index == 12) {
                            readableChapterHtmlWithDisplayTitle("青山", "会说话的肘子", title)
                        } else {
                            unreadableChapterHtml()
                        }
                    }
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(currentSource, candidateSource) },
            sourceFinder = { sourceUrl ->
                listOf(currentSource, candidateSource)
                    .first { source -> source.sourceUrl.substringBefore("#") == sourceUrl.substringBefore("#") }
            },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val candidateBook = SourceBook(
            source = candidateSource,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://candidate-fast-display.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第12章 正文"
        )
        seedBookPersonalSource(
            provider,
            SourceChapter(
                source = candidateSource,
                book = candidateBook,
                index = 11,
                name = "第12章 正文",
                chapterUrl = "https://candidate-fast-display.example/book/1/12.html"
            )
        )
        val currentBook = SourceBook(
            source = currentSource,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://current-fast-display.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第12章 正文"
        )
        val currentChapter = SourceChapter(
            source = currentSource,
            book = currentBook,
            index = 11,
            name = "第12章 正文",
            chapterUrl = "https://current-fast-display.example/book/1/12.html"
        )
        val collBook = CollBookBean().apply {
            title = "青山"
            author = "会说话的肘子"
        }
        val txtChapter = TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(currentBook)
            link = SourceEngineBookRoute.chapterId(currentChapter)
            title = currentChapter.name
            start = 11L
            sourceEngineCurrentReadRequest = true
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("第12章 正文"))
        assertTrue(content.contains("萧炎"))
    }

    @Test
    fun displayableReadingContentDoesNotRequireReadableQualityThreshold() {
        val provider = SourceEngineReaderContentProvider()
        val displayableMethod = provider.javaClass.getDeclaredMethod("hasDisplayableContent", CleanContent::class.java)
        val readableMethod = provider.javaClass.getDeclaredMethod("isReadableContent", CleanContent::class.java)
        displayableMethod.isAccessible = true
        readableMethod.isAccessible = true
        val content = CleanContent(
            rawContent = "月白风清 ".repeat(80),
            cleanedContent = "月白风清 ".repeat(80),
            report = ContentQualityReport(
                qualityScore = 30,
                rawLength = 400,
                cleanedLength = 400,
                paragraphCount = 8,
                removedLineCount = 0,
                duplicateLineCount = 0,
                pollutionMarkers = emptyList(),
                warnings = listOf("content-may-belong-to-other-book"),
                coherenceScore = 0
            )
        )

        assertFalse(readableMethod.invoke(provider, content) as Boolean)
        assertTrue(displayableMethod.invoke(provider, content) as Boolean)
    }

    @Test
    fun runtimeReadableMarkSkipsPrefetchChapter() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第389章 查找", 389).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = false
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent("月白风清 ".repeat(80), qualityScore = 30, coherenceScore = 0)
        )

        assertEquals(V8ChapterMarkState.WRONG.name, txtChapter.sourceIntegrityState)
        assertEquals(markReason("v8"), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeReadableMarkDoesNotClearCurrentDiagnosticChapterWithoutHeading() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第389章 查找", 389).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = true
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent("月白风清 ".repeat(80), qualityScore = 30, coherenceScore = 0)
        )

        assertEquals(V8ChapterMarkState.WRONG.name, txtChapter.sourceIntegrityState)
        assertEquals(markReason("v8"), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeHeadingConflictDoesNotRecordWrongMark() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第390章 吞金", 390)

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent(
                "第391章 吞金\n" + "金霞漫天 ".repeat(80),
                qualityScore = 90,
                coherenceScore = 90
            )
        )

        assertEquals(null, txtChapter.sourceIntegrityState)
        assertEquals(null, txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeHeadingConflictDoesNotClearSingleSourceHiddenMark() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第390章 吞金", 390).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = true
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent(
                "第391章 吞金\n" + "金霞漫天 ".repeat(80),
                qualityScore = 90,
                coherenceScore = 90
            )
        )

        assertEquals(V8ChapterMarkState.WRONG.name, txtChapter.sourceIntegrityState)
        assertEquals(markReason("v8"), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeReadableMarkDoesNotClearHiddenReadableContentWithoutHeading() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第393章 出手", 393).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = true
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent("唐枫和叶峰在考古队长面前低声交谈。".repeat(40), qualityScore = 80, coherenceScore = 80),
            trustedEvidenceCount = 2
        )

        assertEquals(V8ChapterMarkState.WRONG.name, txtChapter.sourceIntegrityState)
        assertEquals(markReason("v8"), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeReadableMarkClearsCurrentChapterWhenTwoTrustedSourcesAgree() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第389章 查找", 389).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = true
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent(
                "第389章 查找\n" + "可惜了，飞升修士大多是求金种子。".repeat(50),
                qualityScore = 70,
                coherenceScore = 80
            ),
            trustedEvidenceCount = 2
        )

        assertEquals(V8ChapterMarkState.NORMAL.name, txtChapter.sourceIntegrityState)
        assertEquals(sourceIntegrityPersistedReason(listOf("runtime readable content v2")), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeReadableMarkClearsPrefetchChapterWhenTwoTrustedSourcesAgree() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第389章 查找", 389).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = false
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent(
                "第389章 查找\n" + "可惜了，飞升修士大多是求金种子。".repeat(50),
                qualityScore = 70,
                coherenceScore = 80
            ),
            trustedEvidenceCount = 2
        )

        assertEquals(V8ChapterMarkState.NORMAL.name, txtChapter.sourceIntegrityState)
        assertEquals(sourceIntegrityPersistedReason(listOf("runtime readable content v2")), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun runtimeReadableMarkDoesNotClearNumberDriftWhenTwoTrustedSourcesAgree() {
        val provider = SourceEngineReaderContentProvider()
        val txtChapter = sourceEngineTxtChapter("第390章 吞金", 390).apply {
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
            sourceEngineCurrentReadRequest = false
        }

        invokeDisplayedChapterMark(
            provider,
            txtChapter,
            cleanContent(
                "第391章 吞金\n" + "片刻后，下方战场已经再无一个活人。".repeat(50),
                qualityScore = 70,
                coherenceScore = 80
            ),
            trustedEvidenceCount = 2
        )

        assertEquals(V8ChapterMarkState.WRONG.name, txtChapter.sourceIntegrityState)
        assertEquals(markReason("v8"), txtChapter.sourceIntegrityReason)
    }

    @Test
    fun getBookContentDisplaysLowQualityDiagnosticContentInsteadOfBlocking() = runBlocking {
        val source = changduSource("低质诊断源", "https://low-quality-display.example")
        val chapterTitles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://low-quality-display.example",
                    title = "青山",
                    author = "会说话的肘子",
                    chapterTitles = chapterTitles,
                    customChapterHtml = { index, _ ->
                        if (index == 12) foreignButDisplayableChapterHtml() else qingShanReadableChapterHtml(index)
                    }
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val book = SourceBook(
            source = source,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://low-quality-display.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第12章 正文"
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = 11,
            name = "第12章 正文",
            chapterUrl = "https://low-quality-display.example/book/1/12.html"
        )
        val collBook = CollBookBean().apply {
            title = "青山"
            author = "会说话的肘子"
        }
        val txtChapter = TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(book)
            link = SourceEngineBookRoute.chapterId(chapter)
            title = chapter.name
            start = 11L
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("月白风清"))
        assertTrue(content.contains("曼荼罗"))
    }

    @Test
    fun searchBooksRejectsCatalogBackedAuthorOnlyTitleQueryResult() = runBlocking {
        val source = changduSource("污染源", "https://polluted.example")
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://polluted.example/modules/article/search.php" to searchHtml(
                        bookUrl = "https://polluted.example/books/1/",
                        title = "团宠娇娇是锦鲤，白眼狼们悔断肠",
                        lastChapter = "第50章 郝氏作妖",
                        author = "步步惊心之丽"
                    ),
                    "https://polluted.example/books/1/" to detailHtml(
                        title = "团宠娇娇是锦鲤，白眼狼们悔断肠",
                        author = "步步惊心之丽",
                        lastChapter = "第50章 郝氏作妖"
                    ),
                    "https://polluted.example/book/1/" to catalogHtml("https://polluted.example", 50)
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source }
        )

        val books = provider.searchBooks("步步惊心")

        assertTrue(books.isEmpty())
    }

    @Test
    fun searchBooksRejectsCatalogBackedDetailTitleMismatch() = runBlocking {
        val source = changduSource("详情错配源", "https://mismatch.example")
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://mismatch.example/modules/article/search.php" to searchHtml(
                        bookUrl = "https://mismatch.example/books/1/",
                        title = "《何以笙箫默》txt+epub+mobi全集",
                        lastChapter = "全集",
                        author = "顾漫"
                    ),
                    "https://mismatch.example/books/1/" to detailHtml(
                        title = "致,逝去的青春 最新章节 作者其他书",
                        author = "米宣儿",
                        lastChapter = "尾声"
                    ),
                    "https://mismatch.example/book/1/" to catalogHtml("https://mismatch.example", 9)
                )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source }
        )

        val books = provider.searchBooks("何以笙箫默")

        assertTrue(books.isEmpty())
    }

    private fun invokeDisplayedChapterMark(
        provider: SourceEngineReaderContentProvider,
        txtChapter: TxtChapter,
        content: CleanContent,
        trustedEvidenceCount: Int = 1
    ) {
        val method = provider.javaClass.getDeclaredMethod(
            "markDisplayedChapterReadable",
            CollBookBean::class.java,
            TxtChapter::class.java,
            CleanContent::class.java,
            String::class.java,
            Integer.TYPE
        )
        method.isAccessible = true
        method.invoke(
            provider,
            CollBookBean().apply {
                title = "苟在两界修仙"
                author = "文抄公"
            },
            txtChapter,
            content,
            "平凡文学",
            trustedEvidenceCount
        )
    }

    private fun seedBookPersonalSource(
        provider: SourceEngineReaderContentProvider,
        chapter: SourceChapter
    ) {
        val field = provider.javaClass.getDeclaredField("sourceQualityRouter")
        field.isAccessible = true
        val router = field.get(provider)
        val method = router.javaClass.getDeclaredMethod(
            "recordContentResolved",
            SourceChapter::class.java,
            CleanContent::class.java
        )
        repeat(2) {
            method.invoke(
                router,
                chapter,
                cleanContent(
                    "第12章 正文\n" + "陈迹与老耳朵站在镜城港。".repeat(80),
                    qualityScore = 90,
                    coherenceScore = 90
                )
            )
        }
    }

    private fun sourceEngineTxtChapter(title: String, index: Int): TxtChapter {
        val source = source("平凡文学", "https://pfwx.example")
        val book = SourceBook(
            source = source,
            name = "苟在两界修仙",
            author = "文抄公",
            bookUrl = "https://pfwx.example/book/1/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = "第999章 正文"
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = index,
            name = title,
            chapterUrl = "https://pfwx.example/book/1/$index.html"
        )
        return TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(book)
            link = SourceEngineBookRoute.chapterId(chapter)
            this.title = title
            catalogIndex = index
        }
    }

    private fun cleanContent(text: String, qualityScore: Int, coherenceScore: Int): CleanContent {
        return CleanContent(
            rawContent = text,
            cleanedContent = text,
            report = ContentQualityReport(
                qualityScore = qualityScore,
                rawLength = text.length,
                cleanedLength = text.length,
                paragraphCount = text.lines().size,
                removedLineCount = 0,
                duplicateLineCount = 0,
                pollutionMarkers = emptyList(),
                warnings = emptyList(),
                coherenceScore = coherenceScore
            )
        )
    }

    private fun rankedBook(
        sourceName: String,
        sourceUrl: String,
        score: Int
    ): RankedSearchBook {
        return RankedSearchBook(
            book = SourceBook(
                source = source(sourceName, sourceUrl),
                name = "苟在妖武乱世修仙",
                author = "文抄公",
                bookUrl = "$sourceUrl/books/1/",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第1100章 大结局"
            ),
            score = score,
            evidence = "fixture",
            sourceIndex = 99,
            resultIndex = 0
        )
    }

    private fun sourceBook(
        sourceName: String,
        sourceUrl: String,
        title: String,
        author: String
    ): SourceBook {
        return SourceBook(
            source = source(sourceName, sourceUrl),
            name = title,
            author = author,
            bookUrl = "$sourceUrl/book/1/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun source(sourceName: String, sourceUrl: String): BookSource {
        return BookSource(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
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

    private fun changduSource(): BookSource {
        return changduSource("55读书", "https://www.changduzw.com#未月十八repair")
    }

    private fun changduSource(sourceName: String, sourceUrl: String): BookSource {
        val baseUrl = sourceUrl.substringBefore("#")
        return BookSource(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "$baseUrl/modules/article/search.php,{'charset':'utf-8','method':'POST','body':'searchkey={{key}}&type=submit'}",
            ruleSearch = LegadoRuleSet(
                "ruleSearch",
                mapOf(
                    "author" to "td.2@text",
                    "bookList" to "tbody tr!0",
                    "bookUrl" to "td.0@a@href",
                    "lastChapter" to "td.1@a@text",
                    "name" to "td.0@a@text"
                )
            ),
            ruleBookInfo = LegadoRuleSet(
                "ruleBookInfo",
                mapOf(
                    "author" to "class.status@tag.p.1@text",
                    "coverUrl" to "class.imgbox@tag.img@src",
                    "intro" to "class.intro@text",
                    "kind" to "class.status@tag.a.0@text",
                    "lastChapter" to "class.red.1@text",
                    "name" to "class.status@tag.h1@text",
                    "tocUrl" to "text.点击阅读@href"
                )
            ),
            ruleToc = LegadoRuleSet(
                "ruleToc",
                mapOf(
                    "chapterList" to "class.mulu_list@tag.a",
                    "chapterName" to "text",
                    "chapterUrl" to "href"
                )
            ),
            ruleContent = LegadoRuleSet(
                "ruleContent",
                mapOf("content" to "#content@text||body@text")
            ),
            diagnostics = emptyList()
        )
    }

    private fun searchHtml(
        bookUrl: String,
        title: String,
        lastChapter: String,
        author: String
    ): String {
        return """
            <html><body>
              <table><tbody>
                <tr><th>小说</th><th>最新章节</th><th>作者</th></tr>
                <tr>
                  <td class="odd"><a href="$bookUrl">$title</a></td>
                  <td class="even"><a href="/chapter/latest.html">$lastChapter</a></td>
                  <td class="odd">$author</td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
    }

    private fun detailHtml(
        title: String,
        author: String,
        lastChapter: String,
        coverUrl: String = "file:///cover.jpg"
    ): String {
        val coverHtml = if (coverUrl.isBlank()) {
            ""
        } else {
            """<div class="imgbox"><img src="$coverUrl" /></div>"""
        }
        return """
            <html><body>
              $coverHtml
              <div class="intro">$title 是 $author 创作的长篇小说，情节完整，人物关系清晰。</div>
              <div class="status">
                <h1>$title</h1>
                <p class="bz"></p>
                <p class="author">作者：$author</p>
                <p>分　类：<a href="/fenlei/2_1/">言情小说</a></p>
                <p>更　新：<span class="red">2026-05-20</span></p>
                <p>最新章节：<a href="/chapter/latest.html"><span class="red">$lastChapter</span></a></p>
                <a class="button read" href="/book/1/">点击阅读</a>
              </div>
            </body></html>
        """.trimIndent()
    }

    private fun trustedBookFixture(
        baseUrl: String,
        title: String,
        author: String,
        lastChapter: String,
        chapterCount: Int,
        coverUrl: String = "file:///cover.jpg",
        searchAuthor: String = author
    ): Map<String, String> {
        return mapOf(
            "$baseUrl/modules/article/search.php" to searchHtml(
                bookUrl = "$baseUrl/books/1/",
                title = title,
                lastChapter = lastChapter,
                author = searchAuthor
            ),
            "$baseUrl/books/1/" to detailHtml(
                title = title,
                author = author,
                lastChapter = lastChapter,
                coverUrl = coverUrl
            ),
            "$baseUrl/book/1/" to catalogHtml(baseUrl, chapterCount)
        ) + (1..chapterCount).associate { index ->
            "$baseUrl/book/1/$index.html" to readableChapterHtml(title, author, index)
        }
    }

    private fun qingShanTailFixture(
        baseUrl: String,
        chapterCount: Int,
        pollutedChapters: Set<Int>
    ): Map<String, String> {
        return mapOf(
            "$baseUrl/modules/article/search.php" to searchHtml(
                bookUrl = "$baseUrl/books/1/",
                title = "青山",
                lastChapter = "第${chapterCount}章 正文",
                author = "会说话的肘子"
            ),
            "$baseUrl/books/1/" to detailHtml(
                title = "青山",
                author = "会说话的肘子",
                lastChapter = "第${chapterCount}章 正文"
            ),
            "$baseUrl/book/1/" to catalogHtml(baseUrl, chapterCount)
        ) + (1..chapterCount).associate { index ->
            "$baseUrl/book/1/$index.html" to if (index in pollutedChapters) {
                unreadableChapterHtml()
            } else {
                qingShanReadableChapterHtml(index)
            }
        }
    }

    private fun qingShanReadableChapterHtml(index: Int): String {
        val body = (1..8).joinToString("\n") { paragraph ->
            "第${index}章 第${paragraph}段，陈迹与老耳朵站在船舷旁说起镜城港，" +
                "凭姨在艉楼里斟茶，灯火的船队穿过海雾。高丽参、牛角、牛筋与景朝口岸的消息交错，" +
                "陈迹仍想着轩辕藏剑、陆氏旧事和宁朝风波。"
        }
        return """
            <html><body>
              <div id="content">$body</div>
            </body></html>
        """.trimIndent()
    }

    private fun pageCatalogFixture(
        baseUrl: String,
        title: String,
        author: String,
        pageCount: Int
    ): Map<String, String> {
        return mapOf(
            "$baseUrl/modules/article/search.php" to searchHtml(
                bookUrl = "$baseUrl/books/1/",
                title = title,
                lastChapter = "第${pageCount}页",
                author = author
            ),
            "$baseUrl/books/1/" to detailHtml(
                title = title,
                author = author,
                lastChapter = "第${pageCount}页"
            ),
            "$baseUrl/book/1/" to pageCatalogHtml(baseUrl, pageCount)
        ) + (1..pageCount).associate { index ->
            "$baseUrl/book/1/$index.html" to readableChapterHtml(title, author, index)
        }
    }

    private fun customCatalogFixture(
        baseUrl: String,
        title: String,
        author: String,
        chapterTitles: List<String>,
        customChapterHtml: ((Int, String) -> String)? = null
    ): Map<String, String> {
        val chapterHtml = customChapterHtml ?: { index: Int, _: String ->
            readableChapterHtml(title, author, index)
        }
        return mapOf(
            "$baseUrl/modules/article/search.php" to searchHtml(
                bookUrl = "$baseUrl/books/1/",
                title = title,
                lastChapter = chapterTitles.last(),
                author = author
            ),
            "$baseUrl/books/1/" to detailHtml(
                title = title,
                author = author,
                lastChapter = chapterTitles.last()
            ),
            "$baseUrl/book/1/" to titledCatalogHtml(baseUrl, chapterTitles)
        ) + chapterTitles.indices.associate { index ->
            "$baseUrl/book/1/${index + 1}.html" to chapterHtml(index + 1, chapterTitles[index])
        }
    }

    private fun unreadableBookFixture(
        baseUrl: String,
        title: String,
        author: String,
        lastChapter: String,
        chapterCount: Int,
        coverUrl: String = "file:///cover.jpg"
    ): Map<String, String> {
        return mapOf(
            "$baseUrl/modules/article/search.php" to searchHtml(
                bookUrl = "$baseUrl/books/1/",
                title = title,
                lastChapter = lastChapter,
                author = author
            ),
            "$baseUrl/books/1/" to detailHtml(
                title = title,
                author = author,
                lastChapter = lastChapter,
                coverUrl = coverUrl
            ),
            "$baseUrl/book/1/" to catalogHtml(baseUrl, chapterCount)
        ) + (1..chapterCount).associate { index ->
            "$baseUrl/book/1/$index.html" to unreadableChapterHtml()
        }
    }

    private fun catalogHtml(baseUrl: String, count: Int): String {
        val chapters = (1..count).joinToString("\n") { index ->
            """<li><a href="$baseUrl/book/1/$index.html">第${index}章 正文</a></li>"""
        }
        return """
            <html><body>
              <ul class="mulu_list">
                $chapters
              </ul>
            </body></html>
        """.trimIndent()
    }

    private fun pageCatalogHtml(baseUrl: String, count: Int): String {
        val chapters = (1..count).joinToString("\n") { index ->
            """<li><a href="$baseUrl/book/1/$index.html">第${index}页</a></li>"""
        }
        return """
            <html><body>
              <ul class="mulu_list">
                $chapters
              </ul>
            </body></html>
        """.trimIndent()
    }

    private fun titledCatalogHtml(baseUrl: String, titles: List<String>): String {
        val chapters = titles.mapIndexed { index, title ->
            """<li><a href="$baseUrl/book/1/${index + 1}.html">$title</a></li>"""
        }.joinToString("\n")
        return """
            <html><body>
              <ul class="mulu_list">
                $chapters
              </ul>
            </body></html>
        """.trimIndent()
    }

    private fun unreadableChapterHtml(): String {
        return """
            <html><body>
              <div id="content">请收藏本站，方便下次阅读。</div>
            </body></html>
        """.trimIndent()
    }

    private fun readableChapterHtml(title: String, author: String, index: Int): String {
        val body = (1..8).joinToString("\n") { paragraph ->
            "第${index}章 第${paragraph}段，$title 的故事继续推进。陈平安与宁姚在剑气长城商议远行，" +
                "$author 写下山河、飞剑、渡船、宗门与旧友之间的因果。夜色落下，众人仍守着本心，" +
                "把眼前的风雪、城头的剑光和远处的江湖一一记清。"
        }
        return """
            <html><body>
              <div id="content">$body</div>
            </body></html>
        """.trimIndent()
    }

    private fun readableChapterHtmlWithDisplayTitle(title: String, author: String, chapterTitle: String): String {
        val body = (1..8).joinToString("\n") { paragraph ->
            "$chapterTitle 第${paragraph}段，$title 的故事继续推进。萧炎与药老在黑角域商议远行，" +
                "$author 写下异火、斗气、宗门与旧友之间的因果。夜色落下，众人仍守着本心，" +
                "把眼前的风雪、火焰和远处的大陆一一记清。"
        }
        return """
            <html><body>
              <div id="content">$body</div>
            </body></html>
        """.trimIndent()
    }

    private fun foreignButDisplayableChapterHtml(): String {
        val body = (1..10).joinToString("\n") { paragraph ->
            "第十二章 月白风清 第${paragraph}段，元始法则的曼荼罗殿在月色里摇晃，" +
                "秦桑沿着石阶听见钟声，白鹿青、云海、剑阵和旧日仙盟的消息交错而来。"
        }
        return """
            <html><body>
              <div id="content">$body</div>
            </body></html>
        """.trimIndent()
    }

    private fun testBookCacheFolderPath(folderName: String?): String {
        val root = java.io.File("build/tmp/source-engine-reader-content-provider-test/book-cache")
        return java.io.File(
            root,
            com.ldp.reader.utils.BookCacheKey.folderSegment(folderName).orEmpty()
        ).absolutePath
    }

    private fun markReason(value: String): String {
        return sourceIntegrityPersistedReason(listOf(value))
    }

    private class MapFetcher(
        private val responses: Map<String, String>,
        private val delays: Map<String, Long> = emptyMap()
    ) : HttpFetcher {
        override fun fetch(request: HttpRequest): HttpResponse {
            delays.entries.firstOrNull { (prefix, _) -> request.url.startsWith(prefix) }
                ?.value
                ?.let { Thread.sleep(it) }
            val body = responses[request.url]
                ?: readableChapterResponse(request.url)
                ?: error("No fixture response for ${request.url}")
            return HttpResponse(request.url, body)
        }

        private fun readableChapterResponse(url: String): String? {
            if (!url.contains("/book/") || !url.endsWith(".html")) return null
            return """
                <html><body>
                  <div id="content">
                    ${readableChapterText()}
                  </div>
                </body></html>
            """.trimIndent()
        }

        private fun readableChapterText(): String {
            return buildString {
                repeat(12) {
                    append("陆江仙在大黎山旁醒来，青灰铜镜映出云雾与溪流。")
                    append("李家族人沿着山路巡视灵田，符阵与灵脉缓缓运转。")
                    append("这一章保持家族修仙的连续语境，人物、场景和修行线索都清楚完整。")
                }
            }
        }
    }
}
