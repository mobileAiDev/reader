package com.ldp.reader.media

import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import com.ldp.reader.media.legado.MediaHttpResponse
import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaExtractorTest {
    @Test
    fun comicExtractorReadsImageTagsAndDataSrc() {
        val pages = ComicPageExtractor.extract(
            """
            <div>
              <img data-src="https://img.example/1.jpg">
              <img src="https://img.example/2.webp">
              <img src="https://img.example/2.webp">
            </div>
            """.trimIndent()
        )

        assertEquals(listOf("https://img.example/1.jpg", "https://img.example/2.webp"), pages)
    }

    @Test
    fun comicExtractorReadsPlainImageLines() {
        val pages = ComicPageExtractor.extract(
            """
            https://img.example/1.jpg
            ignored text
            https://img.example/2.png?token=1
            """.trimIndent()
        )

        assertEquals(listOf("https://img.example/1.jpg", "https://img.example/2.png?token=1"), pages)
    }

    @Test
    fun comicExtractorIgnoresPlaceholderImages() {
        val pages = ComicPageExtractor.extract(
            """
            <div>
              <img src="/static/boodo/img/load.gif" class="reader-pic-pending">
              <img src="https://img.example/real.webp">
            </div>
            """.trimIndent()
        )

        assertEquals(listOf("https://img.example/real.webp"), pages)
    }

    @Test
    fun comicExtractorReadsEmbeddedImageUrls() {
        val pages = ComicPageExtractor.extract(
            """{"images":[{"url":"https://img.example/1.webp"},{"url":"https://img.example/load.gif"}]}"""
        )

        assertEquals(listOf("https://img.example/1.webp"), pages)
    }

    @Test
    fun comicExtractorPreservesLegadoImageHeaders() {
        val pages = ComicPageExtractor.extractRequests(
            """<img src="https://img.example/1.webp,{"headers":{"Referer":"https://comic.example/chapter/1"}}" />""",
            baseUrl = "https://comic.example/chapter/1"
        )

        assertEquals("https://img.example/1.webp", pages.single().url)
        assertEquals("https://comic.example/chapter/1", pages.single().headers["Referer"])
    }

    @Test
    fun comicExtractorPreservesLegadoHeadersWhenImageRefererDiffersFromBase() {
        val pages = ComicPageExtractor.extractRequests(
            """
            <img src="https://f40-1-4.g-mh.online/scomic/fanrenxiuxianchuan/0/1.webp,{"headers":{"User-Agent":"SunnyUA","referer":"https://manhuafree.com/"}}">
            <img src="https://f40-1-4.g-mh.online/scomic/fanrenxiuxianchuan/0/2.webp,{"headers":{"User-Agent":"SunnyUA","referer":"https://manhuafree.com/"}}">
            """.trimIndent(),
            baseUrl = "https://v1.gyks.cf/content?item_id=MTkyXzM4NDcxNA&version=4.6.29"
        )

        assertEquals(
            listOf(
                "https://f40-1-4.g-mh.online/scomic/fanrenxiuxianchuan/0/1.webp",
                "https://f40-1-4.g-mh.online/scomic/fanrenxiuxianchuan/0/2.webp"
            ),
            pages.map { it.url }
        )
        assertEquals("SunnyUA", pages.first().headers["User-Agent"])
        assertEquals("https://manhuafree.com/", pages.first().headers["referer"])
    }

    @Test
    fun comicExtractorResolvesRelativeImagesWithReferer() {
        val pages = ComicPageExtractor.extractRequests(
            """<img data-src="/images/1.jpg">""",
            baseUrl = "https://comic.example/chapter/1",
            defaultHeaders = mapOf("Cookie" to "sid=1")
        )

        assertEquals("https://comic.example/images/1.jpg", pages.single().url)
        assertEquals("https://comic.example/chapter/1", pages.single().headers["Referer"])
        assertEquals("sid=1", pages.single().headers["Cookie"])
    }

    @Test
    fun requestParserResolvesRelativeUrlWhenBaseQueryContainsChinese() {
        val url = MediaRequestParser.resolveUrl(
            baseUrl = "https://m.shuyinfm.com/e/search/?searchget=1&keyboard=爱时光音社",
            rawUrl = "/album/2-22810.html"
        )

        assertEquals("https://m.shuyinfm.com/album/2-22810.html", url)
    }

    @Test
    fun mediaContentAdapterWrapsLegadoSplitLineImagesWithReferer() {
        val chapter = comicChapter(
            contentRule = """
            #imgsec@img@data-src@js:
            headers={"headers":{"Referer":baseUrl}}
            result.split("\n").map(x=>'<img src="'+x+','+JSON.stringify(headers)+'">').join("\n")
            """.trimIndent(),
            chapterUrl = "https://comic.example/chapter/1"
        )

        val adapted = MediaContentJsAdapter.adaptComicRawContent(
            rawContent = """
            https://img.example/1.jpg
            https://img.example/2.webp
            """.trimIndent(),
            chapter = chapter
        )
        val pages = ComicPageExtractor.extractRequests(adapted, chapter.chapterUrl, chapter.source.headers)

        assertEquals(listOf("https://img.example/1.jpg", "https://img.example/2.webp"), pages.map { it.url })
        assertEquals("https://comic.example/chapter/1", pages.first().headers["Referer"])
    }

    @Test
    fun mediaContentAdapterDoesNotRewrapLegadoImageMarkup() {
        val chapter = comicChapter(
            contentRule = """
            amp-img@img@src@js:
            headers={"headers":{"Referer":baseUrl}}
            result.split("\n").map(x=>'<img src="'+x+','+JSON.stringify(headers)+'">').join("\n")
            """.trimIndent(),
            chapterUrl = "https://comic.example/chapter/1"
        )

        val adapted = MediaContentJsAdapter.adaptComicRawContent(
            rawContent = """
            <img src="https://img.example/1.jpg,{"headers":{"Referer":"https://comic.example/chapter/1"}}">
            <img src="https://img.example/2.webp,{"headers":{"Referer":"https://comic.example/chapter/1"}}">
            """.trimIndent(),
            chapter = chapter
        )
        val pages = ComicPageExtractor.extractRequests(adapted, chapter.chapterUrl, chapter.source.headers)

        assertEquals(listOf("https://img.example/1.jpg", "https://img.example/2.webp"), pages.map { it.url })
        assertEquals("https://comic.example/chapter/1", pages.first().headers["Referer"])
    }

    @Test
    fun mediaContentAdapterExpandsJsonUrlImageArrays() {
        val chapter = comicChapter(
            contentRule = """
            ${'$'}..images.images
            @js:
            let u=result;
            const g="https://f40-1-4.g-mh.online";
            let n=JSON.parse(u).map(i=>`<img src="${'$'}{g}${'$'}{i.url}">`).join('\n')
            n
            """.trimIndent(),
            chapterUrl = "https://m.g-mh.org/api/chapter/1"
        )

        val adapted = MediaContentJsAdapter.adaptComicRawContent(
            rawContent = """[{"url":"/images/1.webp"},{"url":"/images/loading.gif"}]""",
            chapter = chapter
        )
        val pages = ComicPageExtractor.extractRequests(adapted, chapter.chapterUrl, chapter.source.headers)

        assertEquals(listOf("https://f40-1-4.g-mh.online/images/1.webp"), pages.map { it.url })
    }

    @Test
    fun comicLazyImageResolverReadsReaderPicsBatchProtocol() {
        val html = """
            <script>
            let read={aid:'4619',cid:'2089194',apiCid:'2089194',picCount:17}
            </script>
            <div id="imgsec">
              <figure class="reader-pic-slot" data-chapter-id="2089194" data-aid="4619" data-pic-index="0"></figure>
            </div>
        """.trimIndent()
        val fetcher = object : MediaHttpFetcher {
            override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                assertEquals("https://comic.example/api/comic/read/pics", request.url)
                assertEquals("POST", request.method)
                assertEquals("https://comic.example/chapter/1", request.headers["Referer"])
                assertEquals("id=2089194&aid=4619&offset=0&limit=2", request.body)
                return MediaHttpResponse(
                    request.url,
                    """
                    {
                      "code": 1,
                      "data": {
                        "pic": [
                          {"pic": "https:\/\/img.example\/1.webp"},
                          {"pic": "https:\/\/img.example\/2.webp"}
                        ],
                        "offset": 0,
                        "limit": 2,
                        "total": 17
                      }
                    }
                    """.trimIndent()
                )
            }
        }

        val pages = ComicLazyImageResolver.resolveRequests(
            pageHtml = html,
            pageUrl = "https://comic.example/chapter/1",
            fetcher = fetcher,
            defaultHeaders = emptyMap(),
            maxPages = 2
        )

        assertEquals(listOf("https://img.example/1.webp", "https://img.example/2.webp"), pages.map { it.url })
        assertEquals("https://comic.example/chapter/1", pages.first().headers["Referer"])
    }

    @Test
    fun audioExtractorPrefersAudioTags() {
        val url = AudioUrlExtractor.extract(
            """
            <audio><source src="https://audio.example/1.m4a"></audio>
            https://audio.example/fallback.mp3
            """.trimIndent()
        )

        assertEquals("https://audio.example/1.m4a", url)
    }

    @Test
    fun audioExtractorFindsAudioUrlInText() {
        assertEquals(
            "https://audio.example/playlist.m3u8",
            AudioUrlExtractor.extract("播放地址 https://audio.example/playlist.m3u8")
        )
    }

    @Test
    fun audioExtractorReadsJsonWrappedPlaybackUrl() {
        val raw = """
            {
              "code": 200,
              "data": {
                "format": "m4a",
                "url": "http:\/\/audio.example\/book\/chapter.m4a?token=1"
              }
            }
        """.trimIndent()

        assertEquals(
            "http://audio.example/book/chapter.m4a?token=1",
            AudioUrlExtractor.extract(raw)
        )
    }

    @Test
    fun audioExtractorPreservesLegadoPlaybackHeaders() {
        val request = AudioUrlExtractor.extractRequest(
            """https://audio.example/book/1.m4a,{"headers":{"Referer":"https://audio.example/play/1"}}"""
        )

        assertEquals("https://audio.example/book/1.m4a", request?.url)
        assertEquals("https://audio.example/play/1", request?.headers?.get("Referer"))
    }

    @Test
    fun audioExtractorPreservesEmbeddedLegadoPlaybackHeaders() {
        val request = AudioUrlExtractor.extractRequest(
            """play('https://audio.example/book/1.m4a,{"headers":{"Referer":"https://audio.example/play/1"}}')"""
        )

        assertEquals("https://audio.example/book/1.m4a", request?.url)
        assertEquals("https://audio.example/play/1", request?.headers?.get("Referer"))
    }

    @Test
    fun audioExtractorTrimsTrailingTextPunctuation() {
        val request = AudioUrlExtractor.extractRequest("播放 https://audio.example/book/1.mp3。")

        assertEquals("https://audio.example/book/1.mp3", request?.url)
    }

    @Test
    fun audioPlaybackResolverAddsPlayableRequestHeaders() {
        val request = AudioPlaybackUrlResolver.resolveRequest(
            rawContent = "播放 https://cdn.example/book/1.mp3",
            pageUrl = "https://m.example.com/book/1",
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse = error("No network expected")
            },
            headers = mapOf("Cookie" to "sid=1")
        )

        assertEquals("https://cdn.example/book/1.mp3", request?.url)
        assertEquals("https://m.example.com/book/1", request?.headers?.get("Referer"))
        assertEquals(MediaPlaybackHeaders.MOBILE_USER_AGENT, request?.headers?.get("User-Agent"))
        assertEquals("sid=1", request?.headers?.get("Cookie"))
    }

    @Test
    fun audioPlaybackResolverReplacesBlockedPlaybackUserAgent() {
        val request = AudioPlaybackUrlResolver.resolveRequest(
            rawContent = "https://cdn.example/book/1.mp3",
            pageUrl = "https://m.example.com/book/1",
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse = error("No network expected")
            },
            headers = mapOf("User-Agent" to "okhttp/3.10.0")
        )

        assertEquals(MediaPlaybackHeaders.MOBILE_USER_AGENT, request?.headers?.get("User-Agent"))
    }

    @Test
    fun audioTlsPolicyAcceptsKnownAliyunCdnAliasOnly() {
        assertTrue(
            MediaPlaybackTlsPolicy.acceptsKnownAudioCdnAlias(
                host = "pp.ting55.com",
                certificateDnsNames = listOf("*.aliyun.com", "*.alicdn.com")
            )
        )
    }

    @Test
    fun audioTlsPolicyRejectsUnknownHostsAndCertificates() {
        assertFalse(
            MediaPlaybackTlsPolicy.acceptsKnownAudioCdnAlias(
                host = "cdn.example.com",
                certificateDnsNames = listOf("*.aliyun.com")
            )
        )
        assertFalse(
            MediaPlaybackTlsPolicy.acceptsKnownAudioCdnAlias(
                host = "pp.ting55.com",
                certificateDnsNames = listOf("*.example.com")
            )
        )
    }

    @Test
    fun audioExtractorSkipsUnsupportedWmaPlaybackUrl() {
        val raw = """{"data":{"url":"http:\/\/audio.example\/book\/chapter.wma?token=1"}}"""

        assertNull(AudioUrlExtractor.extract(raw))
    }

    @Test
    fun audioExtractorRejectsKuwoCopyrightError() {
        val raw = """
            {
              "code":407,
              "data":{"format":"None","sig":"None","url":"None"},
              "msg":"This resource is not available in your region or country due to copyright protection"
            }
        """.trimIndent()

        assertNull(AudioUrlExtractor.extract(raw))
    }

    @Test
    fun audioExtractorAcceptsPlayableMp4Container() {
        val raw = """{"content":"https:\/\/cdn.example\/audio\/chapter.mp4?token=1"}"""

        assertEquals("https://cdn.example/audio/chapter.mp4?token=1", AudioUrlExtractor.extract(raw))
    }

    @Test
    fun audioExtractorRejectsQuotaOrLoginPageText() {
        val raw = "https://v1.gyks.cf/您今日免登录访问次数已达上限(3次)！继续阅读请登录后刷新页面。"

        assertNull(AudioUrlExtractor.extract(raw))
    }

    @Test
    fun audioExtractorRejectsKnownSourcePlaceholderAudio() {
        assertNull(
            AudioUrlExtractor.extract("https://music.163.com/song/media/outer/url?id=1817544979")
        )
    }

    @Test
    fun audioPlaybackResolverReadsSignedDataCodePlaybackUrl() {
        val html = """<ul id="jp-lines"><li data-code="abc+/=">line:0</li></ul>"""
        val fetcher = object : MediaHttpFetcher {
            override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                return when {
                    request.method == "POST" && request.url == "https://m.shuyinfm.com/e/extend/url.php" -> {
                        assertEquals("code=abc%2B%2F%3D&timestamp=1000", request.body)
                        MediaHttpResponse(request.url, """{"signature":"sig+/=","uuid":"uuid"}""")
                    }
                    request.url == "https://m.shuyinfm.com/e/extend/url.php?code=abc%2B%2F%3D&timestamp=1000&signature=sig%2B%2F%3D&uuid=uuid" -> {
                        MediaHttpResponse(request.url, """{"url":"https:\/\/cdn.example\/book\/1.m4a"}""")
                    }
                    else -> error("Unexpected request ${request.method} ${request.url} body=${request.body}")
                }
            }
        }

        val url = AudioPlaybackUrlResolver.resolve(
            rawContent = html,
            pageUrl = "https://m.shuyinfm.com/audio/2-1-0.html",
            fetcher = fetcher,
            headers = emptyMap(),
            timestampSeconds = 1_000L
        )

        assertEquals("https://cdn.example/book/1.m4a", url)
    }

    @Test
    fun audioPlaybackResolverReadsSignedPlaybackUrlFromPageFallback() {
        val fetcher = object : MediaHttpFetcher {
            override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                return when {
                    request.method == "GET" && request.url == "https://m.example.com/audio/1.html" -> {
                        MediaHttpResponse(request.url, """<li data-code="abc+/=">line:0</li>""")
                    }
                    request.method == "POST" && request.url == "https://m.example.com/e/extend/url.php" -> {
                        assertEquals("code=abc%2B%2F%3D&timestamp=1000", request.body)
                        MediaHttpResponse(request.url, """{"signature":"sig","uuid":"uuid"}""")
                    }
                    request.url == "https://m.example.com/e/extend/url.php?code=abc%2B%2F%3D&timestamp=1000&signature=sig&uuid=uuid" -> {
                        MediaHttpResponse(request.url, """{"url":"https:\/\/cdn.example\/book\/1.mp3"}""")
                    }
                    else -> error("Unexpected request ${request.method} ${request.url} body=${request.body}")
                }
            }
        }

        val request = AudioPlaybackUrlResolver.resolveRequestFromPage(
            pageUrl = "https://m.example.com/audio/1.html",
            fetcher = fetcher,
            headers = emptyMap(),
            timestampSeconds = 1_000L
        )

        assertEquals("https://cdn.example/book/1.mp3", request?.url)
    }

    @Test
    fun audioExtractorReturnsNullForBlankContent() {
        assertNull(AudioUrlExtractor.extract("   "))
    }

    @Test
    fun mediaDisplayCleanerDropsRuleScriptFragments() {
        assertEquals(
            "",
            MediaDisplayTextCleaner.clean("<js> function fq_last() { return java.ajax(url); } </js>")
        )
        assertEquals("", MediaDisplayTextCleaner.clean("@js:result"))
        assertEquals("", MediaDisplayTextCleaner.clean("{{eval source.bookSourceUrl}}"))
    }

    @Test
    fun mediaDisplayCleanerKeepsReadableMetadata() {
        assertEquals(
            "第12话 新的伙伴",
            MediaDisplayTextCleaner.clean("<p>第12话&nbsp;新的伙伴</p>")
        )
    }

    @Test
    fun mediaDisplayCleanerDecodesVisibleEscapes() {
        assertEquals(
            "庆余年 & 第二季",
            MediaDisplayTextCleaner.clean("""\u5e86\u4f59\u5e74 \u0026amp; \u7b2c\u4e8c\u5b63""")
        )
        assertEquals(
            "斗罗大陆 唐三",
            MediaDisplayTextCleaner.clean("&lt;p&gt;斗罗大陆&#32;唐三&lt;/p&gt;")
        )
        assertEquals(
            "斗罗大陆&唐家三少 | 第2847集 | 唐门外门弟子；这里没有魔法",
            MediaDisplayTextCleaner.clean("""斗罗大陆\\&唐家三少 | 第2847集 | 唐门外门弟子\\ ;这里没有魔法""")
        )
        assertEquals(
            "\"伴随着魂导科技的进步\"",
            MediaDisplayTextCleaner.clean("""\\"伴随着魂导科技的进步\\"""")
        )
    }

    private fun comicChapter(contentRule: String, chapterUrl: String): MediaSourceChapter {
        val source = MediaSourceDefinition(
            sourceName = "fixture comic",
            sourceUrl = "https://comic.example",
            sourceType = MediaSourceType.COMIC,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search?q={{key}}",
            ruleSearch = MediaLegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = MediaLegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = MediaLegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = MediaLegadoRuleSet("ruleContent", mapOf("content" to contentRule)),
            diagnostics = emptyList()
        )
        val book = MediaSourceBook(
            source = source,
            name = "凡人修仙传",
            author = "",
            bookUrl = "https://comic.example/book/1",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        return MediaSourceChapter(
            source = source,
            book = book,
            index = 0,
            name = "第一话",
            chapterUrl = chapterUrl
        )
    }
}
