package com.ldp.reader.algorithmtest.core

import com.ldp.reader.algorithmtest.source.BatchNovelTargets
import com.ldp.reader.sourceengine.content.v5.ChapterInput as V5ChapterInput
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import com.ldp.reader.sourceengine.content.v5.V5ChapterValidationPlanner
import com.ldp.reader.sourceengine.content.v5.V5DiagnosticSink
import com.ldp.reader.sourceengine.content.v5.V5SourceChapterValidator
import com.ldp.reader.sourceengine.content.v5.V5SourceRunRequest
import com.ldp.reader.sourceengine.content.v5.V5ValidationChapter
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RawCorpusTargetReplayTest {
    private val validationPlanner = V5ChapterValidationPlanner()

    @Test
    fun replayTargetedFetchedRawCorpusWithMemoryBackfill() {
        assumeTrue(
            "Set -DrawCorpusTargetReplay=true to replay targeted fetched raw corpus.",
            System.getProperty("rawCorpusTargetReplay") == "true"
        )

        val root = File(
            System.getProperty("rawCorpusTargetRoot").takeIf { value -> !value.isNullOrBlank() }
                ?: "algorithm-test/build/raw-corpus-101/tmp-replay-existing"
        )
        assertTrue("target raw corpus root missing: ${root.absolutePath}", root.isDirectory)

        val outputRoot = File(
            System.getProperty("rawCorpusTargetOutput").takeIf { value -> !value.isNullOrBlank() }
                ?: "algorithm-test/build/raw-corpus-target-replay-${System.currentTimeMillis()}"
        ).apply { mkdirs() }
        val items = findItems(root)
        assertTrue("expected at least one target raw corpus item", items.isNotEmpty())
        val expectedNoSuggestBooks = parseBookNoSet(System.getProperty("rawCorpusExpectedNoSuggestBooks").orEmpty())
        val expectedSuggestIndexes = parseExpectedSuggestIndexes(
            System.getProperty("rawCorpusExpectedSuggestIndexes").orEmpty()
        )

        val failures = ArrayList<String>()
        val summary = File(outputRoot, "summary.tsv")
        summary.writeText(
            "bookNo\ttitle\tauthor\tfullChapters\tanalysisChapters\ttargetChapters\tcontextChapters\t" +
                "usableContext\tchunks\twrongMarks\twrongMarkIndexes\tnonStoryMarks\tbadExtractionMarks\t" +
                "rawSuggestions\trawSuggestionIndexes\treportDir\n"
        )

        items.forEach { item ->
            val reportDir = File(outputRoot, item.reportName).apply { mkdirs() }
            val chapterFiles = item.listChapters()
            val selection = selectReplaySelection(chapterFiles)
            if (selection.usableContext < V5ChapterValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS) {
                failures.add(
                    "${item.reportName}: usable context ${selection.usableContext} < " +
                        V5ChapterValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS
                )
            }
            val chapters = selection.analysis.map { file -> file.readChapter() }
            val result = V5SourceChapterValidator().validate(
                V5SourceRunRequest(
                    title = item.title,
                    author = item.author,
                    sourceKey = item.reportName,
                    chapters = chapters,
                    seedChapterIndexes = selection.contextIndexes,
                    markableChapterIndexes = selection.targetIndexes
                )
            )
            val report = result.report
            if (report.logs.any { line -> line.contains("quality.abort") }) {
                failures.add("${item.reportName}: analyzer still requested more clean story context")
            }

            val targetMarks = result.marks.filter { mark -> mark.chapterIndex in selection.targetIndexes }
            val wrongMarkIndexes = targetMarks
                .filter { mark -> mark.state == V5ChapterMarkState.WRONG }
                .map { mark -> mark.chapterIndex }
                .distinct()
                .sorted()
            val nonStoryCount = targetMarks.count { mark -> mark.state == V5ChapterMarkState.NON_STORY }
            val badExtractionCount = targetMarks.count { mark -> mark.state == V5ChapterMarkState.BAD_EXTRACTION }
            val rawSuggestionIndexes = report.suggestions
                .filter { suggestion -> suggestion.chapterIndex in selection.targetIndexes }
                .map { suggestion -> suggestion.chapterIndex }
                .distinct()
                .sorted()
            if (item.bookNo in expectedNoSuggestBooks && wrongMarkIndexes.isNotEmpty()) {
                failures.add(
                    "${item.reportName}: expected no target wrong marks, actual=${wrongMarkIndexes.joinToString(",")}"
                )
            }
            expectedSuggestIndexes[item.bookNo]?.let { expected ->
                val missing = expected - wrongMarkIndexes.toSet()
                if (missing.isNotEmpty()) {
                    failures.add(
                        "${item.reportName}: expected wrong marks missing=${missing.sorted().joinToString(",")} " +
                            "actual=${wrongMarkIndexes.joinToString(",")}"
                    )
                }
            }
            File(reportDir, "algorithm-report.txt").writeText(report.humanSummary(maxFeatures = 20))
            File(reportDir, "algorithm-log.txt").writeText(report.logs.joinToString("\n"))
            File(reportDir, "v5-marks.tsv").writeText(
                buildString {
                    appendLine("index\trole\ttitle\tstate\tquality\tsuggestion\taction\tconfidence\treasons")
                    result.marks.forEach { mark ->
                        appendLine(
                            listOf(
                                mark.chapterIndex.toString(),
                                selection.rolesByChapterIndex[mark.chapterIndex].orEmpty(),
                                mark.chapterTitle,
                                mark.state.name,
                                mark.qualityType?.name.orEmpty(),
                                mark.suggestionState?.name.orEmpty(),
                                mark.action?.name.orEmpty(),
                                "%.3f".format(mark.confidence),
                                mark.reasons.joinToString("|")
                            ).joinToString("\t")
                        )
                    }
                }
            )
            File(reportDir, "validation-plan.txt").writeText(selection.describe())
            File(reportDir, "source-dir.txt").writeText(item.sourceDir.absolutePath)
            summary.appendText(
                listOf(
                    item.bookNo.toString(),
                    item.title,
                    item.author,
                    chapterFiles.size.toString(),
                    selection.analysis.size.toString(),
                    selection.targetIndexes.size.toString(),
                    selection.contextIndexes.size.toString(),
                    selection.usableContext.toString(),
                    report.chunkCount.toString(),
                    wrongMarkIndexes.size.toString(),
                    wrongMarkIndexes.joinToString(","),
                    nonStoryCount.toString(),
                    badExtractionCount.toString(),
                    rawSuggestionIndexes.size.toString(),
                    rawSuggestionIndexes.joinToString(","),
                    reportDir.absolutePath
                ).joinToString("\t") + "\n"
            )
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun findItems(root: File): List<TargetItem> {
        val requestedBookNos = System.getProperty("rawCorpusTargetBooks")
            .orEmpty()
            .split(',')
            .mapNotNull { value -> value.trim().toIntOrNull() }
            .toSet()
        return root.walkTopDown()
            .filter { file -> file.isFile && file.name == "fetch-report.txt" }
            .mapNotNull { report ->
                val sourceDir = report.parentFile ?: return@mapNotNull null
                val bookNo = parseBookNo(sourceDir.name) ?: return@mapNotNull null
                val target = BatchNovelTargets.all.getOrNull(bookNo - 1)
                TargetItem(
                    bookNo = bookNo,
                    title = target?.title ?: sourceDir.name,
                    author = target?.author.orEmpty(),
                    sourceDir = sourceDir
                )
            }
            .filter { item -> requestedBookNos.isEmpty() || item.bookNo in requestedBookNos }
            .sortedBy { item -> item.bookNo }
            .toList()
    }

    private fun selectReplaySelection(chapterFiles: List<TargetChapterFile>): TargetReplaySelection {
        val plan = validationPlanner.selectChapters(
            chapters = chapterFiles.map { file -> V5ValidationChapter(file.index, file.title) },
            readContent = { position, _ -> chapterFiles[position].file.readText(Charsets.UTF_8) },
            diagnosticSink = V5DiagnosticSink.None
        )
        return TargetReplaySelection(
            analysis = plan.analysisPositions.map { position -> chapterFiles[position] },
            targetIndexes = plan.targetIndexes,
            contextIndexes = plan.contextIndexes,
            rolesByChapterIndex = plan.rolesByChapterIndex,
            diagnostics = plan.diagnostics,
            usableContext = plan.usableContext
        )
    }

    private fun TargetItem.listChapters(): List<TargetChapterFile> {
        return File(sourceDir, "chapters")
            .listFiles { file -> file.isFile && file.extension == "txt" }
            .orEmpty()
            .sortedWith(compareBy({ parseChapterIndex(it.name) }, { it.name }))
            .map { file ->
                TargetChapterFile(
                    index = parseChapterIndex(file.name),
                    title = parseChapterTitle(file.name),
                    file = file
                )
            }
    }

    private fun parseBookNo(name: String): Int? {
        return Regex("""(?:^book-|^)(\d{1,3})(?:-|$)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseChapterIndex(name: String): Int {
        return Regex("""^(\d+)-""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseChapterTitle(name: String): String {
        return name.removeSuffix(".txt").replace(Regex("""^\d+-"""), "").replace('_', ' ')
    }

    private fun parseBookNoSet(value: String): Set<Int> {
        return value
            .split(',')
            .mapNotNull { token -> token.trim().toIntOrNull() }
            .toSet()
    }

    private fun parseExpectedSuggestIndexes(value: String): Map<Int, Set<Int>> {
        if (value.isBlank()) return emptyMap()
        return value
            .split(';')
            .mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                val bookNo = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val indexes = parts.getOrNull(1)
                    .orEmpty()
                    .split(',')
                    .mapNotNull { token -> token.trim().toIntOrNull() }
                    .toSet()
                if (indexes.isEmpty()) null else bookNo to indexes
            }
            .toMap()
    }

    private data class TargetItem(
        val bookNo: Int,
        val title: String,
        val author: String,
        val sourceDir: File
    ) {
        val reportName: String = "book-${bookNo.toString().padStart(3, '0')}"
    }

    private data class TargetChapterFile(
        val index: Int,
        val title: String,
        val file: File
    ) {
        fun readChapter(): V5ChapterInput = V5ChapterInput(index, title, file.readText(Charsets.UTF_8))
    }

    private data class TargetReplaySelection(
        val analysis: List<TargetChapterFile>,
        val targetIndexes: Set<Int>,
        val contextIndexes: Set<Int>,
        val rolesByChapterIndex: Map<Int, String>,
        val diagnostics: List<String>,
        val usableContext: Int
    ) {
        fun describe(): String {
            return buildString {
                appendLine("analysisChapters=${analysis.size}")
                appendLine("targetChapters=${targetIndexes.size}")
                appendLine("contextChapters=${contextIndexes.size}")
                appendLine("usableContext=$usableContext")
                diagnostics.forEach { diagnostic -> appendLine(diagnostic) }
                analysis.forEach { chapter ->
                    appendLine("${chapter.index}\t${rolesByChapterIndex[chapter.index].orEmpty()}\t${chapter.title}")
                }
            }
        }
    }

}
