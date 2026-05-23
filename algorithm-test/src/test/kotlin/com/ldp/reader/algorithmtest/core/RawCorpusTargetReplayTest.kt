package com.ldp.reader.algorithmtest.core

import com.ldp.reader.algorithmtest.source.BatchNovelTargets
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RawCorpusTargetReplayTest {
    private val qualityGate = ChapterQualityGate()

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

        val failures = ArrayList<String>()
        val summary = File(outputRoot, "summary.tsv")
        summary.writeText(
            "bookNo\ttitle\tauthor\tfullChapters\tanalysisChapters\ttargetChapters\tcontextChapters\t" +
                "usableContext\tchunks\tsuggestions\tsuggestionIndexes\treportDir\n"
        )

        items.forEach { item ->
            val reportDir = File(outputRoot, item.reportName).apply { mkdirs() }
            val chapterFiles = item.listChapters()
            val sample = selectSample(chapterFiles)
            if (sample.usableContext < MIN_USABLE_CONTEXT_CHAPTERS) {
                failures.add("${item.reportName}: usable context ${sample.usableContext} < $MIN_USABLE_CONTEXT_CHAPTERS")
            }
            val chapters = sample.analysis.map { file -> file.readChapter() }
            val report = NovelPollutionAnalyzer().analyze(
                title = item.title,
                author = item.author,
                chapters = chapters,
                seedChapterIndexes = sample.contextIndexes
            )
            if (report.logs.any { line -> line.contains("quality.abort") }) {
                failures.add("${item.reportName}: analyzer still requested more clean story context")
            }

            val suggestionIndexes = report.suggestions
                .filter { suggestion -> suggestion.chapterIndex in sample.targetIndexes }
                .map { suggestion -> suggestion.chapterIndex }
                .distinct()
                .sorted()
            File(reportDir, "algorithm-report.txt").writeText(report.humanSummary(maxFeatures = 20))
            File(reportDir, "algorithm-log.txt").writeText(report.logs.joinToString("\n"))
            File(reportDir, "sampling-plan.txt").writeText(sample.describe())
            File(reportDir, "source-dir.txt").writeText(item.sourceDir.absolutePath)
            summary.appendText(
                listOf(
                    item.bookNo.toString(),
                    item.title,
                    item.author,
                    chapterFiles.size.toString(),
                    sample.analysis.size.toString(),
                    sample.targetIndexes.size.toString(),
                    sample.contextIndexes.size.toString(),
                    sample.usableContext.toString(),
                    report.chunkCount.toString(),
                    suggestionIndexes.size.toString(),
                    suggestionIndexes.joinToString(","),
                    reportDir.absolutePath
                ).joinToString("\t") + "\n"
            )
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun findItems(root: File): List<TargetItem> {
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
            .sortedBy { item -> item.bookNo }
            .toList()
    }

    private fun selectSample(chapterFiles: List<TargetChapterFile>): TargetReplaySample {
        val chapterCount = chapterFiles.size
        if (chapterCount <= 0) {
            return TargetReplaySample(emptyList(), emptySet(), emptySet(), emptyMap(), emptyList(), 0)
        }
        val tailStart = (chapterCount - TAIL_RISK_WINDOW_CHAPTERS).coerceAtLeast(0)
        val targetRolesByPosition = selectTargetProbePositions(chapterCount, tailStart)
        val targetPositions = targetRolesByPosition.keys.sorted()
        val nearContextStart = (tailStart - NEAR_CONTEXT_SPAN).coerceAtLeast(0)
        val nearContextPositions = evenlySpacedPositions(nearContextStart, tailStart, NEAR_CONTEXT_SAMPLES)
        val midContextStart = (chapterCount * 35 / 100).coerceAtMost(nearContextStart)
        val midContextPositions = evenlySpacedPositions(midContextStart, nearContextStart, MID_CONTEXT_SAMPLES)
        val longAnchorPositions = evenlySpacedPositions(0, midContextStart, LONG_ANCHOR_SAMPLES)

        val rolesByPosition = LinkedHashMap<Int, String>()
        longAnchorPositions.forEach { position -> rolesByPosition[position] = "LONG_ANCHOR" }
        midContextPositions.forEach { position -> rolesByPosition[position] = "MID_CONTEXT" }
        nearContextPositions.forEach { position -> rolesByPosition[position] = "NEAR_CONTEXT" }
        targetRolesByPosition.forEach { (position, role) -> rolesByPosition[position] = role }

        val diagnostics = ArrayList<String>()
        val targetPositionSet = targetPositions.toSet()
        val contextIndexes = rolesByPosition
            .filterKeys { position -> position !in targetPositionSet }
            .map { (position, _) -> chapterFiles[position].index }
            .toMutableSet()
        var usableContext = usableContextCount(chapterFiles, contextIndexes)
        val selectedPositions = rolesByPosition.keys.toMutableSet()
        val backfill = rawContextBackfillPositions(
            chapterCount = chapterCount,
            tailStart = tailStart,
            excludedPositions = selectedPositions,
            targetPositions = targetPositionSet,
            maxAttempts = MAX_CONTEXT_BACKFILL_ATTEMPTS
        )
        diagnostics.add("qualityBackfillStart usableContext=$usableContext candidates=${backfill.size}")
        for (position in backfill) {
            val file = chapterFiles[position]
            val quality = qualityGate.inspect(file.readChapter())
            diagnostics.add(
                "qualityBackfillProbe position=$position chapter=${file.index} " +
                    "quality=${quality.type} cleanChars=${quality.metrics.cleanedChars}"
            )
            if (quality.usableForStory) {
                rolesByPosition[position] = "MEMORY_BACKFILL"
                contextIndexes.add(file.index)
                usableContext += 1
                diagnostics.add("qualityBackfillAccept chapter=${file.index} usableContext=$usableContext")
                if (usableContext >= MIN_USABLE_CONTEXT_CHAPTERS) break
            }
        }
        diagnostics.add("qualityBackfillFinish usableContext=$usableContext analysis=${rolesByPosition.size}")

        val selected = rolesByPosition.keys.sorted()
        val analysis = selected.map { position -> chapterFiles[position] }
        val targetIndexes = targetPositionSet.map { position -> chapterFiles[position].index }.toSet()
        val rolesByChapterIndex = selected.associate { position ->
            chapterFiles[position].index to rolesByPosition.getValue(position)
        }
        return TargetReplaySample(
            analysis = analysis,
            targetIndexes = targetIndexes,
            contextIndexes = contextIndexes,
            rolesByChapterIndex = rolesByChapterIndex,
            diagnostics = diagnostics,
            usableContext = usableContext
        )
    }

    private fun selectTargetProbePositions(chapterCount: Int, tailStart: Int): Map<Int, String> {
        val selected = LinkedHashMap<Int, String>()
        val recentStart = (chapterCount - TARGET_RECENT_CHAPTERS).coerceAtLeast(tailStart)
        (recentStart until chapterCount).forEach { position -> selected[position] = "TARGET_RECENT" }

        val riskWindowSize = chapterCount - tailStart
        val offsets = ArrayList<Int>()
        var offset = 1
        while (offset <= riskWindowSize) {
            offsets.add(offset)
            offset *= 2
        }
        if (chapterCount > TAIL_RISK_WINDOW_CHAPTERS) offsets.add(TAIL_RISK_WINDOW_CHAPTERS)
        offset = TARGET_EXTENDED_MIN_OFFSET
        while (offset <= chapterCount) {
            offsets.add(offset)
            offset *= 2
        }
        offsets
            .filter { oneBasedOffset -> oneBasedOffset in 1..chapterCount }
            .distinct()
            .sorted()
            .forEach { oneBasedOffset ->
                val center = chapterCount - oneBasedOffset
                val role = if (oneBasedOffset <= TAIL_RISK_WINDOW_CHAPTERS) "TARGET_TAIL" else "TARGET_EXTENDED"
                selected.putIfAbsent(center, role)
            }
        return selected.toSortedMap()
    }

    private fun rawContextBackfillPositions(
        chapterCount: Int,
        tailStart: Int,
        excludedPositions: Set<Int>,
        targetPositions: Set<Int>,
        maxAttempts: Int
    ): List<Int> {
        if (chapterCount <= 0 || maxAttempts <= 0) return emptyList()
        val endExclusive = tailStart.takeIf { value -> value > 0 } ?: (chapterCount * 7 / 10).coerceAtLeast(1)
        val selected = LinkedHashSet<Int>()
        fun add(position: Int) {
            if (position in 0 until endExclusive &&
                position !in excludedPositions &&
                position !in targetPositions
            ) {
                selected.add(position)
            }
        }

        var offset = 1
        while (offset <= endExclusive && selected.size < maxAttempts / 2) {
            add(endExclusive - offset)
            offset *= 2
        }
        val nearCount = minOf(24, maxAttempts)
        val nearStart = (endExclusive - nearCount * 3).coerceAtLeast(0)
        evenlySpacedPositions(nearStart, endExclusive, nearCount).asReversed().forEach(::add)
        evenlySpacedPositions(0, endExclusive, maxAttempts).forEach(::add)
        return selected.take(maxAttempts)
    }

    private fun usableContextCount(chapterFiles: List<TargetChapterFile>, contextIndexes: Set<Int>): Int {
        return chapterFiles
            .asSequence()
            .filter { file -> file.index in contextIndexes }
            .count { file -> qualityGate.inspect(file.readChapter()).usableForStory }
    }

    private fun evenlySpacedPositions(startInclusive: Int, endExclusive: Int, count: Int): List<Int> {
        val size = endExclusive - startInclusive
        if (size <= 0) return emptyList()
        if (size <= count) return (startInclusive until endExclusive).toList()
        return (0 until count).map { index ->
            startInclusive + ((size - 1).toLong() * index / (count - 1).coerceAtLeast(1)).toInt()
        }.distinct()
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
        fun readChapter(): ChapterInput = ChapterInput(index, title, file.readText(Charsets.UTF_8))
    }

    private data class TargetReplaySample(
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

    private companion object {
        private const val TAIL_RISK_WINDOW_CHAPTERS = 100
        private const val TARGET_RECENT_CHAPTERS = 2
        private const val TARGET_EXTENDED_MIN_OFFSET = 256
        private const val NEAR_CONTEXT_SPAN = 300
        private const val NEAR_CONTEXT_SAMPLES = 8
        private const val MID_CONTEXT_SAMPLES = 2
        private const val LONG_ANCHOR_SAMPLES = 1
        private const val MIN_USABLE_CONTEXT_CHAPTERS = 8
        private const val MAX_CONTEXT_BACKFILL_ATTEMPTS = 256
    }
}
