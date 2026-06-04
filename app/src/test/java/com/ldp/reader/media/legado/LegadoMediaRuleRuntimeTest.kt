package com.ldp.reader.media.legado

import com.ldp.reader.media.MediaEngineResult
import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import com.ldp.reader.media.legado.MediaHttpResponse
import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LegadoMediaRuleRuntimeTest {
    @Test
    fun searchRunsScriptRequestAndJsonArrayBookList() {
        val source = source(
            searchUrl = """
                <js>
                method = 'POST';
                body = 'keyword=' + encodeURIComponent(key);
                url = '/api/search';
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "<js>JSON.parse(result).items</js>",
                "name" to "title",
                "bookUrl" to "url",
                "coverUrl" to "cover"
            )
        )
        val requests = ArrayList<MediaHttpRequest>()
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    requests.add(request)
                    assertEquals("POST", request.method)
                    assertEquals("keyword=%E5%87%A1%E4%BA%BA", request.body)
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1","cover":"/cover.jpg"}]}"""
                    )
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("https://audio.example/api/search", requests.single().url)
        assertEquals("凡人修仙传", report.books.single().name)
        assertEquals("https://audio.example/book/1", report.books.single().bookUrl)
        assertEquals("https://audio.example/cover.jpg", report.books.single().coverUrl)
    }

    @Test
    fun searchUsesScriptReturnedRequestBeforeTemporaryScopeVariables() {
        val source = source(
            searchUrl = """
                <js>
                body = 'keyword=' + encodeURIComponent(key);
                url = '/api/search?' + body;
                sign = java.md5Encode(body + 'secret');
                url + ',' + JSON.stringify({headers:{signature:String(sign)}})
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val requests = ArrayList<MediaHttpRequest>()
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    requests.add(request)
                    assertEquals("GET", request.method)
                    assertEquals(null, request.body)
                    assertEquals("668b07e9f8d4c58efcb736144d1e861c", request.headers["signature"])
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                    )
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("https://audio.example/api/search?keyword=%E5%87%A1%E4%BA%BA", requests.single().url)
        assertEquals("凡人修仙传", report.books.single().name)
    }

    @Test
    fun searchKeepsJavaBridgeAvailableInsideJavaImporterScope() {
        val source = source(
            searchUrl = """
                <js>
                var javaImport = new JavaImporter(Packages.java.lang);
                with (javaImport) {
                    sign = java.md5Encode('abc');
                    url = '/api/search,' + JSON.stringify({headers:{signature:String(sign)}});
                }
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    assertEquals("900150983cd24fb0d6963f7d28e17f72", request.headers["signature"])
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                    )
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("凡人修仙传", report.books.single().name)
    }

    @Test
    fun evalKeepsReaderJavaBridgeForDynamicSourceScripts() {
        val source = source(
            searchUrl = """
                <js>
                var code = 'function sign(){ return ' + 'ja' + 'va.md5Encode' + "('abc'); }";
                eval(code);
                url = '/api/search,' + JSON.stringify({headers:{signature:String(sign())}});
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    assertEquals("900150983cd24fb0d6963f7d28e17f72", request.headers["signature"])
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                    )
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("凡人修仙传", report.books.single().name)
    }

    @Test
    fun hutoolImportsSupportFanqieCryptoHelpersInsideJavaImporterScope() {
        val source = source(
            jsLib = """
                var javaImport = new JavaImporter();
                javaImport.importPackage(
                    Packages.cn.hutool.core.util,
                    Packages.cn.hutool.core.codec,
                    Packages.cn.hutool.crypto.digest
                );
                with (javaImport) {
                    function fanqiePieces(data) {
                        var value = RandomUtil.randomBytes(32);
                        var decoded = Base64.decode(Base64.encode(value));
                        var digest = DigestUtil.sha512(value);
                        var zipped = ZipUtil.gzip(data, "");
                        var encrypted = java.createSymmetricCrypto(
                            "AES/CBC/PKCS5Padding",
                            digest.slice(0, 16),
                            digest.slice(16, 32)
                        ).encrypt(zipped);
                        return {
                            value: value,
                            decoded: decoded,
                            digest: digest,
                            zipped: zipped,
                            encrypted: encrypted,
                            reverse: StrUtil.reverse("abcd"),
                            md5: DigestUtil.md5Hex("abc")
                        };
                    }
                }
            """.trimIndent(),
            searchUrl = """
                <js>
                var pieces = fanqiePieces(JSON.stringify({name: key}));
                url = '/api/search,' + JSON.stringify({headers:{
                    value: String(pieces.value.length),
                    decoded: String(pieces.decoded.length),
                    digest: String(pieces.digest.length),
                    zipped: String(pieces.zipped.length),
                    encrypted: String(pieces.encrypted.length),
                    reverse: String(pieces.reverse),
                    md5: String(pieces.md5)
                }});
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val requests = ArrayList<MediaHttpRequest>()
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    requests.add(request)
                    assertEquals("32", request.headers["value"])
                    assertEquals("32", request.headers["decoded"])
                    assertEquals("64", request.headers["digest"])
                    assertEquals("dcba", request.headers["reverse"])
                    assertEquals("900150983cd24fb0d6963f7d28e17f72", request.headers["md5"])
                    assertTrue(request.headers.getValue("zipped").toInt() > 16)
                    assertTrue(request.headers.getValue("encrypted").toInt() % 16 == 0)
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                    )
                }
            }
        )

        val result = runtime.search(source, "凡人")

        assertTrue(result is MediaEngineResult.Success)
        val report = (result as MediaEngineResult.Success).value
        assertEquals(report.attempts.single().message, 1, requests.size)
    }

    @Test
    fun searchScopeBodyDefaultsToPostWhenNoMethodIsProvided() {
        val source = source(
            searchUrl = """
                <js>
                body = 'keyword=' + encodeURIComponent(key);
                url = '/api/search';
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    assertEquals("POST", request.method)
                    assertEquals("keyword=%E5%87%A1%E4%BA%BA", request.body)
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                    )
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("凡人修仙传", report.books.single().name)
    }

    @Test
    fun searchDecodesLegadoDataUrlBeforeRules() {
        val source = source(
            searchUrl = """
                <js>
                'data:;base64,' + java.base64Encode(JSON.stringify({
                    items: [{title: '凡人修仙传', url: 'https://audio.example/book/1'}]
                })) + ',{"type":"audio"}'
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    error("data urls must not hit the network")
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("凡人修仙传", report.books.single().name)
        assertEquals("https://audio.example/book/1", report.books.single().bookUrl)
    }

    @Test
    fun searchScriptSupportsLegadoUtilityFunctionsWithoutWebView() {
        val source = source(
            searchUrl = """
                <js>
                id = java.randomUUID();
                hex = java.hexEncodeToString('ab');
                day = java.timeFormatUTC(0, 'yyyy-MM-dd HH:mm:ss', 8);
                if (String(hex) !== '6162' || String(day) !== '1970-01-01 08:00:00' || String(id).length < 32) {
                    throw 'bad utility hex=' + hex + ' day=' + day + ' id=' + id;
                }
                java.ajaxAll(['/one', '/two']);
                '/search'
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val urls = ArrayList<String>()
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    urls.add(request.url)
                    return when (request.url) {
                        "https://audio.example/one" -> MediaHttpResponse(request.url, "{}")
                        "https://audio.example/two" -> MediaHttpResponse(request.url, "{}")
                        else -> MediaHttpResponse(
                            request.url,
                            """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                        )
                    }
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals(report.attempts.single().message, true, report.attempts.single().success)
        assertEquals(
            listOf("https://audio.example/one", "https://audio.example/two", "https://audio.example/search"),
            urls
        )
        assertEquals("凡人修仙传", report.books.single().name)
    }

    @Test
    fun webViewBridgeRunsInlineScriptWithoutUserAction() {
        val source = source(
            contentRules = mapOf(
                "content" to """
                    @js:
                    java.webView(
                      '<html><script>token = "ok-" + window.btoa("media");</script></html>',
                      null,
                      'token'
                    );
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher("https://audio.example/chapter/1" to "<html></html>")
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals("ok-bWVkaWE=", raw)
    }

    @Test
    fun browserBridgeCallsStillFailAsUserActions() {
        val source = source(
            searchUrl = "<js>java.startBrowserAwait('https://login.example', '登录')</js>",
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(fetcher = fixtureFetcher())

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals(false, report.attempts.single().success)
        assertEquals(true, report.attempts.single().message.contains("browser action unsupported"))
    }

    @Test
    fun detailSupportsXPathTocRule() {
        val source = source(
            bookInfoRules = mapOf(
                "tocUrl" to """//div[@class="book"][1]/a[1]/@href"""
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/book" to """<div class="book"><a href="/play/1">立即播放</a></div>"""
            )
        )

        val detail = (runtime.detail(book(source)) as MediaEngineResult.Success).value

        assertEquals("https://audio.example/play/1", detail.tocUrl)
    }

    @Test
    fun chaptersRunLegadoJsAndPreserveUrlOptions() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to "#playlist li a",
                "chapterName" to "text",
                "chapterUrl" to """
                    href
                    @js:
                    result+',{"webView":true}'
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/toc" to """
                    <ul id="playlist">
                      <li><a href="/audio/1.html">第1集</a></li>
                    </ul>
                """.trimIndent()
            )
        )

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc")) as MediaEngineResult.Success).value

        assertEquals("第1集", chapters.single().name)
        assertEquals("""https://audio.example/audio/1.html,{"webView":true}""", chapters.single().chapterUrl)
    }

    @Test
    fun chaptersRenderJsonTemplatesBeforeRunningLegadoJs() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to "$.data[*]",
                "chapterName" to "$.title",
                "chapterUrl" to """
                    <js>
                    '/content?item_id={{$.item_id}}&source={{$.source}}&tab={{$.tab}}'
                    </js>
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/toc" to """
                    {"data":[{"title":"最后一集","item_id":123,"source":"番茄","tab":"听书"}]}
                """.trimIndent()
            )
        )

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc")) as MediaEngineResult.Success).value

        assertEquals("最后一集", chapters.single().name)
        assertEquals(
            "https://audio.example/content?item_id=123&source=%E7%95%AA%E8%8C%84&tab=%E5%90%AC%E4%B9%A6",
            chapters.single().chapterUrl
        )
    }

    @Test
    fun chaptersFollowNextTocUrlPages() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to "#playlist li a",
                "chapterName" to "text",
                "chapterUrl" to "href",
                "nextTocUrl" to "#next@href"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/toc" to """
                    <ul id="playlist">
                      <li><a href="/audio/1.html">第1集</a></li>
                    </ul>
                    <a id="next" href="/toc/2">下一页</a>
                """.trimIndent(),
                "https://audio.example/toc/2" to """
                    <ul id="playlist">
                      <li><a href="/audio/2.html">第2集</a></li>
                    </ul>
                """.trimIndent()
            )
        )

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc")) as MediaEngineResult.Success).value

        assertEquals(listOf("第1集", "第2集"), chapters.map { it.name })
        assertEquals(listOf(0, 1), chapters.map { it.index })
    }

    @Test
    fun chaptersFollowLongPagedTocBeyondFirstDozenPages() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to "#playlist li a",
                "chapterName" to "text",
                "chapterUrl" to "href",
                "nextTocUrl" to "#next@href"
            )
        )
        val responses = (1..20).map { page ->
            val next = if (page < 20) """<a id="next" href="/toc/${page + 1}">下一页</a>""" else ""
            "https://audio.example/toc/$page" to """
                <ul id="playlist">
                  <li><a href="/audio/$page.html">第${page}集</a></li>
                </ul>
                $next
            """.trimIndent()
        }.toTypedArray()
        val runtime = LegadoMediaRuleRuntime(fetcher = fixtureFetcher(*responses))

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc/1")) as MediaEngineResult.Success).value

        assertEquals(20, chapters.size)
        assertEquals("第20集", chapters.last().name)
    }

    @Test
    fun chaptersCleanReverseTocPrefix() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to "-#playlist li a",
                "chapterName" to "text",
                "chapterUrl" to "href"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/toc" to """
                    <ul id="playlist">
                      <li><a href="/audio/2.html">第2集</a></li>
                      <li><a href="/audio/1.html">第1集</a></li>
                    </ul>
                """.trimIndent()
            )
        )

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc")) as MediaEngineResult.Success).value

        assertEquals(listOf("第1集", "第2集"), chapters.map { it.name })
    }

    @Test
    fun chaptersSupportLegadoCssPrefixRule() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to "@css:.chapter-wrap-list.normal>li",
                "chapterName" to "a@text",
                "chapterUrl" to "a@href"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/toc" to """
                    <ol class="chapter-wrap-list normal">
                      <li><a href="/comic/1.html">第一话</a></li>
                    </ol>
                """.trimIndent()
            )
        )

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc")) as MediaEngineResult.Success).value

        assertEquals("第一话", chapters.single().name)
        assertEquals("https://audio.example/comic/1.html", chapters.single().chapterUrl)
    }

    @Test
    fun chaptersPassCombinedStaticCssResultsIntoFollowingScript() {
        val source = source(
            tocRules = mapOf(
                "chapterList" to """
                    h3.volume:not(div)&&h3.volume:not(div) ~ ul
                    <js>
                    var voList = Array.from(result).filter(n => String(n).includes('<h3'));
                    var ulList = Array.from(result).filter(n => String(n).includes('<ul'));
                    if (voList.length !== 1 || ulList.length !== 1) throw 'missing iterable combined nodes';
                    [{
                        name: java.getString('text', voList[0]) + '-' + org.jsoup.Jsoup.parse(ulList[0]).select('a').text(),
                        url: org.jsoup.Jsoup.parse(ulList[0]).select('a')[0].attr('href')
                    }];
                    </js>
                """.trimIndent(),
                "chapterName" to "$.name",
                "chapterUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/toc" to """
                    <h3 class="volume">卷一</h3>
                    <ul><li><a href="/comic/1.html">第一话</a></li></ul>
                """.trimIndent()
            )
        )

        val chapters = (runtime.chapters(detail(source, tocUrl = "https://audio.example/toc")) as MediaEngineResult.Success).value

        assertEquals("卷一-第一话", chapters.single().name)
        assertEquals("https://audio.example/comic/1.html", chapters.single().chapterUrl)
    }

    @Test
    fun rawContentUsesLegadoContentRuleForAudioUrl() {
        val source = source(
            contentRules = mapOf("content" to "audio[id*=audio]@src")
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to """<audio id="audio" src="/book/1.m4a"></audio>"""
            )
        )

        val result = runtime.rawContent(chapter(source))
        val raw = (result as? MediaEngineResult.Success)?.value ?: error(result.toString())

        assertEquals("/book/1.m4a", raw)
    }

    @Test
    fun rawContentRunsJavaAjaxInsideLegadoJsRule() {
        val source = source(
            contentRules = mapOf("content" to """@js:JSON.parse(java.ajax('/api/play')).url""")
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to "<html></html>",
                "https://audio.example/api/play" to """{"url":"https://cdn.example/book/1.m4a"}"""
            )
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals("https://cdn.example/book/1.m4a", raw)
    }

    @Test
    fun rawContentPassesPageBodyToLegadoJsResult() {
        val source = source(
            contentRules = mapOf(
                "content" to """<js>result.match(/data-url="([^"]+)/)[1]</js>"""
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to """<div data-url="https://cdn.example/book/1.m4a"></div>"""
            )
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals("https://cdn.example/book/1.m4a", raw)
    }

    @Test
    fun rawContentCanCallSourceJsLibFunction() {
        val source = source(
            contentRules = mapOf("content" to """@js:decodePlayUrl(result)"""),
            jsLib = """
                function decodePlayUrl(html) {
                  return html.match(/data-url="([^"]+)/)[1];
                }
            """.trimIndent()
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to """<div data-url="https://cdn.example/book/jslib.m4a"></div>"""
            )
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals("https://cdn.example/book/jslib.m4a", raw)
    }

    @Test
    fun rawContentLoadsRemoteJsLibMapAndReloadHelper() {
        val source = source(
            contentRules = mapOf("content" to """@js:decodePlay(JSON.parse(Reload('/api/play')).path)"""),
            jsLib = """{"fixture":"https://cdn.example/jslib.js"}"""
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to """<html></html>""",
                "https://cdn.example/jslib.js" to """
                    function decodePlay(path) {
                      return 'https://cdn.example' + path;
                    }
                """.trimIndent(),
                "https://audio.example/api/play" to """{"path":"/book/remote.m4a"}"""
            )
        )

        val result = runtime.rawContent(chapter(source))
        val raw = (result as? MediaEngineResult.Success)?.value ?: error(result.toString())

        assertEquals("https://cdn.example/book/remote.m4a", raw)
    }

    @Test
    fun rawContentCanImportRemoteScript() {
        val source = source(
            contentRules = mapOf(
                "content" to """
                    @js:
                    Function('html', java.importScript('https://cdn.example/helper.js') + '; return importedPlayUrl(html);')(result);
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to """<div data-id="remote"></div>""",
                "https://cdn.example/helper.js" to """
                    function importedPlayUrl(html) {
                      return 'https://cdn.example/book/' + html.match(/data-id="([^"]+)/)[1] + '.m4a';
                    }
                """.trimIndent()
            )
        )

        val result = runtime.rawContent(chapter(source))
        val raw = (result as? MediaEngineResult.Success)?.value ?: error(result.toString())

        assertEquals("https://cdn.example/book/remote.m4a", raw)
    }

    @Test
    fun rawContentProvidesMediaDeviceGlobals() {
        val source = source(
            contentRules = mapOf(
                "content" to """
                    @js:
                    SystemPropsUtil.getProps().indexOf('Android') >= 0 &&
                      java.getWebViewUA().indexOf('Android') >= 0 ? 'ok' : 'bad';
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher("https://audio.example/chapter/1" to "<html></html>")
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals("ok", raw)
    }

    @Test
    fun rawContentJsCanReadCurrentJsonWithGetStringList() {
        val source = source(
            sourceType = MediaSourceType.COMIC,
            contentRules = mapOf(
                "content" to """
                    <js>
                    var urls = java.getStringList('$.data[*].url');
                    '<img src="' + urls.get(0) + '">\n<img src="' + urls.get(1) + '">';
                    </js>
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher(
                "https://audio.example/chapter/1" to """{"data":[{"url":"https://img.example/1.webp"},{"url":"https://img.example/2.webp"}]}"""
            )
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals(
            listOf(
                """<img src="https://img.example/1.webp">""",
                """<img src="https://img.example/2.webp">"""
            ),
            raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        )
    }

    @Test
    fun urlRequestParsesLegadoStyleLenientOptions() {
        val request = LegadoMediaUrlRequest.parse(
            rawValue = "https://audio.example/play,{webView:“true”,headers:{'Referer':'https://audio.example/book'}}",
            baseUrl = "https://audio.example",
            defaultHeaders = emptyMap()
        )

        assertEquals(true, request.webView)
        assertEquals("https://audio.example/book", request.headers["Referer"])
    }

    @Test
    fun urlRequestDefaultsBodyOnlyConfigToPost() {
        val request = LegadoMediaUrlRequest.parse(
            rawValue = "https://audio.example/search,{body:'keyword=%E5%87%A1%E4%BA%BA'}",
            baseUrl = "https://audio.example",
            defaultHeaders = emptyMap()
        )

        assertEquals("POST", request.method)
        assertEquals("keyword=%E5%87%A1%E4%BA%BA", request.body)
    }

    @Test
    fun rawContentSupportsJavaPostResponseBodyAndHeaders() {
        val source = source(
            contentRules = mapOf(
                "content" to """
                    @js:
                    var res = java.post('/api/play', 'id=1', {'Content-Type':'application/x-www-form-urlencoded'});
                    var cookies = res.cookies();
                    res.header('x-token') + '|' + res.headers('Location') + '|' + res.headers().location + '|' + cookies.sid + '|' + String(cookies) + '|' + res.code() + '|' + res.body();
                """.trimIndent()
            )
        )
        val requests = ArrayList<MediaHttpRequest>()
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    requests.add(request)
                    return MediaHttpResponse(
                        finalUrl = request.url,
                        body = "playable",
                        headers = mapOf(
                            "x-token" to "token-1",
                            "Location" to "/redirected",
                            "Set-Cookie" to "sid=abc"
                        ),
                        statusCode = 200
                    )
                }
            }
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        val postRequest = requests.last()
        assertEquals("POST", postRequest.method)
        assertEquals("id=1", postRequest.body)
        assertEquals("application/x-www-form-urlencoded", postRequest.headers["Content-Type"])
        assertEquals("https://audio.example/api/play", postRequest.url)
        assertEquals("token-1|/redirected|/redirected|abc|sid=abc|200|playable", raw)
    }

    @Test
    fun rawContentSupportsLegadoCacheCookieAndSetContent() {
        val source = source(
            contentRules = mapOf(
                "content" to """
                    <js>
                    cache.put('payload', '{"items":[{"url":"https://img.example/1.webp"}]}', 30);
                    java.setContent(cache.get('payload'));
                    cookie.setCookie(source.getKey(), 'sid=abc; uid=1');
                    var urls = java.getStringList('$.items[*].url');
                    cache.delete('payload');
                    urls.get(0) + '|' + cookie.getCookie(source.key, 'sid') + '|' + (cache.get('payload') === null);
                    </js>
                """.trimIndent()
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher("https://audio.example/chapter/1" to "<html></html>")
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals("https://img.example/1.webp|abc|true", raw)
    }

    @Test
    fun rawContentKeepsAudioPageWhenContentRuleIsBlank() {
        val source = source(
            contentRules = mapOf("content" to "audio[id*=audio]@src")
        )
        val page = """<ul id="jp-lines"><li data-code="abc">line:0</li></ul>"""
        val runtime = LegadoMediaRuleRuntime(
            fetcher = fixtureFetcher("https://audio.example/chapter/1" to page)
        )

        val raw = (runtime.rawContent(chapter(source)) as MediaEngineResult.Success).value

        assertEquals(page, raw)
    }

    @Test
    fun searchScriptSupportsLegacyAesBase64Decode() {
        val key = "1234567890123456"
        val iv = "abcdefghijklmnop"
        val encrypted = aesBase64("decoded", key, iv)
        val source = source(
            searchUrl = """
                <js>
                var decoded = java.aesBase64DecodeToString('$encrypted', '$key', 'AES/CBC/PKCS5Padding', '$iv');
                url = '/api/search?value=' + decoded;
                </js>
            """.trimIndent(),
            searchRules = mapOf(
                "bookList" to "$.items[*]",
                "name" to "$.title",
                "bookUrl" to "$.url"
            )
        )
        val runtime = LegadoMediaRuleRuntime(
            fetcher = object : MediaHttpFetcher {
                override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                    assertEquals("https://audio.example/api/search?value=decoded", request.url)
                    return MediaHttpResponse(
                        request.url,
                        """{"items":[{"title":"凡人修仙传","url":"/book/1"}]}"""
                    )
                }
            }
        )

        val report = (runtime.search(source, "凡人") as MediaEngineResult.Success).value

        assertEquals("凡人修仙传", report.books.single().name)
    }

    private fun aesBase64(data: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private fun fixtureFetcher(vararg responses: Pair<String, String>): MediaHttpFetcher {
        val byUrl = responses.toMap()
        return object : MediaHttpFetcher {
            override fun fetch(request: MediaHttpRequest): MediaHttpResponse {
                val body = byUrl[request.url] ?: error("Unexpected request ${request.method} ${request.url}")
                return MediaHttpResponse(request.url, body)
            }
        }
    }

    private fun detail(source: MediaSourceDefinition, tocUrl: String): MediaSourceBookDetail {
        val book = book(source)
        return MediaSourceBookDetail(
            book = book,
            name = book.name,
            author = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            kind = book.kind,
            lastChapter = book.lastChapter,
            tocUrl = tocUrl
        )
    }

    private fun chapter(source: MediaSourceDefinition): MediaSourceChapter {
        val book = book(source)
        return MediaSourceChapter(
            source = source,
            book = book,
            index = 0,
            name = "第1集",
            chapterUrl = "https://audio.example/chapter/1"
        )
    }

    private fun book(source: MediaSourceDefinition): MediaSourceBook {
        return MediaSourceBook(
            source = source,
            name = "凡人修仙传",
            author = "",
            bookUrl = "https://audio.example/book",
            coverUrl = "https://audio.example/cover.jpg",
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun source(
        searchUrl: String = "/search?q={{key}}",
        searchRules: Map<String, String> = emptyMap(),
        bookInfoRules: Map<String, String> = emptyMap(),
        tocRules: Map<String, String> = emptyMap(),
        contentRules: Map<String, String> = emptyMap(),
        sourceType: Int = MediaSourceType.AUDIO,
        jsLib: String = ""
    ): MediaSourceDefinition {
        return MediaSourceDefinition(
            sourceName = "fixture audio",
            sourceUrl = "https://audio.example",
            sourceType = sourceType,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = searchUrl,
            ruleSearch = MediaLegadoRuleSet("ruleSearch", searchRules),
            ruleBookInfo = MediaLegadoRuleSet("ruleBookInfo", bookInfoRules),
            ruleToc = MediaLegadoRuleSet("ruleToc", tocRules),
            ruleContent = MediaLegadoRuleSet("ruleContent", contentRules),
            diagnostics = emptyList(),
            jsLib = jsLib
        )
    }
}
