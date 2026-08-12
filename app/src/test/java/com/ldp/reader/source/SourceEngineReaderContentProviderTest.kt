package com.ldp.reader.source

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.EngineFailure
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
import com.ldp.reader.sourceengine.model.SourceBookDetail
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer
import com.ldp.reader.sourceengine.content.v8.V8ChapterInput
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8ContentQualitySignal
import com.ldp.reader.sourceengine.content.v8.V8SourceRunRequest
import com.ldp.reader.sourceengine.content.v8.V8SourceRunResult
import com.ldp.reader.sourceengine.search.RankedSearchBook
import com.ldp.reader.widget.page.TxtChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass

class SourceEngineReaderContentProviderTest {
    @Test
    fun catalogFullScanJoinsLightScanThenProcessesEveryStoryChapterAndReusesCache() = runBlocking {
        val baseUrl = "https://full-catalog-v8.example"
        val title = "全目录检查测试书"
        val author = "测试作者"
        val storyTitles = (1..127).map { index -> "第${index}章 正文" }
        val catalogTitles = storyTitles + listOf("作者感言", "下载说明")
        val source = changduSource("全目录检查源", baseUrl)
        val fetchedUrls = Collections.synchronizedList(ArrayList<String>())
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = customCatalogFixture(baseUrl, title, author, catalogTitles),
                onFetch = fetchedUrls::add
            )
        )
        val lightValidationEntered = CountDownLatch(1)
        val releaseLightValidation = CountDownLatch(1)
        val firstValidation = AtomicBoolean(true)
        val validatedTargetBatches = Collections.synchronizedList(ArrayList<Set<Int>>())
        val cacheRoot = java.nio.file.Files.createTempDirectory("reader-v8-full-provider").toFile()
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { true },
            v8MarkCacheFactory = { SourceEngineV8MarkCache { cacheRoot } },
            v8Validator = { request: V8SourceRunRequest ->
                val targets = request.markableChapterIndexes.orEmpty().toSortedSet()
                validatedTargetBatches.add(targets)
                if (firstValidation.compareAndSet(true, false)) {
                    lightValidationEntered.countDown()
                    releaseLightValidation.await(5, TimeUnit.SECONDS)
                }
                Thread.sleep(5L)
                val titlesByIndex = request.chapters.associateBy { chapter -> chapter.index }
                val marks = targets.map { index ->
                    V8ChapterMarkResult(
                        chapterIndex = index,
                        chapterTitle = titlesByIndex[index]?.title.orEmpty(),
                        state = if (index == 77) V8ChapterMarkState.WRONG else V8ChapterMarkState.NORMAL,
                        confidence = 0.9,
                        qualityType = null,
                        suggestionState = null,
                        action = null,
                        reasons = listOf("provider-test")
                    )
                }
                V8SourceRunResult(
                    title = request.title,
                    author = request.author,
                    sourceKey = request.sourceKey,
                    marks = marks,
                    planningMarks = marks
                )
            }
        )
        val sourceBook = sourceBook(source, title, author, storyTitles.last())
        val routeId = SourceEngineBookRoute.bookId(sourceBook)
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_full-catalog-provider")
            this.title = title
            this.author = author
        }

        try {
            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = false,
                triggerV8 = true,
                requestPriority = SourceRequestPriority.BACKGROUND,
                mode = SourceContentTierMode.READING_LIGHT
            )
            assertTrue("light scan must be active before the full scan is requested", lightValidationEntered.await(5, TimeUnit.SECONDS))

            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = false,
                triggerV8 = true,
                requestPriority = SourceRequestPriority.BACKGROUND,
                mode = SourceContentTierMode.CATALOG_FULL_SCAN
            )
            releaseLightValidation.countDown()

            val snapshots = ArrayList<SourceEngineV8ScanProgress>()
            val terminal = withTimeout(20_000L) {
                while (true) {
                    provider.fullCatalogV8ProgressForBook(routeId, collBook)?.let { progress ->
                        snapshots += progress
                        if (!progress.running) return@withTimeout progress
                    }
                    delay(5L)
                }
                error("unreachable")
            }
            val coveredTargets = synchronized(validatedTargetBatches) {
                validatedTargetBatches.flatten().toSet()
            }

            assertEquals((0 until 127).toSet(), coveredTargets)
            assertTrue(validatedTargetBatches.size > 2)
            assertTrue(validatedTargetBatches.all { batch -> batch.size <= 16 })
            assertEquals(SourceEngineV8ScanPhase.COMPLETED, terminal.phase)
            assertEquals(127, terminal.processed)
            assertEquals(127, terminal.target)
            assertEquals(1, terminal.wrong)
            assertTrue(snapshots.zipWithNext().all { (left, right) -> right.processed >= left.processed })
            assertTrue(snapshots.all { progress -> progress.target == 127 })
            assertFalse(fetchedUrls.any { url -> url.endsWith("/128.html") || url.endsWith("/129.html") })

            val displayChapters = catalogTitles.mapIndexed { offset, chapterTitle ->
                BookChapterBean().apply {
                    val chapter = SourceChapter(
                        source = source,
                        book = sourceBook,
                        index = offset,
                        name = chapterTitle,
                        chapterUrl = "$baseUrl/book/1/${offset + 1}.html"
                    )
                    link = SourceEngineBookRoute.chapterId(chapter)
                    this.title = chapterTitle
                }
            }
            val applied = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(displayChapters)
            assertTrue(applied.matched >= 127)
            assertEquals(V8ChapterMarkState.WRONG.name, displayChapters[77].sourceIntegrityState)

            val validationCountBeforeCacheHit = validatedTargetBatches.size
            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = false,
                triggerV8 = true,
                requestPriority = SourceRequestPriority.BACKGROUND,
                mode = SourceContentTierMode.CATALOG_FULL_SCAN
            )
            delay(100L)
            val cacheHit = provider.fullCatalogV8ProgressForBook(routeId, collBook)
            assertEquals(validationCountBeforeCacheHit, validatedTargetBatches.size)
            assertTrue(cacheHit?.cacheHit == true)
            assertEquals(127, cacheHit?.processed)
        } finally {
            releaseLightValidation.countDown()
            provider.cancelV8ValidationForBook(routeId, collBook, "test-finished")
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun catalogFullScanFollowsTheVisibleCatalogSourceInsteadOfALongerTierSource() = runBlocking {
        val title = "可见目录全量检查测试书"
        val author = "测试作者"
        val visibleSource = changduSource("可见目录源", "https://visible-catalog-v8.example")
        val longerTierSource = changduSource("更长补源", "https://longer-tier-v8.example")
        val sources = listOf(visibleSource, longerTierSource)
        val visibleTitles = (1..100).map { index -> "第${index}章 正文" }
        val longerTierTitles = (1..127).map { index -> "第${index}章 补源正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(visibleSource.sourceUrl, title, author, visibleTitles) +
                    customCatalogFixture(longerTierSource.sourceUrl, title, author, longerTierTitles)
            )
        )
        val visibleBook = sourceBook(visibleSource, title, author, visibleTitles.last())
        val longerTierBook = sourceBook(longerTierSource, title, author, longerTierTitles.last())
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        repeat(3) {
            sourceQualityRouter.recordTrustedCatalogResolved(
                longerTierBook,
                chapterCount = longerTierTitles.size,
                rawChapterCount = longerTierTitles.size
            )
        }
        val validatedSources = Collections.synchronizedList(ArrayList<String>())
        val validatedTargets = Collections.synchronizedList(ArrayList<Set<Int>>())
        val cacheRoot = java.nio.file.Files.createTempDirectory("reader-v8-visible-catalog-provider").toFile()
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { true },
            v8MarkCacheFactory = { SourceEngineV8MarkCache { cacheRoot } },
            v8Validator = { request: V8SourceRunRequest ->
                val targets = request.markableChapterIndexes.orEmpty().toSortedSet()
                validatedSources.add(request.sourceKey)
                validatedTargets.add(targets)
                val titlesByIndex = request.chapters.associateBy { chapter -> chapter.index }
                val marks = targets.map { index ->
                    V8ChapterMarkResult(
                        chapterIndex = index,
                        chapterTitle = titlesByIndex[index]?.title.orEmpty(),
                        state = V8ChapterMarkState.NORMAL,
                        confidence = 0.9,
                        qualityType = null,
                        suggestionState = null,
                        action = null,
                        reasons = listOf("visible-catalog-provider-test")
                    )
                }
                V8SourceRunResult(
                    title = request.title,
                    author = request.author,
                    sourceKey = request.sourceKey,
                    marks = marks,
                    planningMarks = marks
                )
            }
        )
        val routeId = SourceEngineBookRoute.bookId(visibleBook)
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_visible-catalog-provider")
            this.title = title
            this.author = author
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        val tierFile = java.io.File(cacheFolder, ".source_engine_content_tier")
        tierFile.parentFile?.mkdirs()
        tierFile.writeText(
            listOf(visibleBook, longerTierBook)
                .joinToString("\n") { book -> SourceEngineBookRoute.bookId(book) }
        )

        try {
            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = true,
                triggerV8 = false,
                requestPriority = SourceRequestPriority.BACKGROUND,
                mode = SourceContentTierMode.READING_LIGHT
            )
            collBook.setBookChapters(
                visibleTitles.mapIndexed { index, chapterTitle ->
                    val chapter = SourceChapter(
                        source = visibleSource,
                        book = visibleBook,
                        index = index,
                        name = chapterTitle,
                        chapterUrl = "${visibleSource.sourceUrl}/book/1/${index + 1}.html"
                    )
                    BookChapterBean().apply {
                        bookId = collBook.get_id()
                        link = SourceEngineBookRoute.chapterId(chapter)
                        this.title = chapterTitle
                    }
                }
            )

            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = true,
                triggerV8 = true,
                requestPriority = SourceRequestPriority.BACKGROUND,
                mode = SourceContentTierMode.CATALOG_FULL_SCAN
            )
            val terminal = withTimeout(20_000L) {
                while (true) {
                    provider.fullCatalogV8ProgressForBook(routeId, collBook)?.let { progress ->
                        if (!progress.running) return@withTimeout progress
                    }
                    delay(5L)
                }
                error("unreachable")
            }
            val coveredTargets = synchronized(validatedTargets) {
                validatedTargets.flatten().toSet()
            }
            val applied = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(
                collBook.getBookChapters().orEmpty()
            )

            assertEquals(SourceEngineV8ScanPhase.COMPLETED, terminal.phase)
            assertEquals(visibleTitles.size, terminal.target)
            assertEquals(visibleTitles.size, terminal.processed)
            assertEquals((visibleTitles.indices).toSet(), coveredTargets)
            assertTrue(validatedSources.isNotEmpty())
            assertTrue(validatedSources.all { sourceKey -> sourceKey.startsWith("${visibleSource.sourceName}@") })
            assertEquals(visibleTitles.size, applied.matched)
        } finally {
            provider.cancelV8ValidationForBook(routeId, collBook, "test-finished")
            cacheRoot.deleteRecursively()
            cacheFolder.deleteRecursively()
        }
    }

    @Test
    fun progressiveSearchOnlyStopsAfterItsVisibleTargetIsReadable() {
        val provider = SourceEngineReaderContentProvider()
        val previewExact = BookSearchResult().apply {
            routeId = "source://preview-exact"
            title = "斗破苍穹"
            author = "天蚕土豆"
            readingReady = false
        }
        val previewRelated = BookSearchResult().apply {
            routeId = "source://preview-related"
            title = "穿越斗破苍穹"
            author = "另一作者"
            readingReady = false
        }
        val readableExact = BookSearchResult().apply {
            routeId = "source://readable-exact"
            title = "斗破苍穹"
            author = "天蚕土豆"
            readingReady = true
        }
        val readableRelated = BookSearchResult().apply {
            routeId = "source://readable-related"
            title = "穿越斗破苍穹"
            author = "另一作者"
            readingReady = true
        }

        assertFalse(provider.progressOutputReadyToStop("斗破苍穹", listOf(previewExact, previewRelated)))
        assertFalse(provider.progressOutputReadyToStop("斗破苍穹", listOf(previewExact, readableRelated)))
        assertFalse(provider.progressOutputReadyToStop("斗破苍穹", listOf(readableExact, previewRelated)))
        assertTrue(provider.progressOutputReadyToStop("斗破苍穹", listOf(readableExact, readableRelated)))
    }

    @Test
    fun progressiveOutputStateKeyChangesWhenTheSameRouteBecomesReadable() {
        val provider = SourceEngineReaderContentProvider()
        val preview = BookSearchResult().apply {
            routeId = "source://same-route"
            title = "斗破苍穹"
            author = "天蚕土豆"
            readingReady = false
        }
        val readable = BookSearchResult().apply {
            routeId = preview.routeId
            title = preview.title
            author = preview.author
            readingReady = true
        }

        assertFalse(
            provider.searchOutputStateKey(listOf(preview)) ==
                provider.searchOutputStateKey(listOf(readable))
        )
    }

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
    fun cachedV8MarksRestoreIntoRegistryBeforeValidationFinishes() {
        val provider = SourceEngineReaderContentProvider()
        val source = source("笔趣阁", "https://restore-cache.example")
        val book = sourceBook("笔趣阁", "https://restore-cache.example", "清光宝鉴", "一片苏叶")
        val titles = listOf("第一章 正文", "第二章 错章", "第三章 尾章")
        val catalogIdentity = SourceEngineCatalogMarkRegistry.catalogIdentity(titles)
        val cacheIdentity = SourceEngineV8MarkCache.Identity(
            sourceBookKey = SourceEngineCatalogMarkRegistry.sourceBookKey(source.sourceUrl, book.bookUrl),
            sourceUrl = source.sourceUrl,
            bookUrl = book.bookUrl,
            bookName = book.name,
            author = book.author,
            catalogSize = catalogIdentity.catalogSize,
            firstTitle = catalogIdentity.firstTitle,
            lastTitle = catalogIdentity.lastTitle,
            tailTitleDigest = catalogIdentity.tailTitleDigest
        )
        val cached = SourceEngineV8MarkCache.CachedMarks(
            identity = cacheIdentity,
            sourceLabel = "笔趣阁@https://restore-cache.example",
            marks = listOf(
                V8ChapterMarkResult(
                    chapterIndex = 2,
                    chapterTitle = "第二章 错章",
                    state = V8ChapterMarkState.WRONG,
                    confidence = 0.9,
                    qualityType = null,
                    suggestionState = null,
                    action = null,
                    reasons = listOf("cached")
                )
            ),
            contentDigest = "digest",
            targetChapterIndexes = listOf(2),
            inputFingerprintsByChapterIndex = emptyMap(),
            createdAtMs = 1L
        )
        val method = provider.javaClass.getDeclaredMethod(
            "recordCachedV8Marks",
            SourceEngineV8MarkCache.CachedMarks::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        val restored = method.invoke(provider, cached, "test", "unit")
        val chapters = titles.mapIndexed { index, title ->
            BookChapterBean().apply {
                val sourceChapter = SourceChapter(
                    source = source,
                    book = book,
                    index = index + 1,
                    name = title,
                    chapterUrl = "https://restore-cache.example/book/1/${index + 1}.html"
                )
                link = SourceEngineBookRoute.chapterId(sourceChapter)
                this.title = title
            }
        }
        val stats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(chapters)

        assertEquals(true, restored)
        assertEquals(1, stats.matched)
        assertEquals(1, stats.hidden)
        assertEquals(V8ChapterMarkState.WRONG.name, chapters[1].sourceIntegrityState)
    }

    @Test
    fun cachedV8MarksApplyToEnrichedCatalogAfterSecondReadingCatalogLoad() = runBlocking {
        val primary = changduSource("V8 截断标题主源", "https://v8-enriched-primary.example")
        val completeTitleSource = changduSource("V8 完整标题源", "https://v8-enriched-complete.example")
        val sources = listOf(primary, completeTitleSource)
        val title = "V8 标题补全测试书"
        val author = "测试作者"
        val primaryTitles = (1..120).map { ordinal ->
            if (ordinal == 42) "第四十二章 融合..." else "第${ordinal}章 正文$ordinal"
        }
        val completeTitles = (1..100).map { ordinal ->
            if (ordinal == 42) "第四十二章 融合成功！" else "第${ordinal}章 正文$ordinal"
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(primary.sourceUrl, title, author, primaryTitles) +
                    customCatalogFixture(completeTitleSource.sourceUrl, title, author, completeTitles)
            )
        )
        val cacheRoot = java.nio.file.Files.createTempDirectory("reader-v8-enriched-cache").toFile()
        val validationCount = AtomicInteger(0)
        val validationEntered = CountDownLatch(1)
        var testedRouteId: String? = null
        var testedCollBook: CollBookBean? = null
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { true },
            v8MarkCacheFactory = { SourceEngineV8MarkCache { cacheRoot } },
            v8Validator = { request ->
                validationCount.incrementAndGet()
                validationEntered.countDown()
                wrongV8Result(request, "provider-cache-identity-test")
            }
        )

        try {
            val searchResult = provider.searchBooks(title).single()
            val detail = provider.getBookInfo(searchResult.routeId)
            val routeId = requireNotNull(detail.routeId)
            val collBook = detail.collBookBean
            testedRouteId = routeId
            testedCollBook = collBook
            val firstCatalog = provider.getBookFolder(routeId, collBook, triggerV8ForReading = true)
            assertEquals("第四十二章 融合成功！", firstCatalog[41].title)
            assertTrue(validationEntered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000L) {
                while (provider.hasActiveV8ValidationForBook(routeId, collBook)) delay(5L)
            }
            val liveStats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(firstCatalog)
            assertTrue("live V8 marks must apply to the enriched display catalog", liveStats.hidden > 0)

            SourceEngineCatalogMarkRegistry.clearForTest()
            val secondCatalog = provider.getBookFolder(routeId, collBook, triggerV8ForReading = true)
            delay(100L)
            val cachedStats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(secondCatalog)

            assertEquals("the second load must reuse the current V8 cache", 1, validationCount.get())
            assertTrue("cached V8 marks must apply to the same enriched display catalog", cachedStats.hidden > 0)
        } finally {
            provider.cancelV8ValidationForBook(testedRouteId, testedCollBook, "test-finished")
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun cachedV8MarksApplyAfterLeadingCatalogFrontMatterIsDropped() = runBlocking {
        val source = changduSource("V8 目录前言源", "https://v8-leading-front-matter.example")
        val title = "V8 目录前言测试书"
        val author = "测试作者"
        val rawTitles = listOf("资料") + (1..120).map { ordinal -> "第${ordinal}章 正文$ordinal" }
        val engine = LegadoSourceEngine(
            MapFetcher(customCatalogFixture(source.sourceUrl, title, author, rawTitles))
        )
        val cacheRoot = java.nio.file.Files.createTempDirectory("reader-v8-front-matter-cache").toFile()
        val validationCount = AtomicInteger(0)
        val validationEntered = CountDownLatch(1)
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { sourceUrl ->
                source.takeIf { it.sourceUrl == sourceUrl } ?: error("unknown source $sourceUrl")
            },
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { true },
            v8MarkCacheFactory = { SourceEngineV8MarkCache { cacheRoot } },
            v8Validator = { request ->
                validationCount.incrementAndGet()
                validationEntered.countDown()
                wrongV8Result(request, "provider-cache-front-matter-test")
            }
        )
        val routeId = SourceEngineBookRoute.bookId(sourceBook(source, title, author, rawTitles.last()))
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_v8-leading-front-matter")
            this.title = title
            this.author = author
        }

        try {
            val firstCatalog = provider.getBookFolder(routeId, collBook, triggerV8ForReading = true)
            assertEquals(120, firstCatalog.size)
            assertEquals("第1章 正文1", firstCatalog.first().title)
            assertTrue(validationEntered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000L) {
                while (provider.hasActiveV8ValidationForBook(routeId, collBook)) delay(5L)
            }
            val liveStats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(firstCatalog)
            assertTrue("live V8 marks must apply after front matter is dropped", liveStats.hidden > 0)

            SourceEngineCatalogMarkRegistry.clearForTest()
            val secondCatalog = provider.getBookFolder(routeId, collBook, triggerV8ForReading = true)
            delay(100L)
            val cachedStats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(secondCatalog)

            assertEquals("the second load must reuse the current V8 cache", 1, validationCount.get())
            assertTrue("cached V8 marks must apply after front matter is dropped", cachedStats.hidden > 0)
        } finally {
            provider.cancelV8ValidationForBook(routeId, collBook, "test-finished")
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun cachedReadingCatalogReusesDetailPreviewResolvedCatalog() = runBlocking {
        val source = changduSource("详情会话缓存源", "https://detail-session-cache.example")
        val sources = listOf(source)
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://detail-session-cache.example",
                    title = "叩问仙道",
                    author = "雨打青石",
                    chapterTitles = (1..120).map { index -> "第${index}章 正文" }
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
        val routeId = SourceEngineBookRoute.bookId(
            SourceBook(
                source = source,
                name = "叩问仙道",
                author = "雨打青石",
                bookUrl = "https://detail-session-cache.example/books/1/",
                coverUrl = "file:///cover.jpg",
                intro = "",
                kind = "",
                lastChapter = "第120章 正文"
            )
        )
        val detail = provider.getBookInfo(routeId)
        val cached = provider.getCachedReadingCatalog(
            routeId,
            CollBookBean().apply {
                set_id("source_engine_shelf_detail-session-cache")
                title = "叩问仙道"
                author = "雨打青石"
            }
        )

        assertEquals(120, detail.chaptersCount)
        assertEquals(120, cached.size)
        assertEquals("第120章 正文", cached.last().title)
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
    fun validationCandidateSelectionFillsRemainingSlotsAfterPriorityDeduplication() {
        val provider = SourceEngineReaderContentProvider()
        val group = (1..20).map { index ->
            rankedBook(
                sourceName = "普通源$index",
                sourceUrl = "https://ordinary$index.example",
                score = 20_000 - index
            )
        }

        val method = provider.javaClass.getDeclaredMethod("validationCandidatesForTitle", List::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val candidates = method.invoke(provider, group) as List<RankedSearchBook>

        assertEquals(16, candidates.size)
        assertTrue(candidates.any { it.book.source.sourceUrl == "https://ordinary17.example" })
    }

    @Test
    fun validationCandidateSelectionSpreadsLargeGroupsPastFrontPrefix() {
        val provider = SourceEngineReaderContentProvider()
        val group = (1..32).map { index ->
            rankedBook(
                sourceName = "大组源$index",
                sourceUrl = "https://large-group-$index.example",
                score = 40_000 - index
            )
        }

        val method = provider.javaClass.getDeclaredMethod("validationCandidatesForTitle", List::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val candidates = method.invoke(provider, group) as List<RankedSearchBook>

        assertEquals(16, candidates.size)
        assertTrue(candidates.any { it.book.source.sourceUrl == "https://large-group-17.example" })
        assertTrue(candidates.any { it.book.source.sourceUrl == "https://large-group-27.example" })
    }

    @Test
    fun validationCandidateBatchesKeepTierThreeAsSupplement() {
        val frontA = rankedBook("前段源1", "https://front-validation-1.example", score = 10_000)
        val frontB = rankedBook("前段源2", "https://front-validation-2.example", score = 9_000)
        val tier3 = (1..20).map { index ->
            rankedBook("后段源$index", "https://tier3-validation-$index.example", score = 30_000 - index)
        }
        val provider = SourceEngineReaderContentProvider(
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    """
                    source	${frontA.book.source.sourceUrl}	${frontA.book.source.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${frontB.book.source.sourceUrl}	${frontB.book.source.sourceName}	2	breadth	6000	0	0	0	0	0	test
                    ${tier3.joinToString("\n") { ranked ->
                        "source\t${ranked.book.source.sourceUrl}\t${ranked.book.source.sourceName}\t3\tbreadth\t1000\t0\t0\t0\t0\t0\ttest"
                    }}
                    """.trimIndent()
                )
            )
        )

        val method = provider.javaClass.getDeclaredMethod(
            "validationCandidateBatchesForTitle",
            List::class.java,
            Integer.TYPE,
            java.lang.Boolean.TYPE
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val batches = method.invoke(provider, tier3 + frontA + frontB, 16, true) as List<Any>

        assertEquals("front-tier", validationBatchLabel(batches[0]))
        assertEquals(
            setOf(frontA.book.source.sourceUrl, frontB.book.source.sourceUrl),
            validationBatchCandidates(batches[0]).map { it.book.source.sourceUrl }.toSet()
        )
        assertEquals("tier3-supplement", validationBatchLabel(batches[1]))
        assertTrue(validationBatchCandidates(batches[1]).all { it.book.source.sourceUrl.startsWith("https://tier3-validation-") })
    }

    @Test
    fun validationCandidateBatchesContinueWithinFrontTierAfterFirstPage() {
        val front = (1..30).map { index ->
            rankedBook("前段分页源$index", "https://front-page-validation-$index.example", score = 30_000 - index)
        }
        val provider = SourceEngineReaderContentProvider(
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    front.joinToString("\n") { ranked ->
                        "source\t${ranked.book.source.sourceUrl}\t${ranked.book.source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            )
        )

        val method = provider.javaClass.getDeclaredMethod(
            "validationCandidateBatchesForTitle",
            List::class.java,
            Integer.TYPE,
            java.lang.Boolean.TYPE
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val batches = method.invoke(provider, front, 16, false) as List<Any>

        assertEquals("front-tier", validationBatchLabel(batches[0]))
        assertEquals("front-tier-2", validationBatchLabel(batches[1]))
        assertEquals(16, validationBatchCandidates(batches[0]).size)
        assertTrue(validationBatchCandidates(batches[1]).isNotEmpty())
        assertTrue(
            validationBatchCandidates(batches[1])
                .any { it.book.source.sourceUrl == "https://front-page-validation-17.example" }
        )
    }

    @Test
    fun completedValidationBatchesDoNotStopAtSixteenCandidates() {
        val front = (1..30).map { index ->
            rankedBook("完整验证源$index", "https://completed-validation-$index.example", score = 30_000 - index)
        }
        val provider = SourceEngineReaderContentProvider(
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    front.joinToString("\n") { ranked ->
                        "source\t${ranked.book.source.sourceUrl}\t${ranked.book.source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            )
        )

        val method = provider.javaClass.getDeclaredMethod(
            "validationCandidateBatchesForTitle",
            List::class.java,
            Integer.TYPE,
            java.lang.Boolean.TYPE
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val batches = method.invoke(provider, front, Int.MAX_VALUE, false) as List<Any>

        assertEquals(1, batches.size)
        assertEquals("front-tier", validationBatchLabel(batches[0]))
        assertEquals(
            front.map { it.book.source.sourceUrl }.toSet(),
            validationBatchCandidates(batches[0]).map { it.book.source.sourceUrl }.toSet()
        )
    }

    @Test
    fun exactProgressValidationCanReturnAfterTwoCompletedCandidates() {
        val provider = SourceEngineReaderContentProvider()
        val method = provider.javaClass.getDeclaredMethod(
            "searchProgressValidationMinCompletedBeforeEarlyReturn",
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            Integer.TYPE
        )
        method.isAccessible = true

        assertEquals(2, method.invoke(provider, true, true, 8))
        assertEquals(4, method.invoke(provider, true, false, 8))
        assertEquals(2, method.invoke(provider, true, false, 2))
    }

    @Test
    fun preferredProgressValidationMaterializesOnlyThePlannedPriorityPairFirst() = runBlocking {
        val query = "星渊仙路"
        val title = "星渊仙途灵兽知恩录"
        val author = "知恩客"
        val prioritySources = (1..2).map { index ->
            changduSource("优先目录源$index", "https://preferred-wave-priority-$index.example")
        }
        val lowerSources = (1..10).map { index ->
            changduSource("后备目录源$index", "https://preferred-wave-lower-$index.example")
        }
        val sources = prioritySources + lowerSources
        val chapterTitles = (1..60).map { index -> "第${index}章 星渊旧事$index" }
        val responses = sources.fold(emptyMap<String, String>()) { current, source ->
            current + customCatalogFixture(source.sourceUrl, title, author, chapterTitles)
        }
        val calls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = prioritySources.associate { source ->
                "${source.sourceUrl}/books/1/" to 250L
            }
        )
        val fetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 5_000,
            callFactory = calls::newCall
        )
        val engine = LegadoSourceEngine(fetcher)
        val materializationStarts = Collections.synchronizedList(ArrayList<String>())
        val activeMaterializations = AtomicInteger(0)
        val maxActiveMaterializations = AtomicInteger(0)
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = fetcher,
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        val score = if (source in prioritySources) 10_000 else 9_000
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t$score\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            catalogMaterializationLifecycle = { book, started ->
                if (started) {
                    materializationStarts.add(book.source.sourceUrl)
                    val active = activeMaterializations.incrementAndGet()
                    maxActiveMaterializations.updateAndGet { previous -> maxOf(previous, active) }
                } else {
                    activeMaterializations.decrementAndGet()
                }
            }
        )
        val candidates = sources.mapIndexed { index, source ->
            RankedSearchBook(
                book = sourceBook(source, title, author, chapterTitles.last()),
                score = 20_000 - index,
                evidence = "preferred-wave-fixture",
                sourceIndex = index,
                resultIndex = 0
            )
        }

        try {
            assertTrue("the fixture must exceed the preferred six-candidate limit", candidates.size > 6)
            val validated = invokePreferredSearchTitleGroupValidation(
                provider = provider,
                candidates = candidates,
                titleKey = title,
                authorKey = author,
                queryKey = query
            )
            val started = synchronized(materializationStarts) { materializationStarts.toList() }
            val executedDetails = calls.executedUrlsSnapshot().filter { url -> url.endsWith("/books/1/") }
            val expectedPriorityUrls = prioritySources.map { source -> source.sourceUrl }

            assertTrue("the planned priority pair must produce a validated group", !validated.isNullOrEmpty())
            assertEquals(
                "only the sorted priority pair may enter full materialization before preferred publication",
                expectedPriorityUrls.toSet(),
                started.toSet()
            )
            assertEquals("exactly one priority wave may start", 2, started.size)
            assertEquals(
                "lower-ranked candidates must not even start detail work before the priority pair is decided",
                prioritySources.mapTo(linkedSetOf()) { source -> "${source.sourceUrl}/books/1/" },
                executedDetails.toSet()
            )
            assertTrue(
                "the P0 catalog memory guard must remain at two active materializations; max=${maxActiveMaterializations.get()}",
                maxActiveMaterializations.get() <= 2
            )
        } finally {
            calls.cancelAll()
        }
    }

    @Test
    fun preferredProgressValidationAdvancesToNextPairWhenPriorityPairIsUnreadable() = runBlocking {
        val query = "星河仙路"
        val title = "星河仙途灵兽知恩录"
        val author = "观星客"
        val prioritySources = (1..2).map { index ->
            changduSource("优先空目录源$index", "https://preferred-wave-empty-$index.example")
        }
        val fallbackSources = (1..2).map { index ->
            changduSource("后备可读源$index", "https://preferred-wave-readable-$index.example")
        }
        val sources = prioritySources + fallbackSources
        val chapterTitles = (1..60).map { index -> "第${index}章 星河旧事$index" }
        val responses = buildMap {
            prioritySources.forEach { source ->
                putAll(detailOnlyFixture(source.sourceUrl, title, author, chapterTitles.last()))
            }
            fallbackSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, title, author, chapterTitles))
            }
        }
        val calls = CancelableFixtureCallFactory(responses)
        val fetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 5_000,
            callFactory = calls::newCall
        )
        val engine = LegadoSourceEngine(fetcher)
        val materializationStarts = Collections.synchronizedList(ArrayList<String>())
        val activeMaterializations = AtomicInteger(0)
        val maxActiveMaterializations = AtomicInteger(0)
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = fetcher,
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        val score = if (source in prioritySources) 10_000 else 9_000
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t$score\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            catalogMaterializationLifecycle = { book, started ->
                if (started) {
                    materializationStarts.add(book.source.sourceUrl)
                    val active = activeMaterializations.incrementAndGet()
                    maxActiveMaterializations.updateAndGet { previous -> maxOf(previous, active) }
                } else {
                    activeMaterializations.decrementAndGet()
                }
            }
        )
        val candidates = sources.mapIndexed { index, source ->
            RankedSearchBook(
                book = sourceBook(source, title, author, chapterTitles.last()),
                score = 20_000 - index,
                evidence = "preferred-wave-fallback-fixture",
                sourceIndex = index,
                resultIndex = 0
            )
        }

        try {
            val validated = invokePreferredSearchTitleGroupValidation(
                provider = provider,
                candidates = candidates,
                titleKey = title,
                authorKey = author,
                queryKey = query
            ).orEmpty()
            val started = synchronized(materializationStarts) { materializationStarts.toList() }
            val readableSourceUrls = validated.asSequence()
                .filter { candidate -> validatedSearchCandidateChapterCount(candidate) >= chapterTitles.size }
                .map { candidate -> validatedSearchCandidateSourceUrl(candidate) }
                .toSet()

            assertEquals("the unreadable priority pair must be the first wave", prioritySources.map { it.sourceUrl }.toSet(), started.take(2).toSet())
            assertEquals("an unreadable first wave must advance to the next sorted pair", fallbackSources.map { it.sourceUrl }.toSet(), started.drop(2).toSet())
            assertEquals("only two ordered waves should be needed", 4, started.size)
            assertEquals("the fallback pair must become readable", fallbackSources.map { it.sourceUrl }.toSet(), readableSourceUrls)
            assertTrue(
                "catalog materialization must remain globally bounded at two; max=${maxActiveMaterializations.get()}",
                maxActiveMaterializations.get() <= 2
            )
        } finally {
            calls.cancelAll()
        }
    }

    @Test
    fun preferredProgressValidationTimeoutInterruptsAndDrainsPriorityPairBeforeStartingFallbackPair() = runBlocking {
        val query = "月海仙路"
        val title = "月海仙途灵兽知恩录"
        val author = "听潮客"
        val prioritySources = (1..2).map { index ->
            changduSource("超时优先目录源$index", "https://preferred-timeout-priority-$index.example")
        }
        val fallbackSources = (1..2).map { index ->
            changduSource("超时后备可读源$index", "https://preferred-timeout-fallback-$index.example")
        }
        val sources = prioritySources + fallbackSources
        val prioritySourceUrls = prioritySources.mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val fallbackSourceUrls = fallbackSources.mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val chapterTitles = (1..60).map { index -> "第${index}章 月海旧事$index" }
        val responses = sources.fold(emptyMap<String, String>()) { current, source ->
            current + detailOnlyFixture(source.sourceUrl, title, author, chapterTitles.last())
        }
        val engine = LegadoSourceEngine(MapFetcher(responses))
        val releaseBlockedPriority = CountDownLatch(1)
        val priorityLoadersEntered = CountDownLatch(prioritySources.size)
        val priorityLoadersInterrupted = AtomicInteger(0)
        val activePriorityMaterializations = AtomicInteger(0)
        val activeMaterializations = AtomicInteger(0)
        val maxActiveMaterializations = AtomicInteger(0)
        val fallbackPriorityActiveAtStart = Collections.synchronizedList(ArrayList<Int>())
        val materializationStarts = Collections.synchronizedList(ArrayList<String>())
        val loader: (SourceBookDetail) -> EngineResult<CanonicalChapterList> = { detail ->
            val sourceUrl = detail.book.source.sourceUrl
            if (sourceUrl in prioritySourceUrls) {
                priorityLoadersEntered.countDown()
                try {
                    if (releaseBlockedPriority.await(5L, TimeUnit.SECONDS)) {
                        error("the blocked priority parser was released by test cleanup instead of cancellation")
                    }
                    error("the blocked priority parser was not interrupted within five seconds")
                } catch (error: InterruptedException) {
                    priorityLoadersInterrupted.incrementAndGet()
                    throw error
                }
            } else {
                fallbackPriorityActiveAtStart.add(activePriorityMaterializations.get())
                EngineResult.Success(canonicalCatalogFixture(detail.book, chapterTitles))
            }
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        val score = if (source in prioritySources) 10_000 else 9_000
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t$score\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            catalogMaterializationLifecycle = { book, started ->
                val priority = book.source.sourceUrl in prioritySourceUrls
                if (started) {
                    materializationStarts.add(book.source.sourceUrl)
                    if (priority) activePriorityMaterializations.incrementAndGet()
                    val active = activeMaterializations.incrementAndGet()
                    maxActiveMaterializations.updateAndGet { previous -> maxOf(previous, active) }
                } else {
                    if (priority) activePriorityMaterializations.decrementAndGet()
                    activeMaterializations.decrementAndGet()
                }
            },
            // Test-only seams. Production defaults remain the real engine loader and the 15s budget.
            canonicalCatalogLoader = loader,
            searchProgressExactValidationBatchTimeoutMs = 250L
        )
        val candidates = sources.mapIndexed { index, source ->
            RankedSearchBook(
                book = sourceBook(source, title, author, chapterTitles.last()),
                score = 20_000 - index,
                evidence = "preferred-timeout-drain-fixture",
                sourceIndex = index,
                resultIndex = 0
            )
        }

        val startedAt = System.currentTimeMillis()
        try {
            val validated = invokePreferredSearchTitleGroupValidation(
                provider = provider,
                candidates = candidates,
                titleKey = title,
                authorKey = author,
                queryKey = query
            ).orEmpty()
            val elapsedMs = System.currentTimeMillis() - startedAt
            val started = synchronized(materializationStarts) { materializationStarts.toList() }
            val fallbackPriorityActive = synchronized(fallbackPriorityActiveAtStart) {
                fallbackPriorityActiveAtStart.toList()
            }
            val readableSourceUrls = validated.asSequence()
                .filter { candidate -> validatedSearchCandidateChapterCount(candidate) >= chapterTitles.size }
                .map { candidate -> validatedSearchCandidateSourceUrl(candidate) }
                .toSet()

            assertEquals("both priority parsers must enter the blocking fixture", 0L, priorityLoadersEntered.count)
            assertEquals("both timed-out priority parsers must be interrupted", 2, priorityLoadersInterrupted.get())
            assertEquals("the timed-out priority pair must be the first wave", prioritySourceUrls, started.take(2).toSet())
            assertEquals("the fallback pair must start only after the timeout drain", fallbackSourceUrls, started.drop(2).toSet())
            assertEquals("exactly two ordered materialization waves must start", 4, started.size)
            assertEquals(
                "each fallback parser must start after the timed-out priority pair reaches active=0",
                listOf(0, 0),
                fallbackPriorityActive
            )
            assertEquals("the fallback pair must become readable", fallbackSourceUrls, readableSourceUrls)
            assertTrue(
                "catalog materialization must remain globally bounded at two; max=${maxActiveMaterializations.get()}",
                maxActiveMaterializations.get() <= 2
            )
            assertEquals("validation must not return with a retained priority parser", 0, activePriorityMaterializations.get())
            assertEquals("validation must return only after every materialization is drained", 0, activeMaterializations.get())
            assertTrue("the cooperative timeout path must finish well before the 5s fallback; elapsedMs=$elapsedMs", elapsedMs < 1_500L)
        } finally {
            releaseBlockedPriority.countDown()
            withTimeoutOrNull(1_000L) {
                while (activeMaterializations.get() != 0) delay(5L)
            }
        }
    }

    @Test
    fun validationOrderingPrefersExactTitleAuthorBeforeAliases() {
        val provider = SourceEngineReaderContentProvider()
        val noisyShortTitle = rankedSearchBook(
            sourceName = "短标题源",
            sourceUrl = "https://validation-order-short.example",
            title = "仙路",
            author = "无关作者",
            score = 50_000,
            sourceIndex = 0
        )
        val nearTitleAlias = rankedSearchBook(
            sourceName = "近名别名源",
            sourceUrl = "https://validation-order-alias.example",
            title = "灵源仙途：我养的灵兽太懂感恩了",
            author = "春雾煮茶",
            score = 40_000,
            sourceIndex = 1
        )
        val containedWrongAuthor = rankedSearchBook(
            sourceName = "包含标题搬运源",
            sourceUrl = "https://validation-order-contained.example",
            title = "灵源仙路：我养的灵兽皆可证道",
            author = "搬运源",
            score = 30_000,
            sourceIndex = 2
        )
        val exact = rankedSearchBook(
            sourceName = "精确标题源",
            sourceUrl = "https://validation-order-exact.example",
            title = "灵源仙路",
            author = "春雾煮茶",
            score = 100,
            sourceIndex = 20
        )
        val method = provider.javaClass.getDeclaredMethod(
            "orderedValidationCandidatesForTitle",
            List::class.java,
            Integer.TYPE,
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val ordered = method.invoke(
            provider,
            listOf(noisyShortTitle, nearTitleAlias, containedWrongAuthor, exact),
            4,
            "灵源仙路",
            "春雾煮茶",
            "灵源仙路",
            false
        ) as List<RankedSearchBook>

        assertEquals("灵源仙路", ordered.first().book.name)
        assertEquals("春雾煮茶", ordered.first().book.author)
    }

    @Test
    fun completedSearchDoesNotStopAtTwelveTitleGroups() = runBlocking {
        data class GroupSource(
            val groupIndex: Int,
            val sourceIndex: Int,
            val source: BookSource,
            val title: String,
            val chapterCount: Int
        )

        val groupSources = (1..13).flatMap { groupIndex ->
            val title = "全量标题${groupIndex.toString().padStart(2, '0')}"
            val chapterCount = if (groupIndex == 13) 120 else 2
            (1..2).map { sourceIndex ->
                GroupSource(
                    groupIndex = groupIndex,
                    sourceIndex = sourceIndex,
                    source = changduSource(
                        "全量组${groupIndex}源${sourceIndex}",
                        "https://completed-title-group-$groupIndex-$sourceIndex.example"
                    ),
                    title = title,
                    chapterCount = chapterCount
                )
            }
        }
        val sources = groupSources.map { it.source }
        val responses = groupSources.fold(emptyMap<String, String>()) { acc, item ->
            acc + trustedBookFixture(
                baseUrl = item.source.sourceUrl,
                title = item.title,
                author = "测试作者",
                lastChapter = "第${item.chapterCount}章 正文",
                chapterCount = item.chapterCount
            )
        }
        val engine = LegadoSourceEngine(MapFetcher(responses))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    groupSources.joinToString("\n") { item ->
                        "source\t${item.source.sourceUrl}\t${item.source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooks("全量标题")

        assertEquals(listOf("全量标题13"), books.map { it.title })
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
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-unreadable"))
        assertTrue(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-tail-pending"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-content"))
        assertFalse(provider.searchCatalogValidated(chapterCount = 5, validation = "detail-catalog-content-fingerprint"))
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
    fun searchUsesOnlyExactUserQuery() {
        val provider = SourceEngineReaderContentProvider()
        val method = provider.javaClass.getDeclaredMethod("searchQueriesFor", String::class.java)
        method.isAccessible = true

        val longTitleQueries = method.invoke(provider, "雪中悍刀行") as List<*>
        val aliasTitleQueries = method.invoke(provider, "灵源仙路") as List<*>

        assertEquals(listOf("雪中悍刀行"), longTitleQueries)
        assertEquals(listOf("灵源仙路"), aliasTitleQueries)
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
    fun searchBooksPublishesCatalogVerifiedResultWhenTailProbeIsSlow() = runBlocking {
        val sourceA = changduSource("目录可信源A", "https://catalog-tail-slow-a.example")
        val sourceB = changduSource("目录可信源B", "https://catalog-tail-slow-b.example")
        val sources = listOf(sourceA, sourceB)
        val chapterCount = 120
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = trustedBookFixture(
                    baseUrl = sourceA.sourceUrl,
                    title = "仙都",
                    author = "陈猿",
                    lastChapter = "第120章 正文",
                    chapterCount = chapterCount
                ) + trustedBookFixture(
                    baseUrl = sourceB.sourceUrl,
                    title = "仙都",
                    author = "陈猿",
                    lastChapter = "第120章 正文",
                    chapterCount = chapterCount
                ),
                delays = mapOf(
                    "${sourceA.sourceUrl}/book/1/$chapterCount.html" to 10_000L,
                    "${sourceB.sourceUrl}/book/1/$chapterCount.html" to 10_000L
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

        val books = provider.searchBooks("仙都")

        assertEquals(1, books.size)
        assertEquals("仙都", books.first().title)
        assertEquals("陈猿", books.first().author)
        assertTrue(SourceEngineBookRoute.isBookId(books.first().routeId))
    }

    @Test
    fun progressiveVisibleReadingReadyResultSkipsFinalCompletedTailProbeButKeepsCatalogValidation() = runBlocking {
        val query = "仙都"
        val author = "陈猿"
        val exactA = changduSource("可见完成态精确源A", "https://visible-completed-tail-a.example")
        val exactB = changduSource("可见完成态精确源B", "https://visible-completed-tail-b.example")
        val slowVariantA = changduSource("可见完成态慢变体源A", "https://visible-completed-slow-a.example")
        val slowVariantB = changduSource("可见完成态慢变体源B", "https://visible-completed-slow-b.example")
        val sources = listOf(exactA, exactB, slowVariantA, slowVariantB)
        val publishedReadingReady = AtomicBoolean(false)
        val releaseSlowSearches = CountDownLatch(1)
        val slowSearchUrls = setOf(
            "${slowVariantA.sourceUrl}/modules/article/search.php",
            "${slowVariantB.sourceUrl}/modules/article/search.php"
        )
        val responses = trustedBookFixture(exactA.sourceUrl, query, author, "第一百二十章 正文", 120) +
            trustedBookFixture(exactB.sourceUrl, query, author, "第一百二十章 正文", 120) +
            trustedBookFixture(slowVariantA.sourceUrl, "仙都传说", "仙都黄龙", "第三百章 正文", 300) +
            trustedBookFixture(slowVariantB.sourceUrl, "仙都传说", "仙都黄龙", "第三百章 正文", 300)
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = responses,
                onFetch = { url ->
                    if (url in slowSearchUrls) releaseSlowSearches.await(3L, TimeUnit.SECONDS)
                }
            )
        )
        val scopeProbeFetcher = OkHttpSourceEngineFetcher(1_000, 1_000)
        val completedCatalogLoads = AtomicInteger(0)
        val completedTailLoads = AtomicInteger(0)
        val completedValidationStartedAfterVisibleReady = AtomicBoolean(false)
        fun inCompletedValidationScope(): Boolean {
            return scopeProbeFetcher.currentRequestScope()
                ?.tracePath()
                ?.contains("searchCompletedValidationGroup:") == true
        }
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = scopeProbeFetcher,
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            canonicalCatalogLoader = { detail ->
                if (inCompletedValidationScope()) {
                    if (completedCatalogLoads.incrementAndGet() == 1) {
                        completedValidationStartedAfterVisibleReady.set(publishedReadingReady.get())
                    }
                }
                engine.getCanonicalChapterList(detail)
            },
            searchTailContentLoader = { chapter ->
                if (inCompletedValidationScope()) completedTailLoads.incrementAndGet()
                engine.getCleanContent(chapter)
            },
            searchProgressGroupsValidationTimeoutMs = 1_000L,
            searchProgressExactValidationBatchTimeoutMs = 1_000L,
            searchVisibleCompletedRankTimeoutMs = 2_000L,
            searchTierOneSettleTimeoutMs = 3_500L,
            searchProgressPollIntervalMs = 25L
        )

        val books = provider.searchBooksProgressively(query) { update ->
            if (update.any { book -> book.readingReady }) {
                publishedReadingReady.set(true)
                releaseSlowSearches.countDown()
            }
        }

        assertTrue("progressive search must publish a reading-ready result", publishedReadingReady.get())
        assertTrue("the final result must remain reading-ready", books.any { book -> book.readingReady })
        assertTrue(
            "the fixture must enter final completed validation only after a reading-ready update is already visible",
            completedValidationStartedAfterVisibleReady.get()
        )
        assertTrue(
            "final completed validation must still materialize a canonical catalog",
            completedCatalogLoads.get() > 0
        )
        assertEquals(
            "a final completed rank must not start inline tail work after a reading-ready result is already visible",
            0,
            completedTailLoads.get()
        )
    }

    @Test
    fun nonProgressiveSearchStillRunsInlineTailProbe() = runBlocking {
        val query = "普通搜索尾探针"
        val author = "测试作者"
        val sources = listOf(
            changduSource("普通搜索尾探针源A", "https://non-progressive-tail-a.example"),
            changduSource("普通搜索尾探针源B", "https://non-progressive-tail-b.example")
        )
        val responses = trustedBookFixture(sources[0].sourceUrl, query, author, "第一百二十章 正文", 120) +
            trustedBookFixture(sources[1].sourceUrl, query, author, "第一百二十章 正文", 120)
        val engine = LegadoSourceEngine(MapFetcher(responses))
        val scopeProbeFetcher = OkHttpSourceEngineFetcher(1_000, 1_000)
        val completedCatalogLoads = AtomicInteger(0)
        val completedTailLoads = AtomicInteger(0)
        fun inCompletedValidationScope(): Boolean {
            return scopeProbeFetcher.currentRequestScope()
                ?.tracePath()
                ?.contains("searchCompletedValidationGroup:") == true
        }
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = scopeProbeFetcher,
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            canonicalCatalogLoader = { detail ->
                if (inCompletedValidationScope()) completedCatalogLoads.incrementAndGet()
                engine.getCanonicalChapterList(detail)
            },
            searchTailContentLoader = { chapter ->
                if (inCompletedValidationScope()) completedTailLoads.incrementAndGet()
                engine.getCleanContent(chapter)
            },
            searchTierOneSettleTimeoutMs = 100L,
            searchProgressPollIntervalMs = 5L
        )

        val books = provider.searchBooks(query)

        assertTrue("ordinary search must return a reading-ready result", books.any { book -> book.readingReady })
        assertTrue("ordinary completed validation must materialize a canonical catalog", completedCatalogLoads.get() > 0)
        assertTrue("ordinary non-progressive search must retain inline tail validation", completedTailLoads.get() > 0)
    }

    @Test
    fun completedSearchValidatesPreferredStrongAliasBeforeUnrelatedShortGroups() = runBlocking {
        val query = "灵源仙路"
        val targetTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val targetAuthor = "木子知因"
        val shortLingyuanSources = (1..4).map { index ->
            changduSource("灵源短组源$index", "https://completed-priority-lingyuan-$index.example")
        }
        val shortXianluSources = (1..2).map { index ->
            changduSource("仙路短组源$index", "https://completed-priority-xianlu-$index.example")
        }
        val targetSources = (1..6).map { index ->
            changduSource("目标强别名源$index", "https://completed-priority-target-$index.example")
        }
        val sources = shortLingyuanSources + shortXianluSources + targetSources
        val chapterTitles = (1..60).map { index -> "第${index}章 灵兽感恩$index" }
        val responses = buildMap {
            shortLingyuanSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, "灵源", "短组作者甲", chapterTitles))
            }
            shortXianluSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, "仙路", "短组作者乙", chapterTitles))
            }
            targetSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, targetTitle, targetAuthor, chapterTitles))
            }
        }
        val engine = LegadoSourceEngine(MapFetcher(responses))
        val targetSourceUrls = targetSources.mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val preferredTargetPairUrls = targetSources.take(2).mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val catalogStarts = Collections.synchronizedList(ArrayList<String>())
        val targetSawUnrelatedActive = Collections.synchronizedList(ArrayList<Int>())
        val activeCatalogs = AtomicInteger(0)
        val activeUnrelatedCatalogs = AtomicInteger(0)
        val maxActiveCatalogs = AtomicInteger(0)
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            canonicalCatalogLoader = { detail ->
                val sourceUrl = detail.book.source.sourceUrl
                val target = sourceUrl in targetSourceUrls
                catalogStarts.add(sourceUrl)
                if (target) {
                    targetSawUnrelatedActive.add(activeUnrelatedCatalogs.get())
                } else {
                    activeUnrelatedCatalogs.incrementAndGet()
                }
                val active = activeCatalogs.incrementAndGet()
                maxActiveCatalogs.updateAndGet { previous -> maxOf(previous, active) }
                try {
                    Thread.sleep(if (target) 250L else 350L)
                    EngineResult.Success(canonicalCatalogFor(detail.book, chapterTitles))
                } finally {
                    activeCatalogs.decrementAndGet()
                    if (!target) activeUnrelatedCatalogs.decrementAndGet()
                }
            },
            searchTailContentLoader = { EngineResult.Success(readableCleanContent()) },
            searchValidationTimeoutMs = 450L,
            searchTierOneSettleTimeoutMs = 100L,
            searchProgressPollIntervalMs = 5L
        )

        val books = provider.searchBooks(query)
        val starts = synchronized(catalogStarts) { catalogStarts.toList() }

        assertEquals(
            "the preferred strong alias pair must exclusively own the first completed catalog wave",
            preferredTargetPairUrls,
            starts.take(preferredTargetPairUrls.size).toSet()
        )
        assertEquals(
            "a readable preferred pair must prevent lower-ranked alias candidates and fallback groups from materializing",
            preferredTargetPairUrls,
            starts.toSet()
        )
        assertTrue(
            "unrelated short groups must not hold a catalog slot when the preferred alias starts",
            synchronized(targetSawUnrelatedActive) { targetSawUnrelatedActive.all { active -> active == 0 } }
        )
        assertTrue(
            "both slightly-slow target catalogs must finish inside their own validation budget",
            books.any { book ->
                book.title == targetTitle && book.author == targetAuthor && book.readingReady
            }
        )
        assertTrue("the global catalog guard must remain two; max=${maxActiveCatalogs.get()}", maxActiveCatalogs.get() <= 2)
        assertEquals("all catalog loaders must drain before searchBooks returns", 0, activeCatalogs.get())
    }

    @Test
    fun completedSearchFallsBackAfterPreferredStrongAliasPairFails() = runBlocking {
        val query = "星河仙路"
        val targetTitle = "星河仙途：灵兽知恩录"
        val targetAuthor = "观星客"
        val shortSources = (1..2).map { index ->
            changduSource("星河短组源$index", "https://completed-fallback-short-$index.example")
        }
        val targetSources = (1..4).map { index ->
            changduSource("星河目标源$index", "https://completed-fallback-target-$index.example")
        }
        val sources = shortSources + targetSources
        val chapterTitles = (1..60).map { index -> "第${index}章 星河灵兽$index" }
        val responses = buildMap {
            shortSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, "仙路", "短组作者", chapterTitles))
            }
            targetSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, targetTitle, targetAuthor, chapterTitles))
            }
        }
        val engine = LegadoSourceEngine(MapFetcher(responses))
        val failedPair = targetSources.take(2).mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val fallbackPair = targetSources.drop(2).mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val shortSourceUrls = shortSources.mapTo(linkedSetOf()) { source -> source.sourceUrl }
        val catalogStarts = Collections.synchronizedList(ArrayList<String>())
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            canonicalCatalogLoader = { detail ->
                val sourceUrl = detail.book.source.sourceUrl
                catalogStarts.add(sourceUrl)
                if (sourceUrl in failedPair) {
                    EngineResult.Failure(EngineFailure.ParseError("preferred pair fixture failure"))
                } else {
                    EngineResult.Success(canonicalCatalogFor(detail.book, chapterTitles))
                }
            },
            searchTailContentLoader = { EngineResult.Success(readableCleanContent()) },
            searchTierOneSettleTimeoutMs = 100L,
            searchProgressPollIntervalMs = 5L
        )

        val books = provider.searchBooks(query)
        val starts = synchronized(catalogStarts) { catalogStarts.toList() }

        assertEquals("the failed preferred pair must still be the exclusive first wave", failedPair, starts.take(2).toSet())
        assertTrue("remaining preferred candidates must advance after the first pair fails", starts.any { it in fallbackPair })
        assertTrue("unrelated groups must be restored only in the fallback phase", starts.any { it in shortSourceUrls })
        assertTrue(
            "the remaining preferred pair must recover a readable target result",
            books.any { book ->
                book.title == targetTitle && book.author == targetAuthor && book.readingReady
            }
        )
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
                    lastChapter = "第120章 正文",
                    chapterCount = 120,
                    coverUrl = "",
                    searchAuthor = ""
                ) + trustedBookFixture(
                    baseUrl = "https://readable-b.example",
                    title = "诡秘之主",
                    author = "爱潜水的乌贼",
                    lastChapter = "第120章 正文",
                    chapterCount = 120,
                    coverUrl = "",
                    searchAuthor = ""
                ) + unreadableBookFixture(
                    baseUrl = "https://cover-only.example",
                    title = "诡秘之主",
                    author = "爱潜水的乌贼",
                    lastChapter = "第120章 正文",
                    chapterCount = 120,
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
        val route = SourceEngineBookRoute.decodeBookId(books.first().routeId!!)
        assertEquals("诡秘之主", books.first().title)
        assertEquals("爱潜水的乌贼", books.first().author)
        assertEquals("file:///cover.jpg", books.first().cover)
        assertTrue(books.first().coverCandidates.orEmpty().contains("file:///cover.jpg"))
        assertTrue(SourceEngineBookRoute.coverCandidates(route).contains("file:///cover.jpg"))
        assertEquals("https://readable-a.example", route.sourceUrl)
    }

    @Test
    fun getBookInfoDisplaysLatestChapterFromVerifiedCatalog() = runBlocking {
        val source = changduSource("尾章污染源", "https://tail.example")
        val mirrorSource = changduSource("尾章污染镜像源", "https://tail-mirror.example")
        val sources = listOf(source, mirrorSource)
        val fixture = trustedBookFixture(
            baseUrl = "https://tail.example",
            title = "青山",
            author = "会说话的肘子",
            lastChapter = "第121章 伪更新",
            chapterCount = 120
        ) + trustedBookFixture(
            baseUrl = "https://tail-mirror.example",
            title = "青山",
            author = "会说话的肘子",
            lastChapter = "第121章 伪更新",
            chapterCount = 120
        )
        val engine = LegadoSourceEngine(MapFetcher(fixture))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath,
            cleanIntroEnabled = { true }
        )

        val books = provider.searchBooks("青山")
        val detail = provider.getBookInfo(books.first().routeId)
        val chapters = provider.getBookFolder(books.first().routeId, detail.collBookBean)

        assertEquals("第120章 正文", detail.lastChapter)
        assertEquals(120, detail.chaptersCount)
        assertEquals("第120章 正文", chapters.last().title)
        assertEquals(120, chapters.size)
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
                trustedBookFixture("https://zhu.example", "难哄", "竹已", "第150章 上火", 150) +
                    trustedBookFixture("https://zhu-mirror.example", "难哄", "竹已", "第150章 上火", 150) +
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
    fun searchBooksMergesSameTitleAnonymousAuthorIntoNamedAuthor() = runBlocking {
        val sources = listOf(
            changduSource("命名作者源", "https://fanren-named-author.example"),
            changduSource("佚名作者源", "https://fanren-anonymous-author.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    "https://fanren-named-author.example",
                    "凡人修仙传",
                    "忘语",
                    "第120章 正文",
                    120
                ) +
                    trustedBookFixture(
                        "https://fanren-anonymous-author.example",
                        "凡人修仙传",
                        "佚名",
                        "第120章 正文",
                        120
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

        val books = provider.searchBooks("凡人修仙传")

        assertEquals(listOf("凡人修仙传" to "忘语"), books.map { it.title to it.author })
    }

    @Test
    fun progressiveSearchStartsTier2OnlyAfterTier1SearchTimeout() = runBlocking {
        val tier1 = changduSource("瀑布慢T1", "https://waterfall-slow-tier1.example")
        val tier2A = changduSource("瀑布快T2A", "https://waterfall-fast-tier2-a.example")
        val tier2B = changduSource("瀑布快T2B", "https://waterfall-fast-tier2-b.example")
        val sources = listOf(tier1, tier2A, tier2B)
        val tier2FetchTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val startedAt = System.currentTimeMillis()
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    tier1.sourceUrl,
                    "瀑布测试书",
                    "测试作者",
                    "第120章 正文",
                    120
                ) +
                    trustedBookFixture(
                        tier2A.sourceUrl,
                        "瀑布测试书",
                        "测试作者",
                        "第120章 正文",
                        120
                    ) +
                    trustedBookFixture(
                        tier2B.sourceUrl,
                        "瀑布测试书",
                        "测试作者",
                        "第120章 正文",
                        120
                    ),
                delays = mapOf("${tier1.sourceUrl}/modules/article/search.php" to 12_000L),
                onFetch = { url ->
                    if (url.startsWith("${tier2A.sourceUrl}/modules/article/search.php") ||
                        url.startsWith("${tier2B.sourceUrl}/modules/article/search.php")
                    ) {
                        tier2FetchTimes.add(System.currentTimeMillis() - startedAt)
                    }
                }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    """
                    source	${tier1.sourceUrl}	${tier1.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${tier2A.sourceUrl}	${tier2A.sourceName}	2	classic	6000	0	0	0	0	0	test
                    source	${tier2B.sourceUrl}	${tier2B.sourceName}	2	classic	6000	0	0	0	0	0	test
                    """.trimIndent()
                )
            ),
            bookCacheFolderPath = ::testBookCacheFolderPath
        )

        val books = provider.searchBooksProgressively("瀑布测试书") {}

        assertTrue("expected tier2 fetches", tier2FetchTimes.isNotEmpty())
        assertTrue(
            "tier2 started too early: $tier2FetchTimes",
            tier2FetchTimes.minOrNull()!! >= 9_500L
        )
        assertEquals("瀑布测试书", books.first().title)
    }

    @Test
    fun progressiveSearchPublishesReadableStrongAliasBeforeTier2WaveSettles() = runBlocking {
        val query = "云岚仙路"
        val aliasTitle = "云岚仙途：灵舟知恩录"
        val aliasAuthor = "听潮客"
        val aliasA = changduSource("云岚强别名源A", "https://tier2-strong-alias-a.example")
        val aliasB = changduSource("云岚强别名源B", "https://tier2-strong-alias-b.example")
        val blocking = changduSource("云岚阻塞源", "https://tier2-blocking-source.example")
        val sources = listOf(aliasA, aliasB, blocking)
        val chapterTitles = (1..60).map { index -> "第${index}章 云岚灵舟$index" }
        val blockingSearchUrl = "${blocking.sourceUrl}/modules/article/search.php"
        val responses = customCatalogFixture(aliasA.sourceUrl, aliasTitle, aliasAuthor, chapterTitles) +
            customCatalogFixture(aliasB.sourceUrl, aliasTitle, aliasAuthor, chapterTitles) +
            mapOf(blockingSearchUrl to searchHtmlRows(emptyList()))
        val searchCalls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = mapOf(blockingSearchUrl to 20_000L)
        )
        val searchFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 25_000,
            callFactory = searchCalls::newCall
        )
        val searchEngine = LegadoSourceEngine(searchFetcher)
        val validationEngine = LegadoSourceEngine(MapFetcher(responses))
        val provider = SourceEngineReaderContentProvider(
            searchEngineFetcher = searchFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t2\tclassic\t6000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            searchProgressPollIntervalMs = 15_000L
        )
        val readablePublishedAt = CompletableDeferred<Long>()
        val startedAt = System.currentTimeMillis()
        val search = async {
            provider.searchBooksProgressively(query) { update ->
                if (
                    update.any { book ->
                        book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
                    }
                ) {
                    readablePublishedAt.complete(System.currentTimeMillis())
                }
            }
        }

        try {
            val publishedAt = withTimeoutOrNull(8_000L) { readablePublishedAt.await() }
            assertTrue(
                "two authored tier2 alias sources must publish a catalog-validated readable result " +
                    "without waiting for the blocking source or the long tier poll; " +
                    "executed=${searchCalls.executedUrlsSnapshot()}",
                publishedAt != null
            )
            val elapsedMs = requireNotNull(publishedAt) - startedAt
            assertTrue("readable alias must publish before tier2 settles; elapsedMs=$elapsedMs", elapsedMs < 8_000L)
            assertTrue(
                "the same tier2 wave must still contain the blocking source at publication",
                blockingSearchUrl in searchCalls.executedUrlsSnapshot() && searchCalls.cancelCount.get() == 0
            )
        } finally {
            search.cancel()
            searchCalls.cancelAll()
            withTimeoutOrNull(2_000L) { search.join() }
        }
    }

    @Test
    fun tierReadyRankBudgetReservesPreparationBeforePreferredValidation() {
        val provider = SourceEngineReaderContentProvider()
        val method = provider.javaClass.getDeclaredMethod(
            "searchTierReadyRankTimeoutMs",
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        val minimumBudgetMs = 30_000L
        val budgets = (1..3).associateWith { tier -> method.invoke(provider, tier) as Long }

        assertTrue(
            "tier-ready outer rank budget must leave preparation headroom before the preferred 15s validation; budgets=$budgets",
            budgets.values.all { timeoutMs -> timeoutMs >= minimumBudgetMs }
        )
    }

    @Test
    fun progressiveSearchBoundsTierWaveWorkWhileKeepingThePrioritizedHeadFirst() = runBlocking {
        val keyword = "并发调度测试书"
        val sources = (1..32).map { index ->
            changduSource(
                sourceName = "并发搜索源${index.toString().padStart(2, '0')}",
                sourceUrl = "https://search-wave-${index.toString().padStart(2, '0')}.example"
            )
        }
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        val prioritizedHead = sourceQualityRouter
            .waterfallSourcesForBook(sources, keyword)
            .take(16)
            .mapTo(linkedSetOf()) { source -> "${source.sourceUrl}/modules/article/search.php" }
        val emptySearchHtml = """
            <html><body>
              <table><tbody>
                <tr><th>小说</th><th>最新章节</th><th>作者</th></tr>
              </tbody></table>
            </body></html>
        """.trimIndent()
        val searchUrls = sources.mapTo(linkedSetOf()) { source ->
            "${source.sourceUrl}/modules/article/search.php"
        }
        val fetcher = ConcurrentSearchWaveTrackingFetcher(
            responses = searchUrls.associateWith { emptySearchHtml },
            trackedSearchUrls = searchUrls,
            overlapThreshold = 17
        )
        val engine = LegadoSourceEngine(fetcher)
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter
        )

        val books = withTimeout(5_000L) {
            provider.searchBooksProgressively(keyword) {}
        }

        assertTrue(books.isEmpty())
        assertTrue(
            "a tier wave must not run more than 16 synchronous fetch/parse jobs at once; " +
                "max=${fetcher.maxConcurrentSearches.get()}",
            fetcher.maxConcurrentSearches.get() <= 16
        )
        assertEquals(prioritizedHead, fetcher.firstFullWindow)
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
            bookCacheFolderPath = ::testBookCacheFolderPath,
            cleanIntroEnabled = { true }
        )
        val books = provider.searchBooks("斗破苍穹")

        val detail = provider.getBookInfo(books.first().routeId)
        val chapters = provider.getBookFolder(books.first().routeId, detail.collBookBean)

        assertEquals("第162章 正文", chapters.last().title)
    }

    @Test
    fun getBookFolderCollapsesKnownUpdateDecorationWhileKeepingSelectedReadableRoute() = runBlocking {
        val primary = changduSource("主阅读源", "https://title-primary.example")
        val cleanTitleCandidate = changduSource("干净完整标题源", "https://title-clean-candidate.example")
        val decoratedTitleCandidate = changduSource("带更新尾注标题源", "https://title-decorated-candidate.example")
        val sources = listOf(primary, cleanTitleCandidate, decoratedTitleCandidate)
        val primaryTitles = (1..620).map { ordinal ->
            if (ordinal == 591) "第五百九十一章 融合成功！【第三更！】" else "第${ordinal}章 正文$ordinal"
        }
        val cleanCandidateTitles = (1..600).map { ordinal ->
            if (ordinal == 591) "第五百九十一章 融合成功！" else "第${ordinal}章 正文$ordinal"
        }
        val decoratedCandidateTitles = (1..600).map { ordinal ->
            if (ordinal == 591) "第五百九十一章 融合成功！〖第三更！〗" else "第${ordinal}章 正文$ordinal"
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = primary.sourceUrl,
                    title = "测试书籍",
                    author = "测试作者",
                    chapterTitles = primaryTitles
                ) + customCatalogFixture(
                    baseUrl = cleanTitleCandidate.sourceUrl,
                    title = "测试书籍",
                    author = "测试作者",
                    chapterTitles = cleanCandidateTitles
                ) + customCatalogFixture(
                    baseUrl = decoratedTitleCandidate.sourceUrl,
                    title = "测试书籍",
                    author = "测试作者",
                    chapterTitles = decoratedCandidateTitles
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

        val books = provider.searchBooks("测试书籍")
        val detail = provider.getBookInfo(books.single().routeId)
        val detailChapter = detail.collBookBean.getBookChapters().orEmpty().single { chapter ->
            ChapterNormalizer().normalize(chapter.title.orEmpty()).ordinal == 591
        }
        val chapters = provider.getBookFolder(detail.routeId, detail.collBookBean)
        val upgraded = chapters.single { chapter ->
            ChapterNormalizer().normalize(chapter.title.orEmpty()).ordinal == 591
        }
        val route = SourceEngineBookRoute.decodeChapterId(requireNotNull(upgraded.link))
        val persistedBeforeEnrichment = chapters.map { chapter ->
            BookChapterBean().apply {
                link = chapter.link
                title = if (chapter.link == upgraded.link) {
                    "第五百九十一章 融合成功！【第三更！】"
                } else {
                    chapter.title
                }
            }
        }
        val persistence = SourceEngineCatalogPersistencePolicy.evaluate(
            persisted = persistedBeforeEnrichment,
            candidate = chapters
        )
        val unsafePersistence = SourceEngineCatalogPersistencePolicy.evaluate(
            persisted = chapters.map { chapter ->
                BookChapterBean().apply {
                    link = chapter.link
                    title = if (chapter.link == upgraded.link) {
                        "第五百九十一章 融合成功！【番外】"
                    } else {
                        chapter.title
                    }
                }
            },
            candidate = chapters
        )

        assertEquals("第五百九十一章 融合成功！", detailChapter.title)
        assertEquals("第五百九十一章 融合成功！", upgraded.title)
        assertEquals(primary.sourceUrl, route.sourceUrl)
        assertEquals("第五百九十一章 融合成功！【第三更！】", route.chapterName)
        assertTrue(persistence.compatible)
        assertTrue(persistence.changed)
        assertEquals(1, persistence.safeTitleUpgrades)
        assertFalse(unsafePersistence.compatible)
    }

    @Test
    fun getBookFolderPrefersLongTrustedCatalogOverCurrentShortAnchor() = runBlocking {
        val currentShort = changduSource("当前短目录源", "https://current-short-anchor.example")
        val longA = changduSource("长目录源A", "https://long-trusted-a.example")
        val longB = changduSource("长目录源B", "https://long-trusted-b.example")
        val sources = listOf(currentShort, longA, longB)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    baseUrl = "https://current-short-anchor.example",
                    title = "叩问仙道",
                    author = "雨打青石",
                    lastChapter = "第五十章 夜访",
                    chapterCount = 50
                ) +
                    trustedBookFixture(
                        baseUrl = "https://long-trusted-a.example",
                        title = "叩问仙道",
                        author = "雨打青石",
                        lastChapter = "第三百章 归山",
                        chapterCount = 300
                    ) +
                    trustedBookFixture(
                        baseUrl = "https://long-trusted-b.example",
                        title = "叩问仙道",
                        author = "雨打青石",
                        lastChapter = "第三百章 归山",
                        chapterCount = 300
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

        provider.searchBooks("叩问仙道")
        val currentBook = SourceBook(
            source = currentShort,
            name = "叩问仙道",
            author = "雨打青石",
            bookUrl = "https://current-short-anchor.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第五十章 夜访"
        )
        val chapters = provider.getBookFolder(
            SourceEngineBookRoute.bookId(currentBook),
            CollBookBean().apply {
                set_id("source_engine_shelf_qouwen-regression")
                title = "叩问仙道"
                author = "雨打青石"
            }
        )

        assertEquals(300, chapters.size)
        assertEquals("第300章 正文", chapters.last().title)
    }

    @Test
    fun getBookFolderSearchesGlobalFallbackWhenCurrentAnchorTrailsExistingCatalog() = runBlocking {
        val title = "灵源仙路"
        val author = "春雾煮茶"
        val currentSource = changduSource("当前别名旧源", "https://stale-anchor-current.example")
        val personalSources = (1..2).map { index ->
            changduSource("旧个人源$index", "https://stale-anchor-personal-$index.example")
        }
        val globalSource = changduSource("全局新目录源", "https://stale-anchor-global.example")
        val sources = listOf(currentSource) + personalSources + globalSource
        val currentTitles = (1..20).map { index -> "第${index}章 正文" }
        val existingTitles = (1..30).map { index -> "第${index}章 正文" }
        val freshTitles = (1..31).map { index -> "第${index}章 正文" }
        val aliasBookUrl = "${currentSource.sourceUrl}/lingyuanxiantuwoyangdelingshoutaidongganenle/"
        val fetches = Collections.synchronizedList(ArrayList<String>())
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = customCatalogFixture(
                    baseUrl = currentSource.sourceUrl,
                    title = title,
                    author = author,
                    chapterTitles = currentTitles
                ) + mapOf(
                    aliasBookUrl to detailHtml(
                        title = title,
                        author = author,
                        lastChapter = currentTitles.last()
                    )
                ) + personalSources.flatMap { source ->
                    customCatalogFixture(source.sourceUrl, title, author, currentTitles).entries
                }.associate { it.toPair() } + customCatalogFixture(
                    baseUrl = globalSource.sourceUrl,
                    title = title,
                    author = author,
                    chapterTitles = freshTitles
                ),
                onFetch = { url -> fetches.add(url) }
            )
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        personalSources.forEach { source ->
            sourceQualityRouter.recordTrustedCatalogResolved(
                sourceBook(source, title, author, currentTitles.last()),
                chapterCount = currentTitles.size,
                rawChapterCount = currentTitles.size
            )
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = SourceBook(
            source = currentSource,
            name = title,
            author = author,
            bookUrl = aliasBookUrl,
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = currentTitles.last()
        )
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_stale-anchor-global")
            this.title = title
            this.author = author
            setBookChapters(existingTitles.mapIndexed { index, chapterTitle ->
                BookChapterBean().apply {
                    bookId = get_id()
                    link = "existing://${index + 1}"
                    this.title = chapterTitle
                }
            })
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        cacheFolder.mkdirs()
        java.io.File(cacheFolder, ".source_engine_content_tier").writeText(
            personalSources.joinToString("\n") { source ->
                SourceEngineBookRoute.bookId(sourceBook(source, title, author, currentTitles.last()))
            }
        )

        val chapters = provider.getBookFolder(SourceEngineBookRoute.bookId(currentBook), collBook)

        assertEquals(31, chapters.size)
        assertEquals("第31章 正文", chapters.last().title)
        assertTrue(fetches.contains("${globalSource.sourceUrl}/modules/article/search.php"))
        assertFalse(
            "a catalog that is already behind the persisted ordinal must not fetch fingerprint chapter bodies: $fetches",
            fetches.any { url ->
                url.startsWith("${currentSource.sourceUrl}/book/1/") && url.endsWith(".html")
            }
        )
        assertTrue(
            "the fixture must exercise a short stale fallback catalog: $fetches",
            personalSources.any { source -> fetches.contains("${source.sourceUrl}/book/1/") }
        )
        assertFalse(
            "raw freshness must reject short stale fallback catalogs without fetching their chapter bodies: $fetches",
            fetches.any { url ->
                personalSources.any { source ->
                    url.startsWith("${source.sourceUrl}/book/1/") && url.endsWith(".html")
                }
            }
        )
        assertEquals(
            "the verified path must reuse the exact stale anchor materialized by the fast path",
            1,
            fetches.count { url -> url == aliasBookUrl }
        )
        assertEquals(
            "alias and canonical routes must not materialize the same physical catalog twice",
            1,
            fetches.count { url -> url == "${currentSource.sourceUrl}/book/1/" }
        )
        cacheFolder.deleteRecursively()
        Unit
    }

    @Test
    fun staleCatalogFallbackBoundsConcurrentFullCatalogMaterialization() = runBlocking {
        val title = "并发目录内存回归"
        val author = "测试作者"
        val currentSource = changduSource("当前旧目录源", "https://bounded-catalog-current.example")
        val fallbackSources = (1..8).map { index ->
            changduSource("候选新目录源$index", "https://bounded-catalog-fallback-$index.example")
        }
        val sources = listOf(currentSource) + fallbackSources
        val currentTitles = (1..580).map { index -> "第${index}章 正文" }
        val existingTitles = (1..590).map { index -> "第${index}章 正文" }
        val freshTitles = (1..600).map { index -> "第${index}章 正文" }
        val responses = customCatalogFixture(
            currentSource.sourceUrl,
            title,
            author,
            currentTitles
        ) + fallbackSources.flatMap { source ->
            customCatalogFixture(source.sourceUrl, title, author, freshTitles).entries
        }.associate { entry -> entry.toPair() }
        val fetcher = ConcurrentCatalogTrackingFetcher(
            responses = responses,
            trackedCatalogUrls = fallbackSources.mapTo(mutableSetOf()) { source ->
                "${source.sourceUrl}/book/1/"
            },
            catalogDelayMs = 150L
        )
        val engine = LegadoSourceEngine(fetcher)
        val quality = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        val activeMaterializations = AtomicInteger(0)
        val maxActiveMaterializations = AtomicInteger(0)
        val fallbackMaterializationsStarted = AtomicInteger(0)
        fallbackSources.forEach { source ->
            quality.recordTrustedCatalogResolved(
                sourceBook(source, title, author, freshTitles.last()),
                chapterCount = freshTitles.size,
                rawChapterCount = freshTitles.size
            )
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = quality,
            bookCacheFolderPath = ::testBookCacheFolderPath,
            catalogMaterializationLifecycle = { book, started ->
                if (started) {
                    if (fallbackSources.any { source -> source.sourceUrl == book.source.sourceUrl }) {
                        fallbackMaterializationsStarted.incrementAndGet()
                    }
                    val active = activeMaterializations.incrementAndGet()
                    maxActiveMaterializations.updateAndGet { previous -> maxOf(previous, active) }
                } else {
                    activeMaterializations.decrementAndGet()
                }
            }
        )
        val currentBook = sourceBook(currentSource, title, author, currentTitles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_bounded-catalog-memory")
            this.title = title
            this.author = author
            setBookChapters(existingTitles.mapIndexed { index, chapterTitle ->
                BookChapterBean().apply {
                    bookId = get_id()
                    link = "existing://${index + 1}"
                    this.title = chapterTitle
                }
            })
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        cacheFolder.mkdirs()
        java.io.File(cacheFolder, ".source_engine_content_tier").writeText(
            fallbackSources.joinToString("\n") { source ->
                SourceEngineBookRoute.bookId(sourceBook(source, title, author, freshTitles.last()))
            }
        )

        val chapters = provider.getBookFolder(SourceEngineBookRoute.bookId(currentBook), collBook)

        assertEquals(600, chapters.size)
        assertTrue(
            "stale refresh must not materialize every full catalog at once; max=${fetcher.maxConcurrentCatalogFetches.get()}",
            fetcher.maxConcurrentCatalogFetches.get() <= 2
        )
        assertTrue(
            "the hard bound must cover the complete detail/catalog parse, not only HTTP; max=${maxActiveMaterializations.get()}",
            maxActiveMaterializations.get() <= 2
        )
        assertTrue(
            "the first qualifying batch must stop later full-catalog materialization; started=${fallbackMaterializationsStarted.get()}",
            fallbackMaterializationsStarted.get() in 1..2
        )
        assertEquals(
            "getBookFolder must not return while a detached full-catalog parse is still retained",
            0,
            activeMaterializations.get()
        )
        assertFalse(
            "persisted fallback routes must be probed before repeating the 30-source search",
            fetcher.fetchedUrls.any { url ->
                fallbackSources.any { source -> url == "${source.sourceUrl}/modules/article/search.php" }
            }
        )
        cacheFolder.deleteRecursively()
        assertFalse(cacheFolder.exists())
    }

    @Test
    fun readingLightTierEnrichesStaleFallbackCatalogTitleBeforeSameSizePromotion() = runBlocking {
        val title = "斗破苍穹"
        val author = "天蚕土豆"
        val stalePrimary = changduSource("文桑完整标题旧目录", "https://tier-title-primary.example")
        val staleMirror = changduSource("完整标题镜像旧目录", "https://tier-title-mirror.example")
        val currentFallback = changduSource("九五截断标题当前目录", "https://tier-title-fallback.example")
        val sources = listOf(stalePrimary, staleMirror, currentFallback)
        val staleTitles = (1..600).map { ordinal ->
            if (ordinal == 591) "第五百九十一章 融合成功！【第三更！】" else "第${ordinal}章 正文$ordinal"
        } + (1..30).map { index -> "番外旧目录$index" }
        val currentTitles = (1..620).map { ordinal ->
            if (ordinal == 591) "第五百九十一章 融合..." else "第${ordinal}章 正文$ordinal"
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(stalePrimary.sourceUrl, title, author, staleTitles) +
                    customCatalogFixture(staleMirror.sourceUrl, title, author, staleTitles) +
                    customCatalogFixture(currentFallback.sourceUrl, title, author, currentTitles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val primaryBook = sourceBook(stalePrimary, title, author, staleTitles.last())
        val mirrorBook = sourceBook(staleMirror, title, author, staleTitles.last())
        val fallbackBook = sourceBook(currentFallback, title, author, currentTitles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_tier-title-enrichment")
            this.title = title
            this.author = author
            setBookChapters(currentTitles.mapIndexed { index, chapterTitle ->
                BookChapterBean().apply {
                    bookId = get_id()
                    link = SourceEngineBookRoute.chapterId(
                        SourceChapter(
                            source = currentFallback,
                            book = fallbackBook,
                            index = index,
                            name = chapterTitle,
                            chapterUrl = "${currentFallback.sourceUrl}/book/1/${index + 1}.html"
                        )
                    )
                    this.title = chapterTitle
                }
            })
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        cacheFolder.mkdirs()
        java.io.File(cacheFolder, ".source_engine_content_tier").writeText(
            listOf(mirrorBook, fallbackBook).joinToString("\n") { book ->
                SourceEngineBookRoute.bookId(book)
            }
        )

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(primaryBook),
            collBook,
            persist = true,
            triggerV8 = false,
            requestPriority = SourceRequestPriority.BACKGROUND,
            mode = SourceContentTierMode.READING_LIGHT
        )
        val refreshed = provider.getBookFolder(SourceEngineBookRoute.bookId(primaryBook), collBook)
        val chapter591 = refreshed.single { chapter ->
            ChapterNormalizer().normalize(chapter.title.orEmpty()).ordinal == 591
        }

        assertEquals("第五百九十一章 融合成功！", chapter591.title)
        assertEquals(
            currentFallback.sourceUrl,
            SourceEngineBookRoute.decodeChapterId(requireNotNull(chapter591.link)).sourceUrl
        )
        cacheFolder.deleteRecursively()
        Unit
    }

    @Test
    fun getBookFolderKeepsTailProbeSuspectChaptersVisibleForV8Marking() = runBlocking {
        val longA = changduSource("长目录污染尾源A", "https://long-tail-for-v8-a.example")
        val sources = listOf(longA)
        fun longTailFixture(baseUrl: String): Map<String, String> {
            return customCatalogFixture(
                baseUrl = baseUrl,
                title = "叩问仙道",
                author = "雨打青石",
                chapterTitles = (1..300).map { index -> "第${index}章 正文" },
                customChapterHtml = { index, _ ->
                    if (index >= 299) unreadableChapterHtml() else readableChapterHtml("叩问仙道", "雨打青石", index)
                }
            ) + mapOf(
                "$baseUrl/modules/article/search.php" to searchHtml(
                    bookUrl = "$baseUrl/books/1/",
                    title = "叩问仙道",
                    lastChapter = "第298章 正文",
                    author = "雨打青石"
                ),
                "$baseUrl/books/1/" to detailHtml(
                    title = "叩问仙道",
                    author = "雨打青石",
                    lastChapter = "第298章 正文"
                )
            )
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                longTailFixture("https://long-tail-for-v8-a.example")
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
        val sourceBook = SourceBook(
            source = longA,
            name = "叩问仙道",
            author = "雨打青石",
            bookUrl = "https://long-tail-for-v8-a.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第298章 正文"
        )
        val routeId = SourceEngineBookRoute.bookId(sourceBook)
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_tail-for-v8")
            title = "叩问仙道"
            author = "雨打青石"
        }

        provider.prepareBookContentTier(
            routeId,
            collBook,
            persist = false,
            triggerV8 = false,
            maintenanceOnly = true
        )
        val chapters = provider.getBookFolder(routeId, collBook)

        assertEquals(300, chapters.size)
        assertEquals("第300章 正文", chapters.last().title)
    }

    @Test
    fun contentTierTailProbeDoesNotMarkOrHideCatalogTailChapters() = runBlocking {
        val source = changduSource("尾部探测标记源", "https://tail-probe-marks.example")
        val sources = listOf(source)
        val titles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://tail-probe-marks.example",
                    title = "叩问仙道",
                    author = "雨打青石",
                    chapterTitles = titles,
                    customChapterHtml = { index, _ ->
                        if (index >= 11) unreadableChapterHtml() else readableChapterHtml("叩问仙道", "雨打青石", index)
                    }
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
        val sourceBook = SourceBook(
            source = source,
            name = "叩问仙道",
            author = "雨打青石",
            bookUrl = "https://tail-probe-marks.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第12章 正文"
        )
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_tail-probe-marks")
            title = "叩问仙道"
            author = "雨打青石"
        }
        val routeId = SourceEngineBookRoute.bookId(sourceBook)

        provider.prepareBookContentTier(
            routeId,
            collBook,
            persist = false,
            triggerV8 = false,
            maintenanceOnly = true
        )
        val chapters = provider.getBookFolder(routeId, collBook)
        val stats = SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(chapters)

        assertEquals(12, chapters.size)
        assertEquals(0, stats.matched)
        assertEquals(0, stats.hidden)
        assertEquals(null, chapters[10].sourceIntegrityState)
        assertEquals(null, chapters[11].sourceIntegrityState)
    }

    @Test
    fun contentTierReportsExhaustedWhenNoFallbackCandidatesRemain() = runBlocking {
        val source = changduSource("低源书源", "https://content-tier-exhausted.example")
        val sources = listOf(source)
        val titles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://content-tier-exhausted.example",
                    title = "小众修仙录",
                    author = "无名客",
                    chapterTitles = titles
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
        val sourceBook = SourceBook(
            source = source,
            name = "小众修仙录",
            author = "无名客",
            bookUrl = "https://content-tier-exhausted.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第12章 正文"
        )
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_content-tier-exhausted")
            title = "小众修仙录"
            author = "无名客"
        }
        val routeId = SourceEngineBookRoute.bookId(sourceBook)

        val result = provider.prepareBookContentTierResult(
            routeId,
            collBook,
            persist = false,
            triggerV8 = false,
            maintenanceOnly = true
        )

        assertEquals(SourceContentTierPrepareResult.EXHAUSTED, result)
    }

    @Test
    fun contentTierTrustedResolveRecordsBookSourceQuality() = runBlocking {
        val source = changduSource("阅读补源可信源", "https://content-tier-quality.example")
        val sources = listOf(source)
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://content-tier-quality.example",
                    title = "叩问仙道",
                    author = "雨打青石",
                    chapterTitles = titles
                )
            )
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val sourceBook = SourceBook(
            source = source,
            name = "叩问仙道",
            author = "雨打青石",
            bookUrl = "https://content-tier-quality.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第120章 正文"
        )
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_content-tier-quality")
            title = "叩问仙道"
            author = "雨打青石"
        }
        val routeId = SourceEngineBookRoute.bookId(sourceBook)

        provider.prepareBookContentTierResult(
            routeId,
            collBook,
            persist = false,
            triggerV8 = false,
            maintenanceOnly = true
        )

        val snapshot = sourceQualityRouter.bookSourceSnapshot(sourceBook)
        assertEquals(2, snapshot.events)
        assertEquals(120, snapshot.latestObservedOrdinal)
        assertEquals(120, snapshot.latestVerifiedGoodOrdinal)
    }

    @Test
    fun maintenanceOnlyRevalidatesPersistedTierForPersonalQuality() = runBlocking {
        val currentSource = changduSource("维护当前源", "https://maintenance-current.example")
        val persistedSource = changduSource("维护持久候选源", "https://maintenance-persisted.example")
        val sources = listOf(currentSource, persistedSource)
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://maintenance-current.example",
                    title = "叩问仙道",
                    author = "雨打青石",
                    chapterTitles = titles
                ) + customCatalogFixture(
                    baseUrl = "https://maintenance-persisted.example",
                    title = "叩问仙道",
                    author = "雨打青石",
                    chapterTitles = titles
                )
            )
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = SourceBook(
            source = currentSource,
            name = "叩问仙道",
            author = "雨打青石",
            bookUrl = "https://maintenance-current.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第120章 正文"
        )
        val persistedBook = SourceBook(
            source = persistedSource,
            name = "叩问仙道",
            author = "雨打青石",
            bookUrl = "https://maintenance-persisted.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第120章 正文"
        )
        val stalePersistedBook = persistedBook.copy(lastChapter = "第118章 旧正文")
        val alternatePersistedBook = persistedBook.copy(bookUrl = "https://maintenance-persisted.example/books/duplicate/")
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_maintenance-persisted-tier")
            title = "叩问仙道"
            author = "雨打青石"
        }
        val tierFile = java.io.File(testBookCacheFolderPath(collBook.get_id()), ".source_engine_content_tier")
        tierFile.parentFile?.mkdirs()
        tierFile.writeText(
            listOf(
                SourceEngineBookRoute.bookId(stalePersistedBook),
                SourceEngineBookRoute.bookId(alternatePersistedBook),
                SourceEngineBookRoute.bookId(persistedBook)
            ).joinToString("\n")
        )

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = true,
            triggerV8 = false,
            maintenanceOnly = true
        )

        val snapshot = sourceQualityRouter.bookSourceSnapshot(persistedBook)
        assertEquals(2, snapshot.events)
        assertEquals(120, snapshot.latestObservedOrdinal)
        assertEquals(120, snapshot.latestVerifiedGoodOrdinal)
        assertTrue(
            sourceQualityRouter.personalWaterfallSourcesForBook(sources, "叩问仙道")
                .map { source -> source.sourceUrl }
                .contains(persistedSource.sourceUrl)
        )
        val persistedKeys = tierFile.readLines()
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .map { routeId -> SourceEngineBookRoute.sourceKey(SourceEngineBookRoute.decodeBookId(routeId)) }
        assertEquals(persistedKeys.distinct(), persistedKeys)
    }

    @Test
    fun contentTierPersistedHealthCullsFailedRoutesAfterThreshold() = runBlocking {
        val title = "灵源仙路"
        val author = "春雾煮茶"
        val currentSource = changduSource("健康当前源", "https://tier-health-current.example")
        val goodPersistedSource = changduSource("健康好源", "https://tier-health-good.example")
        val badPersistedSources = (1..2).map { index ->
            changduSource("健康坏源$index", "https://tier-health-bad-$index.example")
        }
        val sources = listOf(currentSource, goodPersistedSource) + badPersistedSources
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(currentSource.sourceUrl, title, author, titles) +
                    customCatalogFixture(goodPersistedSource.sourceUrl, title, author, titles)
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
        val currentBook = sourceBook(currentSource, title, author, titles.last())
        val goodPersistedBook = sourceBook(goodPersistedSource, title, author, titles.last())
        val badPersistedBooks = badPersistedSources.map { source ->
            sourceBook(source, title, author, titles.last())
        }
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_tier-health-cull")
            this.title = title
            this.author = author
        }
        val tierFile = java.io.File(testBookCacheFolderPath(collBook.get_id()), ".source_engine_content_tier")
        tierFile.parentFile?.deleteRecursively()
        tierFile.parentFile?.mkdirs()
        tierFile.writeText(
            (badPersistedBooks + goodPersistedBook)
                .joinToString("\n") { book -> SourceEngineBookRoute.bookId(book) }
        )

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = true,
            triggerV8 = false,
            maintenanceOnly = true
        )

        val persistedSourceUrls = tierFile.readLines()
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .map { routeId -> SourceEngineBookRoute.sourceKey(SourceEngineBookRoute.decodeBookId(routeId)) }
            .toSet()
        assertTrue(persistedSourceUrls.contains(currentSource.sourceUrl))
        assertTrue(persistedSourceUrls.contains(goodPersistedSource.sourceUrl))
        badPersistedSources.forEach { source ->
            assertFalse(persistedSourceUrls.contains(source.sourceUrl))
        }
    }

    @Test
    fun contentTierGlobalSearchContinuesWhenPersonalCandidatesAreSparse() = runBlocking {
        val title = "灵源仙路"
        val author = "春雾煮茶"
        val currentSource = changduSource("当前源", "https://tier-global-current.example")
        val personalSources = (1..2).map { index ->
            changduSource("专属源$index", "https://tier-global-personal-$index.example")
        }
        val globalSources = (1..3).map { index ->
            changduSource("全局补源$index", "https://tier-global-extra-$index.example")
        }
        val sources = listOf(currentSource) + personalSources + globalSources
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val fetches = Collections.synchronizedList(ArrayList<String>())
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = sources.flatMap { source ->
                    customCatalogFixture(source.sourceUrl, title, author, titles).entries
                }.associate { it.toPair() },
                onFetch = { url -> fetches.add(url) }
            )
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        personalSources.forEach { source ->
            sourceQualityRouter.recordTrustedCatalogResolved(
                sourceBook(source, title, author, titles.last()),
                chapterCount = titles.size,
                rawChapterCount = titles.size
            )
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(currentSource, title, author, titles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_sparse-personal-global")
            this.title = title
            this.author = author
        }

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = true,
            triggerV8 = false,
            maintenanceOnly = false
        )

        val searchedUrls = fetches.filter { url -> url.endsWith("/modules/article/search.php") }.toSet()
        globalSources.forEach { source ->
            assertTrue(searchedUrls.contains("${source.sourceUrl}/modules/article/search.php"))
        }
        val tierFile = java.io.File(testBookCacheFolderPath(collBook.get_id()), ".source_engine_content_tier")
        val persistedSourceKeys = tierFile.readLines()
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .map { routeId -> SourceEngineBookRoute.sourceKey(SourceEngineBookRoute.decodeBookId(routeId)) }
            .toSet()
        globalSources.forEach { source ->
            assertTrue(persistedSourceKeys.contains(source.sourceUrl))
        }
    }

    @Test
    fun readingLightContentTierDoesNotRunGlobalSearchWhenPersonalCandidatesAreSparse() = runBlocking {
        val title = "灵源仙路"
        val author = "春雾煮茶"
        val currentSource = changduSource("当前源", "https://tier-light-current.example")
        val personalSources = (1..2).map { index ->
            changduSource("轻量专属源$index", "https://tier-light-personal-$index.example")
        }
        val globalSources = (1..3).map { index ->
            changduSource("轻量不应全局源$index", "https://tier-light-global-$index.example")
        }
        val sources = listOf(currentSource) + personalSources + globalSources
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val fetches = Collections.synchronizedList(ArrayList<String>())
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = sources.flatMap { source ->
                    customCatalogFixture(source.sourceUrl, title, author, titles).entries
                }.associate { it.toPair() },
                onFetch = { url -> fetches.add(url) }
            )
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        personalSources.forEach { source ->
            sourceQualityRouter.recordTrustedCatalogResolved(
                sourceBook(source, title, author, titles.last()),
                chapterCount = titles.size,
                rawChapterCount = titles.size
            )
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(currentSource, title, author, titles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_reading-light-personal")
            this.title = title
            this.author = author
        }

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = true,
            triggerV8 = false,
            maintenanceOnly = false,
            mode = SourceContentTierMode.READING_LIGHT
        )

        val searchedUrls = fetches.filter { url -> url.endsWith("/modules/article/search.php") }.toSet()
        globalSources.forEach { source ->
            assertFalse(searchedUrls.contains("${source.sourceUrl}/modules/article/search.php"))
        }
    }

    @Test
    fun contentTierGlobalSearchUsesIdentityProfileAliasQueriesWhenPersonalCandidatesAreSparse() = runBlocking {
        val title = "灵源仙路"
        val aliasTitle = "灵源仙路：我养的灵兽太懂感恩了"
        val aliasSearchQuery = "灵源仙路我养的灵兽太懂感恩了"
        val author = "春雾煮茶"
        val currentSource = changduSource("当前源", "https://tier-alias-current.example")
        val seedAliasSource = changduSource("画像别名源", "https://tier-alias-profile.example")
        val personalSources = (1..2).map { index ->
            changduSource("别名专属源$index", "https://tier-alias-personal-$index.example")
        }
        val globalAliasSource = changduSource("全局别名补源", "https://tier-alias-global.example")
        val titles = (1..120).map { index -> "第${index}章 正文" }
        var activeSources = listOf(currentSource) + personalSources + globalAliasSource
        val fetches = Collections.synchronizedList(ArrayList<HttpRequest>())
        val globalSearchUrl = "${globalAliasSource.sourceUrl}/modules/article/search.php"
        val aliasSearchKey = URLEncoder.encode(aliasSearchQuery, "UTF-8")
        val responses = (
            customCatalogFixture(currentSource.sourceUrl, title, author, titles) +
                customCatalogFixture(seedAliasSource.sourceUrl, aliasTitle, author, titles) +
                personalSources.flatMap { source ->
                    customCatalogFixture(source.sourceUrl, title, author, titles).entries
                }.associate { it.toPair() } +
                customCatalogFixture(globalAliasSource.sourceUrl, aliasTitle, author, titles)
                    .filterKeys { url -> url != globalSearchUrl }
            ).toMutableMap()
        val delegate = MapFetcher(responses)
        val engine = LegadoSourceEngine(
            object : HttpFetcher {
                override fun fetch(request: HttpRequest): HttpResponse {
                    fetches.add(request)
                    if (request.url == globalSearchUrl) {
                        val body = if (request.body.orEmpty().contains(aliasSearchKey)) {
                            searchHtml(
                                bookUrl = "${globalAliasSource.sourceUrl}/books/1/",
                                title = aliasTitle,
                                lastChapter = titles.last(),
                                author = author
                            )
                        } else {
                            searchHtmlRows(emptyList())
                        }
                        return HttpResponse(request.url, body)
                    }
                    return delegate.fetch(request)
                }
            }
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        personalSources.forEach { source ->
            sourceQualityRouter.recordTrustedCatalogResolved(
                sourceBook(source, title, author, titles.last()),
                chapterCount = titles.size,
                rawChapterCount = titles.size
            )
        }
        val allSources = listOf(currentSource, seedAliasSource) + personalSources + globalAliasSource
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { activeSources },
            sourceFinder = { sourceUrl -> allSources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(currentSource, title, author, titles.last())
        seedIdentityProfile(
            provider,
            currentBook,
            sourceBook(seedAliasSource, aliasTitle, author, titles.last())
        )
        assertTrue(personalTierBookNamesFor(provider, currentBook).contains(aliasSearchQuery))
        fetches.clear()
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_sparse-personal-alias-global")
            this.title = title
            this.author = author
        }
        java.io.File(testBookCacheFolderPath(collBook.get_id())).deleteRecursively()

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = true,
            triggerV8 = false,
            maintenanceOnly = false
        )

        assertTrue(
            fetches.any { request ->
                request.url == globalSearchUrl && request.body.orEmpty().contains(aliasSearchKey)
            }
        )
        val tierFile = java.io.File(testBookCacheFolderPath(collBook.get_id()), ".source_engine_content_tier")
        val persistedSourceKeys = tierFile.readLines()
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .map { routeId -> SourceEngineBookRoute.sourceKey(SourceEngineBookRoute.decodeBookId(routeId)) }
            .toSet()
        assertTrue(persistedSourceKeys.contains(globalAliasSource.sourceUrl))
    }

    @Test
    fun contentTierGlobalSearchUsesPersistedAliasNamesWhenPersonalCandidatesAreSparse() = runBlocking {
        val title = "灵源仙路"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val author = "春雾煮茶"
        val currentSource = changduSource("当前源", "https://tier-persisted-alias-current.example")
        val persistedAliasSource = changduSource("已知别名源", "https://tier-persisted-alias-known.example")
        val personalSources = (1..2).map { index ->
            changduSource("别名已知专属源$index", "https://tier-persisted-alias-personal-$index.example")
        }
        val globalAliasSource = changduSource("全局别名新源", "https://tier-persisted-alias-global.example")
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val sources = listOf(currentSource, persistedAliasSource) + personalSources + globalAliasSource
        val fetches = Collections.synchronizedList(ArrayList<HttpRequest>())
        val globalSearchUrl = "${globalAliasSource.sourceUrl}/modules/article/search.php"
        val aliasSearchKey = URLEncoder.encode(aliasTitle, "UTF-8")
        val responses = (
            customCatalogFixture(currentSource.sourceUrl, title, author, titles) +
                customCatalogFixture(persistedAliasSource.sourceUrl, aliasTitle, author, titles) +
                personalSources.flatMap { source ->
                    customCatalogFixture(source.sourceUrl, title, author, titles).entries
                }.associate { it.toPair() } +
                customCatalogFixture(globalAliasSource.sourceUrl, aliasTitle, author, titles)
                    .filterKeys { url -> url != globalSearchUrl }
            ).toMutableMap()
        val delegate = MapFetcher(responses)
        val engine = LegadoSourceEngine(
            object : HttpFetcher {
                override fun fetch(request: HttpRequest): HttpResponse {
                    fetches.add(request)
                    if (request.url == globalSearchUrl) {
                        val body = if (request.body.orEmpty().contains(aliasSearchKey)) {
                            searchHtml(
                                bookUrl = "${globalAliasSource.sourceUrl}/books/1/",
                                title = aliasTitle,
                                lastChapter = titles.last(),
                                author = author
                            )
                        } else {
                            searchHtmlRows(emptyList())
                        }
                        return HttpResponse(request.url, body)
                    }
                    return delegate.fetch(request)
                }
            }
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        personalSources.forEach { source ->
            sourceQualityRouter.recordTrustedCatalogResolved(
                sourceBook(source, title, author, titles.last()),
                chapterCount = titles.size,
                rawChapterCount = titles.size
            )
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(currentSource, title, author, titles.last())
        val persistedAliasBook = sourceBook(persistedAliasSource, aliasTitle, author, titles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_sparse-personal-persisted-alias-global")
            this.title = title
            this.author = author
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        val tierFile = java.io.File(cacheFolder, ".source_engine_content_tier")
        tierFile.parentFile?.mkdirs()
        tierFile.writeText(SourceEngineBookRoute.bookId(persistedAliasBook))

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = true,
            triggerV8 = false,
            maintenanceOnly = false
        )

        assertTrue(
            fetches.any { request ->
                request.url == globalSearchUrl && request.body.orEmpty().contains(aliasSearchKey)
            }
        )
        val persistedSourceKeys = tierFile.readLines()
            .filter { routeId -> SourceEngineBookRoute.isBookId(routeId) }
            .map { routeId -> SourceEngineBookRoute.sourceKey(SourceEngineBookRoute.decodeBookId(routeId)) }
            .toSet()
        assertTrue(persistedSourceKeys.contains(globalAliasSource.sourceUrl))
    }

    @Test
    fun contentTierGlobalSearchSkipsWhenPersonalCandidatesAreSufficient() = runBlocking {
        val title = "叩问仙道"
        val author = "雨打青石"
        val currentSource = changduSource("当前主流源", "https://tier-mainstream-current.example")
        val personalSources = (1..8).map { index ->
            changduSource("主流专属源$index", "https://tier-mainstream-personal-$index.example")
        }
        val globalSource = changduSource("不应触发全局源", "https://tier-mainstream-global.example")
        val sources = listOf(currentSource) + personalSources + globalSource
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val fetches = Collections.synchronizedList(ArrayList<String>())
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = sources.flatMap { source ->
                    customCatalogFixture(source.sourceUrl, title, author, titles).entries
                }.associate { it.toPair() },
                onFetch = { url -> fetches.add(url) }
            )
        )
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        personalSources.forEach { source ->
            sourceQualityRouter.recordTrustedCatalogResolved(
                sourceBook(source, title, author, titles.last()),
                chapterCount = titles.size,
                rawChapterCount = titles.size
            )
        }
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(currentSource, title, author, titles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_sufficient-personal")
            this.title = title
            this.author = author
        }

        provider.prepareBookContentTierResult(
            SourceEngineBookRoute.bookId(currentBook),
            collBook,
            persist = false,
            triggerV8 = false,
            maintenanceOnly = false
        )

        val searchedUrls = fetches.filter { url -> url.endsWith("/modules/article/search.php") }.toSet()
        assertFalse(searchedUrls.contains("${globalSource.sourceUrl}/modules/article/search.php"))
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
                trustedBookFixture("https://doupo.example", "斗破苍穹", "天蚕土豆", "第120章 正文", 120) +
                    trustedBookFixture("https://doupo-mirror.example", "斗破苍穹", "天蚕土豆", "第120章 正文", 120) +
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
        val slowSearchDelayMs = 5_000L
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
                    "https://progress-qingshan-youxing.example/modules/article/search.php" to slowSearchDelayMs,
                    "https://progress-qingshan-youxing-mirror.example/modules/article/search.php" to slowSearchDelayMs
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
        val updateTimes = mutableListOf<Long>()
        val startedAt = System.currentTimeMillis()

        val books = provider.searchBooksProgressively("青山") { update ->
            updates.add(update.map { it.title to it.author })
            updateTimes.add(System.currentTimeMillis() - startedAt)
        }

        assertTrue(
            "first visible result should not wait for a slow related title; firstUpdateMs=${updateTimes.firstOrNull()} updates=$updates",
            updateTimes.first() < slowSearchDelayMs
        )
        assertEquals(listOf("青山" to "会说话的肘子"), updates.first())
        assertTrue(updates.any { update -> update == listOf("青山" to "会说话的肘子") })
        assertEquals(listOf("青山" to "会说话的肘子", "青山有幸" to "更俗"), books.map { it.title to it.author })
        assertTrue(updates.last().contains("青山有幸" to "更俗"))
    }

    @Test
    fun progressiveSearchPublishesValidatedAliasImmediatelyThenPromotesLaterExactConsensus() = runBlocking {
        val query = "星河仙路"
        val aliasTitle = "星河仙途：灵兽知恩录"
        val aliasAuthor = "误书作者"
        val exactAuthor = "正确作者"
        val aliasSources = (1..2).map { index ->
            changduSource("先到近似目录源$index", "https://progressive-alias-first-$index.example")
        }
        val exactSources = (1..2).map { index ->
            changduSource("后到精确目录源$index", "https://progressive-exact-later-$index.example")
        }
        val sources = aliasSources + exactSources
        val aliasSourceUrls = aliasSources.mapTo(LinkedHashSet()) { source -> source.sourceUrl }
        val earlyExactSourceUrl = exactSources.first().sourceUrl
        val blockedExactSearchUrl = "${exactSources.last().sourceUrl}/modules/article/search.php"
        val aliasChapters = (1..60).map { index -> "第${index}章 灵兽旧事$index" }
        val exactChapters = (1..70).map { index -> "第${index}章 仙路归真$index" }
        val responses = buildMap {
            aliasSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, aliasTitle, aliasAuthor, aliasChapters))
            }
            exactSources.forEach { source ->
                putAll(customCatalogFixture(source.sourceUrl, query, exactAuthor, exactChapters))
            }
        }
        val releaseSecondExactSearch = CountDownLatch(1)
        val searchEngine = LegadoSourceEngine(
            MapFetcher(
                responses = responses,
                onFetch = { url ->
                    if (url == blockedExactSearchUrl) {
                        releaseSecondExactSearch.await(10L, TimeUnit.SECONDS)
                    }
                }
            )
        )
        val validationEngine = LegadoSourceEngine(MapFetcher(responses))
        val initialCatalogEvidenceMaterialized = CompletableDeferred<Unit>()
        val requiredInitialCatalogSources = aliasSourceUrls + earlyExactSourceUrl
        val completedInitialCatalogSources = LinkedHashSet<String>()
        val firstUpdate = CompletableDeferred<List<BookSearchResult>>()
        val promotedUpdate = CompletableDeferred<List<BookSearchResult>>()
        val updates = Collections.synchronizedList(ArrayList<List<BookSearchResult>>())
        val provider = SourceEngineReaderContentProvider(
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            catalogMaterializationLifecycle = { book, started ->
                if (!started && book.source.sourceUrl in requiredInitialCatalogSources) {
                    val initialEvidenceFinished = synchronized(completedInitialCatalogSources) {
                        completedInitialCatalogSources.add(book.source.sourceUrl)
                        completedInitialCatalogSources.size == requiredInitialCatalogSources.size
                    }
                    if (initialEvidenceFinished) initialCatalogEvidenceMaterialized.complete(Unit)
                }
            },
            searchProgressGroupsValidationTimeoutMs = 5_000L,
            searchProgressExactValidationBatchTimeoutMs = 2_000L,
            searchVisibleCompletedRankTimeoutMs = 200L,
            searchProgressPollIntervalMs = 10L
        )
        val search = async {
            provider.searchBooksProgressively(query) { update ->
                if (update.isEmpty()) return@searchBooksProgressively
                updates.add(update)
                firstUpdate.complete(update)
                if (
                    update.firstOrNull()?.title == query &&
                    update.first().readingReady &&
                    update.any { book -> book.title == aliasTitle }
                ) {
                    promotedUpdate.complete(update)
                }
            }
        }

        try {
            withTimeout(3_000L) { initialCatalogEvidenceMaterialized.await() }
            val first = withTimeout(1_000L) { firstUpdate.await() }

            assertEquals(
                "one exact catalog source must not veto a two-source catalog-validated alias or add an observation window",
                listOf(aliasTitle to aliasAuthor),
                first.map { book -> book.title to book.author }
            )
            assertTrue("every visible result must already be catalog-reading-ready", first.all { book -> book.readingReady })

            releaseSecondExactSearch.countDown()
            val promoted = withTimeout(5_000L) { promotedUpdate.await() }

            assertEquals(query to exactAuthor, promoted.first().title to promoted.first().author)
            assertTrue("the later exact consensus must be reading-ready", promoted.first().readingReady)
            assertTrue(
                "the earlier catalog-validated alias must remain below the promoted exact result; updates=$updates",
                promoted.drop(1).any { book ->
                    book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
                }
            )
        } finally {
            releaseSecondExactSearch.countDown()
            search.cancel()
            withTimeoutOrNull(2_000L) { search.join() }
        }
    }

    @Test
    fun progressiveSearchPublishesTrustedShortCatalogBeforeSlowSourcesFinish() = runBlocking {
        val slowSearchDelayMs = 5_000L
        val sources = listOf(
            changduSource("剑烛源A", "https://progress-jianzhu-a.example"),
            changduSource("剑烛源B", "https://progress-jianzhu-b.example"),
            changduSource("剑烛源C", "https://progress-jianzhu-c.example"),
            changduSource("剑烛源D", "https://progress-jianzhu-d.example"),
            changduSource("慢源", "https://progress-jianzhu-slow.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture("https://progress-jianzhu-a.example", "剑烛大荒", "爱潜水的乌贼", "第2章 惊蛇", 2) +
                    trustedBookFixture("https://progress-jianzhu-b.example", "剑烛大荒", "爱潜水的乌贼", "第2章 惊蛇", 2) +
                    trustedBookFixture("https://progress-jianzhu-c.example", "剑烛大荒", "爱潜水的乌贼", "第3章 起势", 3) +
                    trustedBookFixture("https://progress-jianzhu-d.example", "剑烛大荒", "爱潜水的乌贼", "第4章 入夜", 4) +
                    trustedBookFixture("https://progress-jianzhu-slow.example", "剑烛大荒外传", "其他作者", "第300章 正文", 300),
                delays = mapOf(
                    "https://progress-jianzhu-slow.example/modules/article/search.php" to slowSearchDelayMs
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
        val updates = mutableListOf<List<Triple<String?, String?, String?>>>()
        val updateTimes = mutableListOf<Long>()
        val startedAt = System.currentTimeMillis()

        val books = provider.searchBooksProgressively("剑烛大荒") { update ->
            if (update.isNotEmpty()) {
                updates.add(update.map { Triple(it.title, it.author, it.cover) })
                updateTimes.add(System.currentTimeMillis() - startedAt)
            }
        }

        assertTrue(updateTimes.isNotEmpty())
        assertTrue(
            "short catalog should publish before slow sources finish; " +
                "firstUpdateMs=${updateTimes.firstOrNull()} updates=$updates",
            updateTimes.first() < slowSearchDelayMs
        )
        assertEquals(listOf("剑烛大荒" to "爱潜水的乌贼"), updates.first().map { it.first to it.second })
        assertEquals("file:///cover.jpg", updates.first().first().third)
        assertEquals(listOf("剑烛大荒" to "爱潜水的乌贼"), books.map { it.title to it.author })
        assertEquals("file:///cover.jpg", books.first().cover)
    }

    @Test
    fun progressiveSearchKeepsCompletedAliasWhenUnrelatedGroupMissesFinalDeadline() = runBlocking {
        val query = "灵源仙"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val aliasAuthor = "春雾煮茶"
        val quickWrongTitle = query
        val slowTitle = "灵源仙记"
        val aliasA = "https://completed-partial-lingyuan-alias-a.example"
        val aliasB = "https://completed-partial-lingyuan-alias-b.example"
        val wrongA = "https://completed-partial-lingyuan-wrong-a.example"
        val wrongB = "https://completed-partial-lingyuan-wrong-b.example"
        val slowA = "https://completed-partial-lingyuan-slow-a.example"
        val slowB = "https://completed-partial-lingyuan-slow-b.example"
        val sources = listOf(aliasA, aliasB, wrongA, wrongB, slowA, slowB).mapIndexed { index, sourceUrl ->
            changduSource("完成态局部结果源$index", sourceUrl)
        }
        fun titles(prefix: String): List<String> {
            return (1..60).map { index -> "第${index}章 $prefix$index" }
        }
        val aliasTitles = titles("灵兽感恩")
        val wrongTitles = titles("仙门旧事")
        val slowTitles = titles("少年游历")
        val responses = customCatalogFixture(aliasA, aliasTitle, aliasAuthor, aliasTitles) +
            customCatalogFixture(aliasB, aliasTitle, aliasAuthor, aliasTitles) +
            customCatalogFixture(wrongA, quickWrongTitle, "李想", wrongTitles) +
            customCatalogFixture(wrongB, quickWrongTitle, "李想", wrongTitles) +
            customCatalogFixture(slowA, slowTitle, "萧不鸣", slowTitles) +
            customCatalogFixture(slowB, slowTitle, "萧不鸣", slowTitles)
        val validationCalls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = mapOf(
                "$aliasA/books/1/" to 800L,
                "$aliasB/books/1/" to 800L,
                "$slowA/books/1/" to 8_000L,
                "$slowB/books/1/" to 8_000L
            )
        )
        val validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 5_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(
            MapFetcher(
                responses = responses
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            searchProgressGroupsValidationTimeoutMs = 500L,
            searchVisibleCompletedRankTimeoutMs = 2_500L
        )
        val updates = mutableListOf<List<Pair<String?, String?>>>()

        val startedAt = System.currentTimeMillis()
        val books = provider.searchBooksProgressively(query) { update ->
            if (update.isNotEmpty()) updates.add(update.map { book -> book.title to book.author })
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertTrue("progressive search must publish at least one usable update; updates=$updates", updates.isNotEmpty())
        assertTrue(
            "the completed alias must survive an unrelated slow group; got ${books.map { it.title to it.author }}",
            books.any { book -> book.title == aliasTitle && book.author == aliasAuthor }
        )
        assertTrue("the unrelated slow request must be cancelled at the completed deadline", validationCalls.cancelCount.get() > 0)
        assertTrue("completed deadline must return before the 8s slow call; elapsedMs=$elapsedMs", elapsedMs < 7_000L)
    }

    @Test
    fun searchValidatesCatalogAliasWhenTheQueryDiffersByOneTitleCharacter() = runBlocking {
        val query = "灵源仙路"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val aliasAuthor = "春雾煮茶"
        val exactA = "https://catalog-alias-exact-a.example"
        val exactB = "https://catalog-alias-exact-b.example"
        val aliasA = "https://catalog-alias-long-a.example"
        val aliasB = "https://catalog-alias-long-b.example"
        val sources = listOf(exactA, exactB, aliasA, aliasB).mapIndexed { index, sourceUrl ->
            changduSource("目录别名搜索源$index", sourceUrl)
        }
        val exactTitles = (1..60).map { index -> "第${index}章 另一部书$index" }
        val aliasTitles = (1..60).map { index -> "第${index}章 灵兽感恩$index" }
        val responses = customCatalogFixture(exactA, query, "李想", exactTitles) +
            customCatalogFixture(exactB, query, "李想", exactTitles) +
            customCatalogFixture(aliasA, aliasTitle, aliasAuthor, aliasTitles) +
            customCatalogFixture(aliasB, aliasTitle, aliasAuthor, aliasTitles)
        val engine = LegadoSourceEngine(MapFetcher(responses = responses))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks(query)

        assertTrue(
            "a catalog-compatible alias must reach validation without a title-specific exception; " +
                "got ${books.map { it.title to it.author }}",
            books.any { book -> book.title == aliasTitle && book.author == aliasAuthor }
        )
    }

    @Test
    fun progressiveSearchKeepsLongCatalogAliasSeparateFromManyShorterDifferentAuthorTitles() = runBlocking {
        val query = "灵源仙路"
        val shortTitle = "灵源"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val aliasAuthor = "春雾煮茶"
        val crowdedShortSources = (1..18).map { index ->
            changduSource("短标题拥挤源$index", "https://short-crowd-$index.example")
        }
        val otherShortSources = (1..5).flatMap { authorIndex ->
            (1..2).map { sourceIndex ->
                changduSource(
                    "短标题异作者${authorIndex}源$sourceIndex",
                    "https://short-author-$authorIndex-$sourceIndex.example"
                ) to "短标题作者$authorIndex"
            }
        }
        val aliasSources = (1..2).map { index ->
            changduSource("长标题目录源$index", "https://long-alias-$index.example")
        }
        val sources = crowdedShortSources + otherShortSources.map { (source, _) -> source } + aliasSources
        val chapterTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        ) + (9..60).map { index -> "第${index}章 灵兽感恩$index" }
        val searchResponses = LinkedHashMap<String, String>()
        crowdedShortSources.forEach { source ->
            searchResponses["${source.sourceUrl}/modules/article/search.php"] = searchHtml(
                bookUrl = "${source.sourceUrl}/books/1/",
                title = shortTitle,
                lastChapter = chapterTitles.last(),
                author = "八宝汤圆"
            )
        }
        otherShortSources.forEach { (source, author) ->
            searchResponses.putAll(customCatalogFixture(source.sourceUrl, shortTitle, author, chapterTitles))
        }
        aliasSources.forEach { source ->
            searchResponses.putAll(customCatalogFixture(source.sourceUrl, aliasTitle, aliasAuthor, chapterTitles))
        }
        val validationResponses = LinkedHashMap<String, String>()
        otherShortSources.forEach { (source, author) ->
            validationResponses.putAll(customCatalogFixture(source.sourceUrl, shortTitle, author, chapterTitles))
        }
        aliasSources.forEach { source ->
            validationResponses.putAll(customCatalogFixture(source.sourceUrl, aliasTitle, aliasAuthor, chapterTitles))
        }
        val validationDelays = buildMap {
            otherShortSources.forEach { (source, _) -> put("${source.sourceUrl}/books/1/", 2_000L) }
            aliasSources.forEach { source -> put("${source.sourceUrl}/books/1/", 1_500L) }
        }
        val searchDelays = aliasSources.associate { source ->
            "${source.sourceUrl}/modules/article/search.php" to 200L
        }
        val searchEngine = LegadoSourceEngine(
            MapFetcher(responses = searchResponses, exactDelays = searchDelays)
        )
        val validationEngine = LegadoSourceEngine(
            MapFetcher(responses = validationResponses, exactDelays = validationDelays)
        )
        val provider = SourceEngineReaderContentProvider(
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            searchVisibleCompletedRankTimeoutMs = 50L
        )
        val updates = mutableListOf<List<BookSearchResult>>()

        val books = provider.searchBooksProgressively(query) { update ->
            if (update.isNotEmpty()) updates.add(update)
        }

        assertTrue(
            "a same-author long catalog alias must remain independently validatable when many shorter " +
                "different-author titles are present; updates=${updates.map { update -> update.map { it.title to it.author } }} " +
                "final=${books.map { it.title to it.author }}",
            books.any { book ->
                book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
            }
        )
    }

    @Test
    fun progressiveSearchPrioritizesStrongCatalogAliasOverShorterContainedGroups() = runBlocking {
        val query = "星河仙路"
        val shortTitle = "星河"
        val relatedTitle = "仙路"
        val aliasTitle = "星河仙途：灵兽知恩录"
        val aliasAuthor = "知恩客"
        val shortSources = (1..17).map { index ->
            changduSource("短包含候选源$index", "https://strong-alias-short-$index.example")
        }
        val relatedSources = (1..2).map { index ->
            changduSource("非前缀相关源$index", "https://strong-alias-related-$index.example")
        }
        val aliasSources = (1..2).map { index ->
            changduSource("强近名目录源$index", "https://strong-alias-long-$index.example")
        }
        val sources = shortSources + relatedSources + aliasSources
        val chapterTitles = listOf(
            "第1章 星河初见",
            "第2章 灵兽知恩",
            "第3章 山中灵泉",
            "第4章 夜访坊市",
            "第5章 玄甲灵龟",
            "第6章 丹火初成",
            "第7章 万兽来朝",
            "第8章 仙途初启"
        ) + (9..60).map { index -> "第${index}章 星河旧事$index" }
        val responses = LinkedHashMap<String, String>()
        shortSources.forEach { source ->
            responses.putAll(
                detailOnlyFixture(
                    baseUrl = source.sourceUrl,
                    title = shortTitle,
                    author = "慢源作者",
                    lastChapter = chapterTitles.last()
                )
            )
        }
        relatedSources.forEach { source ->
            responses.putAll(customCatalogFixture(source.sourceUrl, relatedTitle, "渡河客", chapterTitles))
        }
        aliasSources.forEach { source ->
            responses.putAll(customCatalogFixture(source.sourceUrl, aliasTitle, aliasAuthor, chapterTitles))
        }
        val badDetailUrls = shortSources.mapTo(LinkedHashSet()) { source -> "${source.sourceUrl}/books/1/" }
        val validationCalls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = buildMap {
                badDetailUrls.forEach { url -> put(url, 20_000L) }
                aliasSources.forEach { source -> put("${source.sourceUrl}/books/1/", 4_000L) }
            }
        )
        val validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 25_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(MapFetcher(responses))
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            searchVisibleCompletedRankTimeoutMs = 50L
        )
        var badDetailsBeforeAlias: List<String>? = null
        val updates = mutableListOf<List<BookSearchResult>>()

        try {
            val books = provider.searchBooksProgressively(query) { update ->
                if (update.isNotEmpty()) updates.add(update)
                if (
                    badDetailsBeforeAlias == null &&
                    update.any { book -> book.title == aliasTitle && book.readingReady }
                ) {
                    badDetailsBeforeAlias = validationCalls.executedUrlsSnapshot()
                        .filter { url -> url in badDetailUrls }
                }
            }

            assertTrue(
                "the strongest authored catalog alias must use the extended progress window; " +
                    "updates=${updates.map { update -> update.map { it.title to it.author } }} " +
                    "final=${books.map { it.title to it.author }} " +
                    "executed=${validationCalls.executedUrlsSnapshot()}",
                books.any { book ->
                    book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
                }
            )
            assertEquals(
                "shorter contained groups must not consume progress validation before the preferred alias",
                emptyList<String>(),
                badDetailsBeforeAlias
            )
        } finally {
            validationCalls.cancelAll()
        }
    }

    @Test
    fun progressiveSearchSkipsDisplayPreviewForPreferredStrongCatalogAlias() = runBlocking {
        val query = "云海仙路"
        val aliasTitle = "云海仙途：灵舟知恩录"
        val aliasAuthor = "听潮客"
        val aliasSources = (1..2).map { index ->
            changduSource("强别名直验源$index", "https://strong-alias-direct-$index.example")
        }
        val chapterTitles = (1..60).map { index -> "第${index}章 云海灵舟$index" }
        val responses = aliasSources.fold(emptyMap<String, String>()) { current, source ->
            current + customCatalogFixture(source.sourceUrl, aliasTitle, aliasAuthor, chapterTitles)
        }
        val previewOnlyDetailCalls = AtomicInteger(0)
        lateinit var validationFetcher: OkHttpSourceEngineFetcher
        val validationCalls = CancelableFixtureCallFactory(
            responses = responses,
            requestDelayMs = { request ->
                val scopeName = validationFetcher.currentRequestScope()?.name.orEmpty()
                val isDetail = request.url.encodedPath.endsWith("/books/1/")
                if (scopeName.startsWith("searchProgressValidationGroup:") && isDetail) {
                    previewOnlyDetailCalls.incrementAndGet()
                    20_000L
                } else {
                    null
                }
            }
        )
        validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 25_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(MapFetcher(responses))
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { aliasSources },
            sourceFinder = { sourceUrl -> aliasSources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    aliasSources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t1\tbreadth\t10000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            searchProgressGroupsValidationTimeoutMs = 300L,
            searchVisibleCompletedRankTimeoutMs = 50L
        )

        val startedAt = System.currentTimeMillis()
        try {
            val books = provider.searchBooksProgressively(query) {}
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertTrue(
                "a preferred strong alias must enter full catalog validation directly; " +
                    "elapsedMs=$elapsedMs previewCalls=${previewOnlyDetailCalls.get()} " +
                    "final=${books.map { it.title to it.readingReady }}",
                elapsedMs < 15_000L &&
                    books.any { book ->
                        book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
                    }
            )
            assertEquals(
                "near-title aliases cannot publish a preview, so their preview-only detail stage is pure budget loss",
                0,
                previewOnlyDetailCalls.get()
            )
        } finally {
            validationCalls.cancelAll()
        }
    }

    @Test
    fun progressiveSearchKeepsReadableCatalogAliasWhenRawExactCandidateHasNoCatalog() = runBlocking {
        val query = "星河仙路"
        val aliasTitle = "星河仙途：灵兽知恩录"
        val aliasAuthor = "知恩客"
        val exact = "https://raw-exact-without-catalog.example"
        val aliasA = "https://readable-catalog-alias-a.example"
        val aliasB = "https://readable-catalog-alias-b.example"
        val sources = listOf(
            changduSource("精确单源无目录", exact),
            changduSource("可读别名目录源A", aliasA),
            changduSource("可读别名目录源B", aliasB)
        )
        val aliasTitles = (1..60).map { index -> "第${index}章 灵兽知恩$index" }
        val responses = detailOnlyFixture(exact, query, "孤源作者", "第60章 伪目录") +
            customCatalogFixture(aliasA, aliasTitle, aliasAuthor, aliasTitles) +
            customCatalogFixture(aliasB, aliasTitle, aliasAuthor, aliasTitles)
        val validationCalls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = mapOf("$exact/books/1/" to 8_000L)
        )
        val validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(MapFetcher(responses))
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            searchProgressGroupsValidationTimeoutMs = 300L,
            searchVisibleCompletedRankTimeoutMs = 600L
        )
        val updates = mutableListOf<List<BookSearchResult>>()

        val startedAt = System.currentTimeMillis()
        try {
            val books = provider.searchBooksProgressively(query) { update ->
                if (update.isNotEmpty()) updates.add(update)
            }
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertTrue(
                "a slow raw exact-title candidate without a catalog must not suppress or indefinitely block " +
                    "a validated readable alias; elapsedMs=$elapsedMs " +
                    "updates=${updates.map { update -> update.map { it.title to it.readingReady } }} " +
                    "final=${books.map { it.title to it.readingReady }}",
                elapsedMs < 3_000L &&
                    updates.flatten().any { book -> book.title == aliasTitle && book.readingReady } &&
                    books.any { book -> book.title == aliasTitle && book.readingReady }
            )
            assertTrue("the slow exact validation must be cancelled at the existing deadlines", validationCalls.cancelCount.get() > 0)
        } finally {
            validationCalls.cancelAll()
        }
    }

    @Test
    fun searchProgressRankGateCoalescesInsteadOfQueueingSourceJobs() = runBlocking {
        val gate = SearchProgressRankGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.tryRun {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val startedAt = System.currentTimeMillis()
        val secondEntered = AtomicBoolean(false)
        val second = gate.tryRun {
            secondEntered.set(true)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertFalse("a producer must not queue behind an active progressive rank", second)
        assertFalse(secondEntered.get())
        assertTrue("coalescing must return immediately; elapsedMs=$elapsedMs", elapsedMs < 100L)
        release.complete(Unit)
        assertTrue(withTimeout(1_000L) { first.await() })
    }

    @Test
    fun searchProgressRankGateExclusiveWorkWaitsForPumpAndNeverOverlaps() = runBlocking {
        val gate = SearchProgressRankGate()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val pumpEntered = CompletableDeferred<Unit>()
        val releasePump = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val pump = async {
            gate.tryRun {
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                pumpEntered.complete(Unit)
                try {
                    releasePump.await()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        pumpEntered.await()
        val exclusive = async {
            gate.runExclusive {
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    exclusiveEntered.complete(Unit)
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        assertFalse(
            "exclusive tier/final work must wait while a pump rank owns the gate",
            withTimeoutOrNull(100L) { exclusiveEntered.await() } != null
        )
        releasePump.complete(Unit)
        withTimeout(1_000L) {
            assertTrue(pump.await())
            exclusive.await()
        }

        assertEquals("all rank work must drain before the gate test returns", 0, active.get())
        assertEquals("pump and exclusive rank work must never overlap", 1, maxActive.get())
    }

    @Test
    fun progressiveTierReadyWaitsForActivePumpRankAndPublishesLatestStrongAlias() = runBlocking {
        val query = "灵源仙路"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val aliasAuthor = "木子知因"
        val earlySources = (1..2).map { index ->
            changduSource("早到强别名源$index", "https://rank-gate-early-$index.example")
        }
        val latestSources = (1..2).map { index ->
            changduSource("后到强别名源$index", "https://rank-gate-latest-$index.example")
        }
        val sources = earlySources + latestSources
        val chapterTitles = (1..60).map { index -> "第${index}章 灵兽感恩$index" }
        val responses = sources.fold(emptyMap<String, String>()) { current, source ->
            current + customCatalogFixture(source.sourceUrl, aliasTitle, aliasAuthor, chapterTitles)
        }
        val latestSearchDelays = latestSources.associate { source ->
            "${source.sourceUrl}/modules/article/search.php" to 300L
        }
        val searchEngine = LegadoSourceEngine(
            MapFetcher(
                responses = responses,
                exactDelays = latestSearchDelays
            )
        )
        val validationEngine = LegadoSourceEngine(MapFetcher(responses))
        val activeRanks = AtomicInteger(0)
        val maxActiveRanks = AtomicInteger(0)
        val pumpBlocked = AtomicBoolean(false)
        val pumpEntered = CompletableDeferred<Unit>()
        val releasePump = CompletableDeferred<Unit>()
        val tierReadyEntered = CompletableDeferred<Unit>()
        val rankStages = Collections.synchronizedList(ArrayList<String>())
        val provider = SourceEngineReaderContentProvider(
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    sources.joinToString("\n") { source ->
                        "source\t${source.sourceUrl}\t${source.sourceName}\t2\tclassic\t6000\t0\t0\t0\t0\t0\ttest"
                    }
                )
            ),
            searchProgressGroupsValidationTimeoutMs = 1_000L,
            searchVisibleCompletedRankTimeoutMs = 2_000L,
            searchProgressPollIntervalMs = 5L,
            searchRankStageLifecycle = { stage, started ->
                rankStages.add("${if (started) "start" else "finish"}:$stage")
                if (started) {
                    val current = activeRanks.incrementAndGet()
                    maxActiveRanks.updateAndGet { previous -> maxOf(previous, current) }
                    if (stage == "progress" && pumpBlocked.compareAndSet(false, true)) {
                        pumpEntered.complete(Unit)
                        releasePump.await()
                    }
                    if (stage == "tier2_ready") {
                        tierReadyEntered.complete(Unit)
                    }
                } else {
                    activeRanks.decrementAndGet()
                }
            }
        )
        val updates = Collections.synchronizedList(ArrayList<List<BookSearchResult>>())
        val search = async {
            provider.searchBooksProgressively(query) { update ->
                if (update.isNotEmpty()) updates.add(update)
            }
        }

        try {
            withTimeout(3_000L) { pumpEntered.await() }
            val tierReadyEnteredBeforeRelease = withTimeoutOrNull(1_000L) {
                tierReadyEntered.await()
            } != null
            releasePump.complete(Unit)
            assertFalse(
                "tier-ready must wait behind the active conflated pump rank; stages=$rankStages",
                tierReadyEnteredBeforeRelease
            )
            val completedBooks = withTimeoutOrNull(10_000L) { search.await() }
            assertTrue(
                "the gated public search must complete after the pump is released; " +
                    "stages=$rankStages active=${activeRanks.get()} updates=${synchronized(updates) { updates.size }}",
                completedBooks != null
            )
            val books = requireNotNull(completedBooks)

            assertEquals("all public-search rank stages must share one gate", 1, maxActiveRanks.get())
            assertEquals("rank lifecycle must drain before public search returns", 0, activeRanks.get())
            assertTrue(
                "the latest strong alias snapshot must still publish after the queued tier-ready rank",
                synchronized(updates) {
                    updates.flatten().any { book ->
                        book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
                    }
                }
            )
            assertTrue(
                "the final result must retain the readable strong alias",
                books.any { book ->
                    book.title == aliasTitle && book.author == aliasAuthor && book.readingReady
                }
            )
        } finally {
            releasePump.complete(Unit)
            search.cancel()
            withTimeoutOrNull(2_000L) { search.join() }
        }
    }

    @Test
    fun completedSearchValidationDeadlineKeepsFinishedGroupsInInputOrderAndCancelsPending() = runBlocking {
        val provider = SourceEngineReaderContentProvider(
            sourceProvider = { emptyList() }
        )
        val completionOrder = Collections.synchronizedList(ArrayList<String>())
        val first = async<String?> {
            delay(30L)
            completionOrder.add("first")
            "first"
        }
        val second = async<String?> {
            delay(5L)
            completionOrder.add("second")
            "second"
        }
        val pending = async<String?> {
            delay(5_000L)
            "pending"
        }
        val pendingNetworkCancels = AtomicInteger(0)

        val result = provider.awaitCompletedSearchValidationTasks(
            tasks = listOf(
                SourceEngineReaderContentProvider.CompletedSearchValidationTask(first),
                SourceEngineReaderContentProvider.CompletedSearchValidationTask(second),
                SourceEngineReaderContentProvider.CompletedSearchValidationTask(pending) {
                    pendingNetworkCancels.incrementAndGet()
                }
            ),
            timeoutMs = 100L
        )

        assertEquals(listOf("second", "first"), completionOrder)
        assertEquals(listOf("first", "second"), result.values)
        assertTrue(result.timedOut)
        assertTrue(pending.isCancelled)
        assertEquals(1, pendingNetworkCancels.get())
    }

    @Test
    fun completedSearchValidationDeadlinePhysicallyDrainsCancelledGroupBeforeReturning() = runBlocking {
        val provider = SourceEngineReaderContentProvider(sourceProvider = { emptyList() })
        val activeGroups = AtomicInteger(1)
        val cancellationStarted = CompletableDeferred<Unit>()
        val pending = async<String?> {
            try {
                CompletableDeferred<Unit>().await()
                null
            } finally {
                cancellationStarted.complete(Unit)
                withContext(NonCancellable) {
                    delay(1_200L)
                    activeGroups.decrementAndGet()
                }
            }
        }

        val result = provider.awaitCompletedSearchValidationTasks(
            tasks = listOf(SourceEngineReaderContentProvider.CompletedSearchValidationTask(pending)),
            timeoutMs = 20L
        )

        assertTrue("the deadline must initiate group cancellation", cancellationStarted.isCompleted)
        assertTrue(result.timedOut)
        assertTrue("the cancelled group job must be physically complete before return", pending.isCompleted)
        assertEquals("a later rank cannot inherit active work from the cancelled group", 0, activeGroups.get())
    }

    @Test
    fun completedSearchValidationDeadlineRunsWhenSourceNetworkThreadsAreBlocked() = runBlocking {
        val provider = SourceEngineReaderContentProvider(
            sourceProvider = { emptyList() }
        )
        val started = AtomicInteger(0)
        val releases = List(64) { AtomicBoolean(false) }
        val blockers = releases.map { release ->
            async<String?>(SourceNetworkDispatchers.foreground, start = CoroutineStart.LAZY) {
                started.incrementAndGet()
                val fallbackReleaseAt = System.currentTimeMillis() + 1_000L
                while (!release.get() && System.currentTimeMillis() < fallbackReleaseAt) {
                    Thread.sleep(5L)
                }
                null
            }
        }
        val tasks = blockers.mapIndexed { index, blocker ->
            SourceEngineReaderContentProvider.CompletedSearchValidationTask(blocker) {
                releases[index].set(true)
            }
        }
        val startedAt = System.currentTimeMillis()
        try {
            val result = async(
                SourceNetworkDispatchers.foreground,
                start = CoroutineStart.UNDISPATCHED
            ) {
                provider.awaitCompletedSearchValidationTasks(tasks, timeoutMs = 50L)
            }
            blockers.forEach { blocker -> blocker.start() }
            val completed = withTimeout(2_000L) { result.await() }
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertTrue("fixture must occupy source-network workers", started.get() > 0)
            assertTrue(completed.timedOut)
            assertTrue("deadline control must not wait for the 1s network fallback; elapsedMs=$elapsedMs", elapsedMs < 600L)
        } finally {
            releases.forEach { release -> release.set(true) }
            blockers.forEach { blocker -> blocker.cancel() }
        }
    }

    @Test
    fun completedSearchValidationDeadlineBroadcastsCancellationBeforePhysicalDrain() = runBlocking {
        val provider = SourceEngineReaderContentProvider(
            sourceProvider = { emptyList() }
        )
        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = AtomicBoolean(false)
        val firstFallbackAt = System.currentTimeMillis() + 2_500L
        val first = taskScope.async<String?> {
            firstStarted.countDown()
            while (!releaseFirst.get() && System.currentTimeMillis() < firstFallbackAt) {
                Thread.sleep(5L)
            }
            null
        }
        val second = taskScope.async<String?> {
            delay(5_000L)
            null
        }
        val firstNetworkCancels = AtomicInteger(0)
        val secondNetworkCancels = AtomicInteger(0)

        try {
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS))
            val startedAt = System.currentTimeMillis()
            val result = provider.awaitCompletedSearchValidationTasks(
                tasks = listOf(
                    SourceEngineReaderContentProvider.CompletedSearchValidationTask(first) {
                        firstNetworkCancels.incrementAndGet()
                        releaseFirst.set(true)
                    },
                    SourceEngineReaderContentProvider.CompletedSearchValidationTask(second) {
                        secondNetworkCancels.incrementAndGet()
                    }
                ),
                timeoutMs = 50L
            )
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertTrue(result.timedOut)
            assertEquals("the stuck first request must be cancelled before join", 1, firstNetworkCancels.get())
            assertEquals("later pending requests must be cancelled before draining a stuck first task", 1, secondNetworkCancels.get())
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
            assertTrue("physical drain must finish promptly after all requests are released; elapsedMs=$elapsedMs", elapsedMs < 600L)
        } finally {
            releaseFirst.set(true)
            taskScope.cancel()
        }
    }

    @Test
    fun cancellingNonProgressiveSearchCancelsBlockingCompletedValidationRequest() = runBlocking {
        val query = "取消验证"
        val author = "测试作者"
        val baseUrl = "https://cancel-completed-search.example"
        val source = changduSource("普通搜索取消源", baseUrl)
        val responses = customCatalogFixture(
            baseUrl = baseUrl,
            title = query,
            author = author,
            chapterTitles = (1..60).map { index -> "第${index}章 正文$index" }
        )
        val validationCalls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = mapOf("$baseUrl/books/1/" to 8_000L)
        )
        val validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(MapFetcher(responses = responses))
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source }
        )

        val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val search = searchScope.async {
            provider.searchBooks(query)
        }
        try {
            assertTrue(
                "fixture validation request must start before cancellation",
                validationCalls.executeStarted.await(2L, TimeUnit.SECONDS)
            )
            val startedAt = System.currentTimeMillis()
            search.cancel()
            val joined = withTimeoutOrNull(1_500L) {
                search.join()
                true
            } ?: false
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertTrue("external cancellation must cancel the blocking validation call", validationCalls.cancelCount.get() > 0)
            assertTrue("cancelled search must not wait for the 8s fixture call; elapsedMs=$elapsedMs", joined && elapsedMs < 1_500L)
        } finally {
            validationCalls.cancelAll()
            searchScope.cancel()
        }
    }

    @Test
    fun cancelledSearchValidationDoesNotCacheLateDetailOnlyResult() = runBlocking {
        val query = "取消后重试目录"
        val author = "测试作者"
        val fastA = "https://cancelled-validation-cache-fast-a.example"
        val fastB = "https://cancelled-validation-cache-fast-b.example"
        val slow = "https://cancelled-validation-cache-slow.example"
        val slowCatalogUrl = "$slow/book/1/"
        val sources = listOf(
            changduSource("取消缓存快源A", fastA),
            changduSource("取消缓存快源B", fastB),
            changduSource("取消缓存慢源", slow)
        )
        val chapterTitles = (1..60).map { index -> "第${index}章 正文$index" }
        val responses = customCatalogFixture(fastA, query, author, chapterTitles) +
            customCatalogFixture(fastB, query, author, chapterTitles) +
            customCatalogFixture(slow, query, author, chapterTitles)
        val validationCalls = CancelableFixtureCallFactory(
            responses = responses,
            exactDelays = mapOf(slowCatalogUrl to 2_000L),
            cancelReturnDelayMs = 250L
        )
        val validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(MapFetcher(responses = responses))
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            searchProgressGroupsValidationTimeoutMs = 500L,
            searchVisibleCompletedRankTimeoutMs = 500L
        )

        try {
            val firstBooks = provider.searchBooksProgressively(query) { }
            val catalogRequestsBeforeRetry = validationCalls.executedUrlsSnapshot().count { url -> url == slowCatalogUrl }

            assertTrue(
                "the two fast sources must keep a readable result visible when completed validation reaches its deadline",
                firstBooks.any { book -> book.title == query && book.readingReady }
            )
            assertTrue("the completed deadline must cancel the slow catalog request", validationCalls.cancelCount.get() > 0)
            assertTrue("the slow source must have been attempted before retry", catalogRequestsBeforeRetry > 0)

            provider.searchBooks(query)
            val catalogRequestsAfterRetry = validationCalls.executedUrlsSnapshot().count { url -> url == slowCatalogUrl }

            assertEquals(
                "a cancelled partial validation must not suppress the next catalog request",
                catalogRequestsBeforeRetry + 1,
                catalogRequestsAfterRetry
            )
        } finally {
            validationCalls.cancelAll()
        }
    }

    @Test
    fun completedDetailOnlyValidationRemainsCacheableWhileRequestIsActive() = runBlocking {
        val query = "正常详情缓存测试"
        val author = "测试作者"
        val baseUrl = "https://active-detail-only-cache.example"
        val detailUrl = "$baseUrl/books/1/"
        val source = changduSource("正常详情缓存源", baseUrl)
        val responses = detailOnlyFixture(baseUrl, query, author, "第60章 正文")
        val validationCalls = CancelableFixtureCallFactory(responses = responses)
        val validationFetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            callFactory = validationCalls::newCall
        )
        val validationEngine = LegadoSourceEngine(validationFetcher)
        val searchEngine = LegadoSourceEngine(MapFetcher(responses = responses))
        val provider = SourceEngineReaderContentProvider(
            engineFetcher = validationFetcher,
            engine = validationEngine,
            searchEngine = searchEngine,
            detailProbeEngine = validationEngine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source }
        )

        try {
            provider.searchBooks(query)
            provider.searchBooks(query)

            val detailRequests = validationCalls.executedUrlsSnapshot().count { url -> url == detailUrl }
            assertEquals("an active detail-only validation should retain the existing cache behavior", 1, detailRequests)
        } finally {
            validationCalls.cancelAll()
        }
    }

    @Test
    fun progressiveSearchKeepsEarlierVisibleBookWhenContainedTitleGroupPublishesLater() = runBlocking {
        val targetTitle = "灵源仙路"
        val target = targetTitle to "春雾煮茶"
        val containedTitleBook = "源仙路" to "萧不鸣"
        val targetA = "https://progress-lingyuan-target-a.example"
        val targetB = "https://progress-lingyuan-target-b.example"
        val containedA = "https://progress-lingyuan-contained-a.example"
        val containedB = "https://progress-lingyuan-contained-b.example"
        val sources = listOf(
            changduSource("灵源目标源A", targetA),
            changduSource("灵源目标源B", targetB),
            changduSource("源仙路源A", containedA),
            changduSource("源仙路源B", containedB)
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(targetA, target.first, target.second, "第151章 正文", 151) +
                    trustedBookFixture(targetB, target.first, target.second, "第151章 正文", 151) +
                    trustedBookFixture(containedA, containedTitleBook.first, containedTitleBook.second, "第120章 正文", 120) +
                    trustedBookFixture(containedB, containedTitleBook.first, containedTitleBook.second, "第120章 正文", 120),
                delays = mapOf(
                    "$targetA/books/1/" to 350L,
                    "$targetB/books/1/" to 350L,
                    "$targetA/book/1/" to 350L,
                    "$targetB/book/1/" to 350L,
                    "$containedA/modules/article/search.php" to 2_500L,
                    "$containedB/modules/article/search.php" to 2_500L
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

        val books = provider.searchBooksProgressively("灵源仙路") { update ->
            if (update.isNotEmpty()) {
                updates.add(update.map { it.title to it.author })
            }
        }

        val targetUpdateIndex = updates.indexOfFirst { update -> target in update }
        val finalBooks = books.map { it.title to it.author }
        assertTrue("expected target book to publish first, got $updates", targetUpdateIndex >= 0)
        assertTrue(
            "contained-title update must not replace earlier visible target, got $updates",
            updates.none { update -> containedTitleBook in update && target !in update }
        )
        assertTrue(
            "final result must keep both independent books, got $finalBooks updates=$updates",
            target in finalBooks && containedTitleBook in finalBooks
        )
    }

    @Test
    fun progressiveExactTitleKeepsIdentityConsensusWhenOneReadingSourceIsShort() = runBlocking {
        val exactShort = "https://progress-lingyuan-exact-short.example"
        val exactLong = "https://progress-lingyuan-exact-long.example"
        val containedA = "https://progress-lingyuan-source-a.example"
        val containedB = "https://progress-lingyuan-source-b.example"
        val exactBook = "灵源仙路" to "春雾煮茶"
        val containedBook = "源仙路" to "萧不鸣"
        val sources = listOf(
            changduSource("灵源仙路短目录源", exactShort),
            changduSource("灵源仙路长目录源", exactLong),
            changduSource("源仙路源A", containedA),
            changduSource("源仙路源B", containedB)
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(exactShort, exactBook.first, exactBook.second, "第1515章 正文", 20) +
                    trustedBookFixture(exactLong, exactBook.first, exactBook.second, "第1515章 正文", 1_515) +
                    trustedBookFixture(containedA, containedBook.first, containedBook.second, "第120章 正文", 120) +
                    trustedBookFixture(containedB, containedBook.first, containedBook.second, "第120章 正文", 120)
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

        val books = provider.searchBooksProgressively("灵源仙路") { update ->
            if (update.isNotEmpty()) {
                updates.add(update.map { it.title to it.author })
            }
        }

        val finalBooks = books.map { it.title to it.author }
        assertTrue("expected progressive updates, got none", updates.isNotEmpty())
        assertTrue(
            "exact title must stay visible when only one long reading source remains; updates=$updates",
            updates.first().contains(exactBook)
        )
        assertTrue(
            "final result must keep exact and contained books separate, got $finalBooks",
            exactBook in finalBooks && containedBook in finalBooks
        )
        assertEquals(
            exactLong,
            SourceEngineBookRoute.decodeBookId(requireNotNull(books.first { it.title == exactBook.first }.routeId)).sourceUrl
        )
    }

    @Test
    fun searchMergesContainingTitleAliasesOnlyWhenCatalogPrefixMatches() = runBlocking {
        val exactA = "https://catalog-prefix-exact-a.example"
        val exactB = "https://catalog-prefix-exact-b.example"
        val aliasA = "https://catalog-prefix-alias-a.example"
        val aliasB = "https://catalog-prefix-alias-b.example"
        val sources = listOf(
            changduSource("凡人原书源A", exactA),
            changduSource("凡人原书源B", exactB),
            changduSource("凡人别名源A", aliasA),
            changduSource("凡人别名源B", aliasB)
        )
        val sharedTitles = listOf(
            "第1章 青竹坞",
            "第2章 七玄门",
            "第3章 墨府",
            "第4章 小绿瓶",
            "第5章 炼气",
            "第6章 黄枫谷",
            "第7章 筑基",
            "第8章 乱星海"
        ) + (9..120).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(exactA, "凡人修仙传", "忘语", sharedTitles) +
                    customCatalogFixture(exactB, "凡人修仙传", "忘语", sharedTitles) +
                    customCatalogFixture(aliasA, "凡人修仙传精校版", "忘语", sharedTitles) +
                    customCatalogFixture(aliasB, "凡人修仙传精校版", "忘语", sharedTitles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("凡人修仙传")

        assertEquals(listOf("凡人修仙传" to "忘语"), books.map { it.title to it.author })
    }

    @Test
    fun searchMergesContainingTitleAliasWithWrongAuthorWhenCatalogPrefixMatches() = runBlocking {
        val exactA = "https://catalog-prefix-lingyuan-exact-a.example"
        val exactB = "https://catalog-prefix-lingyuan-exact-b.example"
        val aliasA = "https://catalog-prefix-lingyuan-alias-a.example"
        val aliasB = "https://catalog-prefix-lingyuan-alias-b.example"
        val sources = listOf(
            changduSource("灵源原书源A", exactA),
            changduSource("灵源原书源B", exactB),
            changduSource("灵源长名源A", aliasA),
            changduSource("灵源长名源B", aliasB)
        )
        val sharedTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        ) + (9..120).map { index -> "第${index}章 灵源旧事" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(exactA, "灵源仙路", "春雾煮茶", sharedTitles) +
                    customCatalogFixture(exactB, "灵源仙路", "春雾煮茶", sharedTitles) +
                    customCatalogFixture(aliasA, "灵源仙路：我养的灵兽皆可证道", "搬运源", sharedTitles) +
                    customCatalogFixture(aliasB, "灵源仙路：我养的灵兽皆可证道", "搬运源", sharedTitles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("灵源仙路")

        assertEquals(listOf("灵源仙路" to "春雾煮茶"), books.map { it.title to it.author })
    }

    @Test
    fun searchMergesSameTitleAuthorContainingVariantsAndPrefersLongerAuthor() = runBlocking {
        val shortAuthor = "https://qiongming-short-author.example"
        val longAuthor = "https://qiongming-long-author.example"
        val sources = listOf(
            changduSource("琼明短作者源", shortAuthor),
            changduSource("琼明长作者源", longAuthor)
        )
        val sharedTitles = listOf(
            "第1章 神女初临",
            "第2章 倒悬山",
            "第3章 剑气长存",
            "第4章 夜入琼明",
            "第5章 山门旧事",
            "第6章 春潮带雨",
            "第7章 明月照剑",
            "第8章 归途"
        ) + (9..120).map { index -> "第${index}章 琼明旧事" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(shortAuthor, "琼明神女录", "剑气长存", sharedTitles) +
                    customCatalogFixture(longAuthor, "琼明神女录", "倒悬山剑气长存", sharedTitles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("琼明神女录")

        assertEquals(listOf("琼明神女录" to "倒悬山剑气长存"), books.map { it.title to it.author })
    }

    @Test
    fun progressiveSearchDoesNotPublishNearTitleAliasFromDisplayPreviewWithoutCatalog() = runBlocking {
        val exact = "https://preview-lingyuan-exact.example"
        val aliasA = "https://preview-lingyuan-alias-a.example"
        val aliasB = "https://preview-lingyuan-alias-b.example"
        val sources = listOf(
            changduSource("灵源预览原书源", exact),
            changduSource("灵源预览长名源A", aliasA),
            changduSource("灵源预览长名源B", aliasB)
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                detailOnlyFixture(exact, "灵源仙路", "春雾煮茶", "第120章 正文") +
                    detailOnlyFixture(aliasA, "灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶", "第120章 正文") +
                    detailOnlyFixture(aliasB, "灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶", "第120章 正文")
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

        val books = provider.searchBooksProgressively("灵源仙路") { update ->
            updates.add(update.map { it.title to it.author })
        }

        assertTrue(
            "near-title alias without catalog must not be published from preview; updates=$updates final=${books.map { it.title to it.author }}",
            updates.flatten().none { (title, _) -> title == "灵源仙途：我养的灵兽太懂感恩了" } &&
                books.none { it.title == "灵源仙途：我养的灵兽太懂感恩了" }
        )
    }

    @Test
    fun progressiveExactDetailPreviewIsVisibleButNotReadingReady() = runBlocking {
        val previewA = "https://preview-doupo-a.example"
        val previewB = "https://preview-doupo-b.example"
        val sources = listOf(
            changduSource("斗破预览源A", previewA),
            changduSource("斗破预览源B", previewB)
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                detailOnlyFixture(previewA, "斗破苍穹", "天蚕土豆", "第一千六百二十四章 正文") +
                    detailOnlyFixture(previewB, "斗破苍穹", "天蚕土豆", "第一千六百二十四章 正文")
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<BookSearchResult>>()

        provider.searchBooksProgressively("斗破苍穹") { update ->
            if (update.isNotEmpty()) updates.add(update)
        }

        assertTrue("expected a fast detail preview", updates.isNotEmpty())
        assertTrue(
            "detail-only preview must never claim it can be opened for reading",
            updates.flatten().all { result -> !result.readingReady }
        )
    }

    @Test
    fun progressiveSearchSingleExactCatalogDoesNotVetoTwoSourceAliasConsensus() = runBlocking {
        val exact = "https://catalog-scope-lingyuan-exact.example"
        val aliasA = "https://catalog-scope-lingyuan-alias-a.example"
        val aliasB = "https://catalog-scope-lingyuan-alias-b.example"
        val sources = listOf(
            changduSource("灵源精确目录源", exact),
            changduSource("灵源别名目录源A", aliasA),
            changduSource("灵源别名目录源B", aliasB)
        )
        val exactTitles = listOf(
            "第1章 灵田初醒",
            "第2章 山门旧路",
            "第3章 青木符",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵泉异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        ) + (9..120).map { index -> "第${index}章 灵源旧事" }
        val aliasTitles = listOf(
            "第1章 开局灵兽感恩",
            "第2章 白狐报恩",
            "第3章 灵龟赠宝",
            "第4章 青蛇护主",
            "第5章 丹炉异响",
            "第6章 山海秘闻",
            "第7章 万兽来朝",
            "第8章 仙途初启"
        ) + (9..120).map { index -> "第${index}章 灵兽感恩旧事" }
        val responses = customCatalogFixture(exact, "灵源仙路", "春雾煮茶", exactTitles) +
            customCatalogFixture(aliasA, "灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶", aliasTitles) +
            customCatalogFixture(aliasB, "灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶", aliasTitles)
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = responses,
                exactDelays = mapOf("$exact/books/1/" to 750L)
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
        val updates = mutableListOf<List<BookSearchResult>>()

        val books = provider.searchBooksProgressively("灵源仙路") { update ->
            if (update.isNotEmpty()) updates.add(update)
        }
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"

        assertTrue("expected the two-source alias consensus to become visible", updates.isNotEmpty())
        assertTrue(
            "a single exact catalog must not veto or rank above a readable two-source alias; " +
                "updates=${updates.map { snapshot -> snapshot.map { it.title to it.readingReady } }} " +
                "final=${books.map { it.title to it.readingReady }}",
            updates.all { snapshot ->
                snapshot.first().title == aliasTitle && snapshot.first().readingReady
            } && books.firstOrNull()?.let { result ->
                result.title == aliasTitle && result.readingReady
            } == true
        )
    }

    @Test
    fun searchMergesNearTitleAliasOnlyAfterCatalogPrefixMatches() = runBlocking {
        val exact = "https://catalog-prefix-lingyuan-near-exact.example"
        val alias = "https://catalog-prefix-lingyuan-near-alias.example"
        val sources = listOf(
            changduSource("灵源近名原书源", exact),
            changduSource("灵源近名长名源", alias)
        )
        val sharedTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        ) + (9..120).map { index -> "第${index}章 灵源旧事" }
        val aliasSearchRows = (1..12).map { index ->
            SearchRow(
                bookUrl = "$alias/books/noise-$index/",
                title = "灵源杂记$index",
                lastChapter = "第${index}章 杂记",
                author = "无关作者$index"
            )
        } + SearchRow(
            bookUrl = "$alias/books/1/",
            title = "灵源仙途：我养的灵兽太懂感恩了",
            lastChapter = sharedTitles.last(),
            author = "春雾煮茶"
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(exact, "灵源仙路", "春雾煮茶", sharedTitles) +
                    customCatalogFixture(alias, "灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶", sharedTitles) +
                    mapOf("$alias/modules/article/search.php" to searchHtmlRows(aliasSearchRows))
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("灵源仙路")

        assertEquals(listOf("灵源仙路" to "春雾煮茶"), books.map { it.title to it.author })
    }

    @Test
    fun detailPreservesSearchMergedDisplayIdentityWhenReadingSourceIsAlias() = runBlocking {
        val exact = "https://detail-identity-lingyuan-exact.example"
        val alias = "https://detail-identity-lingyuan-alias.example"
        val sources = listOf(
            changduSource("灵源详情原书源", exact),
            changduSource("灵源详情长名源", alias)
        )
        val sharedPrefix = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        )
        val exactTitles = sharedPrefix + (9..120).map { index -> "第${index}章 灵源旧事" }
        val aliasTitles = sharedPrefix + (9..180).map { index -> "第${index}章 灵源旧事" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(exact, "灵源仙路", "春雾煮茶", exactTitles) +
                    customCatalogFixture(
                        alias,
                        "灵源仙途：我养的灵兽太懂感恩了",
                        "春雾煮茶",
                        aliasTitles,
                        customChapterHtml = { index, _ ->
                            if (index == 1) blankChapterHtml() else readableChapterHtml("灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶", index)
                        }
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

        val books = provider.searchBooks("灵源仙路")
        val searchRoute = SourceEngineBookRoute.decodeBookId(requireNotNull(books.single().routeId))
        val detail = provider.getBookInfo(books.single().routeId)
        val detailRoute = SourceEngineBookRoute.decodeBookId(requireNotNull(detail.routeId))
        val chapters = provider.getBookFolder(detail.routeId, detail.collBookBean)
        val firstChapterRoute = SourceEngineBookRoute.decodeChapterId(requireNotNull(chapters.first().link))
        val firstChapter = TxtChapter().apply {
            bookId = detail.routeId
            link = chapters.first().link
            title = chapters.first().title
            start = 0L
        }
        val content = provider.getBookContent(detail.routeId, detail.collBookBean, firstChapter, 0)

        assertEquals("灵源仙路", books.single().title)
        assertEquals(alias, searchRoute.sourceUrl)
        assertEquals("灵源仙路", detail.title)
        assertEquals("灵源仙路", detailRoute.name)
        assertEquals(alias, detailRoute.sourceUrl)
        assertEquals("灵源仙途：我养的灵兽太懂感恩了", firstChapterRoute.bookName)
        assertEquals(alias, firstChapterRoute.sourceUrl)
        assertTrue(content.contains("灵源仙路 的故事继续推进"))
        assertEquals(SourceEngineBookRoute.shelfBookId(SourceEngineBookRoute.toSourceBook(sources[1], detailRoute)), detail.shelfBookId)
        assertEquals(180, detail.chaptersCount)
        assertEquals(180, chapters.size)
    }

    @Test
    fun readingCatalogAcceptsSearchCertifiedAliasDetailIdentity() = runBlocking {
        val title = "灵源仙路"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val author = "春雾煮茶"
        val current = changduSource("当前别名源", "https://reading-certified-alias-current.example")
        val titles = (1..120).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(current.sourceUrl, aliasTitle, author, titles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(current) },
            sourceFinder = { sourceUrl -> current.takeIf { it.sourceUrl == sourceUrl } ?: error("unknown source $sourceUrl") },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(current, title, author, titles.last())
        seedIdentityProfile(
            provider,
            currentBook,
            sourceBook(current, aliasTitle, author, titles.last())
        )
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_reading-certified-alias-detail")
            this.title = title
            this.author = author
        }
        java.io.File(testBookCacheFolderPath(collBook.get_id())).deleteRecursively()

        val chapters = provider.getBookFolder(SourceEngineBookRoute.bookId(currentBook), collBook)

        assertEquals(120, chapters.size)
        assertEquals(titles.last(), chapters.last().title)
    }

    @Test
    fun readingCatalogAcceptsPersistedCertifiedAliasFallbackResult() = runBlocking {
        val title = "灵源仙路"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val author = "春雾煮茶"
        val current = changduSource("当前失配源", "https://reading-persisted-alias-current.example")
        val persistedAlias = changduSource("持久别名源", "https://reading-persisted-alias-known.example")
        val wrongAuthorAlias = changduSource("错作者别名源", "https://reading-persisted-alias-wrong-author.example")
        val freshAlias = changduSource("新鲜别名源", "https://reading-persisted-alias-fresh.example")
        val currentTitles = (1..40).map { index -> "第${index}章 旁支" }
        val oldAliasTitles = (1..120).map { index -> "第${index}章 正文" }
        val wrongAuthorTitles = (1..180).map { index -> "第${index}章 错书" }
        val freshAliasTitles = (1..132).map { index -> "第${index}章 正文" }
        val allSources = listOf(current, persistedAlias, wrongAuthorAlias, freshAlias)
        val fetches = Collections.synchronizedList(ArrayList<String>())
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(current.sourceUrl, "陌生修真录", "旁人", currentTitles) +
                    customCatalogFixture(persistedAlias.sourceUrl, aliasTitle, author, oldAliasTitles) +
                    customCatalogFixture(wrongAuthorAlias.sourceUrl, aliasTitle, "旁人", wrongAuthorTitles) +
                    customCatalogFixture(freshAlias.sourceUrl, aliasTitle, author, freshAliasTitles),
                onFetch = { url -> fetches.add(url) }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(current, wrongAuthorAlias, freshAlias) },
            sourceFinder = { sourceUrl -> allSources.first { it.sourceUrl == sourceUrl } },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val currentBook = sourceBook(current, title, author, oldAliasTitles.last())
        val persistedAliasBook = sourceBook(persistedAlias, aliasTitle, author, oldAliasTitles.last())
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_reading-persisted-certified-alias")
            this.title = title
            this.author = author
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        val tierFile = java.io.File(cacheFolder, ".source_engine_content_tier")
        tierFile.parentFile?.mkdirs()
        tierFile.writeText(SourceEngineBookRoute.bookId(persistedAliasBook))

        val chapters = provider.getBookFolder(SourceEngineBookRoute.bookId(currentBook), collBook)

        assertEquals(132, chapters.size)
        assertEquals(freshAliasTitles.last(), chapters.last().title)
        val persistedCatalogIndex = fetches.indexOf("${persistedAlias.sourceUrl}/book/1/")
        val freshSearchIndex = fetches.indexOf("${freshAlias.sourceUrl}/modules/article/search.php")
        assertTrue("persisted catalog must be materialized before live search: $fetches", persistedCatalogIndex >= 0)
        assertTrue("fresh search must run when persisted coverage is not preferred: $fetches", freshSearchIndex >= 0)
        assertTrue(
            "persisted tier must be probed before live search: $fetches",
            persistedCatalogIndex < freshSearchIndex
        )
    }

    @Test
    fun identityProfilePersonalTierUnionsCanonicalAndAliasSourceKeys() = runBlocking {
        val exact = "https://personal-tier-lingyuan-exact.example"
        val alias = "https://personal-tier-lingyuan-alias.example"
        val aliasTitle = "灵源仙途：我养的灵兽太懂感恩了"
        val sources = listOf(
            changduSource("灵源专属原书源", exact),
            changduSource("灵源专属长名源", alias)
        )
        val sharedTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        ) + (9..120).map { index -> "第${index}章 灵源旧事" }
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        val exactBook = sourceBook("灵源专属原书源", exact, "灵源仙路", "春雾煮茶")
        val aliasBook = sourceBook("灵源专属长名源", alias, aliasTitle, "春雾煮茶")
        listOf(exactBook, aliasBook).forEach { book ->
            sourceQualityRouter.recordCatalogResolved(book, chapterCount = 120, rawChapterCount = 120)
            sourceQualityRouter.recordContentResolved(
                SourceChapter(
                    source = book.source,
                    book = book,
                    index = 119,
                    name = "第120章 灵源旧事",
                    chapterUrl = "${book.bookUrl}120.html"
                ),
                readableCleanContent()
            )
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(exact, "灵源仙路", "春雾煮茶", sharedTitles) +
                    customCatalogFixture(alias, aliasTitle, "春雾煮茶", sharedTitles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = sourceQualityRouter
        )

        provider.searchBooks("灵源仙路")

        val personalSourceKeys = personalSourceKeysFor(provider, aliasBook)
        assertEquals(setOf(exact, alias), personalSourceKeys)
    }

    @Test
    fun catalogPrefixIdentityAllowsContainingTitleWithWrongAuthorWhenCatalogMatches() {
        val provider = SourceEngineReaderContentProvider()
        val exactBook = sourceBook("灵源原书源", "https://catalog-prefix-identity-exact.example", "灵源仙路", "春雾煮茶")
        val aliasBook = sourceBook(
            "灵源长名源",
            "https://catalog-prefix-identity-alias.example",
            "灵源仙路：我养的灵兽皆可证道",
            "搬运源"
        )
        val titles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        ) + (9..24).map { index -> "第${index}章 灵源旧事" }
        val method = provider.javaClass.getDeclaredMethod(
            "sameBookByCatalogPrefix",
            SourceBook::class.java,
            CanonicalChapterList::class.java,
            SourceBook::class.java,
            CanonicalChapterList::class.java
        )
        method.isAccessible = true

        val sameBook = method.invoke(
            provider,
            exactBook,
            canonicalCatalogFor(exactBook, titles),
            aliasBook,
            canonicalCatalogFor(aliasBook, titles)
        ) as Boolean

        assertTrue("title-contained alias with same catalog must not be blocked by wrong author", sameBook)
    }

    @Test
    fun catalogPrefixIdentityAllowsContainingTitleWithWrongAuthorWhenCatalogIsSimilar() {
        val provider = SourceEngineReaderContentProvider()
        val exactBook = sourceBook("灵源原书源", "https://catalog-prefix-similar-exact.example", "灵源仙路", "春雾煮茶")
        val aliasBook = sourceBook(
            "灵源长名源",
            "https://catalog-prefix-similar-alias.example",
            "灵源仙路：我养的灵兽皆可证道",
            "搬运源"
        )
        val exactTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        )
        val aliasTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 临时公告",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 作品相关",
            "第8章 族中议事"
        )
        val method = provider.javaClass.getDeclaredMethod(
            "sameBookByCatalogPrefix",
            SourceBook::class.java,
            CanonicalChapterList::class.java,
            SourceBook::class.java,
            CanonicalChapterList::class.java
        )
        method.isAccessible = true

        val sameBook = method.invoke(
            provider,
            exactBook,
            canonicalCatalogFor(exactBook, exactTitles),
            aliasBook,
            canonicalCatalogFor(aliasBook, aliasTitles)
        ) as Boolean

        assertTrue("title-contained alias should merge when catalog prefixes are similar", sameBook)
    }

    @Test
    fun catalogPrefixIdentityRejectsNearTitleAliasWhenCatalogDiffers() {
        val provider = SourceEngineReaderContentProvider()
        val exactBook = sourceBook("灵源原书源", "https://catalog-prefix-near-distinct-exact.example", "灵源仙路", "春雾煮茶")
        val aliasBook = sourceBook(
            "灵源近名源",
            "https://catalog-prefix-near-distinct-alias.example",
            "灵源仙途：我养的灵兽太懂感恩了",
            "春雾煮茶"
        )
        val exactTitles = listOf(
            "第1章 灵兽初醒",
            "第2章 山中灵泉",
            "第3章 青木法诀",
            "第4章 夜访坊市",
            "第5章 玄甲龟",
            "第6章 灵田异变",
            "第7章 丹火初成",
            "第8章 族中议事"
        )
        val aliasTitles = listOf(
            "第1章 飞升台",
            "第2章 仙栈",
            "第3章 真仙",
            "第4章 灵寰界",
            "第5章 北寒仙域",
            "第6章 蟹道人",
            "第7章 石穿空",
            "第8章 光阴天璇"
        )
        val method = provider.javaClass.getDeclaredMethod(
            "sameBookByCatalogPrefix",
            SourceBook::class.java,
            CanonicalChapterList::class.java,
            SourceBook::class.java,
            CanonicalChapterList::class.java
        )
        method.isAccessible = true

        val sameBook = method.invoke(
            provider,
            exactBook,
            canonicalCatalogFor(exactBook, exactTitles),
            aliasBook,
            canonicalCatalogFor(aliasBook, aliasTitles)
        ) as Boolean

        assertFalse("near title alias with same author must still require similar catalog", sameBook)
    }

    @Test
    fun searchDoesNotMergeContainingTitlesWhenCatalogPrefixDiffers() = runBlocking {
        val exactA = "https://catalog-prefix-distinct-exact-a.example"
        val exactB = "https://catalog-prefix-distinct-exact-b.example"
        val derivativeA = "https://catalog-prefix-distinct-derivative-a.example"
        val derivativeB = "https://catalog-prefix-distinct-derivative-b.example"
        val sources = listOf(
            changduSource("凡人原书源A", exactA),
            changduSource("凡人原书源B", exactB),
            changduSource("凡人仙界源A", derivativeA),
            changduSource("凡人仙界源B", derivativeB)
        )
        val originalTitles = listOf(
            "第1章 青竹坞",
            "第2章 七玄门",
            "第3章 墨府",
            "第4章 小绿瓶",
            "第5章 炼气",
            "第6章 黄枫谷"
        ) + (7..120).map { index -> "第${index}章 正文" }
        val derivativeTitles = listOf(
            "第1章 飞升台",
            "第2章 仙栈",
            "第3章 真仙",
            "第4章 灵寰界",
            "第5章 北寒仙域",
            "第6章 蟹道人"
        ) + (7..120).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(exactA, "凡人修仙传", "忘语", originalTitles) +
                    customCatalogFixture(exactB, "凡人修仙传", "忘语", originalTitles) +
                    customCatalogFixture(derivativeA, "凡人修仙传仙界篇", "忘语", derivativeTitles) +
                    customCatalogFixture(derivativeB, "凡人修仙传仙界篇", "忘语", derivativeTitles)
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooks("凡人修仙传")

        assertEquals(
            listOf("凡人修仙传" to "忘语", "凡人修仙传仙界篇" to "忘语"),
            books.map { it.title to it.author }
        )
    }

    @Test
    fun progressiveSearchKeepsExactMainBookAndDerivativeWhenLargeExactGroupNeedsSpreadValidation() = runBlocking {
        val exactBadSources = (1..16).map { index ->
            changduSource("凡人前排坏源$index", "https://fanren-front-bad-$index.example")
        }
        val exactGoodSources = (1..16).map { index ->
            changduSource("凡人后段好源$index", "https://fanren-late-good-$index.example")
        }
        val derivativeSources = (1..2).map { index ->
            changduSource("凡人仙界篇源$index", "https://fanren-xianjie-$index.example")
        }
        val sources = exactBadSources + exactGoodSources + derivativeSources
        val engine = LegadoSourceEngine(
            MapFetcher(
                exactBadSources.flatMap { source ->
                    unreadableBookFixture(
                        baseUrl = source.sourceUrl,
                        title = "凡人修仙传",
                        author = "忘语",
                        lastChapter = "第两千四百八十章 正文",
                        chapterCount = 2_480
                    ).entries
                }.associate { it.toPair() } +
                    exactGoodSources.flatMap { source ->
                        trustedBookFixture(
                            baseUrl = source.sourceUrl,
                            title = "凡人修仙传",
                            author = "忘语",
                            lastChapter = "第两千四百八十章 正文",
                            chapterCount = 2_480
                        ).entries
                    }.associate { it.toPair() } +
                    derivativeSources.flatMap { source ->
                        trustedBookFixture(
                            baseUrl = source.sourceUrl,
                            title = "凡人修仙传仙界篇",
                            author = "忘语",
                            lastChapter = "第一千三百九十四章 正文",
                            chapterCount = 1_394
                        ).entries
                    }.associate { it.toPair() }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooksProgressively("凡人修仙传") {}
        val visible = books.map { it.title to it.author }

        assertEquals("凡人修仙传", books.first().title)
        assertTrue("expected main book, got $visible", visible.contains("凡人修仙传" to "忘语"))
        assertTrue("expected derivative book, got $visible", visible.contains("凡人修仙传仙界篇" to "忘语"))
    }

    @Test
    fun progressiveMergeUsesLatestRankOrderAndKeepsPreviousExtras() {
        val engine = LegadoSourceEngine(MapFetcher(emptyMap()))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { emptyList() },
            sourceFinder = { error("unused") }
        )
        val method = SourceEngineReaderContentProvider::class.java.getDeclaredMethod(
            "mergeProgressiveSearchOutput",
            List::class.java,
            List::class.java
        )
        method.isAccessible = true
        val shortCatalog = bookSearchResult("叩问仙道", "练体一亿重", "short")
        val longCatalog = bookSearchResult("叩问仙道", "雨打青石", "long")
        val previousExtra = bookSearchResult("源仙路", "萧不鸣", "extra")

        @Suppress("UNCHECKED_CAST")
        val merged = method.invoke(
            provider,
            listOf(shortCatalog, previousExtra),
            listOf(longCatalog, shortCatalog)
        ) as List<BookSearchResult>

        assertEquals(
            listOf("叩问仙道" to "雨打青石", "叩问仙道" to "练体一亿重", "源仙路" to "萧不鸣"),
            merged.map { it.title to it.author }
        )
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
    fun progressiveSearchContinuesPastSingleRelatedResultUntilLaterTierExactResult() = runBlocking {
        val relatedA = changduSource("凡人相关T1源1", "https://fanren-related-t1-a.example")
        val relatedB = changduSource("凡人相关T1源2", "https://fanren-related-t1-b.example")
        val exactA = changduSource("凡人精确T2源1", "https://fanren-exact-t2-a.example")
        val exactB = changduSource("凡人精确T2源2", "https://fanren-exact-t2-b.example")
        val sources = listOf(relatedA, relatedB, exactA, exactB)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    baseUrl = relatedA.sourceUrl,
                    title = "小小凡人修仙传",
                    author = "至尊小宝",
                    lastChapter = "第六百三十章 正文",
                    chapterCount = 630
                ) +
                    trustedBookFixture(
                        baseUrl = relatedB.sourceUrl,
                        title = "小小凡人修仙传",
                        author = "至尊小宝",
                        lastChapter = "第六百三十章 正文",
                        chapterCount = 630
                    ) +
                    trustedBookFixture(
                        baseUrl = exactA.sourceUrl,
                        title = "凡人修仙传",
                        author = "忘语",
                        lastChapter = "第两千四百八十章 正文",
                        chapterCount = 2_480
                    ) +
                    trustedBookFixture(
                        baseUrl = exactB.sourceUrl,
                        title = "凡人修仙传",
                        author = "忘语",
                        lastChapter = "第两千四百八十章 正文",
                        chapterCount = 2_480
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    """
                    source	${relatedA.sourceUrl}	${relatedA.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${relatedB.sourceUrl}	${relatedB.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${exactA.sourceUrl}	${exactA.sourceName}	2	classic	6000	0	0	0	0	0	test
                    source	${exactB.sourceUrl}	${exactB.sourceName}	2	classic	6000	0	0	0	0	0	test
                    """.trimIndent()
                )
            )
        )
        val updates = mutableListOf<List<Pair<String?, String?>>>()

        val books = provider.searchBooksProgressively("凡人修仙传") { update ->
            if (update.isNotEmpty()) updates.add(update.map { it.title to it.author })
        }

        assertTrue("expected progressive updates, got none", updates.isNotEmpty())
        assertEquals("凡人修仙传", books.first().title)
        assertEquals("忘语", books.first().author)
        assertTrue(
            "expected related result to stay visible after exact result arrives, got ${books.map { it.title to it.author }}",
            books.any { it.title == "小小凡人修仙传" && it.author == "至尊小宝" }
        )
    }

    @Test
    fun progressiveSearchDoesNotStopWhenProgressHasOnlyContainedTitleResults() = runBlocking {
        val relatedA = changduSource("凡人相关多结果T1源1", "https://fanren-many-related-t1-a.example")
        val relatedB = changduSource("凡人相关多结果T1源2", "https://fanren-many-related-t1-b.example")
        val derivativeA = changduSource("凡人衍生T1源1", "https://fanren-derivative-t1-a.example")
        val derivativeB = changduSource("凡人衍生T1源2", "https://fanren-derivative-t1-b.example")
        val exactA = changduSource("凡人精确多结果T2源1", "https://fanren-many-exact-t2-a.example")
        val exactB = changduSource("凡人精确多结果T2源2", "https://fanren-many-exact-t2-b.example")
        val sources = listOf(relatedA, relatedB, derivativeA, derivativeB, exactA, exactB)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    baseUrl = relatedA.sourceUrl,
                    title = "小小凡人修仙传",
                    author = "至尊小宝",
                    lastChapter = "第六百三十章 正文",
                    chapterCount = 630
                ) +
                    trustedBookFixture(
                        baseUrl = relatedB.sourceUrl,
                        title = "小小凡人修仙传",
                        author = "至尊小宝",
                        lastChapter = "第六百三十章 正文",
                        chapterCount = 630
                    ) +
                    trustedBookFixture(
                        baseUrl = derivativeA.sourceUrl,
                        title = "直播凡人修仙传",
                        author = "呼言道人",
                        lastChapter = "第七十一章 正文",
                        chapterCount = 71
                    ) +
                    trustedBookFixture(
                        baseUrl = derivativeB.sourceUrl,
                        title = "直播凡人修仙传",
                        author = "呼言道人",
                        lastChapter = "第七十一章 正文",
                        chapterCount = 71
                    ) +
                    trustedBookFixture(
                        baseUrl = exactA.sourceUrl,
                        title = "凡人修仙传",
                        author = "忘语",
                        lastChapter = "第两千四百八十章 正文",
                        chapterCount = 2_480
                    ) +
                    trustedBookFixture(
                        baseUrl = exactB.sourceUrl,
                        title = "凡人修仙传",
                        author = "忘语",
                        lastChapter = "第两千四百八十章 正文",
                        chapterCount = 2_480
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    """
                    source	${relatedA.sourceUrl}	${relatedA.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${relatedB.sourceUrl}	${relatedB.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${derivativeA.sourceUrl}	${derivativeA.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${derivativeB.sourceUrl}	${derivativeB.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${exactA.sourceUrl}	${exactA.sourceName}	2	classic	6000	0	0	0	0	0	test
                    source	${exactB.sourceUrl}	${exactB.sourceName}	2	classic	6000	0	0	0	0	0	test
                    """.trimIndent()
                )
            )
        )

        val books = provider.searchBooksProgressively("凡人修仙传") {}

        assertEquals("凡人修仙传", books.first().title)
        assertEquals("忘语", books.first().author)
    }

    @Test
    fun searchPublishesReadableCatalogConsensusWhenIntroIsMissing() = runBlocking {
        val sourceA = changduSource("无简介目录源1", "https://no-intro-readable-a.example")
        val sourceB = changduSource("无简介目录源2", "https://no-intro-readable-b.example")
        val sources = listOf(sourceA, sourceB)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookWithoutIntroFixture(
                    baseUrl = sourceA.sourceUrl,
                    title = "凡人修仙传",
                    author = "忘语",
                    lastChapter = "第两千四百八十章 正文",
                    chapterCount = 2_480
                ) +
                    trustedBookWithoutIntroFixture(
                        baseUrl = sourceB.sourceUrl,
                        title = "凡人修仙传",
                        author = "忘语",
                        lastChapter = "第两千四百八十章 正文",
                        chapterCount = 2_480
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

        val books = provider.searchBooksProgressively("凡人修仙传") {}

        assertEquals("凡人修仙传", books.first().title)
        assertEquals("忘语", books.first().author)
    }

    @Test
    fun searchSupplementsTierThreeOnlyAfterFrontTierValidationFails() = runBlocking {
        val frontA = changduSource("前段不可用源1", "https://front-unreadable-a.example")
        val frontB = changduSource("前段不可用源2", "https://front-unreadable-b.example")
        val supplementA = changduSource("后段可读源1", "https://supplement-readable-a.example")
        val supplementB = changduSource("后段可读源2", "https://supplement-readable-b.example")
        val sources = listOf(frontA, frontB, supplementA, supplementB)
        val searchOnlyFrontFixtures = listOf(frontA, frontB).associate { source ->
            "${source.sourceUrl}/modules/article/search.php" to searchHtml(
                bookUrl = "${source.sourceUrl}/books/1/",
                title = "仙都",
                lastChapter = "第二百章 正文",
                author = "陈猿"
            )
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                searchOnlyFrontFixtures +
                    trustedBookFixture(
                        baseUrl = supplementA.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第一百二十章 正文",
                        chapterCount = 120
                    ) +
                    trustedBookFixture(
                        baseUrl = supplementB.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第一百二十章 正文",
                        chapterCount = 120
                    )
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.fromTsv(
                    """
                    source	${frontA.sourceUrl}	${frontA.sourceName}	1	breadth	10000	0	0	0	0	0	test
                    source	${frontB.sourceUrl}	${frontB.sourceName}	2	breadth	6000	0	0	0	0	0	test
                    source	${supplementA.sourceUrl}	${supplementA.sourceName}	3	breadth	1000	0	0	0	0	0	test
                    source	${supplementB.sourceUrl}	${supplementB.sourceName}	3	breadth	1000	0	0	0	0	0	test
                    """.trimIndent()
                )
            )
        )

        val books = provider.searchBooks("仙都")

        assertEquals("仙都", books.first().title)
        assertEquals("陈猿", books.first().author)
    }

    @Test
    fun progressiveShortExactTitleExpandsValidationBeyondSixCandidates() = runBlocking {
        val targetBadSources = (1..5).map { index ->
            changduSource("仙都坏源$index", "https://xiandu-bad-$index.example")
        }
        val targetGoodSources = (1..7).map { index ->
            changduSource("仙都好源$index", "https://xiandu-good-$index.example")
        }
        val containedSources = listOf(
            changduSource("仙都传说源1", "https://xiandu-contained-a.example"),
            changduSource("仙都传说源2", "https://xiandu-contained-b.example")
        )
        val sources = targetBadSources + targetGoodSources + containedSources
        val engine = LegadoSourceEngine(
            MapFetcher(
                targetBadSources.flatMapIndexed { index, source ->
                    unreadableBookFixture(
                        baseUrl = source.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第二千七百八十七节 正文",
                        chapterCount = 2_787,
                        coverUrl = if (index == 0) "file:///cover.jpg" else ""
                    ).entries
                }.associate { it.toPair() } +
                    targetGoodSources.flatMap { source ->
                        trustedBookFixture(
                            baseUrl = source.sourceUrl,
                            title = "仙都",
                            author = "陈猿",
                            lastChapter = "第二千七百八十七节 正文",
                            chapterCount = 2_787
                        ).entries
                    }.associate { it.toPair() } +
                    containedSources.flatMap { source ->
                        trustedBookFixture(
                            baseUrl = source.sourceUrl,
                            title = "仙都传说",
                            author = "仙都黄龙",
                            lastChapter = "第三百五十一章 正文",
                            chapterCount = 351
                        ).entries
                    }.associate { it.toPair() }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooksProgressively("仙都") {}

        assertTrue(books.isNotEmpty())
        assertEquals("仙都", books.first().title)
        assertEquals("陈猿", books.first().author)
    }

    @Test
    fun progressiveSearchDoesNotPublishTwoSourceShortCatalogConsensus() = runBlocking {
        val shortSources = (1..2).map { index ->
            changduSource("仙都短目录源$index", "https://xiandu-two-short-$index.example")
        }
        val sources = shortSources
        val engine = LegadoSourceEngine(
            MapFetcher(
                shortSources.flatMap { source ->
                    trustedBookFixture(
                        baseUrl = source.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第二十章 正文",
                        chapterCount = 20
                    ).entries
                }.associate { it.toPair() }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<BookSearchResult>>()

        val books = provider.searchBooksProgressively("仙都") { update ->
            if (update.isNotEmpty()) updates.add(update)
        }

        assertTrue(updates.isEmpty())
        assertTrue(books.isEmpty())
    }

    @Test
    fun progressiveSearchDoesNotPublishTwoSourceFiftyChapterConsensus() = runBlocking {
        val shortSources = (1..2).map { index ->
            changduSource("仙都五十章源$index", "https://xiandu-two-fifty-$index.example")
        }
        val sources = shortSources
        val engine = LegadoSourceEngine(
            MapFetcher(
                shortSources.flatMap { source ->
                    trustedBookFixture(
                        baseUrl = source.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第五十章 正文",
                        chapterCount = 50
                    ).entries
                }.associate { it.toPair() }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<BookSearchResult>>()

        val books = provider.searchBooksProgressively("仙都") { update ->
            if (update.isNotEmpty()) updates.add(update)
        }

        assertTrue(updates.isEmpty())
        assertTrue(books.isEmpty())
    }

    @Test
    fun progressiveSearchPublishesAboveFiftyExactCatalogAheadOfOneChapterVariant() = runBlocking {
        val exactA = changduSource("琼明正书源1", "https://qiongming-exact-a.example")
        val exactB = changduSource("琼明正书源2", "https://qiongming-exact-b.example")
        val variantSources = (1..4).map { index ->
            changduSource("琼明变体源$index", "https://qiongming-variant-$index.example")
        }
        val sources = listOf(exactA, exactB) + variantSources
        val exactATitles = (1..84).map { index -> "第${index}章 青璃$index" }
        val exactBTitles = (1..94).map { index -> "第${index}节 星台$index" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = exactA.sourceUrl,
                    title = "琼明神女录",
                    author = "剑气长存",
                    chapterTitles = exactATitles
                ) +
                    customCatalogFixture(
                        baseUrl = exactB.sourceUrl,
                        title = "琼明神女录",
                        author = "剑气长存",
                        chapterTitles = exactBTitles
                    ) +
                    variantSources.flatMap { source ->
                        trustedBookFixture(
                            baseUrl = source.sourceUrl,
                            title = "琼明神女录-无绿帽版",
                            author = "周黑粥",
                            lastChapter = "第1章 正文",
                            chapterCount = 1
                        ).entries
                    }.associate { it.toPair() }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )

        val books = provider.searchBooksProgressively("琼明神女录") {}
        val visible = books.map { it.title to it.author }

        assertEquals("琼明神女录", books.first().title)
        assertTrue("expected exact book, got $visible", visible.contains("琼明神女录" to "剑气长存"))
    }

    @Test
    fun progressiveSearchPublishesTwoSourceConsensusWhenOneCatalogIsLong() = runBlocking {
        val shortSource = changduSource("仙都短目录源", "https://xiandu-short-with-long.example")
        val longSource = changduSource("仙都长目录源", "https://xiandu-long-with-short.example")
        val sources = listOf(shortSource, longSource)
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    baseUrl = shortSource.sourceUrl,
                    title = "仙都",
                    author = "陈猿",
                    lastChapter = "第二十章 正文",
                    chapterCount = 20
                ) +
                    trustedBookFixture(
                        baseUrl = longSource.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第一百二十章 正文",
                        chapterCount = 120
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

        val books = provider.searchBooksProgressively("仙都") { update ->
            if (update.isNotEmpty()) {
                updates.add(update.map { result ->
                    SourceEngineBookRoute.decodeBookId(requireNotNull(result.routeId)).sourceUrl
                })
            }
        }

        assertTrue(updates.isNotEmpty())
        assertEquals(
            longSource.sourceUrl,
            SourceEngineBookRoute.decodeBookId(requireNotNull(books.first().routeId)).sourceUrl
        )
    }

    @Test
    fun progressiveSearchPublishesShortExactLongCatalogConsensusBeforeSlowWaveSettles() = runBlocking {
        val exactCatalogDelayMs = 250L
        val slowSearchDelayMs = 8_000L
        val exactA = changduSource("仙都长目录源A", "https://xiandu-first-exact-a.example")
        val exactB = changduSource("仙都长目录源B", "https://xiandu-first-exact-b.example")
        val slowVariantA = changduSource("仙都慢变体源A", "https://xiandu-first-slow-a.example")
        val slowVariantB = changduSource("仙都慢变体源B", "https://xiandu-first-slow-b.example")
        val sources = listOf(exactA, exactB, slowVariantA, slowVariantB)
        var startedAt = 0L
        val fetches = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, String>>()
        val engine = LegadoSourceEngine(
            MapFetcher(
                trustedBookFixture(
                    baseUrl = exactA.sourceUrl,
                    title = "仙都",
                    author = "陈猿",
                    lastChapter = "第二千七百八十七节 正文",
                    chapterCount = 2_787
                ) +
                    trustedBookFixture(
                        baseUrl = exactB.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第二千七百八十七节 正文",
                        chapterCount = 2_787
                    ) +
                    trustedBookFixture(
                        baseUrl = slowVariantA.sourceUrl,
                        title = "仙都传说",
                        author = "仙都黄龙",
                        lastChapter = "第三百章 正文",
                        chapterCount = 300
                    ) +
                    trustedBookFixture(
                        baseUrl = slowVariantB.sourceUrl,
                        title = "仙都传说",
                        author = "仙都黄龙",
                        lastChapter = "第三百章 正文",
                        chapterCount = 300
                    ),
                exactDelays = mapOf(
                    "${exactA.sourceUrl}/book/1/" to exactCatalogDelayMs,
                    "${exactB.sourceUrl}/book/1/" to exactCatalogDelayMs
                ),
                delays = mapOf(
                    "${slowVariantA.sourceUrl}/modules/article/search.php" to slowSearchDelayMs,
                    "${slowVariantB.sourceUrl}/modules/article/search.php" to slowSearchDelayMs
                ),
                onFetch = { url ->
                    val base = startedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    fetches.add(System.currentTimeMillis() - base to url)
                }
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
        val updateTimes = mutableListOf<Long>()

        startedAt = System.currentTimeMillis()
        val books = provider.searchBooksProgressively("仙都") { update ->
            if (update.isNotEmpty()) {
                updates.add(update.map { it.title to it.author })
                updateTimes.add(System.currentTimeMillis() - startedAt)
            }
        }

        assertTrue("expected an early visible exact result; updates=$updates", updates.isNotEmpty())
        val timingSummary = "updates=$updateTimes fetches=${
            fetches.take(16).joinToString("|") { (time, url) -> "${time}ms:${url.substringAfter("https://")}" }
        }"
        assertTrue(
            "first exact result should publish before slow source wave settles; firstUpdateMs=${updateTimes.firstOrNull()} $timingSummary",
            updateTimes.first() < slowSearchDelayMs
        )
        assertTrue(
            "first exact result should publish as soon as the two-source catalog consensus is ready; firstUpdateMs=${updateTimes.firstOrNull()} $timingSummary",
            updateTimes.first() < 3_000L
        )
        assertEquals(listOf("仙都" to "陈猿"), updates.first())
        assertEquals("仙都", books.first().title)
        assertEquals("陈猿", books.first().author)
    }

    @Test
    fun progressiveSearchPromotesExactPreviewBeforeSlowSearchTailSettles() = runBlocking {
        val title = "斗破苍穹"
        val author = "天蚕土豆"
        val slowSearchDelayMs = 8_000L
        val previewOnlyA = changduSource("预览源A", "https://battle-preview-a.example")
        val previewOnlyB = changduSource("预览源B", "https://battle-preview-b.example")
        val readableA = changduSource("可读源A", "https://battle-readable-a.example")
        val readableB = changduSource("可读源B", "https://battle-readable-b.example")
        val slowTailA = changduSource("慢尾部源A", "https://battle-slow-tail-a.example")
        val slowTailB = changduSource("慢尾部源B", "https://battle-slow-tail-b.example")
        val sources = listOf(previewOnlyA, previewOnlyB, readableA, readableB, slowTailA, slowTailB)
        val fetches = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, String>>()
        var fetchStartedAt = 0L
        val responses = buildMap {
            putAll(detailOnlyFixture(previewOnlyA.sourceUrl, title, author, "第一千六百二十三章 结局"))
            putAll(detailOnlyFixture(previewOnlyB.sourceUrl, title, author, "第一千六百二十三章 结局"))
            putAll(trustedBookFixture(readableA.sourceUrl, title, author, "第一千六百二十三章 结局", 120))
            putAll(trustedBookFixture(readableB.sourceUrl, title, author, "第一千六百二十三章 结局", 120))
            putAll(trustedBookFixture(slowTailA.sourceUrl, title, author, "第一千六百二十三章 结局", 120))
            putAll(trustedBookFixture(slowTailB.sourceUrl, title, author, "第一千六百二十三章 结局", 120))
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                responses = responses,
                delays = mapOf(
                    "${slowTailA.sourceUrl}/modules/article/search.php" to slowSearchDelayMs,
                    "${slowTailB.sourceUrl}/modules/article/search.php" to slowSearchDelayMs
                ),
                onFetch = { url -> fetches.add(System.currentTimeMillis() - fetchStartedAt to url) }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val startedAt = System.currentTimeMillis()
        fetchStartedAt = startedAt
        val previewAtMs = CompletableDeferred<Long>()
        val readableAtMs = CompletableDeferred<Long>()
        val searchJob = async {
            provider.searchBooksProgressively(title) { update ->
                update.firstOrNull { book -> book.title == title }?.let { exact ->
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    if (exact.readingReady) {
                        readableAtMs.complete(elapsedMs)
                    } else {
                        previewAtMs.complete(elapsedMs)
                    }
                }
            }
        }
        try {
            val readyMs = withTimeout(3_000L) { readableAtMs.await() }
            val previewMs = withTimeout(500L) { previewAtMs.await() }
            assertTrue(
                "exact readiness must publish before the ${slowSearchDelayMs}ms search tail; preview=${previewMs}ms ready=${readyMs}ms fetches=${fetches.joinToString("|") { (time, url) -> "${time}ms:${url.substringAfter("https://")}" }}",
                readyMs < 3_000L
            )
        } finally {
            searchJob.cancel()
            searchJob.join()
        }
    }

    @Test
    fun progressiveSearchPublishesFourSourceRoughlySimilarShortCatalogConsensus() = runBlocking {
        val shortSources = (1..4).map { index ->
            changduSource("仙都短目录共识源$index", "https://xiandu-short-consensus-$index.example")
        }
        val counts = listOf(20, 21, 23, 25)
        val sources = shortSources
        val engine = LegadoSourceEngine(
            MapFetcher(
                shortSources.flatMapIndexed { index, source ->
                    trustedBookFixture(
                        baseUrl = source.sourceUrl,
                        title = "仙都",
                        author = "陈猿",
                        lastChapter = "第二十五章 正文",
                        chapterCount = counts[index]
                    ).entries
                }.associate { it.toPair() }
            )
        )
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { it.sourceUrl == sourceUrl } }
        )
        val updates = mutableListOf<List<BookSearchResult>>()

        val books = provider.searchBooksProgressively("仙都") { update ->
            if (update.isNotEmpty()) updates.add(update)
        }

        assertTrue(updates.isNotEmpty())
        assertTrue(books.isNotEmpty())
        assertEquals("仙都", books.first().title)
        assertEquals("陈猿", books.first().author)
    }

    @Test
    fun progressiveSearchPublishesEarlyResultAndEventuallyPromotesReadableTailCandidate() = runBlocking {
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
        assertTrue(
            "expected an update from good tail source, got $updates",
            updates.flatten().any { sourceUrl ->
                sourceUrl in setOf(
                    "https://progress-tail-good-a.example",
                    "https://progress-tail-good-b.example"
                )
            }
        )
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
            changduSource("短一点但尾部完整", "https://verified-tail.example"),
            changduSource("短一点但尾部完整镜像", "https://verified-tail-mirror.example")
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                qingShanTailFixture(
                    baseUrl = "https://raw-long-bad-a.example",
                    chapterCount = 108,
                    pollutedChapters = setOf(108)
                ) +
                    qingShanTailFixture(
                        baseUrl = "https://raw-long-bad-b.example",
                        chapterCount = 108,
                        pollutedChapters = setOf(108)
                    ) +
                    qingShanTailFixture(
                        baseUrl = "https://verified-tail.example",
                        chapterCount = 106,
                        pollutedChapters = emptySet()
                    ) +
                    qingShanTailFixture(
                        baseUrl = "https://verified-tail-mirror.example",
                        chapterCount = 106,
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

        assertEquals("第106章 正文", folder.last().title)
        assertEquals(106, folder.size)
    }

    @Test
    fun getBookContentCleansEnrichedDisplayTitleWithoutChangingTheRawChapterRoute() = runBlocking {
        val baseUrl = "https://enriched-content-title.example"
        val rawRouteTitle = "第五百九十一章 融合"
        val enrichedDisplayTitle = "第五百九十一章 融合成功！"
        val source = changduSource("完整正文标题源", baseUrl).copy(
            ruleContent = LegadoRuleSet(
                "ruleContent",
                mapOf("content" to "#content@html")
            )
        )
        val book = SourceBook(
            source = source,
            name = "斗破苍穹",
            author = "天蚕土豆",
            bookUrl = "$baseUrl/books/1/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = rawRouteTitle
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = 590,
            name = rawRouteTitle,
            chapterUrl = "$baseUrl/book/1/591.html"
        )
        val paragraphs = (1..8).joinToString("\n") { index ->
            "<p>第${index}段，晶莹剔透的宽敞经脉中，乳白色的火焰如潮水般涌动。" +
                "萧炎与药老在黑角域商议远行，异火、斗气、宗门与旧友之间的因果逐渐清晰。</p>"
        }
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    chapter.chapterUrl to """
                        <html><body>
                          <div id="content">
                            <p>$enrichedDisplayTitle</p>
                            $paragraphs
                          </div>
                        </body></html>
                    """.trimIndent()
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
        val collBook = CollBookBean().apply {
            title = book.name
            author = book.author
        }
        val txtChapter = TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(book)
            link = SourceEngineBookRoute.chapterId(chapter)
            title = enrichedDisplayTitle
            start = 590L
            sourceEngineCurrentReadRequest = true
        }

        val routeBeforeRead = SourceEngineBookRoute.decodeChapterId(requireNotNull(txtChapter.link))
        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)
        val routeAfterRead = SourceEngineBookRoute.decodeChapterId(requireNotNull(txtChapter.link))

        assertEquals(rawRouteTitle, routeBeforeRead.chapterName)
        assertEquals(rawRouteTitle, routeAfterRead.chapterName)
        assertFalse(content.lineSequence().any { line -> line.trim() == enrichedDisplayTitle })
        assertTrue(content.startsWith("第1段，晶莹剔透的宽敞经脉中"))
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
    fun getBookContentDisplaysCurrentShortCatalogChapterWithoutFingerprint() = runBlocking {
        val source = changduSource("短篇当前源", "https://short-current.example")
        val engine = LegadoSourceEngine(
            MapFetcher(
                qingShanTailFixture(
                    baseUrl = "https://short-current.example",
                    chapterCount = 7,
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
            bookUrl = "https://short-current.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第7章 正文"
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = 5,
            name = "第6章 正文",
            chapterUrl = "https://short-current.example/book/1/6.html"
        )
        val collBook = CollBookBean().apply {
            title = "青山"
            author = "会说话的肘子"
        }
        val txtChapter = TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(book)
            link = SourceEngineBookRoute.chapterId(chapter)
            title = chapter.name
            start = 5L
            sourceEngineCurrentReadRequest = true
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("第6章 第1段"))
        assertTrue(content.contains("陈迹与老耳朵"))
    }

    @Test
    fun getBookContentDisplaysCurrentTwoChapterBookWithoutFingerprint() = runBlocking {
        val source = changduSource("两章当前源", "https://two-chapter-current.example")
        val engine = LegadoSourceEngine(
            MapFetcher(
                qingShanTailFixture(
                    baseUrl = "https://two-chapter-current.example",
                    chapterCount = 2,
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
            bookUrl = "https://two-chapter-current.example/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = "第2章 正文"
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = 1,
            name = "第2章 正文",
            chapterUrl = "https://two-chapter-current.example/book/1/2.html"
        )
        val collBook = CollBookBean().apply {
            title = "青山"
            author = "会说话的肘子"
        }
        val txtChapter = TxtChapter().apply {
            bookId = SourceEngineBookRoute.bookId(book)
            link = SourceEngineBookRoute.chapterId(chapter)
            title = chapter.name
            start = 1L
            sourceEngineCurrentReadRequest = true
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("第2章 第1段"))
        assertTrue(content.contains("陈迹与老耳朵"))
    }

    @Test
    fun getBookContentDisplaysCurrentChapterFromDirectSourceWithoutHiddenMark() = runBlocking {
        val source = changduSource("当前可读源", "https://current-readable-direct.example")
        val chapterTitles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://current-readable-direct.example",
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
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val book = SourceBook(
            source = source,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://current-readable-direct.example/books/1/",
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
            chapterUrl = "https://current-readable-direct.example/book/1/12.html"
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
            sourceEngineCurrentReadRequest = true
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("第12章 正文"))
        assertTrue(content.contains("萧炎"))
    }

    @Test
    fun getBookContentDisplaysCurrentChapterWhenCurrentSourceIsNotWholeBookTrusted() = runBlocking {
        val source = changduSource("当前可读源", "https://current-readable-untrusted.example")
        val chapterTitles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://current-readable-untrusted.example",
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
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            bookCacheFolderPath = ::testBookCacheFolderPath
        )
        val book = SourceBook(
            source = source,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://current-readable-untrusted.example/books/1/",
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
            chapterUrl = "https://current-readable-untrusted.example/book/1/12.html"
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
            sourceEngineCurrentReadRequest = true
            sourceIntegrityState = V8ChapterMarkState.WRONG.name
            sourceIntegrityConfidence = 0.8
            sourceIntegrityReason = markReason("v8")
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("第12章 正文"))
        assertTrue(content.contains("萧炎"))
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
    fun routeChapterDisplaysPersonalCandidateBeforeFingerprintTrust() = runBlocking {
        val currentSource = changduSource("错位当前源", "https://route-fast-display.example")
        val candidateSource = changduSource("专属候选源", "https://route-candidate-fast-display.example")
        val chapterTitles = (1..12).map { index -> "第${index}章 正文" }
        val engine = LegadoSourceEngine(
            MapFetcher(
                customCatalogFixture(
                    baseUrl = "https://route-fast-display.example",
                    title = "青山",
                    author = "会说话的肘子",
                    chapterTitles = chapterTitles,
                    customChapterHtml = { index, _ ->
                        if (index == 12) {
                            readableChapterHtmlWithDisplayTitle("青山", "会说话的肘子", "第486章 飞天鼠王")
                        } else {
                            unreadableChapterHtml()
                        }
                    }
                ) + customCatalogFixture(
                    baseUrl = "https://route-candidate-fast-display.example",
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
            bookUrl = "https://route-candidate-fast-display.example/books/1/",
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
                chapterUrl = "https://route-candidate-fast-display.example/book/1/12.html"
            )
        )
        val currentBook = SourceBook(
            source = currentSource,
            name = "青山",
            author = "会说话的肘子",
            bookUrl = "https://route-fast-display.example/books/1/",
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
            chapterUrl = "https://route-fast-display.example/book/1/12.html"
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
            sourceEngineCurrentReadRequest = false
        }

        val content = provider.getBookContent(txtChapter.bookId, collBook, txtChapter, 0)

        assertTrue(content.contains("第12章 正文"))
        assertTrue(content.contains("萧炎"))
        assertFalse(content.contains("第486章 飞天鼠王"))
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

    private fun rankedSearchBook(
        sourceName: String,
        sourceUrl: String,
        title: String,
        author: String,
        score: Int,
        sourceIndex: Int
    ): RankedSearchBook {
        return RankedSearchBook(
            book = SourceBook(
                source = source(sourceName, sourceUrl),
                name = title,
                author = author,
                bookUrl = "$sourceUrl/books/1/",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第120章 正文"
            ),
            score = score,
            evidence = "fixture",
            sourceIndex = sourceIndex,
            resultIndex = 0
        )
    }

    private fun canonicalCatalogFixture(
        book: SourceBook,
        chapterTitles: List<String>
    ): CanonicalChapterList {
        val chapters = chapterTitles.mapIndexed { index, title ->
            val sourceChapter = SourceChapter(
                source = book.source,
                book = book,
                index = index,
                name = title,
                chapterUrl = "${book.source.sourceUrl}/book/1/${index + 1}.html"
            )
            CanonicalChapter(
                key = "chapter-${index + 1}",
                displayTitle = title,
                ordinal = index + 1,
                sourceChapters = listOf(sourceChapter)
            )
        }
        return CanonicalChapterList(
            chapters = chapters,
            duplicateCount = 0,
            missingOrdinalRanges = emptyList()
        )
    }

    private fun validationBatchLabel(batch: Any): String {
        val field = batch.javaClass.getDeclaredField("label")
        field.isAccessible = true
        return field.get(batch) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun validationBatchCandidates(batch: Any): List<RankedSearchBook> {
        val field = batch.javaClass.getDeclaredField("candidates")
        field.isAccessible = true
        return field.get(batch) as List<RankedSearchBook>
    }

    private fun validatedSearchCandidateChapterCount(candidate: Any): Int {
        val field = candidate.javaClass.getDeclaredField("chapterCount")
        field.isAccessible = true
        return field.getInt(candidate)
    }

    private fun validatedSearchCandidateSourceUrl(candidate: Any): String {
        val field = candidate.javaClass.getDeclaredField("book")
        field.isAccessible = true
        return (field.get(candidate) as SourceBook).source.sourceUrl
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invokePreferredSearchTitleGroupValidation(
        provider: SourceEngineReaderContentProvider,
        candidates: List<RankedSearchBook>,
        titleKey: String,
        authorKey: String,
        queryKey: String
    ): List<Any>? {
        val providerClass = provider.javaClass
        val groupClass = Class.forName("${providerClass.name}\$SearchTitleGroup")
        val constructor = groupClass.declaredConstructors.single { candidate ->
            candidate.parameterTypes.size == 4
        }
        constructor.isAccessible = true
        val titleGroup = constructor.newInstance(
            "$titleKey\n$authorKey",
            titleKey,
            candidates,
            mapOf(authorKey to candidates.size)
        )
        val method = providerClass.getDeclaredMethod(
            "validateSearchTitleGroup",
            groupClass,
            Semaphore::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            Continuation::class.java
        )
        method.isAccessible = true
        return suspendCoroutine { continuation ->
            val result = method.invoke(
                provider,
                titleGroup,
                Semaphore(12),
                true,
                false,
                queryKey,
                true,
                false,
                continuation
            )
            if (result !== COROUTINE_SUSPENDED) {
                continuation.resume(result as List<Any>?)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun personalSourceKeysFor(
        provider: SourceEngineReaderContentProvider,
        book: SourceBook
    ): Set<String> {
        val method = provider.javaClass.getDeclaredMethod("personalSourceKeysForBook", SourceBook::class.java)
        method.isAccessible = true
        return method.invoke(provider, book) as Set<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun personalTierBookNamesFor(
        provider: SourceEngineReaderContentProvider,
        book: SourceBook
    ): List<String> {
        val method = provider.javaClass.getDeclaredMethod("personalTierBookNamesFor", SourceBook::class.java)
        method.isAccessible = true
        return method.invoke(provider, book) as List<String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedIdentityProfile(
        provider: SourceEngineReaderContentProvider,
        currentBook: SourceBook,
        aliasBook: SourceBook
    ) {
        val providerClass = provider.javaClass
        val profileClass = Class.forName("${providerClass.name}\$BookIdentityProfile")
        val rawKeyMethod = providerClass.getDeclaredMethod("rawBookWaterfallKey", SourceBook::class.java)
        rawKeyMethod.isAccessible = true
        val waterfallKey = rawKeyMethod.invoke(provider, currentBook) as String
        val constructor = profileClass.declaredConstructors.first { constructor ->
            constructor.parameterTypes.size == 7
        }
        constructor.isAccessible = true
        val profile = constructor.newInstance(
            currentBook.name,
            currentBook.author,
            waterfallKey,
            linkedSetOf<String>(),
            linkedSetOf<String>(),
            linkedSetOf<String>(),
            linkedSetOf<String>()
        )
        val addMethod = providerClass.getDeclaredMethod("addBookToIdentityProfile", profileClass, SourceBook::class.java)
        addMethod.isAccessible = true
        addMethod.invoke(provider, profile, currentBook)
        addMethod.invoke(provider, profile, aliasBook)
        val profilesField = providerClass.getDeclaredField("bookIdentityProfiles")
        profilesField.isAccessible = true
        (profilesField.get(provider) as MutableList<Any>).add(profile)
    }

    private fun bookSearchResult(title: String, author: String, routeId: String): BookSearchResult {
        return BookSearchResult().apply {
            this.title = title
            this.author = author
            this.routeId = routeId
        }
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

    private fun sourceBook(
        source: BookSource,
        title: String,
        author: String,
        lastChapter: String
    ): SourceBook {
        return SourceBook(
            source = source,
            name = title,
            author = author,
            bookUrl = "${source.sourceUrl}/books/1/",
            coverUrl = "file:///cover.jpg",
            intro = "",
            kind = "",
            lastChapter = lastChapter
        )
    }

    private fun readableCleanContent(): CleanContent {
        val content = "陆江仙在大黎山旁醒来，青灰铜镜映出云雾与溪流。".repeat(24)
        return CleanContent(
            rawContent = content,
            cleanedContent = content,
            report = ContentQualityReport(
                qualityScore = 95,
                rawLength = content.length,
                cleanedLength = content.length,
                paragraphCount = 8,
                removedLineCount = 0,
                duplicateLineCount = 0,
                pollutionMarkers = emptyList(),
                warnings = emptyList(),
                coherenceScore = 95
            )
        )
    }

    private fun wrongV8Result(request: V8SourceRunRequest, reason: String): V8SourceRunResult {
        val chaptersByIndex = request.chapters.associateBy { chapter -> chapter.index }
        val marks = request.markableChapterIndexes.orEmpty().sorted().map { index ->
            V8ChapterMarkResult(
                chapterIndex = index,
                chapterTitle = chaptersByIndex[index]?.title.orEmpty(),
                state = V8ChapterMarkState.WRONG,
                confidence = 0.9,
                qualityType = null,
                suggestionState = null,
                action = null,
                reasons = listOf(reason)
            )
        }
        return V8SourceRunResult(
            title = request.title,
            author = request.author,
            sourceKey = request.sourceKey,
            marks = marks,
            planningMarks = marks
        )
    }

    private fun canonicalCatalogFor(book: SourceBook, titles: List<String>): CanonicalChapterList {
        return CanonicalChapterList(
            chapters = titles.mapIndexed { index, title ->
                CanonicalChapter(
                    key = "chapter-${index + 1}",
                    displayTitle = title,
                    ordinal = index + 1,
                    sourceChapters = listOf(
                        SourceChapter(
                            source = book.source,
                            book = book,
                            index = index,
                            name = title,
                            chapterUrl = "${book.bookUrl}${index + 1}.html"
                        )
                    )
                )
            },
            duplicateCount = 0,
            missingOrdinalRanges = emptyList()
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

    @Test
    fun personalSearchStageDoesNotPoisonTheScopeForTheGlobalStage() = runBlocking {
        // Regression: each search stage cancelled its enclosing request scope in a finally
        // block. Scope cancellation is permanent, so after the personal stage ran, the global
        // stage (and every later probe in the same scope) failed instantly without network,
        // and the per-book tier never grew past the anchor itself.
        val title = "作用域隔离测试书"
        val author = "测试作者"
        val chapterTitles = (1..127).map { index -> "第${index}章 正文" }
        val anchorBase = "https://scope-anchor.example"
        val globalBase = "https://scope-global.example"
        val anchorSource = changduSource("锚点源", anchorBase)
        val globalSource = changduSource("全局源", globalBase)
        val fixtures = customCatalogFixture(anchorBase, title, author, chapterTitles) +
            customCatalogFixture(globalBase, title, author, chapterTitles)
        val engine = LegadoSourceEngine(MapFetcher(fixtures))
        val searchFetcher = OkHttpSourceEngineFetcher(5_000, 5_000, callFactory = { request ->
            FixtureCall(request, fixtures)
        })
        val sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage())
        val anchorBook = SourceBook(
            source = anchorSource,
            name = title,
            author = author,
            bookUrl = "$anchorBase/books/1/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        repeat(3) {
            sourceQualityRouter.recordTrustedCatalogResolved(
                anchorBook,
                chapterCount = chapterTitles.size,
                rawChapterCount = chapterTitles.size
            )
        }
        sourceQualityRouter.recordCatalogTailTrimmed(
            anchorBook,
            kept = chapterTitles.size,
            rawChapterCount = chapterTitles.size
        )
        assertTrue(
            "test setup: the anchor source must be personal so the personal stage actually runs",
            sourceQualityRouter
                .personalWaterfallSourcesForBook(listOf(anchorSource, globalSource), title)
                .isNotEmpty()
        )

        val provider = SourceEngineReaderContentProvider(
            searchEngineFetcher = searchFetcher,
            engine = engine,
            searchEngine = LegadoSourceEngine(fetcher = searchFetcher),
            detailProbeEngine = engine,
            sourceProvider = { listOf(anchorSource, globalSource) },
            sourceFinder = { sourceUrl ->
                listOf(anchorSource, globalSource).first { source -> source.sourceUrl == sourceUrl }
            },
            sourceQualityRouter = sourceQualityRouter,
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { false }
        )
        val routeId = SourceEngineBookRoute.bookId(anchorBook)
        // Search/detail sessions carry the route id as _id; the tier must land under the shelf id.
        val collBook = CollBookBean().apply {
            set_id(routeId)
            bookIdInBiquge = routeId
            this.title = title
            this.author = author
        }
        val shelfId = com.ldp.reader.utils.BookIdentity.sourceEngineShelfId(title, author)
        val cacheFolder = java.io.File(testBookCacheFolderPath(shelfId))
        cacheFolder.deleteRecursively()

        try {
            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = true,
                triggerV8 = false,
                requestPriority = SourceRequestPriority.FOREGROUND,
                mode = SourceContentTierMode.FULL
            )
            val tierFile = java.io.File(cacheFolder, ".source_engine_content_tier")
            assertTrue("tier file must be written under the canonical shelf id", tierFile.isFile)
            val persistedSourceUrls = tierFile.readLines()
                .filter { line -> SourceEngineBookRoute.isBookId(line) }
                .map { line -> SourceEngineBookRoute.decodeBookId(line).sourceUrl }
            assertTrue(
                "the global stage must verify the candidate it finds after the personal stage " +
                    "ran; persisted=$persistedSourceUrls",
                globalSource.sourceUrl in persistedSourceUrls
            )
        } finally {
            cacheFolder.deleteRecursively()
        }
    }

    @Test
    fun completedGlobalPassRecordsPoolSizeAndOnlyPoolChangesReArmTheFill() = runBlocking {
        // A book whose whole source universe is smaller than the tier target must settle
        // after one completed global pass instead of re-searching on every open; a cull
        // (or any pool change) re-arms the fill.
        val title = "小宇宙测试书"
        val author = "测试作者"
        val chapterTitles = (1..127).map { index -> "第${index}章 正文" }
        val anchorBase = "https://universe-anchor.example"
        val globalBase = "https://universe-global.example"
        val anchorSource = changduSource("宇宙锚点", anchorBase)
        val globalSource = changduSource("宇宙全局", globalBase)
        val fixtures = customCatalogFixture(anchorBase, title, author, chapterTitles) +
            customCatalogFixture(globalBase, title, author, chapterTitles)
        val engine = LegadoSourceEngine(MapFetcher(fixtures))
        val searchFetcher = OkHttpSourceEngineFetcher(5_000, 5_000, callFactory = { request ->
            FixtureCall(request, fixtures)
        })
        val provider = SourceEngineReaderContentProvider(
            searchEngineFetcher = searchFetcher,
            engine = engine,
            searchEngine = LegadoSourceEngine(fetcher = searchFetcher),
            detailProbeEngine = engine,
            sourceProvider = { listOf(anchorSource, globalSource) },
            sourceFinder = { sourceUrl ->
                listOf(anchorSource, globalSource).first { source -> source.sourceUrl == sourceUrl }
            },
            sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage()),
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { false }
        )
        val anchorBook = SourceBook(
            source = anchorSource,
            name = title,
            author = author,
            bookUrl = "$anchorBase/books/1/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        val routeId = SourceEngineBookRoute.bookId(anchorBook)
        val collBook = CollBookBean().apply {
            set_id(routeId)
            bookIdInBiquge = routeId
            this.title = title
            this.author = author
        }
        val shelfId = com.ldp.reader.utils.BookIdentity.sourceEngineShelfId(title, author)
        val cacheFolder = java.io.File(testBookCacheFolderPath(shelfId))
        cacheFolder.deleteRecursively()
        val tierFile = java.io.File(cacheFolder, ".source_engine_content_tier")

        try {
            assertTrue(
                "a book without a completed pass must be pending",
                provider.isPersistedTierFillPending(collBook)
            )
            provider.prepareBookContentTierResult(
                routeId,
                collBook,
                persist = true,
                triggerV8 = false,
                requestPriority = SourceRequestPriority.FOREGROUND,
                mode = SourceContentTierMode.FULL
            )
            val settledRoutes = tierFile.readLines().count { line -> line.isNotBlank() }
            assertTrue("the exhausted pass must have verified the universe", settledRoutes >= 2)
            assertFalse(
                "a completed pass over an unchanged pool must not re-arm the fill",
                provider.isPersistedTierFillPending(collBook)
            )

            val culled = tierFile.readLines().filter { line -> line.isNotBlank() }.drop(1)
            tierFile.writeText(culled.joinToString("\n"))
            assertTrue(
                "a culled pool must re-arm the fill",
                provider.isPersistedTierFillPending(collBook)
            )
        } finally {
            cacheFolder.deleteRecursively()
        }
    }

    @Test
    fun zeroVerifiedRoundStillCullsFailedRoutesFromThePersistedTier() = runBlocking {
        // Regression: persistVerifiedTier returned before the health cull when the round
        // verified nothing, so a tier whose routes had all gone bad could never evict them.
        val title = "淘汰测试书"
        val author = "测试作者"
        val anchorSource = changduSource("死锚点", "https://cull-anchor.example")
        val deadSourceB = changduSource("死源B", "https://cull-dead-b.example")
        val deadSourceC = changduSource("死源C", "https://cull-dead-c.example")
        val sources = listOf(anchorSource, deadSourceB, deadSourceC)
        val engine = LegadoSourceEngine(MapFetcher(emptyMap()))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(storage = InMemorySourceQualityStorage()),
            bookCacheFolderPath = ::testBookCacheFolderPath,
            wrongChapterAnalysisEnabled = { false }
        )
        fun routeFor(source: BookSource): String {
            return SourceEngineBookRoute.bookId(
                SourceBook(
                    source = source,
                    name = title,
                    author = author,
                    bookUrl = "${source.sourceUrl.substringBefore("#")}/books/1/",
                    coverUrl = "",
                    intro = "",
                    kind = "",
                    lastChapter = ""
                )
            )
        }
        val anchorRoute = routeFor(anchorSource)
        val routeB = routeFor(deadSourceB)
        val routeC = routeFor(deadSourceC)
        val collBook = CollBookBean().apply {
            set_id("source_engine_shelf_cull-test")
            this.title = title
            this.author = author
        }
        val cacheFolder = java.io.File(testBookCacheFolderPath(collBook.get_id()))
        cacheFolder.deleteRecursively()
        val tierFile = java.io.File(cacheFolder, ".source_engine_content_tier")
        tierFile.parentFile?.mkdirs()
        tierFile.writeText(listOf(anchorRoute, routeB, routeC).joinToString("\n"))

        try {
            provider.prepareBookContentTierResult(
                anchorRoute,
                collBook,
                persist = true,
                triggerV8 = false,
                requestPriority = SourceRequestPriority.BACKGROUND,
                mode = SourceContentTierMode.READING_LIGHT
            )
            assertEquals(
                "probed-and-failed routes must be culled even when nothing was verified",
                listOf(anchorRoute),
                tierFile.readLines().filter { line -> line.isNotBlank() }
            )
        } finally {
            cacheFolder.deleteRecursively()
        }
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

    private data class SearchRow(
        val bookUrl: String,
        val title: String,
        val lastChapter: String,
        val author: String
    )

    private fun searchHtmlRows(rows: List<SearchRow>): String {
        val rowHtml = rows.joinToString("\n") { row ->
            """
                <tr>
                  <td class="odd"><a href="${row.bookUrl}">${row.title}</a></td>
                  <td class="even"><a href="/chapter/latest.html">${row.lastChapter}</a></td>
                  <td class="odd">${row.author}</td>
                </tr>
            """.trimIndent()
        }
        return """
            <html><body>
              <table><tbody>
                <tr><th>小说</th><th>最新章节</th><th>作者</th></tr>
                $rowHtml
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

    private fun detailOnlyFixture(
        baseUrl: String,
        title: String,
        author: String,
        lastChapter: String
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
                lastChapter = lastChapter
            )
        )
    }

    private fun trustedBookWithoutIntroFixture(
        baseUrl: String,
        title: String,
        author: String,
        lastChapter: String,
        chapterCount: Int
    ): Map<String, String> {
        return trustedBookFixture(baseUrl, title, author, lastChapter, chapterCount) +
            mapOf(
                "$baseUrl/books/1/" to detailHtmlWithoutIntro(
                    title = title,
                    author = author,
                    lastChapter = lastChapter
                )
            )
    }

    private fun detailHtmlWithoutIntro(
        title: String,
        author: String,
        lastChapter: String
    ): String {
        return """
            <html><body>
              <img class="cover" src="file:///cover.jpg"/>
              <div class="status">
                <h1>$title</h1>
                <p class="bz"></p>
                <p class="author">作者：$author</p>
                <p>最新章节：<a href="/chapter/latest.html"><span class="red">$lastChapter</span></a></p>
                <a class="button read" href="/book/1/">点击阅读</a>
              </div>
            </body></html>
        """.trimIndent()
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

    private fun blankChapterHtml(): String {
        return """
            <html><body>
              <div id="content"></div>
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

    private class CancelableFixtureCallFactory(
        private val responses: Map<String, String>,
        private val exactDelays: Map<String, Long> = emptyMap(),
        private val cancelReturnDelayMs: Long = 0L,
        private val requestDelayMs: ((Request) -> Long?)? = null
    ) {
        val cancelCount = AtomicInteger(0)
        val executeStarted = CountDownLatch(1)
        private val calls = Collections.synchronizedList(ArrayList<Call>())
        private val executedUrls = Collections.synchronizedList(ArrayList<String>())

        fun newCall(request: Request): Call {
            val url = request.url.toString()
            val body = responses[url] ?: error("No fixture response for $url")
            return CancelableFixtureCall(
                request = request,
                body = body,
                delayMs = requestDelayMs?.invoke(request) ?: exactDelays[url] ?: 0L,
                onExecute = {
                    executedUrls.add(url)
                    executeStarted.countDown()
                },
                onCancel = {
                    cancelCount.incrementAndGet()
                    if (cancelReturnDelayMs > 0L) Thread.sleep(cancelReturnDelayMs)
                }
            ).also { call -> calls.add(call) }
        }

        fun executedUrlsSnapshot(): List<String> = synchronized(executedUrls) {
            executedUrls.toList()
        }

        fun cancelAll() {
            calls.toList().forEach { call -> call.cancel() }
        }
    }

    private class CancelableFixtureCall(
        private val request: Request,
        private val body: String,
        private val delayMs: Long,
        private val onExecute: () -> Unit,
        private val onCancel: () -> Unit
    ) : Call {
        private val executed = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            executed.set(true)
            onExecute()
            val deadline = System.currentTimeMillis() + delayMs
            while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L)
            }
            if (cancelled.get()) throw IOException("Canceled")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }

        override fun enqueue(responseCallback: Callback) = error("not used")

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) onCancel()
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = cancelled.get()

        override fun clone(): Call = CancelableFixtureCall(request, body, delayMs, onExecute, onCancel)

        override fun timeout(): Timeout = Timeout()

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }

    /** okhttp Call answering from the fixture map, for tests that need the real scope-checking fetcher. */
    private class FixtureCall(
        private val fixtureRequest: Request,
        private val responses: Map<String, String>
    ) : Call {
        @Volatile
        private var executed = false

        override fun request(): Request = fixtureRequest

        override fun execute(): Response {
            executed = true
            val url = fixtureRequest.url.toString()
            val body = responses[url] ?: error("No fixture response for $url")
            return Response.Builder()
                .request(fixtureRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }

        override fun enqueue(responseCallback: Callback) {
            throw UnsupportedOperationException("FixtureCall only supports execute().")
        }

        override fun cancel() {}

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FixtureCall(fixtureRequest, responses)

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }

    private class MapFetcher(
        private val responses: Map<String, String>,
        private val exactDelays: Map<String, Long> = emptyMap(),
        private val delays: Map<String, Long> = emptyMap(),
        private val onFetch: ((String) -> Unit)? = null
    ) : HttpFetcher {
        override fun fetch(request: HttpRequest): HttpResponse {
            onFetch?.invoke(request.url)
            exactDelays[request.url]?.let { Thread.sleep(it) }
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

    private class ConcurrentSearchWaveTrackingFetcher(
        responses: Map<String, String>,
        private val trackedSearchUrls: Set<String>,
        private val overlapThreshold: Int
    ) : HttpFetcher {
        private val delegate = MapFetcher(responses)
        private val activeSearches = AtomicInteger(0)
        private val activeUrls = linkedSetOf<String>()
        private val releaseOverlap = CountDownLatch(1)
        val maxConcurrentSearches = AtomicInteger(0)

        @Volatile
        var firstFullWindow: Set<String> = emptySet()
            private set

        override fun fetch(request: HttpRequest): HttpResponse {
            if (request.url !in trackedSearchUrls) return delegate.fetch(request)
            synchronized(activeUrls) {
                activeUrls.add(request.url)
            }
            val active = activeSearches.incrementAndGet()
            maxConcurrentSearches.updateAndGet { previous -> maxOf(previous, active) }
            if (active == overlapThreshold - 1) {
                synchronized(activeUrls) {
                    if (firstFullWindow.isEmpty()) {
                        firstFullWindow = activeUrls.toSet()
                    }
                }
            }
            if (active >= overlapThreshold) {
                releaseOverlap.countDown()
            }
            return try {
                releaseOverlap.await(300L, TimeUnit.MILLISECONDS)
                delegate.fetch(request)
            } finally {
                synchronized(activeUrls) {
                    activeUrls.remove(request.url)
                }
                activeSearches.decrementAndGet()
            }
        }
    }

    private class ConcurrentCatalogTrackingFetcher(
        responses: Map<String, String>,
        private val trackedCatalogUrls: Set<String>,
        private val catalogDelayMs: Long
    ) : HttpFetcher {
        private val delegate = MapFetcher(responses)
        private val activeCatalogFetches = AtomicInteger(0)
        val maxConcurrentCatalogFetches = AtomicInteger(0)
        val fetchedUrls = Collections.synchronizedList(ArrayList<String>())

        override fun fetch(request: HttpRequest): HttpResponse {
            fetchedUrls.add(request.url)
            if (request.url !in trackedCatalogUrls) return delegate.fetch(request)
            val active = activeCatalogFetches.incrementAndGet()
            maxConcurrentCatalogFetches.updateAndGet { previous -> maxOf(previous, active) }
            return try {
                Thread.sleep(catalogDelayMs)
                delegate.fetch(request)
            } finally {
                activeCatalogFetches.decrementAndGet()
            }
        }
    }
}
