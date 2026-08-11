package com.ldp.reader.sourceengine.legado

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LegadoRuleEvaluatorCacheTest {
    @Test
    fun plainElementRulesSkipStoredVariableScanningWithoutChangingStoredVariableTemplates() {
        val storedVariableScans = AtomicInteger(0)
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, _ ->
                if (kind == "stored-variable-scan") {
                    storedVariableScans.incrementAndGet()
                }
            }
        )
        val context = evaluator.parseBody(
            "<div id=\"root\"><a>chapter</a></div>",
            "https://cache.example/"
        )
        val root = evaluator.list("id.root", context).single()
        root.variables["chapterId"] = "42"

        repeat(64) {
            assertEquals("chapter", evaluator.string("tag.a@text", root))
        }
        assertEquals(
            "https://cache.example/chapter/42",
            evaluator.string("{{baseUrl}}chapter/@get:{chapterId}", root)
        )

        assertEquals(1, storedVariableScans.get())
    }

    @Test
    fun plainElementRulesSkipTemplateScanningWithoutChangingTemplateRendering() {
        val templateScans = AtomicInteger(0)
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, _ ->
                if (kind == "template-scan") {
                    templateScans.incrementAndGet()
                }
            }
        )
        val context = evaluator.parseBody(
            "<div id=\"root\"><a>chapter</a></div>",
            "https://cache.example/"
        )
        val root = evaluator.list("id.root", context).single()

        repeat(64) {
            assertEquals("chapter", evaluator.string("tag.a@text", root))
        }
        assertEquals(
            "https://cache.example/chapter",
            evaluator.string("{{baseUrl}}chapter", root)
        )

        assertEquals(1, templateScans.get())
    }

    @Test
    fun plainElementRulesSkipFilterSplittingWithoutChangingFilteredValues() {
        val filterSplits = AtomicInteger(0)
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, _ ->
                if (kind == "filter-split") {
                    filterSplits.incrementAndGet()
                }
            }
        )
        val context = evaluator.parseBody(
            "<div id=\"root\"><a>chapter</a></div>",
            "https://cache.example/"
        )
        val root = evaluator.list("id.root", context).single()

        repeat(64) {
            assertEquals("chapter", evaluator.string("tag.a@text", root))
        }
        assertEquals(
            "filtered",
            evaluator.string("tag.a@text##^chapter$##filtered", root)
        )

        assertEquals(1, filterSplits.get())
    }

    @Test
    fun concurrentFilterLookupsCompileTheSameKeyOnlyOnce() {
        val compilationCount = AtomicInteger(0)
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, rule ->
                if (kind == "filter" && rule == "shared-filter") {
                    compilationCount.incrementAndGet()
                }
            }
        )
        val context = evaluator.parseBody("<div id=\"root\">value</div>", "https://cache.example/")
        val root = evaluator.list("id.root", context).single()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(16)
        try {
            val workers = List(16) {
                executor.submit {
                    start.await()
                    repeat(16) {
                        evaluator.string("text##shared-filter##", root)
                    }
                }
            }

            start.countDown()
            workers.forEach { worker -> worker.get(5, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertEquals(1, compilationCount.get())
    }

    @Test
    fun invalidFilterIsNotCached() {
        val compilationCount = AtomicInteger(0)
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, rule ->
                if (kind == "filter" && rule == "[") {
                    compilationCount.incrementAndGet()
                }
            }
        )
        val context = evaluator.parseBody("<div id=\"root\">value</div>", "https://cache.example/")
        val root = evaluator.list("id.root", context).single()

        assertEquals("value", evaluator.string("text##[##", root))
        assertEquals("value", evaluator.string("text##[##", root))

        assertEquals(2, compilationCount.get())
    }

    @Test
    fun dynamicFilterCacheEvictsOldestEntryAndKeepsNewestEntryHot() {
        val compilations = LinkedHashMap<String, Int>()
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, rule ->
                if (kind == "selector" || kind == "filter") {
                    val key = "$kind\n$rule"
                    compilations[key] = compilations.getOrDefault(key, 0) + 1
                }
            }
        )
        val context = evaluator.parseBody("<div id=\"root\">value</div>", "https://cache.example/")
        val root = evaluator.list("id.root", context).single()

        repeat(257) { index ->
            evaluator.string("text##cache-filter-$index##", root)
        }
        evaluator.string("text##cache-filter-256##", root)
        evaluator.string("text##cache-filter-0##", root)

        assertEquals(1, compilations["filter\ncache-filter-256"])
        assertEquals(2, compilations["filter\ncache-filter-0"])
    }

    @Test
    fun dynamicSelectorCacheEvictsOldestEntryAndKeepsNewestEntryHot() {
        val compilations = LinkedHashMap<String, Int>()
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, rule ->
                val key = "$kind\n$rule"
                compilations[key] = compilations.getOrDefault(key, 0) + 1
            }
        )
        val context = evaluator.parseBody(
            "<div id=\"root\"><span data-cache-key=\"256\">latest</span></div>",
            "https://cache.example/"
        )
        val root = evaluator.list("id.root", context).single()

        repeat(257) { index ->
            evaluator.string("span[data-cache-key=\"$index\"]@text", root)
        }
        evaluator.string("span[data-cache-key=\"256\"]@text", root)
        evaluator.string("span[data-cache-key=\"0\"]@text", root)

        assertEquals(1, compilations["selector\nspan[data-cache-key=\"256\"]"])
        assertEquals(2, compilations["selector\nspan[data-cache-key=\"0\"]"])
    }

    @Test
    fun repeatedChapterRulesCompileEachSelectorAndFilterOnlyOnce() {
        val compilations = LinkedHashMap<String, Int>()
        val evaluator = LegadoRuleEvaluator(
            LegadoRuleEvaluatorWorkProbe { kind, rule ->
                if (kind == "selector" || kind == "filter") {
                    val key = "$kind\n$rule"
                    compilations[key] = compilations.getOrDefault(key, 0) + 1
                }
            }
        )
        val chapterCount = 2_054
        val html = buildString {
            append("<div id=\"list\">")
            repeat(chapterCount) { index ->
                append("<dd><a href=\"/")
                append(index + 1)
                append(".html\">第")
                append(index + 1)
                append("章 正文</a></dd>")
            }
            append("</div>")
        }
        val context = evaluator.parseBody(html, "https://catalog.example/book/")

        val chapters = evaluator.list("id.list@tag.dd", context)
        chapters.forEachIndexed { index, chapter ->
            assertEquals(
                "${index + 1}章 正文",
                evaluator.string("tag.a@text##^第##", chapter)
            )
            assertEquals(
                "/${index + 1}.html",
                evaluator.string("tag.a@href", chapter)
            )
        }

        assertEquals(chapterCount, chapters.size)
        assertEquals(
            mapOf(
                "selector\nid.list" to 1,
                "selector\ntag.dd" to 1,
                "selector\ntag.a" to 1,
                "filter\n^第" to 1
            ),
            compilations
        )
    }
}
