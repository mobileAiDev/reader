package com.ldp.reader.source

import com.ldp.reader.model.bean.BookSearchResult
import com.ldp.reader.sourceengine.legado.HttpFetcher
import com.ldp.reader.sourceengine.legado.HttpRequest
import com.ldp.reader.sourceengine.legado.HttpResponse
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.model.BookSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SourceSearchLateParseProgressTest {
    @Test
    fun cancelledTierLateExactCandidatesPublishWithoutWaitingForTheNextTier() = runBlocking {
        val query = "迟到候选测试书"
        val author = "测试作者"
        val tierOneSources = listOf(
            searchSource("优先源甲", "https://late-tier-one-a.example"),
            searchSource("优先源乙", "https://late-tier-one-b.example")
        )
        val tierTwoSources = List(TIER_TWO_SOURCE_COUNT) { index ->
            searchSource("后续阻塞源${index + 1}", "https://blocked-tier-two-${index + 1}.example")
        }
        val sources = tierOneSources + tierTwoSources
        val releaseTierOne = CountDownLatch(1)
        val releaseTierTwo = CountDownLatch(1)
        val tierOneEntered = CountDownLatch(tierOneSources.size)
        val tierOneResponsesReturned = CountDownLatch(tierOneSources.size)
        val tierTwoEntered = CountDownLatch(1)
        val searchFetcher = BlockingWaveSearchFetcher(
            tierOneResponses = tierOneSources.associate { source ->
                searchEndpoint(source) to searchHtml(source, query, author)
            },
            tierTwoSearchUrls = tierTwoSources.mapTo(LinkedHashSet()) { source -> searchEndpoint(source) },
            releaseTierOne = releaseTierOne,
            releaseTierTwo = releaseTierTwo,
            tierOneEntered = tierOneEntered,
            tierOneResponsesReturned = tierOneResponsesReturned,
            tierTwoEntered = tierTwoEntered
        )
        val validationEngine = LegadoSourceEngine(
            FixtureFetcher(readableBookResponses(tierOneSources, query, author))
        )
        val provider = SourceEngineReaderContentProvider(
            engine = validationEngine,
            searchEngine = LegadoSourceEngine(searchFetcher),
            detailProbeEngine = validationEngine,
            sourceProvider = { sources },
            sourceFinder = { sourceUrl -> sources.first { source -> source.sourceUrl == sourceUrl } },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = qualitySeed(tierOneSources, tierTwoSources)
            ),
            searchTierOneSettleTimeoutMs = TIER_ONE_SETTLE_TIMEOUT_MS,
            searchProgressPollIntervalMs = ROOT_PROGRESS_POLL_INTERVAL_MS
        )
        val published = CompletableDeferred<List<BookSearchResult>>()
        val searchFailure = AtomicReference<Throwable?>()
        var search: Deferred<List<BookSearchResult>>? = null

        try {
            search = async(Dispatchers.Default) {
                try {
                    provider.searchBooksProgressively(query) { update ->
                        if (
                            update.any { book ->
                                book.title == query && book.author == author && book.readingReady
                            }
                        ) {
                            published.complete(update)
                        }
                    }
                } catch (error: Throwable) {
                    searchFailure.set(error)
                    throw error
                }
            }

            assertTrue(
                "both tier-one searches must be inside non-cooperative synchronous work; " +
                    "seen=${searchFetcher.seenUrls()} completed=${search?.isCompleted} " +
                    "failure=${searchFailure.get()}",
                tierOneEntered.await(3, TimeUnit.SECONDS)
            )
            assertTrue(
                "tier two must start only after the late tier-one children have been cancelled",
                tierTwoEntered.await(6, TimeUnit.SECONDS)
            )

            releaseTierOne.countDown()
            assertTrue(
                "both late tier-one responses must return while the root search is still active",
                tierOneResponsesReturned.await(1, TimeUnit.SECONDS)
            )
            val update = withTimeoutOrNull(EARLY_PROGRESS_DEADLINE_MS) {
                published.await()
            }

            assertNotNull(
                "late exact candidates from cancelled wave children must wake root-owned progressive ranking " +
                    "before the ${ROOT_PROGRESS_POLL_INTERVAL_MS}ms fallback poll or the blocked tier settles",
                update
            )
        } finally {
            releaseTierOne.countDown()
            releaseTierTwo.countDown()
            search?.cancel()
            withTimeout(5_000L) {
                search?.join()
            }
        }
    }

    private fun searchSource(name: String, baseUrl: String): BookSource {
        return BookSource(
            sourceName = name,
            sourceUrl = baseUrl,
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

    private fun qualitySeed(
        tierOneSources: List<BookSource>,
        tierTwoSources: List<BookSource>
    ): SourceQualitySeed {
        val tierOne = tierOneSources.map { source -> source to 1 }
        val tierTwo = tierTwoSources.map { source -> source to 2 }
        return SourceQualitySeed.fromTsv(
            (tierOne + tierTwo).joinToString("\n") { (source, tier) ->
                val score = if (tier == 1) 10_000 else 6_000
                "source\t${source.sourceUrl}\t${source.sourceName}\t$tier\ttest\t$score\t0\t0\t0\t0\t0\ttest"
            }
        )
    }

    private fun searchEndpoint(source: BookSource): String {
        return "${source.sourceUrl}/modules/article/search.php"
    }

    private fun searchHtml(source: BookSource, title: String, author: String): String {
        return """
            <html><body><table><tbody>
              <tr><th>小说</th><th>最新章节</th><th>作者</th></tr>
              <tr>
                <td><a href="${source.sourceUrl}/books/1/">$title</a></td>
                <td><a href="/chapter/latest.html">第60章 正文</a></td>
                <td>$author</td>
              </tr>
            </tbody></table></body></html>
        """.trimIndent()
    }

    private fun readableBookResponses(
        sources: List<BookSource>,
        title: String,
        author: String
    ): Map<String, String> {
        return buildMap {
            sources.forEach { source ->
                put(
                    "${source.sourceUrl}/books/1/",
                    """
                        <html><body>
                          <div class="imgbox"><img src="file:///cover.jpg" /></div>
                          <div class="intro">$title 是 $author 创作的完整长篇小说。</div>
                          <div class="status">
                            <h1>$title</h1>
                            <p></p>
                            <p>作者：$author</p>
                            <p>分类：<a href="/category/1">仙侠</a></p>
                            <p>更新：<span class="red">2026-08-11</span></p>
                            <p>最新章节：<span class="red">第60章 正文</span></p>
                            <a class="button read" href="/book/1/">点击阅读</a>
                          </div>
                        </body></html>
                    """.trimIndent()
                )
                put("${source.sourceUrl}/book/1/", catalogHtml(source.sourceUrl))
            }
        }
    }

    private fun catalogHtml(baseUrl: String): String {
        val chapters = (1..60).joinToString("\n") { index ->
            """<li><a href="$baseUrl/book/1/$index.html">第${index}章 正文</a></li>"""
        }
        return "<html><body><ul class=\"mulu_list\">$chapters</ul></body></html>"
    }

    private class BlockingWaveSearchFetcher(
        private val tierOneResponses: Map<String, String>,
        private val tierTwoSearchUrls: Set<String>,
        private val releaseTierOne: CountDownLatch,
        private val releaseTierTwo: CountDownLatch,
        private val tierOneEntered: CountDownLatch,
        private val tierOneResponsesReturned: CountDownLatch,
        private val tierTwoEntered: CountDownLatch
    ) : HttpFetcher {
        private val seen = CopyOnWriteArrayList<String>()

        override fun fetch(request: HttpRequest): HttpResponse {
            seen.add(request.url)
            tierOneResponses[request.url]?.let { body ->
                tierOneEntered.countDown()
                releaseTierOne.await(10, TimeUnit.SECONDS)
                tierOneResponsesReturned.countDown()
                return HttpResponse(request.url, body)
            }
            if (request.url in tierTwoSearchUrls) {
                tierTwoEntered.countDown()
                releaseTierTwo.await(10, TimeUnit.SECONDS)
                return HttpResponse(request.url, EMPTY_SEARCH_HTML)
            }
            error("No search fixture response for ${request.url}")
        }

        fun seenUrls(): List<String> = seen.toList()
    }

    private class FixtureFetcher(
        private val responses: Map<String, String>
    ) : HttpFetcher {
        override fun fetch(request: HttpRequest): HttpResponse {
            val body = responses[request.url] ?: if (
                request.url.contains("/book/1/") && request.url.endsWith(".html")
            ) {
                READABLE_CHAPTER_HTML
            } else {
                error("No validation fixture response for ${request.url}")
            }
            return HttpResponse(request.url, body)
        }
    }

    private companion object {
        const val TIER_TWO_SOURCE_COUNT = 16
        const val TIER_ONE_SETTLE_TIMEOUT_MS = 100L
        const val ROOT_PROGRESS_POLL_INTERVAL_MS = 5_000L
        const val EARLY_PROGRESS_DEADLINE_MS = 1_500L
        const val EMPTY_SEARCH_HTML = "<html><body><table><tbody>" +
            "<tr><th>小说</th><th>最新章节</th><th>作者</th></tr>" +
            "</tbody></table></body></html>"
        val READABLE_CHAPTER_HTML = "<html><body><div id=\"content\">" +
            (
                "山河之间灵气流转，少年沿着古道继续修行。" +
                    "师友在宗门中商议远行，剑光与云海映照长夜。" +
                    "人物、场景与修行线索完整连贯，故事在此稳步推进。"
                ).repeat(12) +
            "</div></body></html>"
    }
}
