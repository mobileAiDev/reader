package com.ldp.reader.sourceengine.legado

import org.junit.Assert.assertEquals
import org.junit.Test

class LegadoJsonRuleEvaluatorTest {
    private val evaluator = LegadoRuleEvaluator()

    @Test
    fun jsonListSupportsLeadingDotExistsFilter() {
        val context = evaluator.parseBody(
            """
            [
              {"title":"第一集","id":1},
              {"id":2},
              {"title":"第二集","id":3}
            ]
            """.trimIndent(),
            "https://audio.example/catalog"
        )

        val nodes = evaluator.list(".[?(@.title)]", context)

        assertEquals(2, nodes.size)
        assertEquals("第一集", evaluator.string("title", nodes[0]))
        assertEquals("3", evaluator.string("id", nodes[1]))
    }

    @Test
    fun jsonStringSupportsLeadingDotField() {
        val context = evaluator.parseBody("""{"url":"https://cdn.example/1.mp3"}""", "https://audio.example")
        val node = evaluator.list("$", context).single()

        assertEquals("https://cdn.example/1.mp3", evaluator.string(".url", node))
    }

    @Test
    fun jsonListSupportsFallbackPathCandidates() {
        val context = evaluator.parseBody(
            """{"info":{"episodes":{"music":[{"name":"片尾曲"}]}}}""",
            "https://audio.example/catalog"
        )

        val nodes = evaluator.list("$.info.episodes.episode[*]||$.info.episodes.music[*]", context)

        assertEquals(1, nodes.size)
        assertEquals("片尾曲", evaluator.string("$.name", nodes.single()))
    }

    @Test
    fun jsonTemplateSupportsFallbackPathCandidates() {
        val context = evaluator.parseBody(
            """{"info":{"sound":{"id":7788}}}""",
            "https://audio.example/catalog"
        )
        val node = evaluator.list("$", context).single()

        val value = evaluator.string(
            "https://audio.example/sound/{{$.sound_id||$.info.sound.id}}",
            node
        )

        assertEquals("https://audio.example/sound/7788", value)
    }

    @Test
    fun jsonPathSupportsRecursiveObjectWildcard() {
        val context = evaluator.parseBody(
            """
            {"result":{"docs":{"a":{"title":"凡人修仙传"},"b":{"title":"凡人修仙传之仙界篇"}}}}
            """.trimIndent(),
            "https://audio.example/search"
        )

        val nodes = evaluator.list("$..docs.*", context)

        assertEquals(listOf("凡人修仙传", "凡人修仙传之仙界篇"), nodes.map { evaluator.string("$.title", it) })
    }

    @Test
    fun strictJsonPathOnHtmlReturnsEmptyInsteadOfCssParseFailure() {
        val context = evaluator.parseBody(
            """<html><body>server error</body></html>""",
            "https://audio.example/search"
        )

        val nodes = evaluator.list("$.data.content[*]", context)

        assertEquals(0, nodes.size)
    }

    @Test
    fun pureJsTemplateLiteralCanRenderJsonTemplateValues() {
        val context = evaluator.parseBody("""{"id":12345}""", "https://audio.example/catalog")
        val node = evaluator.list("$", context).single()

        val value = evaluator.string(
            """
            @js:
            `../chapter?force=false&id={{$.id}}&offset=0&viewId=`
            """.trimIndent(),
            node
        )

        assertEquals("../chapter?force=false&id=12345&offset=0&viewId=", value)
    }

    @Test
    fun templateUsesBookVariableDefaultValue() {
        val context = evaluator.parseBody("""{"item_id":"abc","source":"酷我","tab":"听书"}""", "https://audio.example/catalog")
        val node = evaluator.list("$", context).single()

        val value = evaluator.string(
            "/content?item_id={{$.item_id}}&tone_id={{String(book.getVariable('custom')) || '4'}}",
            node
        )

        assertEquals("/content?item_id=abc&tone_id=4", value)
    }

    @Test
    fun templateReadsStoredBookVariableValue() {
        val context = evaluator.parseBody("""{"item_id":"abc"}""", "https://audio.example/catalog")
        val node = evaluator.list("$", context).single()
        node.variables["custom"] = "7"

        val value = evaluator.string(
            "/content?item_id={{$.item_id}}&tone_id={{String(book.getVariable('custom')) || '4'}}",
            node
        )

        assertEquals("/content?item_id=abc&tone_id=7", value)
    }

    @Test
    fun htmlExtractorKeepsSelfClosingImageTags() {
        val context = evaluator.parseBody(
            """<div class="epContent"><img src="/1.jpg"><img data-src="/2.webp"></div>""",
            "https://comic.example/chapter/1"
        )
        val node = evaluator.list(".epContent", context).single()

        assertEquals(
            """<img src="/1.jpg">""" + "\n" + """<img data-src="/2.webp">""",
            evaluator.string("img@html", node)
        )
    }
}
