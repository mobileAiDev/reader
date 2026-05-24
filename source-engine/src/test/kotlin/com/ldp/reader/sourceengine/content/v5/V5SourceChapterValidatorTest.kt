package com.ldp.reader.sourceengine.content.v5

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class V5SourceChapterValidatorTest {
    @Test
    fun keepsV5RunArtifactsScopedToOneSource() {
        val validator = V5SourceChapterValidator()
        val chapters = normalBookChapters()

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = chapters
            )
        )

        assertEquals("source-a", result.sourceKey)
        assertEquals(chapters.size, result.marks.size)
        assertTrue(result.report.logs.any { line -> line.contains("quality") })
        assertTrue(result.marks.all { mark -> mark.chapterTitle.isNotBlank() })
    }

    @Test
    fun mapsQualityGateOutputsToChapterMarks() {
        val validator = V5SourceChapterValidator()
        val chapters = normalBookChapters().take(4) + listOf(
            ChapterInput(
                index = 4,
                title = "完结感言",
                content = endingPostscriptSnippet()
            ),
            ChapterInput(
                index = 5,
                title = "第六章 页面壳",
                content = pureBadExtractionSnippet()
            )
        )

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = chapters
            )
        )

        assertEquals(V5ChapterMarkState.NON_STORY, result.marks.first { it.chapterIndex == 4 }.state)
        assertEquals(V5ChapterMarkState.BAD_EXTRACTION, result.marks.first { it.chapterIndex == 5 }.state)
        assertEquals(4, result.latestNormalOrdinal)
        assertEquals(5, result.firstBadTailOrdinal)
    }

    @Test
    fun mapsConfirmedPollutionSuggestionToWrongMark() {
        val validator = V5SourceChapterValidator {
            NovelPollutionAnalyzer(
                AlgorithmConfig(
                    chunkSize = 800,
                    chunkOverlap = 120,
                    minFeatureFrequency = 3,
                    minFeatureChapterCount = 2,
                    refineRounds = 1,
                    minSuffixChunks = 1
                )
            )
        }
        val chapters = normalBookChapters() + ChapterInput(
            index = 8,
            title = "后段混入",
            content = normalParagraph(repeat = 120) + alienParagraph(repeat = 70)
        )

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = chapters
            )
        )

        val mark = result.marks.first { it.chapterIndex == 8 }
        assertEquals(V5ChapterMarkState.WRONG, mark.state)
        assertEquals(NovelStateOutputType.POLLUTED_SUFFIX, mark.suggestionState)
        assertEquals(8, result.latestNormalOrdinal)
        assertEquals(9, result.firstBadTailOrdinal)
    }

    @Test
    fun detectsEarlyPollutedSuffixAfterShortValidOpening() {
        val validator = V5SourceChapterValidator()
        val chapters = normalBookChapters() + ChapterInput(
            index = 8,
            title = "第九章 取剑",
            content = "陈迹走到老耳朵身边，看着甲板外的大海，低声问起青云宗的旧事。" +
                "\n" +
                alienParagraph(repeat = 50)
        )

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = chapters
            )
        )

        val mark = result.marks.first { it.chapterIndex == 8 }
        assertEquals(debugSummary(result, mark), V5ChapterMarkState.WRONG, mark.state)
        assertTrue(
            debugSummary(result, mark),
            mark.suggestionState == NovelStateOutputType.POLLUTED_SUFFIX ||
                mark.suggestionState == NovelStateOutputType.POLLUTED_RUN
        )
    }

    @Test
    fun keepsNormalEarlySceneTransitionClean() {
        val validator = V5SourceChapterValidator()
        val chapters = normalBookChapters() + ChapterInput(
            index = 8,
            title = "第九章 入谷",
            content = "陈迹离开甲板后，望见远处山门，心中想起老耳朵先前说过的话。" +
                "\n" +
                normalParagraph(repeat = 50)
        )

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = chapters
            )
        )

        val mark = result.marks.first { it.chapterIndex == 8 }
        assertEquals(debugSummary(result, mark), V5ChapterMarkState.NORMAL, mark.state)
    }

    @Test
    fun keepsEarlyNewArcCleanWhenFutureChaptersContinueIt() {
        val validator = V5SourceChapterValidator()
        val chapters = normalBookChapters() + listOf(
            ChapterInput(
                index = 8,
                title = "第九章 白塔",
                content = "陈迹离开甲板后，听老耳朵提起白塔城的旧案。" +
                    "\n" +
                    newArcParagraph(repeat = 50)
            ),
            ChapterInput(
                index = 9,
                title = "第十章 星火令",
                content = newArcParagraph(repeat = 70)
            ),
            ChapterInput(
                index = 10,
                title = "第十一章 林岚",
                content = newArcParagraph(repeat = 70)
            )
        )

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = chapters
            )
        )

        val mark = result.marks.first { it.chapterIndex == 8 }
        assertEquals(debugSummary(result, mark), V5ChapterMarkState.NORMAL, mark.state)
    }

    @Test
    fun emitsRunAndMarkDiagnosticsToSink() {
        val diagnostics = ArrayList<String>()
        val validator = V5SourceChapterValidator()

        validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = normalBookChapters().take(2),
                diagnosticSink = V5DiagnosticSink(diagnostics::add)
            )
        )

        assertTrue(diagnostics.any { line -> line.startsWith("v5.run.start") })
        assertTrue(diagnostics.any { line -> line.startsWith("v5.run.analyze.finish") })
        assertTrue(diagnostics.any { line -> line.startsWith("v5.mark.finish") })
    }

    @Test
    fun returnsOnlyMarkableChapterMarksWhenProvided() {
        val validator = V5SourceChapterValidator()

        val result = validator.validate(
            V5SourceRunRequest(
                title = "测试书",
                author = "作者",
                sourceKey = "source-a",
                chapters = normalBookChapters(),
                markableChapterIndexes = setOf(6, 7)
            )
        )

        assertEquals(setOf(6, 7), result.marks.map { mark -> mark.chapterIndex }.toSet())
    }

    @Test
    fun expandsDenseTailPollutionAcrossNormalGaps() {
        val marks = (0 until 10).map { index ->
            mark(
                index = index,
                state = if (index in setOf(3, 5, 7, 9)) V5ChapterMarkState.WRONG else V5ChapterMarkState.NORMAL
            )
        }

        val expanded = V5TailContiguousPollutionExpander().expand(marks)

        assertEquals(3, expanded.startChapterIndex)
        assertEquals(listOf(4, 6, 8), expanded.filledChapterIndexes)
        assertTrue(
            expanded.marks
                .filter { mark -> mark.chapterIndex in 3..9 }
                .all { mark -> mark.state == V5ChapterMarkState.WRONG }
        )
    }

    @Test
    fun backfillsBoundaryCandidatesBeforeConfirmedBadCluster() {
        val marks = (0 until 14).map { index ->
            mark(
                index = index,
                state = if (index in setOf(10, 11, 13)) V5ChapterMarkState.WRONG else V5ChapterMarkState.NORMAL
            )
        }

        val expanded = V5TailContiguousPollutionExpander().expand(
            marks,
            boundaryCandidates = listOf(
                boundaryCandidate(8),
                boundaryCandidate(9)
            )
        )

        assertEquals(listOf(8, 9), expanded.boundaryFilledChapterIndexes)
        assertEquals(V5ChapterMarkState.WRONG, expanded.marks.first { it.chapterIndex == 8 }.state)
        assertEquals(V5ChapterMarkState.WRONG, expanded.marks.first { it.chapterIndex == 9 }.state)
        assertEquals(V5ChapterMarkState.NORMAL, expanded.marks.first { it.chapterIndex == 7 }.state)
    }

    @Test
    fun doesNotBackfillBoundaryCandidateWithoutFollowingBadCluster() {
        val marks = (0 until 14).map { index ->
            mark(
                index = index,
                state = if (index == 10) V5ChapterMarkState.WRONG else V5ChapterMarkState.NORMAL
            )
        }

        val expanded = V5TailContiguousPollutionExpander().expand(
            marks,
            boundaryCandidates = listOf(boundaryCandidate(9))
        )

        assertTrue(expanded.boundaryFilledChapterIndexes.isEmpty())
        assertEquals(V5ChapterMarkState.NORMAL, expanded.marks.first { it.chapterIndex == 9 }.state)
    }

    @Test
    fun fillsSmallInternalNormalGapBetweenConfirmedBadRuns() {
        val marks = (0 until 14).map { index ->
            mark(
                index = index,
                state = if (index in setOf(3, 4, 6, 7)) V5ChapterMarkState.WRONG else V5ChapterMarkState.NORMAL
            )
        }

        val expanded = V5TailContiguousPollutionExpander().expand(
            marks,
            boundaryCandidates = listOf(boundaryCandidate(5))
        )

        assertTrue(5 in expanded.boundaryFilledChapterIndexes + expanded.gapFilledChapterIndexes)
        assertEquals(V5ChapterMarkState.WRONG, expanded.marks.first { it.chapterIndex == 5 }.state)
    }

    @Test
    fun doesNotFillInternalGapWithoutCandidateEvidence() {
        val marks = (0 until 14).map { index ->
            mark(
                index = index,
                state = if (index in setOf(3, 4, 6, 7)) V5ChapterMarkState.WRONG else V5ChapterMarkState.NORMAL
            )
        }

        val expanded = V5TailContiguousPollutionExpander().expand(marks)

        assertTrue(expanded.gapFilledChapterIndexes.isEmpty())
        assertEquals(V5ChapterMarkState.NORMAL, expanded.marks.first { it.chapterIndex == 5 }.state)
    }

    @Test
    fun doesNotFillSingleCleanGapBetweenIsolatedWrongMarks() {
        val marks = (0 until 6).map { index ->
            mark(
                index = index,
                state = if (index in setOf(2, 4)) V5ChapterMarkState.WRONG else V5ChapterMarkState.NORMAL
            )
        }

        val expanded = V5TailContiguousPollutionExpander().expand(marks)

        assertTrue(expanded.gapFilledChapterIndexes.isEmpty())
        assertEquals(V5ChapterMarkState.NORMAL, expanded.marks.first { it.chapterIndex == 3 }.state)
    }

    @Test
    fun fillsBridgeGapWhenShortFragmentedFullChapterAnchorsWrongNeighbor() {
        val marks = (0 until 6).map { index ->
            when (index) {
                2 -> mark(
                    index = index,
                    state = V5ChapterMarkState.WRONG,
                    reasons = listOf(V5_SHORT_FRAGMENTED_FULL_CHAPTER_REASON)
                )
                4 -> mark(index = index, state = V5ChapterMarkState.WRONG)
                else -> mark(index = index, state = V5ChapterMarkState.NORMAL)
            }
        }

        val expanded = V5TailContiguousPollutionExpander().expand(marks)

        assertEquals(listOf(3), expanded.gapFilledChapterIndexes)
        assertEquals(V5ChapterMarkState.WRONG, expanded.marks.first { it.chapterIndex == 3 }.state)
    }

    @Test
    fun detectsOnlyClearlyDifferentCrossSourceText() {
        val sameBookLeft = normalParagraph(repeat = 80)
        val sameBookRight = normalParagraph(repeat = 80).replace("陈迹", "陈迹")
        val alien = alienParagraph(repeat = 80)

        assertTrue(V5SourceTextSimilarity.score(sameBookLeft, sameBookRight) > 0.90)
        assertTrue(V5SourceTextSimilarity.clearlyDissimilar(sameBookLeft, alien))
    }

    @Test
    fun replayAuditedRawCorpusCaseWithProductionValidator() {
        assumeTrue(
            "Set -Dv5RawCorpusParity=true to replay audited raw corpus with production validator.",
            System.getProperty("v5RawCorpusParity") == "true"
        )
        val corpusPath = "algorithm-test/test-datasets/raw-corpus-101-bundle/raw-corpus-101/" +
            "device-full/extracted-wsl/fetch-batch-1779484863140/016-叩问仙道-1779485161947"
        val root = listOf(File(corpusPath), File("../$corpusPath"))
            .firstOrNull { file -> file.isDirectory }
            ?: File(corpusPath)
        assertTrue("raw corpus case missing: ${root.absolutePath}", root.isDirectory)
        val chapterFiles = File(root, "chapters")
            .listFiles { file -> file.isFile && file.extension == "txt" }
            .orEmpty()
            .sortedWith(compareBy({ parseChapterIndex(it.name) }, { it.name }))
        val planner = V5ChapterValidationPlanner()
        val plan = planner.selectChapters(
            chapters = chapterFiles.map { file ->
                V5ValidationChapter(parseChapterIndex(file.name), parseChapterTitle(file.name))
            },
            readContent = { position, _ -> chapterFiles[position].readText(Charsets.UTF_8) }
        )
        val selectedChapters = plan.analysisPositions.map { position ->
            val file = chapterFiles[position]
            ChapterInput(
                index = parseChapterIndex(file.name),
                title = parseChapterTitle(file.name),
                content = file.readText(Charsets.UTF_8)
            )
        }

        val result = V5SourceChapterValidator().validate(
            V5SourceRunRequest(
                title = "叩问仙道",
                author = "雨打青石",
                sourceKey = "raw-corpus-016",
                chapters = selectedChapters,
                seedChapterIndexes = plan.contextIndexes
            )
        )
        val output = File("build/tmp/v5-production-parity/book-016-marks.tsv").apply {
            parentFile.mkdirs()
        }
        val marksByIndex = result.marks.associateBy { mark -> mark.chapterIndex }
        output.writeText(
            buildString {
                appendLine("index\trole\ttitle\tstate\tquality\tsuggestion\taction\tconfidence")
                plan.analysisPositions.forEach { position ->
                    val file = chapterFiles[position]
                    val index = parseChapterIndex(file.name)
                    val mark = marksByIndex[index]
                    appendLine(
                        listOf(
                            index.toString(),
                            plan.rolesByChapterIndex[index].orEmpty(),
                            parseChapterTitle(file.name),
                            mark?.state?.name.orEmpty(),
                            mark?.qualityType?.name.orEmpty(),
                            mark?.suggestionState?.name.orEmpty(),
                            mark?.action?.name.orEmpty(),
                            mark?.confidence?.let { "%.3f".format(it) }.orEmpty()
                        ).joinToString("\t")
                    )
                }
                appendLine()
                appendLine("reportSuggestions=${result.report.suggestions.map { it.chapterIndex }.sorted().joinToString(",")}")
                appendLine("reportSuggestionCount=${result.report.suggestions.size}")
            }
        )

        assertEquals(V5ChapterMarkState.WRONG, marksByIndex[2783]?.state)
        assertEquals(V5ChapterMarkState.WRONG, marksByIndex[2784]?.state)
        assertEquals(V5ChapterMarkState.WRONG, marksByIndex[2785]?.state)
        assertEquals(V5ChapterMarkState.WRONG, marksByIndex[2786]?.state)
        assertEquals(V5ChapterMarkState.WRONG, marksByIndex[2787]?.state)
        assertTrue(output.isFile)
    }

    private fun normalBookChapters(): List<ChapterInput> {
        return (0 until 8).map { index ->
            ChapterInput(
                index = index,
                title = "第${index + 1}章",
                content = normalParagraph(repeat = 80)
            )
        }
    }

    private fun normalParagraph(repeat: Int): String {
        return buildString {
            repeat(repeat) {
                append("陈迹前往青云宗，施展霄剑诀，抵达落星谷，祭出玄冰剑，突破筑基期。")
                append("陈迹与青云宗同行。")
            }
        }
    }

    private fun alienParagraph(repeat: Int): String {
        return buildString {
            repeat(repeat) {
                append("叶辰进入星辉集团，董事会设在江州城，顾婉儿签下豪门合同。")
                append("叶辰与顾婉儿进入星辉集团。")
            }
        }
    }

    private fun newArcParagraph(repeat: Int): String {
        return buildString {
            repeat(repeat) {
                append("林岚抵达白塔城，取出星火令，与陈迹商议青云宗密藏。")
                append("白塔城的旧案牵连玄冰剑，林岚决定同行。")
            }
        }
    }

    private fun endingPostscriptSnippet(): String {
        return """
            码字码到没什么灵感，想了想，就给完本的太一道果写一下完结感言。
            从23年五月到今年四月，近两年的时间，太一道果总算是完本了。
            感谢一路过来陪伴的书友，谢谢你们的支持。
            最后，就是推一推新书《人在高武，言出法随》了。
            总之就是求收藏，求追读。
        """.trimIndent()
    }

    private fun pureBadExtractionSnippet(): String {
        return """
            第2868章第二计划启动_仙人消失之后全文免费阅读 - 潇湘书院
            <script>
            function ajaxLog(pramas){
            var xhr = new XMLHttpRequest();
            xhr.open("POST", '/api/user/guest/log', true);
            xhr.setRequestHeader('Content-type', 'application/json');
            xhr.send(JSON.stringify(pramas))
            }
            document.querySelector('#reader').appendChild(script);
            window.localStorage.setItem('source', 'pc_jump');
            </script>
            登录
            首页
            上一章
            下一章
            返回目录
            相关推荐
            手机阅读
        """.trimIndent()
    }

    private fun debugSummary(result: V5SourceRunResult, mark: V5ChapterMarkResult): String {
        return buildString {
            appendLine("mark=$mark")
            appendLine("suggestions=${result.report.suggestions}")
            result.report.logs.takeLast(30).forEach { line -> appendLine(line) }
        }
    }

    private fun parseChapterIndex(name: String): Int {
        return Regex("""^(\d+)-""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseChapterTitle(name: String): String {
        return name.removeSuffix(".txt").replace(Regex("""^\d+-"""), "").replace('_', ' ')
    }

    private fun mark(
        index: Int,
        state: V5ChapterMarkState,
        reasons: List<String> = emptyList()
    ): V5ChapterMarkResult {
        return V5ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "第${index + 1}章",
            state = state,
            confidence = 0.95,
            qualityType = ChapterQualityType.CLEAN_STORY,
            suggestionState = if (state == V5ChapterMarkState.WRONG) NovelStateOutputType.POLLUTED_RUN else null,
            action = if (state == V5ChapterMarkState.WRONG) CleanAction.SUGGEST_DELETE else null,
            reasons = reasons
        )
    }

    private fun boundaryCandidate(index: Int): V5BoundaryBackfillCandidate {
        return V5BoundaryBackfillCandidate(
            chapterIndex = index,
            chapterTitle = "第${index + 1}章",
            stateType = NovelStateOutputType.POLLUTED_RUN,
            action = CleanAction.MARK_ONLY,
            confidence = 0.72,
            reasons = listOf("near-miss alien run")
        )
    }
}
