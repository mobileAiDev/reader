package com.ldp.reader.sourceengine.content.v5

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class V5KnownPollutedWindowReplayTest {
    @Test
    fun replayKnownPollutedWindowsWithExpandedBudget() {
        assumeTrue(
            "Set -Dv5KnownPollutedWindowReplay=true to replay known polluted windows.",
            System.getProperty("v5KnownPollutedWindowReplay") == "true"
        )

        val outputRoot = File("build/tmp/v5-known-polluted-window-replay").apply { mkdirs() }
        val cases = listOf(
            KnownWindowCase(
                id = "book-016",
                title = "叩问仙道",
                author = "雨打青石",
                directoryName = "016-叩问仙道-1779485161947",
                pollutedStartIndex = 2783,
                pollutedStartTitle = "不同的选择"
            ),
            KnownWindowCase(
                id = "book-017",
                title = "苟在武道世界成圣",
                author = "在水中的纸老虎",
                directoryName = "017-苟在武道世界成圣-1779485163000",
                pollutedStartIndex = 696,
                pollutedStartTitle = "万化",
                ignoredPollutedIndexes = setOf(715)
            ),
            KnownWindowCase(
                id = "book-018",
                title = "苟在两界修仙",
                author = "文抄公",
                directoryName = "018-苟在两界修仙-1779485283857",
                pollutedStartIndex = 394,
                pollutedStartTitle = "出手",
                cleanBeforeIndexes = setOf(390, 391, 392, 393)
            )
        )

        val summary = File(outputRoot, "summary.tsv")
        summary.writeText(
            "id\ttitle\tstartIndex\twindowSize\tproductionCovered\tproductionCoveredIndexes\t" +
                "expandedWrong\texpandedBadExtraction\texpandedInconclusive\texpandedNormal\tmissingIndexes\n"
        )
        val failures = ArrayList<String>()
        cases.forEach { case ->
            val root = findCorpusRoot(case)
            assertTrue("${case.id} corpus missing: ${root.absolutePath}", root.isDirectory)
            val chapterFiles = chapterFiles(root)
            val startPosition = chapterFiles.indexOfFirst { file ->
                parseChapterIndex(file.name) == case.pollutedStartIndex &&
                    parseChapterTitle(file.name).contains(case.pollutedStartTitle)
            }
            assertTrue("${case.id} polluted start missing: ${case.pollutedStartIndex}", startPosition >= 0)
            val windowPositions = (startPosition until chapterFiles.size).toList()
            val productionPlan = V5ChapterValidationPlanner().selectChapters(
                chapters = chapterFiles.map { file ->
                    V5ValidationChapter(parseChapterIndex(file.name), parseChapterTitle(file.name))
                },
                readContent = { position, _ -> chapterFiles[position].readText(Charsets.UTF_8) }
            )
            val selectedPositions = productionPlan.analysisPositions

            val result = V5SourceChapterValidator().validate(
                V5SourceRunRequest(
                    title = case.title,
                    author = case.author,
                    sourceKey = "known-window-${case.id}",
                    chapters = selectedPositions.map { position ->
                        val file = chapterFiles[position]
                        ChapterInput(
                            index = parseChapterIndex(file.name),
                            title = parseChapterTitle(file.name),
                            content = file.readText(Charsets.UTF_8)
                        )
                    },
                    seedChapterIndexes = productionPlan.contextIndexes
                )
            )
            val pollutedIndexes = windowPositions
                .map { position -> parseChapterIndex(chapterFiles[position].name) }
                .filterNot { index -> index in case.ignoredPollutedIndexes }
                .toSet()
            val productionCovered = productionPlan.analysisPositions
                .map { position -> parseChapterIndex(chapterFiles[position].name) }
                .filter { index -> index in pollutedIndexes }
                .toSortedSet()
            val marksByIndex = result.marks.associateBy { mark -> mark.chapterIndex }
            val missing = pollutedIndexes
                .filter { index -> marksByIndex[index]?.state == V5ChapterMarkState.NORMAL }
                .sorted()
            val details = File(outputRoot, "${case.id}.tsv")
            details.writeText(
                buildString {
                    appendLine("index\ttitle\tproductionRole\texpandedState\tquality\tsuggestion\taction\tconfidence\treasons")
                    val detailPositions = (
                        chapterFiles.withIndex()
                            .filter { (_, file) -> parseChapterIndex(file.name) in case.cleanBeforeIndexes }
                            .map { (position, _) -> position } +
                            windowPositions
                        )
                        .distinct()
                        .sorted()
                    detailPositions.forEach { position ->
                        val file = chapterFiles[position]
                        val index = parseChapterIndex(file.name)
                        val mark = marksByIndex[index]
                        appendLine(
                            listOf(
                                index.toString(),
                                parseChapterTitle(file.name),
                                productionPlan.rolesByChapterIndex[index].orEmpty(),
                                mark?.state?.name.orEmpty(),
                                mark?.qualityType?.name.orEmpty(),
                                mark?.suggestionState?.name.orEmpty(),
                                mark?.action?.name.orEmpty(),
                                mark?.confidence?.let { "%.3f".format(it) }.orEmpty(),
                                mark?.reasons?.joinToString(" | ").orEmpty()
                            ).joinToString("\t")
                        )
                    }
                }
            )
            summary.appendText(
                listOf(
                    case.id,
                    case.title,
                    case.pollutedStartIndex.toString(),
                    pollutedIndexes.size.toString(),
                    productionCovered.size.toString(),
                    productionCovered.joinToString(","),
                    pollutedIndexes.count { index -> marksByIndex[index]?.state == V5ChapterMarkState.WRONG }.toString(),
                    pollutedIndexes.count { index -> marksByIndex[index]?.state == V5ChapterMarkState.BAD_EXTRACTION }.toString(),
                    pollutedIndexes.count { index -> marksByIndex[index]?.state == V5ChapterMarkState.INCONCLUSIVE }.toString(),
                    pollutedIndexes.count { index -> marksByIndex[index]?.state == V5ChapterMarkState.NORMAL }.toString(),
                    missing.joinToString(",")
                ).joinToString("\t") + "\n"
            )
            if (productionCovered != pollutedIndexes) {
                failures.add(
                    "${case.id} production planner missed indexes: " +
                        (pollutedIndexes - productionCovered).sorted().joinToString(",")
                )
            }
            if (missing.isNotEmpty()) {
                failures.add("${case.id} V5 marks missed indexes: ${missing.joinToString(",")}")
            }
            val wronglyMarkedClean = case.cleanBeforeIndexes
                .filter { index -> marksByIndex[index]?.state != V5ChapterMarkState.NORMAL }
                .sorted()
            if (wronglyMarkedClean.isNotEmpty()) {
                failures.add("${case.id} V5 over-marked clean indexes: ${wronglyMarkedClean.joinToString(",")}")
            }
        }
        assertTrue(summary.isFile)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun findCorpusRoot(case: KnownWindowCase): File {
        val corpusPath = "algorithm-test/test-datasets/raw-corpus-101-bundle/raw-corpus-101/" +
            "device-full/extracted-wsl/fetch-batch-1779484863140/${case.directoryName}"
        return listOf(File(corpusPath), File("../$corpusPath"))
            .firstOrNull { file -> file.isDirectory }
            ?: File(corpusPath)
    }

    private fun chapterFiles(root: File): List<File> {
        return File(root, "chapters")
            .listFiles { file -> file.isFile && file.extension == "txt" }
            .orEmpty()
            .sortedWith(compareBy({ parseChapterIndex(it.name) }, { it.name }))
    }

    private fun parseChapterIndex(name: String): Int {
        return Regex("""^(\d+)-""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseChapterTitle(name: String): String {
        return name.removeSuffix(".txt").replace(Regex("""^\d+-"""), "").replace('_', ' ')
    }

    private data class KnownWindowCase(
        val id: String,
        val title: String,
        val author: String,
        val directoryName: String,
        val pollutedStartIndex: Int,
        val pollutedStartTitle: String,
        val cleanBeforeIndexes: Set<Int> = emptySet(),
        val ignoredPollutedIndexes: Set<Int> = emptySet()
    )
}
