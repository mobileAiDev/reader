package com.ldp.reader.sourceengine.legado

import com.ldp.reader.sourceengine.EngineFailure
import com.ldp.reader.sourceengine.EngineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegadoSourceImporterTest {
    private val importer = LegadoSourceImporter()

    @Test
    fun importsSupportedLegadoSourceFieldsAndReportsUnsupportedFields() {
        val result = importer.importJson(
            """
            [
              {
                "bookSourceName": "Codex Sample",
                "bookSourceUrl": "https://example.org",
                "bookSourceGroup": "lab",
                "bookSourceComment": "fixture",
                "enabled": true,
                "header": {"User-Agent": "ReaderLab"},
                "searchUrl": "https://example.org/search?q={{key}}",
                "ruleSearch": {
                  "bookList": ".book",
                  "name": ".title",
                  "author": ".author",
                  "bookUrl": "a@href"
                },
                "ruleBookInfo": {
                  "name": "h1",
                  "author": ".author",
                  "tocUrl": ".toc@href"
                },
                "ruleToc": {
                  "chapterList": ".chapter",
                  "chapterName": "a@text",
                  "chapterUrl": "a@href"
                },
                "ruleContent": {
                  "content": ".content@html"
                },
                "loginUrl": "https://example.org/login",
                "unknownFutureField": "must be reported"
              }
            ]
            """.trimIndent()
        )

        assertTrue(result is EngineResult.Success)
        val report = (result as EngineResult.Success).value
        assertEquals(1, report.sources.size)
        assertEquals(0, report.rejectedSources.size)

        val source = report.sources[0]
        assertEquals("Codex Sample", source.sourceName)
        assertEquals("https://example.org", source.sourceUrl)
        assertEquals(true, source.enabled)
        assertEquals("ReaderLab", source.headers["User-Agent"])
        assertEquals(".book", source.ruleSearch.rules["bookList"])
        assertEquals(".content@html", source.ruleContent.rules["content"])

        assertTrue(source.diagnostics.any { it.code == "unsupported_top_level_field" && it.path == "loginUrl" })
        assertTrue(source.diagnostics.any { it.code == "unknown_top_level_field" && it.path == "unknownFutureField" })
    }

    @Test
    fun rejectsSourceWithoutRequiredContractFields() {
        val result = importer.importJson(
            """
            [
              {
                "bookSourceName": "Missing URL"
              }
            ]
            """.trimIndent()
        )

        assertTrue(result is EngineResult.Success)
        val report = (result as EngineResult.Success).value
        assertEquals(0, report.sources.size)
        assertEquals(1, report.rejectedSources.size)
        assertTrue(report.rejectedSources[0].failure is EngineFailure.ContractViolation)
    }

    @Test
    fun malformedJsonFailsImport() {
        val result = importer.importJson("{")

        assertTrue(result is EngineResult.Failure)
        assertTrue((result as EngineResult.Failure).failure is EngineFailure.ParseError)
    }

    @Test
    fun reportsMalformedHeaderInsteadOfSilentlyUsingIt() {
        val result = importer.importJson(
            """
            {
              "bookSourceName": "Bad Header",
              "bookSourceUrl": "https://bad-header.example",
              "header": "{not-json}",
              "ruleSearch": {
                "bookList": ".book"
              }
            }
            """.trimIndent()
        )

        assertTrue(result is EngineResult.Success)
        val source = (result as EngineResult.Success).value.sources[0]
        assertTrue(source.headers.isEmpty())
        assertTrue(source.diagnostics.any { it.code == "malformed_header" && it.path == "header" })
    }

    @Test
    fun importsStorageFixtureWithAcceptedAndRejectedSources() {
        val json = javaClass.classLoader!!
            .getResource("legado/codex-storage-book-sources.json")!!
            .readText()

        val result = importer.importJson(json)

        assertTrue(result is EngineResult.Success)
        val report = (result as EngineResult.Success).value
        assertEquals(1, report.sources.size)
        assertEquals(1, report.rejectedSources.size)
        assertEquals("Codex Storage Source", report.sources[0].sourceName)
        assertEquals("ReaderStorageImport", report.sources[0].headers["User-Agent"])
        assertTrue(report.sources[0].diagnostics.any { it.path == "loginUrl" })
        assertTrue(report.rejectedSources[0].failure is EngineFailure.ContractViolation)
    }

    @Test
    fun importsLegadoWebReturnDataPayload() {
        val result = importer.importJson(
            """
            {
              "isSuccess": true,
              "errorMsg": "",
              "data": [
                {
                  "bookSourceName": "Provider Source",
                  "bookSourceUrl": "https://provider.example",
                  "searchUrl": "/search?q={{key}}",
                  "ruleSearch": {
                    "bookList": ".book",
                    "name": ".title",
                    "bookUrl": "a@href"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(result is EngineResult.Success)
        val report = (result as EngineResult.Success).value
        assertEquals(1, report.sources.size)
        assertEquals("Provider Source", report.sources[0].sourceName)
    }
}
