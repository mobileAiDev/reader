package com.ldp.reader.sourceengine.content.v8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V8ValidationPlannerTest {
    private val planner = V8ValidationPlanner()

    @Test
    fun selectsBookMemoryForShortBookWhoseWholeCatalogIsTailRiskWindow() {
        val chapters = (0 until 97).map { index ->
            V8ValidationChapter(index = index, title = "第${index + 1}章 青灯照影")
        }

        val plan = planner.selectChapters(chapters) { position, _ ->
            storyContent(position)
        }

        assertEquals(97, plan.targetIndexes.size)
        assertEquals(V8ValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS, plan.usableContext)
        assertEquals(V8ValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS, plan.contextPositions.size)
        assertTrue(plan.contextPositions.all { position -> position in chapters.indices })
        assertTrue(plan.contextPositions.any { position -> position < 97 / 2 })
    }

    @Test
    fun keepsNonStoryTitlesOutOfTargetsAndContext() {
        val chapters = listOf(
            V8ValidationChapter(index = 0, title = "第一章 青灯照影"),
            V8ValidationChapter(index = 1, title = "第二章 山门夜雨"),
            V8ValidationChapter(index = 2, title = "请假条"),
            V8ValidationChapter(index = 3, title = "第三章 剑气入云"),
            V8ValidationChapter(index = 4, title = "第四章 石桥听雷"),
            V8ValidationChapter(index = 5, title = "第五章 古卷生尘"),
            V8ValidationChapter(index = 6, title = "第六章 明月照庭"),
            V8ValidationChapter(index = 7, title = "第七章 云台问道"),
            V8ValidationChapter(index = 8, title = "第八章 松风过峡"),
            V8ValidationChapter(index = 9, title = "第九章 寒泉映剑"),
            V8ValidationChapter(index = 10, title = "第十章 夜渡青溪")
        )

        val plan = planner.selectChapters(chapters) { position, _ ->
            storyContent(position)
        }

        assertTrue(2 !in plan.targetIndexes)
        assertTrue(2 !in plan.contextIndexes)
    }

    @Test
    fun rejectsUnparseableCatalogRowsBeforeV8() {
        val chapters = (0 until 97).map { index ->
            val title = when (index) {
                52 -> "鹤守抄，真可笑！"
                else -> "第${index + 1}章 青灯照影"
            }
            V8ValidationChapter(index = index, title = title)
        }

        val plan = planner.selectChapters(chapters) { position, _ ->
            storyContent(position)
        }

        assertTrue(52 !in plan.targetIndexes)
        assertTrue(52 !in plan.contextIndexes)
        assertTrue(51 in plan.targetIndexes)
        assertTrue(53 in plan.targetIndexes)
    }

    @Test
    fun recognizesSectionAndNumericCatalogTitles() {
        assertTrue(V8CatalogTitleClassifier.isStoryChapterTitle("第一百二十二节 第一次试车"))
        assertTrue(V8CatalogTitleClassifier.isStoryChapterTitle("685、举国搜拿"))
        assertTrue(V8CatalogTitleClassifier.isStoryChapterTitle("第六十九章：膏黄仙人"))
        assertFalse(V8CatalogTitleClassifier.isStoryChapterTitle("鹤守抄，真可笑！"))
    }

    @Test
    fun expandsBadTailBackwardsUntilCleanBoundaryIsObserved() {
        val chapters = (0 until 1_107).map { index ->
            V8ValidationChapter(index, "第${index + 1}章 正文")
        }
        val plan = V8ValidationPlan(
            analysisPositions = emptyList(),
            targetPositions = chapters.indices.toSet(),
            contextPositions = emptySet(),
            targetIndexes = chapters.map { chapter -> chapter.index }.toSet(),
            contextIndexes = emptySet(),
            rolesByPosition = emptyMap(),
            rolesByChapterIndex = emptyMap(),
            usableContext = V8ValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS
        )
        val planner = V8ValidationPlanner()

        val initial = planner.initialTargetIndexes(plan, chapters)
        val firstExpanded = planner.expandedTargetIndexes(
            chapters = chapters,
            currentTargetIndexes = initial,
            marks = (1_024 until 1_107).map { index -> mark(index, V8ChapterMarkState.WRONG) }
        )

        assertTrue(firstExpanded.contains(959))
        assertTrue(firstExpanded.contains(958))

        val withCleanBoundary = planner.expandedTargetIndexes(
            chapters = chapters,
            currentTargetIndexes = firstExpanded,
            marks = (941 until 959).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
                (959 until 1_107).map { index -> mark(index, V8ChapterMarkState.WRONG) }
        )

        assertEquals(firstExpanded, withCleanBoundary)
    }

    @Test
    fun sparseCleanSampleDoesNotBlockBadClusterBacktracking() {
        val chapters = (0 until 107).map { index ->
            V8ValidationChapter(index, "第${index + 1}章 正文")
        }
        val planner = V8ValidationPlanner()
        val initial = setOf(42, 58, 74, 83, 91, 92, 93, 94, 95, 96, 98, 99, 100, 101, 102, 103, 104, 105, 106)

        val expanded = planner.expandedTargetIndexes(
            chapters = chapters,
            currentTargetIndexes = initial,
            marks = listOf(
                mark(74, V8ChapterMarkState.NORMAL),
                mark(83, V8ChapterMarkState.WRONG),
                mark(91, V8ChapterMarkState.WRONG)
            )
        )

        assertTrue(expanded.contains(78))
        assertTrue(expanded.contains(79))
        assertTrue(expanded.contains(80))
        assertTrue(expanded.contains(81))
        assertTrue(expanded.contains(82))
    }

    @Test
    fun isolatedNearTailBadSampleDoesNotExpand() {
        val chapters = (0 until 107).map { index ->
            V8ValidationChapter(index, "第${index + 1}章 正文")
        }
        val planner = V8ValidationPlanner()
        val initial = setOf(42, 58, 74, 83, 91, 92, 93, 94, 95, 96, 98, 99, 100, 101, 102, 103, 104, 105, 106)

        val expanded = planner.expandedTargetIndexes(
            chapters = chapters,
            currentTargetIndexes = initial,
            marks = listOf(
                mark(74, V8ChapterMarkState.NORMAL),
                mark(83, V8ChapterMarkState.NORMAL),
                mark(91, V8ChapterMarkState.WRONG)
            )
        )

        assertEquals(initial, expanded)
    }

    @Test
    fun isolatedEarlyBadSampleDoesNotDriveTailExpansion() {
        val chapters = (0 until 95).map { index ->
            V8ValidationChapter(index, "第${index + 1}章 正文")
        }
        val planner = V8ValidationPlanner()
        val initial = setOf(
            31, 47, 63, 71,
            79, 80, 81, 82, 83, 84, 85, 86,
            87, 88, 89, 90, 91, 92, 93, 94
        )

        val expanded = planner.expandedTargetIndexes(
            chapters = chapters,
            currentTargetIndexes = initial,
            marks = listOf(
                mark(31, V8ChapterMarkState.WRONG),
                mark(47, V8ChapterMarkState.NORMAL),
                mark(71, V8ChapterMarkState.WRONG)
            ) + (79..94).map { index -> mark(index, V8ChapterMarkState.WRONG) }
        )

        assertFalse(expanded.contains(7))
        assertTrue(expanded.contains(39))
        assertTrue(expanded.contains(69))
    }

    @Test
    fun isolatedNonTailBadSampleDoesNotExpand() {
        val chapters = (0 until 95).map { index ->
            V8ValidationChapter(index, "第${index + 1}章 正文")
        }
        val planner = V8ValidationPlanner()
        val initial = setOf(31, 47, 63, 71, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94)

        val expanded = planner.expandedTargetIndexes(
            chapters = chapters,
            currentTargetIndexes = initial,
            marks = listOf(
                mark(31, V8ChapterMarkState.WRONG),
                mark(47, V8ChapterMarkState.NORMAL),
                mark(63, V8ChapterMarkState.NORMAL),
                mark(71, V8ChapterMarkState.NORMAL)
            )
        )

        assertEquals(initial, expanded)
    }

    private fun mark(index: Int, state: V8ChapterMarkState): V8ChapterMarkResult {
        return V8ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "第${index + 1}章",
            state = state,
            confidence = 0.9,
            qualityType = null,
            suggestionState = V8NovelStateOutputType.NORMAL,
            action = V8CleanAction.KEEP,
            reasons = emptyList()
        )
    }

    private fun storyContent(seed: Int): String {
        return buildString {
            repeat(12) { round ->
                append("沈青提着青铜灯穿过雨后的山门，石阶旁的灵泉映出第")
                append(seed + round)
                append("道剑痕。")
                append("白长老把旧卷摊在案上，提醒众弟子今夜云台法阵不可有失。")
                append("远处钟声渐沉，竹林深处的飞剑传来细微震鸣。")
            }
        }
    }
}
