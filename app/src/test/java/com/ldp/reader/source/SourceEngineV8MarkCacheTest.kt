package com.ldp.reader.source

import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8ChapterQualityType
import com.ldp.reader.sourceengine.content.v8.V8ChapterInput
import com.ldp.reader.sourceengine.content.v8.V8ValidationChapter
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
    fun storesValidTokenHashesCompactlyAndRoundTripsExactly() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val tokenHashes = List(512) { index ->
                ((index + 1L) * 0x5DEECE66DL and 0xFFFFFFFFFFFFL)
                    .toString(16)
                    .padStart(12, '0')
            }
            val fingerprint = SourceEngineV8MarkCache.InputFingerprint(
                inputDigest = "input-md5",
                normalizedLength = 4_000,
                tokenHashes = tokenHashes
            )

            assertEquals(
                true,
                cache.save(
                    identity,
                    "source@example",
                    listOf(mark(99, V8ChapterMarkState.NORMAL)),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(99),
                    inputFingerprintsByChapterIndex = mapOf(99 to fingerprint)
                )
            )

            val json = cache.fileFor(identity).readText(Charsets.UTF_8)
            val packed = Regex(""""tokenHashPack":"([^"]*)"""")
                .find(json)
                ?.groupValues
                ?.get(1)
            assertNotNull(packed)
            assertEquals(tokenHashes.size * 8, packed!!.length)
            assertEquals(false, json.contains(""""tokenHashes""""))
            assertEquals(true, packed.length < Gson().toJson(tokenHashes).length * 0.6)
            assertEquals(
                fingerprint,
                cache.load(identity)
                    ?.inputFingerprintsByChapterIndex
                    ?.get(99)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadsLegacyTokenHashArraysWithoutChangingTheCacheSchema() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val tokenHashes = listOf(
                "000000000000",
                "0123456789ab",
                "abcdef012345",
                "ffffffffffff"
            )
            val file = writeCacheEntry(
                cache = cache,
                identity = identity,
                sourceLabel = "legacy-source",
                createdAtMs = 10L,
                tokenHashes = tokenHashes
            )

            assertEquals(true, file.readText(Charsets.UTF_8).contains(""""tokenHashes""""))
            assertEquals(
                tokenHashes,
                cache.load(identity)
                    ?.inputFingerprintsByChapterIndex
                    ?.get(99)
                    ?.tokenHashes
            )
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
    fun summaryPreservesRulesWhileSkippingLargeInputFingerprints() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val tokenHashes = List(20_000) { index -> "token-$index-${"x".repeat(24)}" }
            assertEquals(
                true,
                cache.save(
                    identity,
                    "source@example",
                    listOf(mark(99, V8ChapterMarkState.NORMAL)),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(99),
                    inputFingerprintsByChapterIndex = mapOf(
                        99 to SourceEngineV8MarkCache.InputFingerprint(
                            inputDigest = "input-md5",
                            normalizedLength = 4_000,
                            tokenHashes = tokenHashes
                        )
                    )
                )
            )

            val summary = cache.summaries().single()

            assertEquals(identity, summary.identity)
            assertEquals("source@example", summary.sourceLabel)
            assertEquals(1, summary.marks)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresPersistedInconclusiveOnlyCacheOnLoadReplayAndSummary() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 1149, lastTitle = "Chapter 1149")
            assertEquals(
                true,
                cache.save(
                    identity,
                    "source@example",
                    listOf(
                        mark(1148, V8ChapterMarkState.INCONCLUSIVE),
                        mark(1149, V8ChapterMarkState.INCONCLUSIVE)
                    ),
                    contentDigest = "body-md5",
                    targetChapterIndexes = listOf(1148, 1149)
                )
            )

            assertNull(cache.load(identity))
            assertEquals(emptyList<SourceEngineV8MarkCache.CachedMarks>(), cache.replayCandidates(identity).toList())
            assertEquals(emptyList<SourceEngineV8MarkCache.Summary>(), cache.summariesForBook("Target Book", "Target Author"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replayCandidatesPreserveNewestFirstOrderingAndContents() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val current = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val older = current.copy(
                sourceBookKey = "older-key",
                sourceUrl = "https://older.example",
                bookUrl = "https://older.example/book/1"
            )
            val newer = current.copy(
                sourceBookKey = "newer-key",
                sourceUrl = "https://newer.example",
                bookUrl = "https://newer.example/book/1"
            )
            writeCacheEntry(cache, older, "older-source", createdAtMs = 10L)
            writeCacheEntry(cache, newer, "newer-source", createdAtMs = 20L)

            val candidates = cache.replayCandidates(current).toList()

            assertEquals(listOf("newer-source", "older-source"), candidates.map { cached -> cached.sourceLabel })
            assertEquals(listOf(20L, 10L), candidates.map { cached -> cached.createdAtMs })
            assertEquals(listOf(99), candidates.first().targetChapterIndexes)
            assertEquals(V8ChapterMarkState.WRONG, candidates.first().marks.single().state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replayCandidateBodiesAreReadOnlyWhenIterated() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val current = identity(catalogSize = 100, lastTitle = "Chapter 100")
            val newer = current.copy(
                sourceBookKey = "newer-key",
                sourceUrl = "https://newer.example",
                bookUrl = "https://newer.example/book/1"
            )
            val delayed = current.copy(
                sourceBookKey = "delayed-key",
                sourceUrl = "https://delayed.example",
                bookUrl = "https://delayed.example/book/1"
            )
            writeCacheEntry(cache, newer, "newer-source", createdAtMs = 20L)
            val delayedFile = writeCacheEntry(
                cache,
                delayed,
                "delayed-source",
                createdAtMs = 10L,
                tokenHashes = List(20_000) { index -> "token-$index-${"x".repeat(24)}" }
            )

            val candidates = cache.replayCandidates(current)
            delayedFile.writeText("{", Charsets.UTF_8)

            assertEquals(listOf("newer-source"), candidates.map { cached -> cached.sourceLabel })
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
    fun keepsCachedLowConfidenceNormalPreludeWithCurrentBoundaryRules() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 501, lastTitle = "Chapter 501")
            val oldStableMarks = (386..394).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
                listOf(
                    mark(395, V8ChapterMarkState.NORMAL, confidence = 0.67),
                    mark(396, V8ChapterMarkState.NORMAL, confidence = 0.68)
                ) +
                (397..404).map { index -> mark(index, V8ChapterMarkState.WRONG) }

            assertEquals(
                true,
                cache.save(
                    identity,
                    "source@example",
                    oldStableMarks,
                    contentDigest = "body-md5",
                    targetChapterIndexes = oldStableMarks.map { mark -> mark.chapterIndex }
                )
            )

            val cached = cache.load(identity)

            assertNotNull(cached)
            assertEquals(V8ChapterMarkState.NORMAL, cached!!.marks.single { mark -> mark.chapterIndex == 394 }.state)
            assertEquals(V8ChapterMarkState.NORMAL, cached.marks.single { mark -> mark.chapterIndex == 395 }.state)
            assertEquals(V8ChapterMarkState.NORMAL, cached.marks.single { mark -> mark.chapterIndex == 396 }.state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsCachedNormalHoleInsideBadTailWithCurrentBoundaryRules() {
        val root = Files.createTempDirectory("v8-mark-cache").toFile()
        try {
            val cache = SourceEngineV8MarkCache { root }
            val identity = identity(catalogSize = 80, lastTitle = "Chapter 80")
            val oldStableMarks = (20..27).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
                listOf(
                    mark(28, V8ChapterMarkState.WRONG),
                    mark(29, V8ChapterMarkState.WRONG),
                    mark(30, V8ChapterMarkState.NORMAL, confidence = 1.0),
                    mark(31, V8ChapterMarkState.WRONG),
                    mark(32, V8ChapterMarkState.WRONG)
                )

            assertEquals(
                true,
                cache.save(
                    identity,
                    "source@example",
                    oldStableMarks,
                    contentDigest = "body-md5",
                    targetChapterIndexes = oldStableMarks.map { mark -> mark.chapterIndex }
                )
            )

            val cached = cache.load(identity)

            assertNotNull(cached)
            assertEquals(V8ChapterMarkState.NORMAL, cached!!.marks.single { mark -> mark.chapterIndex == 30 }.state)
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
            val malformedCurrent = root.resolve("malformed-current.json")
            malformedCurrent.writeText(
                """{"schemaVersion":$SOURCE_ENGINE_INTEGRITY_MARK_SCHEMA_VERSION,"identity":""",
                Charsets.UTF_8
            )

            SourceEngineV8MarkCache { root }

            assertEquals(false, stale.exists())
            assertEquals(false, unreadable.exists())
            assertEquals(false, malformedCurrent.exists())
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
    fun refusesToCacheInconclusiveOnlyProbeResults() {
        val marks = listOf(
            mark(98, V8ChapterMarkState.INCONCLUSIVE),
            mark(99, V8ChapterMarkState.INCONCLUSIVE)
        )

        assertEquals(
            false,
            SourceEngineV8MarkCachePolicy.shouldSave(
                marks = marks,
                inputLengthsByChapterIndex = mapOf(
                    98 to 3_200,
                    99 to 3_300
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

    @Test
    fun replaysSameSourceOnlyWhenInputsAreUnchanged() {
        val identity = identity(catalogSize = 100, lastTitle = "Chapter 100")
        val inputs = (90..97).map { index -> input(index, repeatedContent(index)) }
        val fingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(inputs)
        val cached = cachedMarks(
            identity = identity,
            marks = inputs.map { input -> mark(input.index, V8ChapterMarkState.NORMAL) },
            targetIndexes = inputs.map { input -> input.index },
            fingerprints = fingerprints
        )

        val replay = SourceEngineV8ReplayCachePolicy.findReplay(
            identity = identity,
            targetIndexes = inputs.map { input -> input.index }.toSet(),
            inputFingerprintsByChapterIndex = fingerprints,
            candidates = listOf(cached)
        )
        val changedFingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(
            inputs.mapIndexed { offset, input ->
                if (offset == 0) input.copy(content = input.content + " changed ending text") else input
            }
        )

        assertNotNull(replay)
        assertEquals("same_source_exact", replay!!.reason)
        assertNull(
            SourceEngineV8ReplayCachePolicy.findReplay(
                identity = identity,
                targetIndexes = inputs.map { input -> input.index }.toSet(),
                inputFingerprintsByChapterIndex = changedFingerprints,
                candidates = listOf(cached)
            )
        )
    }

    @Test
    fun doesNotReplayWhenCatalogGainsTailChapter() {
        val cachedIdentity = identity(catalogSize = 100, lastTitle = "Chapter 100")
        val currentIdentity = identity(catalogSize = 101, lastTitle = "Chapter 101")
        val inputs = (90..97).map { index -> input(index, repeatedContent(index)) }
        val fingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(inputs)

        val replay = SourceEngineV8ReplayCachePolicy.findReplay(
            identity = currentIdentity,
            targetIndexes = inputs.map { input -> input.index }.toSet(),
            inputFingerprintsByChapterIndex = fingerprints,
            candidates = listOf(
                cachedMarks(
                    identity = cachedIdentity,
                    marks = inputs.map { input -> mark(input.index, V8ChapterMarkState.NORMAL) },
                    targetIndexes = inputs.map { input -> input.index },
                    fingerprints = fingerprints
                )
            )
        )

        assertNull(replay)
    }

    @Test
    fun replaysCrossSourceForSameCatalogWhenSampledInputsAreHighlySimilar() {
        val currentIdentity = identity(catalogSize = 100, lastTitle = "Chapter 100")
        val cachedIdentity = currentIdentity.copy(
            sourceBookKey = "https://mirror.example\nhttps://mirror.example/book/1",
            sourceUrl = "https://mirror.example",
            bookUrl = "https://mirror.example/book/1"
        )
        val cachedInputs = (90..97).map { index -> input(index, repeatedContent(index)) }
        val currentInputs = cachedInputs.map { input -> input.copy(content = input.content + " !!!") }
        val cachedFingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(cachedInputs)
        val currentFingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(currentInputs)
        val cached = cachedMarks(
            identity = cachedIdentity,
            marks = cachedInputs.map { input -> mark(input.index, V8ChapterMarkState.NORMAL) },
            targetIndexes = cachedInputs.map { input -> input.index },
            fingerprints = cachedFingerprints
        )

        val replay = SourceEngineV8ReplayCachePolicy.findReplay(
            identity = currentIdentity,
            targetIndexes = currentInputs.map { input -> input.index }.toSet(),
            inputFingerprintsByChapterIndex = currentFingerprints,
            candidates = listOf(cached)
        )

        assertNotNull(replay)
        assertEquals("cross_source_similar", replay!!.reason)
    }

    @Test
    fun doesNotReplayCrossSourceWhenSampledChapterReadabilityDiffers() {
        val currentIdentity = identity(catalogSize = 100, lastTitle = "Chapter 100")
        val cachedIdentity = currentIdentity.copy(
            sourceBookKey = "https://mirror.example\nhttps://mirror.example/book/1",
            sourceUrl = "https://mirror.example",
            bookUrl = "https://mirror.example/book/1"
        )
        val currentInputs = (90..97).map { index -> input(index, repeatedContent(index)) }
        val cachedInputs = currentInputs.map { input ->
            if (input.index == 94) input.copy(content = "请收藏本站，方便下次阅读。") else input
        }
        val currentFingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(currentInputs)
        val cachedFingerprints = SourceEngineV8ValidationDigest.computeInputFingerprints(cachedInputs)
        val cached = cachedMarks(
            identity = cachedIdentity,
            marks = cachedInputs.map { input -> mark(input.index, V8ChapterMarkState.NORMAL) },
            targetIndexes = cachedInputs.map { input -> input.index },
            fingerprints = cachedFingerprints
        )

        val replay = SourceEngineV8ReplayCachePolicy.findReplay(
            identity = currentIdentity,
            targetIndexes = currentInputs.map { input -> input.index }.toSet(),
            inputFingerprintsByChapterIndex = currentFingerprints,
            candidates = listOf(cached)
        )

        assertNull(replay)
    }

    @Test
    fun promotesReadingTailWhenCurrentCacheIsOnlyInconclusive() {
        val currentIdentity = identity(catalogSize = 1149, lastTitle = "Chapter 1149")
        val currentCached = cachedMarks(
            identity = currentIdentity,
            marks = listOf(
                mark(1148, V8ChapterMarkState.INCONCLUSIVE),
                mark(1149, V8ChapterMarkState.INCONCLUSIVE)
            ),
            targetIndexes = listOf(1148, 1149),
            fingerprints = emptyMap()
        )

        val recommendation = SourceEngineV8TailScopePolicy.recommendReadingTail(
            currentIdentity = currentIdentity,
            currentCached = currentCached,
            cachedMarksForBook = listOf(currentCached),
            currentCatalogTitles = (1..1149).map { index -> "Chapter $index" }
        )

        assertNotNull(recommendation)
        assertEquals(SourceEngineV8TailScopePolicy.REASON_CURRENT_INCONCLUSIVE_ONLY, recommendation!!.reason)
    }

    @Test
    fun promotesCatalogChangedScopeWhenOldHiddenTailWasAppended() {
        val oldIdentity = identity(catalogSize = 1139, lastTitle = "Chapter 1139")
        val currentIdentity = identity(catalogSize = 1149, lastTitle = "Chapter 1149")
        val oldCached = cachedMarks(
            identity = oldIdentity,
            marks = listOf(mark(1139, V8ChapterMarkState.WRONG)),
            targetIndexes = listOf(1139),
            fingerprints = emptyMap()
        )

        val recommendation = SourceEngineV8TailScopePolicy.recommendReadingTail(
            currentIdentity = currentIdentity,
            currentCached = null,
            cachedMarksForBook = listOf(oldCached),
            currentCatalogTitles = (1..1149).map { index -> "Chapter $index" }
        )

        assertNotNull(recommendation)
        assertEquals(SourceEngineV8TailScopePolicy.REASON_OLD_HIDDEN_TAIL_APPENDED, recommendation!!.reason)
        assertEquals(1139, recommendation.oldCatalogSize)
        assertEquals("Chapter 1139", recommendation.oldLastTitle)
    }

    @Test
    fun promotesCatalogChangedScopeWhenSameSourceCatalogChangedEvenIfOldCacheWasClean() {
        val oldIdentity = identity(catalogSize = 1139, lastTitle = "Chapter 1139")
        val currentIdentity = identity(catalogSize = 1149, lastTitle = "Chapter 1149")
        val oldCached = cachedMarks(
            identity = oldIdentity,
            marks = listOf(mark(1139, V8ChapterMarkState.NORMAL)),
            targetIndexes = listOf(1139),
            fingerprints = emptyMap()
        )

        val recommendation = SourceEngineV8TailScopePolicy.recommendReadingTail(
            currentIdentity = currentIdentity,
            currentCached = null,
            cachedMarksForBook = listOf(oldCached),
            currentCatalogTitles = (1..1149).map { index -> "Chapter $index" }
        )

        assertNotNull(recommendation)
        assertEquals(SourceEngineV8TailScopePolicy.REASON_CATALOG_IDENTITY_CHANGED, recommendation!!.reason)
    }

    @Test
    fun catalogChangedScopeTakesPriorityOverCurrentInconclusiveOnlyCache() {
        val oldIdentity = identity(catalogSize = 1139, lastTitle = "Chapter 1139")
        val currentIdentity = identity(catalogSize = 1149, lastTitle = "Chapter 1149")
        val currentCached = cachedMarks(
            identity = currentIdentity,
            marks = listOf(
                mark(1148, V8ChapterMarkState.INCONCLUSIVE),
                mark(1149, V8ChapterMarkState.INCONCLUSIVE)
            ),
            targetIndexes = listOf(1148, 1149),
            fingerprints = emptyMap()
        )
        val oldCached = cachedMarks(
            identity = oldIdentity,
            marks = listOf(mark(1139, V8ChapterMarkState.WRONG)),
            targetIndexes = listOf(1139),
            fingerprints = emptyMap()
        )

        val recommendation = SourceEngineV8TailScopePolicy.recommendReadingTail(
            currentIdentity = currentIdentity,
            currentCached = currentCached,
            cachedMarksForBook = listOf(currentCached, oldCached),
            currentCatalogTitles = (1..1149).map { index -> "Chapter $index" }
        )

        assertNotNull(recommendation)
        assertEquals(SourceEngineV8TailScopePolicy.REASON_OLD_HIDDEN_TAIL_APPENDED, recommendation!!.reason)
    }

    @Test
    fun doesNotPromoteCatalogChangedScopeFromDifferentSourceBookCache() {
        val oldIdentity = identity(catalogSize = 1139, lastTitle = "Chapter 1139").copy(
            sourceBookKey = "https://other-source.example\nhttps://other-source.example/book/1",
            sourceUrl = "https://other-source.example",
            bookUrl = "https://other-source.example/book/1"
        )
        val currentIdentity = identity(catalogSize = 1149, lastTitle = "Chapter 1149")
        val oldCached = cachedMarks(
            identity = oldIdentity,
            marks = listOf(mark(1139, V8ChapterMarkState.WRONG)),
            targetIndexes = listOf(1139),
            fingerprints = emptyMap()
        )

        val recommendation = SourceEngineV8TailScopePolicy.recommendReadingTail(
            currentIdentity = currentIdentity,
            currentCached = null,
            cachedMarksForBook = listOf(oldCached),
            currentCatalogTitles = (1..1149).map { index -> "Chapter $index" }
        )

        assertNull(recommendation)
    }

    @Test
    fun catalogChangedTargetWindowUsesContinuousTailRiskWindowInsteadOfInitialSamplesOnly() {
        val chapters = (1..200).map { index -> V8ValidationChapter(index, "Chapter $index") }
        val initialSamples = ((181..200) + listOf(153, 137)).toSet()
        val allTargets = (1..200).toSet()

        val readingLightTargets = SourceEngineV8InitialTargetPolicy.selectTailTargets(
            chapters = chapters,
            initialTargetIndexes = initialSamples,
            allTargetIndexes = allTargets,
            targetLimit = 16
        )
        val catalogChangedTargets = SourceEngineV8InitialTargetPolicy.selectTailTargets(
            chapters = chapters,
            initialTargetIndexes = initialSamples,
            allTargetIndexes = allTargets,
            targetLimit = 160
        )

        assertEquals((185..200).toList(), readingLightTargets.toList())
        assertEquals((41..200).toList(), catalogChangedTargets.toList())
    }

    private fun writeCacheEntry(
        cache: SourceEngineV8MarkCache,
        identity: SourceEngineV8MarkCache.Identity,
        sourceLabel: String,
        createdAtMs: Long,
        tokenHashes: List<String> = emptyList()
    ): java.io.File {
        val file = cache.fileFor(identity)
        file.parentFile?.mkdirs()
        file.writeText(
            Gson().toJson(
                linkedMapOf(
                    "schemaVersion" to SOURCE_ENGINE_INTEGRITY_MARK_SCHEMA_VERSION,
                    "identity" to identity,
                    "sourceLabel" to sourceLabel,
                    "createdAtMs" to createdAtMs,
                    "contentDigest" to "body-md5",
                    "targetChapterIndexes" to listOf(99),
                    "inputFingerprintsByChapterIndex" to mapOf(
                        99 to SourceEngineV8MarkCache.InputFingerprint(
                            inputDigest = "input-md5",
                            normalizedLength = 4_000,
                            tokenHashes = tokenHashes
                        )
                    ),
                    "marks" to listOf(mark(99, V8ChapterMarkState.WRONG))
                )
            ),
            Charsets.UTF_8
        )
        return file
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
        qualityType: V8ChapterQualityType? = null,
        confidence: Double = 0.9
    ): V8ChapterMarkResult {
        return V8ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "Chapter $index",
            state = state,
            confidence = confidence,
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

    private fun cachedMarks(
        identity: SourceEngineV8MarkCache.Identity,
        marks: List<V8ChapterMarkResult>,
        targetIndexes: List<Int>,
        fingerprints: Map<Int, SourceEngineV8MarkCache.InputFingerprint>
    ): SourceEngineV8MarkCache.CachedMarks {
        return SourceEngineV8MarkCache.CachedMarks(
            identity = identity,
            sourceLabel = "source@example",
            marks = marks,
            contentDigest = "digest",
            targetChapterIndexes = targetIndexes,
            inputFingerprintsByChapterIndex = fingerprints,
            createdAtMs = 1L
        )
    }

    private fun input(index: Int, content: String): V8ChapterInput {
        return V8ChapterInput(
            index = index,
            title = "Chapter $index",
            content = content
        )
    }

    private fun repeatedContent(index: Int, prefix: String = "same"): String {
        return (1..50).joinToString(" ") { part -> "$prefix chapter $index paragraph $part keeps enough words" }
    }
}
