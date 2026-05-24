package com.ldp.reader.sourceengine.content.v5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V5ChapterValidationPlannerTest {
    private val planner = V5ChapterValidationPlanner()

    @Test
    fun targetReplayPlanKeepsExpandedTailWindowAndBookMemory() {
        val chapters = chapters(1_000)

        val plan = planner.selectChapters(chapters) { _, _ -> storyContent() }

        assertEquals(486, plan.targetPositions.minOrNull())
        assertEquals(174, plan.targetPositions.size)
        assertTrue((838 until 1_000).all { position -> position in plan.targetPositions })
        assertTrue(488 in plan.targetPositions)
        assertTrue((0 until 32).all { position -> position !in plan.targetPositions })
        assertEquals(V5ChapterValidationPlanner.ROLE_TARGET_EXTENDED, plan.rolesByPosition[488])
        assertEquals(V5ChapterValidationPlanner.ROLE_TARGET_EXTENDED, plan.rolesByPosition[744])
        assertEquals(V5ChapterValidationPlanner.ROLE_TARGET_TAIL, plan.rolesByPosition[840])
        assertEquals(V5ChapterValidationPlanner.ROLE_TARGET_RECENT, plan.rolesByPosition[998])
        assertEquals(V5ChapterValidationPlanner.ROLE_TARGET_RECENT, plan.rolesByPosition[999])
        assertEquals(V5ChapterValidationPlanner.ROLE_LONG_ANCHOR, plan.rolesByPosition[0])
        assertEquals(V5ChapterValidationPlanner.ROLE_MID_CONTEXT, plan.rolesByPosition[350])
        assertEquals(V5ChapterValidationPlanner.ROLE_NEAR_CONTEXT, plan.rolesByPosition[538])
        assertTrue(plan.usableContext >= V5ChapterValidationPlanner.MIN_USABLE_CONTEXT_CHAPTERS)
        assertTrue(plan.targetIndexes.all { index -> index !in plan.contextIndexes })
    }

    @Test
    fun targetReplayPlanCoversBadTailBeforeFormerHundredWindow() {
        val chapters = chapters(1_095)

        val plan = planner.selectChapters(chapters) { _, _ -> storyContent() }

        assertTrue(961 in plan.targetPositions)
        assertTrue(990 in plan.targetPositions)
        assertTrue(1_040 in plan.targetPositions)
        assertTrue(plan.diagnostics.any { line ->
            line.startsWith("v5.plan.start") && line.contains("tailStart=933")
        })
    }

    @Test
    fun targetReplayPlanKeepsBoundaryBacktrackOutOfSeedContext() {
        val chapters = chapters(1_000)

        val plan = planner.selectChapters(chapters) { _, _ -> storyContent() }

        assertTrue(836 in plan.targetPositions)
        assertTrue(837 in plan.targetPositions)
        assertTrue(838 in plan.targetPositions)
        assertTrue(839 in plan.targetPositions)
        assertFalse(836 in plan.contextPositions)
        assertFalse(837 in plan.contextPositions)
        assertFalse(838 in plan.contextPositions)
        assertFalse(839 in plan.contextPositions)
        assertFalse(836 in plan.contextIndexes)
        assertFalse(837 in plan.contextIndexes)
        assertFalse(838 in plan.contextIndexes)
        assertFalse(839 in plan.contextIndexes)
    }

    @Test
    fun targetReplayPlanTreatsExtendedTargetNeighborsAsTargets() {
        val chapters = chapters(1_000)

        val plan = planner.selectChapters(chapters) { _, _ -> storyContent() }

        assertTrue(486 in plan.targetPositions)
        assertTrue(487 in plan.targetPositions)
        assertTrue(488 in plan.targetPositions)
        assertTrue(489 in plan.targetPositions)
        assertTrue(490 in plan.targetPositions)
        assertFalse(486 in plan.contextIndexes)
        assertFalse(487 in plan.contextIndexes)
        assertFalse(489 in plan.contextIndexes)
        assertFalse(490 in plan.contextIndexes)
    }

    @Test
    fun contextBackfillUsesQualityGateUntilEightUsableContextChapters() {
        val chapters = chapters(20)
        val cleanIndexes = setOf(0, 1, 2, 3, 5, 6, 7, 8)

        val plan = planner.selectChapters(chapters) { _, chapter ->
            if (chapter.index in cleanIndexes) {
                storyContent()
            } else {
                badExtractionContent()
            }
        }

        assertEquals(8, plan.usableContext)
        assertTrue(plan.rolesByPosition.values.any { role -> role == V5ChapterValidationPlanner.ROLE_MEMORY_BACKFILL })
        assertTrue(plan.diagnostics.any { line -> line.startsWith("v5.plan.backfill.accept") })
        assertFalse(plan.contextIndexes.any { index -> index in plan.targetIndexes })
    }

    @Test
    fun skipsDefiniteNonStoryCatalogTitlesBeforeReadingContent() {
        val chapters = chapters(20).toMutableList()
        chapters[0] = V5ValidationChapter(0, "作者感言")
        chapters[1] = V5ValidationChapter(1, "冬至快乐！")
        chapters[2] = V5ValidationChapter(2, "第五册预告")
        chapters[3] = V5ValidationChapter(3, "作者推书")
        chapters[17] = V5ValidationChapter(17, "番外")
        chapters[18] = V5ValidationChapter(18, "番外 作者拜年")
        chapters[19] = V5ValidationChapter(19, "谢谢大家and番外")
        val readPositions = ArrayList<Int>()

        val plan = planner.selectChapters(chapters) { position, _ ->
            readPositions.add(position)
            storyContent()
        }

        assertFalse(18 in plan.analysisPositions)
        assertFalse(19 in plan.analysisPositions)
        assertFalse(18 in plan.targetPositions)
        assertFalse(19 in plan.targetPositions)
        assertFalse(0 in readPositions)
        assertFalse(1 in readPositions)
        assertFalse(2 in readPositions)
        assertFalse(3 in readPositions)
        assertFalse(17 in readPositions)
        assertFalse(18 in readPositions)
        assertFalse(19 in readPositions)
        assertTrue(16 in plan.targetPositions)
        assertTrue(plan.diagnostics.any { line ->
            line.startsWith("v5.plan.title_skip") && line.contains("count=7")
        })
    }

    @Test
    fun keepsNumberedStoryTitlesInPlanEvenWhenTheyContainMetaWords() {
        val chapters = chapters(20).toMutableList()
        chapters[17] = V5ValidationChapter(17, "第十八章 作者说了什么")
        chapters[18] = V5ValidationChapter(18, "89 番外")
        chapters[19] = V5ValidationChapter(19, "第二十章 冬至快乐")
        chapters.add(V5ValidationChapter(20, "番外一 山中旧事"))
        val readPositions = ArrayList<Int>()

        val plan = planner.selectChapters(chapters) { position, _ ->
            readPositions.add(position)
            storyContent()
        }

        assertTrue(17 in plan.analysisPositions)
        assertTrue(18 in plan.analysisPositions)
        assertTrue(19 in plan.analysisPositions)
        assertTrue(20 in plan.analysisPositions)
        assertFalse(plan.diagnostics.any { line -> line.startsWith("v5.plan.title_skip") })
    }

    @Test
    fun skipsUnnumberedOutliersWhenCatalogMostlyUsesChapterTitles() {
        val chapters = chapters(20).toMutableList()
        chapters[17] = V5ValidationChapter(17, "荣耀五星卡牌~")
        chapters[18] = V5ValidationChapter(18, "冬至番外○抟玄")
        chapters[19] = V5ValidationChapter(19, "第五册预告")
        val readPositions = ArrayList<Int>()

        val plan = planner.selectChapters(chapters) { position, _ ->
            readPositions.add(position)
            storyContent()
        }

        assertFalse(17 in plan.analysisPositions)
        assertFalse(18 in plan.analysisPositions)
        assertFalse(19 in plan.analysisPositions)
        assertFalse(17 in readPositions)
        assertFalse(18 in readPositions)
        assertFalse(19 in readPositions)
        assertTrue(plan.diagnostics.any { line ->
            line.startsWith("v5.plan.title_gate") && line.contains("required=true")
        })
        assertTrue(plan.diagnostics.any { line ->
            line.startsWith("v5.plan.title_skip") && line.contains("count=3")
        })
    }

    @Test
    fun keepsPlainTitleCatalogWhenChapterTitleGateIsNotReliable() {
        val chapters = (0 until 20).map { index ->
            V5ValidationChapter(index, "青山旧事$index")
        }
        val readPositions = ArrayList<Int>()

        val plan = planner.selectChapters(chapters) { position, _ ->
            readPositions.add(position)
            storyContent()
        }

        assertTrue(17 in plan.analysisPositions)
        assertTrue(18 in plan.analysisPositions)
        assertTrue(19 in plan.analysisPositions)
        assertTrue(plan.diagnostics.any { line ->
            line.startsWith("v5.plan.title_gate") && line.contains("required=false")
        })
        assertFalse(plan.diagnostics.any { line -> line.startsWith("v5.plan.title_skip") })
    }

    @Test
    fun emitsPlannerDiagnosticsToSink() {
        val diagnostics = ArrayList<String>()

        val plan = planner.selectChapters(
            chapters = chapters(120),
            diagnosticSink = V5DiagnosticSink { line -> diagnostics.add(line) }
        ) { _, _ ->
            storyContent()
        }

        assertTrue(plan.diagnostics.any { line -> line.startsWith("v5.plan.start") })
        assertTrue(diagnostics.any { line -> line.startsWith("v5.plan.targets") })
        assertTrue(diagnostics.any { line -> line.startsWith("v5.plan.finish") })
    }

    private fun chapters(count: Int): List<V5ValidationChapter> {
        return (0 until count).map { index -> V5ValidationChapter(index, "第${index + 1}章 正文") }
    }

    private fun storyContent(): String {
        return buildString {
            repeat(40) {
                append("陈迹前往青云宗，施展霄剑诀，抵达落星谷，祭出玄冰剑，突破筑基期。")
                append("陈迹与青云宗同行，众人低声商议，随后继续沿着旧域边缘前行。")
            }
        }
    }

    private fun badExtractionContent(): String {
        return """
            <script>
            function ajaxLog(pramas){
            var xhr = new XMLHttpRequest();
            xhr.open("POST", '/api/user/guest/log', true);
            xhr.send(JSON.stringify(pramas))
            }
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
}
