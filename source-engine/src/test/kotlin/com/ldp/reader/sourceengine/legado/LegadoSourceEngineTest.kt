package com.ldp.reader.sourceengine.legado

import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
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

    @Test
    fun followsFourContentPages() {
        val source = BookSource(
            sourceName = "Paged Content Fixture",
            sourceUrl = "https://example.org",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "",
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet(
                "ruleContent",
                mapOf(
                    "content" to "#content@p@textNodes",
                    "nextContentUrl" to "text.下一页@href"
                )
            ),
            diagnostics = emptyList()
        )
        val book = SourceBook(
            source = source,
            name = "分页书",
            author = "作者",
            bookUrl = "https://example.org/book/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        val chapter = SourceChapter(
            source = source,
            book = book,
            index = 0,
            name = "第670章 论天下英雄",
            chapterUrl = "https://example.org/book/1.html"
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://example.org/book/1.html" to pagedHtml("第一页正文", "/book/1_2.html"),
                    "https://example.org/book/1_2.html" to pagedHtml("第二页正文", "/book/1_3.html"),
                    "https://example.org/book/1_3.html" to pagedHtml("第三页正文", "/book/1_4.html"),
                    "https://example.org/book/1_4.html" to pagedHtml("第四页正文", null)
                )
            )
        )

        val content = engine.getContent(chapter)

        assertTrue(content is EngineResult.Success)
        assertEquals(
            "第一页正文\n第二页正文\n第三页正文\n第四页正文",
            (content as EngineResult.Success).value
        )
    }

    @Test
    fun supportsMultipleIndexedElementsInRule() {
        val source = BookSource(
            sourceName = "Indexed Fixture",
            sourceUrl = "https://example.org/",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search?q={{key}}",
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", mapOf("kind" to ".info@p.3:4:5@text")),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
        val book = com.ldp.reader.sourceengine.model.SourceBook(
            source = source,
            name = "测试书",
            author = "作者",
            bookUrl = "https://example.org/book/1",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://example.org/book/1" to """
                        <html><body>
                          <div class="info">
                            <p>书名</p><p>作者</p><p>封面</p>
                            <p>分类：仙侠</p><p>状态：连载</p><p>更新：今天</p>
                          </div>
                        </body></html>
                    """.trimIndent()
                )
            )
        )

        val detail = engine.getBookDetail(book)

        assertTrue(detail is EngineResult.Success)
        assertEquals("分类：仙侠\n状态：连载\n更新：今天", (detail as EngineResult.Success).value.kind)
    }

    @Test
    fun supportsStoredVariablesAndConfiguredTocRequests() {
        val source = BookSource(
            sourceName = "Variable Fixture",
            sourceUrl = "https://ixdzs8.com",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = mapOf("User-Agent" to "fixture"),
            searchUrl = "https://ixdzs8.com/bsearch?q={{key}}",
            ruleSearch = LegadoRuleSet(
                "ruleSearch",
                mapOf(
                    "bookList" to ".u-list li",
                    "name" to "h3@text",
                    "author" to ".author@text",
                    "bookUrl" to "h3@a@href"
                )
            ),
            ruleBookInfo = LegadoRuleSet(
                "ruleBookInfo",
                mapOf(
                    "init" to """@put:{n:"[property$=book_name]@content",a:"[property$=author]@content"}""",
                    "name" to "@get:{n}",
                    "author" to "@get:{a}",
                    "tocUrl" to """
                        {{java.put("url",baseUrl);
                            "https://ixdzs8.com/novel/clist/"}},{
                          "body": "bid={{baseUrl.match(/(\d+).$/)[1]}}",
                          "headers": {"X-Requested-With": "XMLHttpRequest"},
                          "method": "POST"
                        }
                    """.trimIndent()
                )
            ),
            ruleToc = LegadoRuleSet(
                "ruleToc",
                mapOf(
                    "chapterList" to "$.data",
                    "chapterName" to "$.title",
                    "chapterUrl" to "@get:{url}p{{$.ordernum}}.html"
                )
            ),
            ruleContent = LegadoRuleSet(
                "ruleContent",
                mapOf("content" to "article.page-content section>p@textNodes")
            ),
            diagnostics = emptyList()
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://ixdzs8.com/bsearch?q=%E4%BD%95%E4%BB%A5%E7%AC%99%E7%AE%AB%E9%BB%98" to """
                        <html><body>
                          <ul class="u-list">
                            <li><h3><a href="/read/1215/">何以笙箫默</a></h3><p class="author">顾漫</p></li>
                          </ul>
                        </body></html>
                    """.trimIndent(),
                    "https://ixdzs8.com/read/1215/" to """
                        <html><head>
                          <meta property="og:novel:book_name" content="何以笙箫默"/>
                          <meta property="og:novel:author" content="顾漫"/>
                        </head><body></body></html>
                    """.trimIndent(),
                    "POST https://ixdzs8.com/novel/clist/ body=bid=1215" to """
                        {"rs":200,"data":[{"ordernum":"1","title":"第一章 重逢"},{"ordernum":"2","title":"第二章 转身"}]}
                    """.trimIndent(),
                    "https://ixdzs8.com/read/1215/p1.html" to """
                        <html><body><article class="page-content"><section>
                          <p>第一章正文</p><p>第二段正文</p>
                        </section></article></body></html>
                    """.trimIndent()
                )
            )
        )

        val search = engine.search(listOf(source), "何以笙箫默", maxSources = 1)
        assertTrue(search is EngineResult.Success)
        val book = (search as EngineResult.Success).value.books.single()
        val detail = engine.getBookDetail(book)
        assertTrue(detail is EngineResult.Success)
        assertEquals("何以笙箫默", (detail as EngineResult.Success).value.name)

        val chapters = engine.getChapterList(detail.value)
        assertTrue(chapters.toString(), chapters is EngineResult.Success)
        assertEquals(2, (chapters as EngineResult.Success).value.size)
        assertEquals("https://ixdzs8.com/read/1215/p1.html", chapters.value.first().chapterUrl)

        val content = engine.getContent(chapters.value.first())
        assertTrue(content is EngineResult.Success)
        assertEquals("第一章正文\n第二段正文", (content as EngineResult.Success).value)
    }

    @Test
    fun runsPageBasedCatalogSourceRules() {
        val source = BookSource(
            sourceName = "52shuku Fixture",
            sourceUrl = "https://www.52shuku.net",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = mapOf("User-Agent" to "Mozilla/5.0"),
            searchUrl = "https://www.52shuku.net/so/search.php?q={{key}}",
            ruleSearch = LegadoRuleSet(
                "ruleSearch",
                mapOf(
                    "bookList" to ".excerpt",
                    "name" to """h4@text##^\d+\.\s*|_.*$##""",
                    "author" to """h4@text##.*_([^【]+).*##$1##""",
                    "bookUrl" to "a.0@href",
                    "intro" to ".note@text"
                )
            ),
            ruleBookInfo = LegadoRuleSet(
                "ruleBookInfo",
                mapOf(
                    "name" to """h1@text##_.*$##""",
                    "author" to """h1@text##.*_([^【]+).*##$1##""",
                    "intro" to "article.article-content@p.0:1@text"
                )
            ),
            ruleToc = LegadoRuleSet(
                "ruleToc",
                mapOf(
                    "chapterList" to ".list.clearfix a",
                    "chapterName" to "text",
                    "chapterUrl" to "href"
                )
            ),
            ruleContent = LegadoRuleSet(
                "ruleContent",
                mapOf("content" to "article.article-content@p@textNodes")
            ),
            diagnostics = emptyList()
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://www.52shuku.net/so/search.php?q=%E4%BD%95%E4%BB%A5%E7%AC%99%E7%AE%AB%E9%BB%98" to """
                        <html><body>
                          <article class="excerpt">
                            <header><h2><a href="https://www.52shuku.net/bl/huNu.html">
                              <h4>1. 何以笙箫默同人之做你的阳光_yhablt【完结】</h4>
                            </a></h2></header>
                            <span class="note">同人续写 <a href="https://www.52shuku.net/bl/">栏目</a></span>
                          </article>
                          <article class="excerpt">
                            <header><h2><a href="https://www.52shuku.net/xiandaidushi/1889.html">
                              <h4>5. 何以笙箫默_顾漫【完结+番外】</h4>
                            </a></h2></header>
                            <span class="note">《何以笙箫默》作者：顾漫 <a href="https://www.52shuku.net/xiandaidushi/">栏目</a></span>
                          </article>
                        </body></html>
                    """.trimIndent(),
                    "https://www.52shuku.net/xiandaidushi/1889.html" to """
                        <html><body>
                          <h1 class="article-title">何以笙箫默_顾漫【完结+番外】</h1>
                          <article class="article-content">
                            <p>小说简介：</p>
                            <p>《何以笙箫默》作者：顾漫</p>
                            <ul class="list clearfix">
                              <li class="mulu"><a href="https://www.52shuku.net/xiandaidushi/1889_2.html">第1页</a></li>
                              <li class="mulu"><a href="https://www.52shuku.net/xiandaidushi/1889_3.html">第2页</a></li>
                            </ul>
                          </article>
                        </body></html>
                    """.trimIndent(),
                    "https://www.52shuku.net/xiandaidushi/1889_2.html" to """
                        <html><body>
                          <article class="article-content" id="nr1">
                            <p>第一段正文内容足够稳定。</p>
                            <p>第二段正文内容继续阅读。</p>
                          </article>
                        </body></html>
                    """.trimIndent()
                )
            )
        )

        val search = engine.search(listOf(source), "何以笙箫默", maxSources = 1)
        assertTrue(search is EngineResult.Success)
        val exact = (search as EngineResult.Success).value.books.single { it.name == "何以笙箫默" }
        assertEquals("顾漫", exact.author)

        val detail = engine.getBookDetail(exact)
        assertTrue(detail is EngineResult.Success)
        assertEquals("何以笙箫默", (detail as EngineResult.Success).value.name)
        assertEquals("顾漫", detail.value.author)

        val chapters = engine.getChapterList(detail.value)
        assertTrue(chapters is EngineResult.Success)
        assertEquals(2, (chapters as EngineResult.Success).value.size)
        assertEquals("第1页", chapters.value.first().name)

        val content = engine.getContent(chapters.value.first())
        assertTrue(content is EngineResult.Success)
        assertEquals("第一段正文内容足够稳定。\n第二段正文内容继续阅读。", (content as EngineResult.Success).value)
    }

    @Test
    fun parsesChangduCatalogFromCurrentDeviceHtmlShape() {
        val source = BookSource(
            sourceName = "55读书",
            sourceUrl = "https://www.changduzw.com#未月十八repair",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "https://www.changduzw.com/modules/article/search.php",
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet(
                "ruleBookInfo",
                mapOf(
                    "author" to "class.status@tag.p.1@text",
                    "coverUrl" to "class.imgbox@tag.img@src",
                    "intro" to "class.con@tag.p@text",
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
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
        val book = com.ldp.reader.sourceengine.model.SourceBook(
            source = source,
            name = "玄鉴仙族",
            author = "季越人",
            bookUrl = "https://www.changduzw.com/books/225601/",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        val engine = LegadoSourceEngine(
            MapFetcher(
                mapOf(
                    "https://www.changduzw.com/books/225601/" to """
                        <html><body>
                          <div class="imgbox">
                            <img src="/qiji/images/lazy.gif" data-src="https://www.changduzw.com/modules/article/images/nocover.jpg"/>
                          </div>
                          <div class="status">
                            <h1>玄鉴仙族</h1>
                            <p class="bz"></p>
                            <p class="author">作者：季越人</p>
                            <p>分　类：<a href="/fenlei/2_1/">仙侠修真</a></p>
                            <p>更　新：<span class="red">2026-05-19</span></p>
                            <p>最新章节：<a href="/book/225/225601/81130443.html"><span class="red">第1497章 庙语</span></a></p>
                            <a class="button read" href="/book/225/225601/">点击阅读</a>
                          </div>
                        </body></html>
                    """.trimIndent(),
                    "https://www.changduzw.com/book/225/225601/" to """
                        <html><body>
                          <ul class="mulu_list">
                            <li><a href="/book/225/225601/75191260.html" title="第1章 初入">第1章 初入</a></li>
                            <li><a href="/book/225/225601/75191261.html" title="第2章 李家">第2章 李家</a></li>
                            <li><a href="/book/225/225601/75191262.html" title="第3章 鉴子">第3章 鉴子</a></li>
                            <li><a href="/book/225/225601/75191263.html" title="第4章 李叶盛">第4章 李叶盛</a></li>
                            <li><a href="/book/225/225601/75191264.html" title="第5章 仙缘难得">第5章 仙缘难得</a></li>
                            <li><a href="/book/225/225601/75191265.html" title="第6章 玉石">第6章 玉石</a></li>
                          </ul>
                        </body></html>
                    """.trimIndent()
                )
            )
        )

        val detail = engine.getBookDetail(book)
        assertTrue(detail is EngineResult.Success)
        assertEquals("https://www.changduzw.com/book/225/225601/", (detail as EngineResult.Success).value.tocUrl)

        val chapters = engine.getCanonicalChapterList(detail.value)

        assertTrue(chapters is EngineResult.Success)
        assertEquals(6, (chapters as EngineResult.Success).value.chapters.size)
        assertEquals("第1章 初入", chapters.value.chapters.first().displayTitle)
    }

    private fun pagedHtml(content: String, nextUrl: String?): String {
        val nextLink = nextUrl?.let { """<a href="$it">下一页</a>""" }.orEmpty()
        return """
            <html><body>
              <div id="content"><p>$content</p></div>
              $nextLink
            </body></html>
        """.trimIndent()
    }

    private class MapFetcher(
        private val responses: Map<String, String>
    ) : HttpFetcher {
        override fun fetch(request: HttpRequest): HttpResponse {
            val configuredKey = "${request.method} ${request.url} body=${request.body.orEmpty()}"
            val body = responses[configuredKey]
                ?: responses[request.url]
                ?: error("No fixture response for ${request.url}")
            return HttpResponse(request.url, body)
        }
    }
}
