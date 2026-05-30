package com.ldp.reader.source

import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8ChapterQualityType
import com.google.gson.Gson
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourceEngineV8MarkCacheTest {
    @Test
    fun persistsMarksForMatchingCatalogIdentity() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 100, lastTitle = "Chapter 100")

            assertEquals(
                true,
                cache.save(
                    identity,
                    "source@example",
                    listOf(mark(99, V8ChapterMarkState.WRONG)),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(99)
                )
            )

            val cached = cache.load(identity)
            assertNotNull(cached)
            assertEquals("source@example", cached!!.sourceLabel)
            assertEquals(V8ChapterMarkState.WRONG, cached.marks.single().state)
            assertEquals("body-md5", cached.contentDigest)
            assertEquals(listOf(99), cached.targetChapterIndexes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresCacheWhenCatalogShapeChanges() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val oldIdentity = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val newIdentity = identity(catalogSize = 101, lastTitle = "Chapter 101")
            assertEquals(
                true,
                cache.save(
                    oldIdentity,
                    "source@example",
                    listOf(mark(99, V8ChapterMarkState.WRONG)),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(99)
                )
            )

            assertNull(cache.load(newIdentity))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresCacheWhenTailCatalogDigestChanges() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val oldIdentity = identity(catalogSize = 100, lastTitle = "Chapter 100", tailTitleDigest = "old-tail")
            val newIdentity = identity(catalogSize = 100, lastTitle = "Chapter 100", tailTitleDigest = "new-tail")
            assertEquals(
                true,
                cache.save(
                    oldIdentity,
                    "source@example",
                    listOf(mark(99, V8ChapterMarkState.WRONG)),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(99)
                )
            )

            assertNull(cache.load(newIdentity))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun listsBookSummariesForMaintenanceOrdering() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val target = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val other = target.copy(bookName = "Other Book")
            assertEquals(
                true,
                cache.save(
                    target,
                    "source@example",
                    listOf(mark(99, V8ChapterMarkState.NORMAL)),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(99)
                )
            )
            assertEquals(
                true,
                cache.save(
                    other,
                    "other@example",
                    listOf(mark(9, V8ChapterMarkState.NORMAL)),
                    contentDigest = "other-md5",
                    targetChapterIndexes = listOf(9)
                )
            )

            val summaries = cache.summariesForBook(" Target  Book ", "Target Author")

            assertEquals(1, summaries.size)
            assertEquals("Target Book", summaries.single().identity.bookName)
            assertEquals(100, summaries.single().identity.catalogSize)
            assertEquals("Chapter 100", summaries.single().identity.lastTitle)
            assertEquals(1, summaries.single().marks)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresOldSchemaCacheAfterV8DecisionChange() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val file = cache.fileFor(identity)
            file.parentFile?.mkdirs()
            file.writeText(
                Gson().toJson(
                    mapOf(
                        "schemaVersion" to 11,
                        "identity" to identity,
                        "sourceLabel" to "source@example",
                        "createdAtMs" to 1L,
                        "marks" to listOf(mark(99, V8ChapterMarkState.WRONG))
                    )
                ),
                Charsets.UTF_8
            )

            assertNull(cache.load(identity))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesStaleCurrentDirectoryCacheFiles() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            root.mkdirs()
            val stale = root.resolve("stale.json")
            stale.writeText("""{"schemaVersion":1}""", Charsets.UTF_8)
            val unreadable = root.resolve("unreadable.json")
            unreadable.writeText("{", Charsets.UTF_8)

            SourceEngineV8MarkCache { root }

            assertEquals(false, stale.exists())
            assertEquals(false, unreadable.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesObsoleteIntegrityCacheDirectories() {
        val parent = Files.createTempDirectory("source-engine-cache").toFile()
        try {
            val root = parent.resolve("source_engine_v8_marks")
            val obsolete = listOf(
                parent.resolve("source_engine_old_marks"),
                parent.resolve("source_engine_old_term_stats"),
                parent.resolve("source_engine_previous_marks")
            )
            obsolete.forEach { dir ->
                dir.mkdirs()
                dir.resolve("stale.json").writeText("stale", Charsets.UTF_8)
            }
            val unrelated = parent.resolve("source_engine_shelf_cache")
            unrelated.mkdirs()

            SourceEngineV8MarkCache { root }

            assertEquals(false, obsolete.any { it.exists() })
            assertEquals(true, unrelated.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun refusesToCacheThinInconclusiveProbeResults() {
        val marks = listOf(
            mark(97, V8ChapterMarkState.NORMAL),
            mark(98, V8ChapterMarkState.INCONCLUSIVE, V8ChapterQualityType.TOO_SHORT_UNCERTAIN),
            mark(99, V8ChapterMarkState.INCONCLUSIVE, V8ChapterQualityType.TOO_SHORT_UNCERTAIN)
        )

        assertEquals(
            false,
            SourceEngineV8MarkCachePolicy.shouldSave(
                marks = marks,
                inputLengthsByChapterIndex = mapOf(
                    97 to 4_000,
                    98 to 0,
                    99 to 27
                )
            )
        )
    }

    @Test
    fun cachesReplayResultsWhenSuspectChaptersHadRealContent() {
        val marks = listOf(
            mark(97, V8ChapterMarkState.NORMAL),
            mark(98, V8ChapterMarkState.WRONG),
            mark(99, V8ChapterMarkState.INCONCLUSIVE, V8ChapterQualityType.TOO_SHORT_UNCERTAIN)
        )

        assertEquals(
            true,
            SourceEngineV8MarkCachePolicy.shouldSave(
                marks = marks,
                inputLengthsByChapterIndex = mapOf(
                    97 to 4_000,
                    98 to 3_200,
                    99 to 3_100
                )
            )
        )
    }

    @Test
    fun cachesStableCleanProbeResultsSoMaintenanceCanSkipUnchangedBooks() {
        val marks = listOf(
            mark(97, V8ChapterMarkState.NORMAL),
            mark(98, V8ChapterMarkState.NORMAL),
            mark(99, V8ChapterMarkState.NORMAL)
        )

        assertEquals(
            true,
            SourceEngineV8MarkCachePolicy.shouldSave(
                marks = marks,
                inputLengthsByChapterIndex = mapOf(
                    97 to 3_200,
                    98 to 3_300,
                    99 to 3_400
                )
            )
        )
    }

    @Test
    fun cachesStableCleanProbeResultsAfterDroppingThinInconclusiveMarks() {
        val marks = listOf(
            mark(96, V8ChapterMarkState.NORMAL),
            mark(97, V8ChapterMarkState.NORMAL),
            mark(98, V8ChapterMarkState.NORMAL),
            mark(99, V8ChapterMarkState.INCONCLUSIVE, V8ChapterQualityType.TOO_SHORT_UNCERTAIN)
        )
        val inputLengths = mapOf(
            96 to 3_200,
            97 to 3_300,
            98 to 3_400,
            99 to 27
        )

        val cacheableMarks = SourceEngineV8MarkCachePolicy.cacheableMarks(
            marks = marks,
            inputLengthsByChapterIndex = inputLengths
        )

        assertEquals(true, SourceEngineV8MarkCachePolicy.shouldSave(marks, inputLengths))
        assertEquals(listOf(96, 97, 98), cacheableMarks.map { mark -> mark.chapterIndex })
    }

    @Test
    fun cachesStableWrongMarksWhileDroppingThinInconclusiveProbeResults() {
        val marks = listOf(
            mark(36, V8ChapterMarkState.INCONCLUSIVE, V8ChapterQualityType.TOO_SHORT_UNCERTAIN),
            mark(76, V8ChapterMarkState.WRONG),
            mark(77, V8ChapterMarkState.WRONG)
        )
        val inputLengths = mapOf(
            36 to 0,
            76 to 3_200,
            77 to 3_100
        )

        val cacheableMarks = SourceEngineV8MarkCachePolicy.cacheableMarks(
            marks = marks,
            inputLengthsByChapterIndex = inputLengths
        )

        assertEquals(true, SourceEngineV8MarkCachePolicy.shouldSave(marks, inputLengths))
        assertEquals(listOf(76, 77), cacheableMarks.map { mark -> mark.chapterIndex })
    }

    private fun identity(
        catalogSize: Int,
        lastTitle: String,
        tailTitleDigest: String = "tail-$catalogSize-$lastTitle"
    ): SourceEngineV8MarkCache.Identity {
        return SourceEngineV8MarkCache.Identity(
            sourceBookKey = "https://source.example\nhttps://source.example/book/1",
            sourceUrl = "https://source.example",
            bookUrl = "https://source.example/book/1",
            bookName = "Target Book",
            author = "Target Author",
            catalogSize = catalogSize,
            firstTitle = "Chapter 1",
            lastTitle = lastTitle,
            tailTitleDigest = tailTitleDigest
        )
    }

    private fun mark(
        index: Int,
        state: V8ChapterMarkState,
        qualityType: V8ChapterQualityType? = null
    ): V8ChapterMarkResult {
        return V8ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "Chapter $index",
            state = state,
            confidence = 0.9,
            qualityType = qualityType,
            suggestionState = null,
            action = null,
            reasons = if (qualityType == V8ChapterQualityType.TOO_SHORT_UNCERTAIN) {
                listOf("clean text too short after shell removal")
            } else {
                listOf("test")
            }
        )
    }
}
