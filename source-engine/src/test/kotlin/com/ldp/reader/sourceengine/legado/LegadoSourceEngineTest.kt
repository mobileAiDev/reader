package com.ldp.reader.sourceengine.legado

import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegadoSourceEngineTest {
    @Test
    fun runsHtmlSearchDetailTocAndContentRules() {
        val source = BookSource(
            sourceName = "Fixture Source",
            sourceUrl = "https://m.ptwxz.org/",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "so/,{\"body\":\"searchkey={{key}}\",\"method\":\"POST\"}",
            ruleSearch = LegadoRuleSet(
                "ruleSearch",
                mapOf(
                    "bookList" to ".hot_sale",
                    "name" to "p.0@text",
                    "author" to "p.1@text",
                    "bookUrl" to "{{@@a@href##${'$'}##${'$'}index.html}}##^##https://www.ptwxz.org",
                    "intro" to "p.-1@text##简介："
                )
            ),
            ruleBookInfo = LegadoRuleSet(
                "ruleBookInfo",
                mapOf(
                    "name" to "[property$=book_name]@content",
                    "author" to "[property$=author]@content"
                )
            ),
            ruleToc = LegadoRuleSet(
                "ruleToc",
                mapOf(
                    "chapterList" to "#list@dl.1@dd@a",
                    "chapterName" to "text",
                    "chapterUrl" to "href"
                )
            ),
            ruleContent = LegadoRuleSet(
                "ruleContent",
                mapOf(
                    "content" to "#content@p!-1@html",
                    "nextContentUrl" to "text.下一页@href"
                )
            ),
            diagnostics = emptyList()
        )
        val fetcher = MapFetcher(
            mapOf(
                "https://m.ptwxz.org/so/" to """
                    <html><body>
                      <div class="hot_sale">
                        <a href="/ptwx/123/"><img data-original="/cover.jpg"/></a>
                        <p>斗破苍穹</p>
                        <p>天蚕土豆</p>
                        <p>简介：这里是简介</p>
                      </div>
                    </body></html>
                """.trimIndent(),
                "https://www.ptwxz.org/ptwx/123/index.html" to """
                    <html><head>
                      <meta property="og:novel:book_name" content="斗破苍穹"/>
                      <meta property="og:novel:author" content="天蚕土豆"/>
                    </head><body>
                      <div id="list">
                        <dl><dd><a href="/ignore.html">ignore</a></dd></dl>
                        <dl>
                          <dd><a href="/ptwx/123/1.html">第一章</a></dd>
                          <dd><a href="/ptwx/123/2.html">第二章</a></dd>
                        </dl>
                      </div>
                    </body></html>
                """.trimIndent(),
                "https://www.ptwxz.org/ptwx/123/1.html" to """
                    <html><body>
                      <div id="content">
                        <p>正文第一段</p>
                        <p>正文第二段</p>
                        <p>footer</p>
                      </div>
                    </body></html>
                """.trimIndent()
            )
        )
        val engine = LegadoSourceEngine(fetcher)

        val search = engine.search(listOf(source), "斗破苍穹", maxSources = 1)
        assertTrue(search is EngineResult.Success)
        val book = (search as EngineResult.Success).value.books.single()
        assertEquals("斗破苍穹", book.name)
        assertEquals("天蚕土豆", book.author)
        assertEquals("https://www.ptwxz.org/ptwx/123/index.html", book.bookUrl)

        val detail = engine.getBookDetail(book)
        assertTrue(detail is EngineResult.Success)
        assertEquals("斗破苍穹", (detail as EngineResult.Success).value.name)

        val chapters = engine.getChapterList(detail.value)
        assertTrue(chapters.toString(), chapters is EngineResult.Success)
        assertEquals(2, (chapters as EngineResult.Success).value.size)
        assertEquals("第一章", chapters.value[0].name)

        val canonical = engine.getCanonicalChapterList(detail.value)
        assertTrue(canonical is EngineResult.Success)
        assertEquals(2, (canonical as EngineResult.Success).value.chapters.size)
        assertEquals(1, canonical.value.chapters[0].ordinal)

        val content = engine.getContent(chapters.value[0])
        assertTrue(content is EngineResult.Success)
        assertEquals("正文第一段\n正文第二段", (content as EngineResult.Success).value)

        val cleanContent = engine.getCleanContent(chapters.value[0])
        assertTrue(cleanContent is EngineResult.Success)
        assertEquals("正文第一段\n正文第二段", (cleanContent as EngineResult.Success).value.cleanedContent)
        assertEquals(2, cleanContent.value.report.paragraphCount)
    }

    @Test
    fun runsJsonSearchRules() {
        val source = BookSource(
            sourceName = "JSON Source",
            sourceUrl = "https://api.example/",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search?q={{java.encodeURI(key)}}",
            ruleSearch = LegadoRuleSet(
                "ruleSearch",
                mapOf(
                    "bookList" to "$.data.search",
                    "name" to "$.book_name",
                    "author" to "$.author",
                    "bookUrl" to "$.book_detail_url"
                )
            ),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://api.example/search?q=%E6%B5%8B%E8%AF%95" to """
                        {"data":{"search":[{"book_name":"测试书","author":"作者","book_detail_url":"/book/1"}]}}
                    """.trimIndent()
                )
            )
        )

        val result = engine.search(listOf(source), "测试", maxSources = 1)

        assertTrue(result is EngineResult.Success)
        val books = (result as EngineResult.Success).value.books
        assertEquals(1, books.size)
        assertEquals("测试书", books[0].name)
        assertEquals("https://api.example/book/1", books[0].bookUrl)
    }

    private class MapFetcher(
        private val responses: Map<String, String>
    ) : HttpFetcher {
        override fun fetch(request: HttpRequest): HttpResponse {
            val body = responses[request.url]
                ?: error("No fixture response for ${request.url}")
            return HttpResponse(request.url, body)
        }
    }
}
