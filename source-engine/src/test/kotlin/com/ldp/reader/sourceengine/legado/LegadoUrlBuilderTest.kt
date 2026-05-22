package com.ldp.reader.sourceengine.legado

import com.ldp.reader.sourceengine.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegadoUrlBuilderTest {
    private val builder = LegadoUrlBuilder()

    @Test
    fun encodesSearchUrlKeyWithConfiguredCharset() {
        val request = builder.buildRequest(
            source = source(),
            rawSearchUrl = "/search.php?searchkey={{key}},{\"charset\":\"gbk\"}",
            keyword = "我在",
            page = 1
        )

        assertEquals("gbk", request.charset)
        assertTrue(request.url.contains("searchkey=%CE%D2%D4%DA"))
        assertFalse(request.url.contains("%E6%88%91%E5%9C%A8"))
    }

    @Test
    fun encodesPostBodyKeyWithConfiguredCharset() {
        val request = builder.buildRequest(
            source = source(),
            rawSearchUrl = "/e/search/index.php,{\"charset\":\"gb2312\",\"method\":\"POST\",\"body\":\"keyboard={{key}}&show=title\"}",
            keyword = "我在",
            page = 1
        )

        assertEquals("POST", request.method)
        assertEquals("gb2312", request.charset)
        assertEquals("keyboard=%CE%D2%D4%DA&show=title", request.body)
    }

    @Test
    fun rendersTraditionalKeywordTemplateWithConfiguredCharset() {
        val request = builder.buildRequest(
            source = source(),
            rawSearchUrl = "/modules/article/wap_search.php,{\"charset\":\"big5\",\"method\":\"POST\",\"body\":\"type=articlename&searchkey={{java.s2t(key)}}\"}",
            keyword = "我在修仙界万古长青",
            page = 1
        )

        assertEquals("big5", request.charset)
        assertEquals(
            "type=articlename&searchkey=%A7%DA%A6%62%AD%D7%A5%50%AC%C9%B8%55%A5%6A%AA%F8%AB%43",
            request.body
        )
    }

    private fun source(): BookSource {
        return BookSource(
            sourceName = "fixture",
            sourceUrl = "https://example.org",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
    }
}
