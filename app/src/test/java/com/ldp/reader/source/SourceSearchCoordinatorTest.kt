package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.HttpFetcher
import com.ldp.reader.sourceengine.legado.HttpRequest
import com.ldp.reader.sourceengine.legado.HttpResponse
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.legado.LegadoSourceEngine
import com.ldp.reader.sourceengine.model.BookSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SourceSearchCoordinatorTest {
    @Test
    fun progressiveSearchStartsItsWaveWhileEveryForegroundWorkerIsBusy() = runBlocking {
        val foregroundWorkers = staticInt(SourceNetworkDispatchers::class.java, "FOREGROUND_THREADS")
        assertEquals(16, foregroundWorkers)

        val waveStarted = CountDownLatch(1)
        val source = searchSource("https://search-coordinator.example")
        val engine = LegadoSourceEngine(WaveStartFetcher(waveStarted))
        val provider = SourceEngineReaderContentProvider(
            engine = engine,
            searchEngine = engine,
            detailProbeEngine = engine,
            sourceProvider = { listOf(source) },
            sourceFinder = { source },
            sourceQualityRouter = SourceQualityRouter(
                storage = InMemorySourceQualityStorage(),
                seed = SourceQualitySeed.empty()
            )
        )
        val blockersEntered = CountDownLatch(foregroundWorkers)
        val releaseBlockers = CountDownLatch(1)
        val blockers = List(foregroundWorkers) {
            async(SourceNetworkDispatchers.foreground) {
                blockersEntered.countDown()
                releaseBlockers.await(10, TimeUnit.SECONDS)
            }
        }
        var search: Deferred<*>? = null

        try {
            assertTrue(
                "all foreground workers must be occupied before search starts",
                blockersEntered.await(2, TimeUnit.SECONDS)
            )
            search = async(start = CoroutineStart.UNDISPATCHED) {
                provider.searchBooksProgressively("调度饥饿测试书") {}
            }

            assertTrue(
                "progressive search must start its source wave without waiting for shared foreground workers",
                waveStarted.await(1, TimeUnit.SECONDS)
            )
        } finally {
            search?.cancel()
            releaseBlockers.countDown()
            withTimeout(5_000L) {
                blockers.forEach { blocker -> blocker.join() }
                search?.join()
            }
        }
    }

    @Test
    fun searchValidationConcurrencyLeavesFourForegroundWorkersAvailable() {
        val foregroundWorkers = staticInt(SourceNetworkDispatchers::class.java, "FOREGROUND_THREADS")
        val maxValidations = staticInt(
            SourceEngineReaderContentProvider::class.java,
            "MAX_CONCURRENT_VALIDATIONS"
        )

        assertTrue(
            "search validation must leave at least four foreground workers available; " +
                "foreground=$foregroundWorkers validations=$maxValidations",
            maxValidations <= foregroundWorkers - 4
        )
    }

    private fun staticInt(owner: Class<*>, fieldName: String): Int {
        return owner.getDeclaredField(fieldName).run {
            isAccessible = true
            getInt(null)
        }
    }

    private fun searchSource(baseUrl: String): BookSource {
        return BookSource(
            sourceName = "协调器测试源",
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
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
    }

    private class WaveStartFetcher(
        private val waveStarted: CountDownLatch
    ) : HttpFetcher {
        override fun fetch(request: HttpRequest): HttpResponse {
            waveStarted.countDown()
            return HttpResponse(request.url, EMPTY_SEARCH_HTML)
        }
    }

    private companion object {
        const val EMPTY_SEARCH_HTML = "<html><body><table><tbody>" +
            "<tr><th>小说</th><th>最新章节</th><th>作者</th></tr>" +
            "</tbody></table></body></html>"
    }
}
