package com.ldp.reader.ui.activity

import com.ldp.reader.media.ReaderMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFlowPolicyTest {
    @Test
    fun everySearchKindMarksExactlyItsMatchingTabSelected() {
        val tabStates = ReaderMediaKind.entries.associateWith { activeKind ->
            SearchFlowPolicy.tabStates(activeKind)
                .associate { state -> state.kind to state.selected }
        }

        assertEquals(
            mapOf(
                ReaderMediaKind.NOVEL to mapOf(
                    ReaderMediaKind.NOVEL to true,
                    ReaderMediaKind.COMIC to false,
                    ReaderMediaKind.AUDIO to false
                ),
                ReaderMediaKind.COMIC to mapOf(
                    ReaderMediaKind.NOVEL to false,
                    ReaderMediaKind.COMIC to true,
                    ReaderMediaKind.AUDIO to false
                ),
                ReaderMediaKind.AUDIO to mapOf(
                    ReaderMediaKind.NOVEL to false,
                    ReaderMediaKind.COMIC to false,
                    ReaderMediaKind.AUDIO to true
                )
            ),
            tabStates
        )
    }

    @Test
    fun provisionalNovelResultWaitsWithoutOpeningWhileReadableResultOpens() {
        assertEquals(
            NovelResultClickAction.WAIT_FOR_READING_CONTENT,
            SearchFlowPolicy.novelResultClickAction(readingReady = false)
        )
        assertEquals(
            NovelResultClickAction.OPEN,
            SearchFlowPolicy.novelResultClickAction(readingReady = true)
        )
    }

    @Test
    fun readablePublishedResultStaysReadyWhileBackgroundContentPreparationRetries() {
        val phase = SearchFlowPolicy.novelProgressPhase(
            resultCount = 1,
            final = false,
            hasReadableResult = true,
            tierPreparing = true,
            tierReady = false,
            phaseOverride = BookSearchProgressPhase.CHECKING_READING_SOURCE
        )

        assertEquals(BookSearchProgressPhase.READY, phase)
        assertEquals(
            "已找到可阅读内容，仍在整理更多结果",
            SearchFlowPolicy.novelProgressMessage(
                BookSearchProgressState(
                    query = "斗破苍穹",
                    phase = phase,
                    resultCount = 1,
                    elapsedMs = 13_074
                )
            )
        )
    }

    @Test
    fun novelProgressMessagesDescribeUserOutcomeWithoutInternalSourceCheckingTerms() {
        val messages = listOf(
            BookSearchProgressPhase.FOUND_SEARCHING,
            BookSearchProgressPhase.PREPARING_READING,
            BookSearchProgressPhase.CHECKING_READING_SOURCE,
            BookSearchProgressPhase.WAITING_READING_SOURCE,
            BookSearchProgressPhase.READY,
            BookSearchProgressPhase.FINISHED
        ).map { phase ->
            SearchFlowPolicy.novelProgressMessage(
                BookSearchProgressState(
                    query = "斗破苍穹",
                    phase = phase,
                    resultCount = 1,
                    elapsedMs = 5_000,
                    attempt = 2,
                    nextDelayMs = 8_000,
                    checkedCount = 1,
                    targetCount = 3
                )
            )
        }

        assertEquals(
            listOf(
                "已找到 1 本，仍在查找更多结果",
                "已找到 1 本，正在确认是否可以阅读",
                "已找到 1 本，正在确认是否可以阅读",
                "已找到 1 本，仍在查找可阅读内容",
                "已找到可阅读内容，仍在整理更多结果",
                "搜索完成"
            ),
            messages
        )
        messages.forEach { message ->
            listOf("阅读源", "来源", "验证", "轮", "1/3").forEach { technicalTerm ->
                assertFalse("message=$message", message.contains(technicalTerm))
            }
        }
    }

    @Test
    fun recreationKeepsRetainedNovelSearchWithoutRestartingIt() {
        val plan = SearchFlowPolicy.restorationPlan(
            savedKind = "novel",
            rawInputQuery = " 诡秘之主 ",
            rawSubmittedQuery = "诡秘之主",
            searchWasSubmitted = true,
            hasRetainedNovelSession = true
        )

        assertEquals(ReaderMediaKind.NOVEL, plan.kind)
        assertEquals("诡秘之主", plan.inputQuery)
        assertEquals("诡秘之主", plan.submittedQuery)
        assertFalse(plan.shouldRestartSearch)
    }

    @Test
    fun recreationRestartsSubmittedMediaSearchAndRejectsUnknownKind() {
        val mediaPlan = SearchFlowPolicy.restorationPlan(
            savedKind = "comic",
            rawInputQuery = " 一人之下 ",
            rawSubmittedQuery = "一人之下",
            searchWasSubmitted = true,
            hasRetainedNovelSession = false
        )
        val safeFallback = SearchFlowPolicy.restorationPlan(
            savedKind = "not-a-kind",
            rawInputQuery = "凡人修仙传",
            rawSubmittedQuery = "凡人修仙传",
            searchWasSubmitted = false,
            hasRetainedNovelSession = false
        )

        assertEquals(ReaderMediaKind.COMIC, mediaPlan.kind)
        assertTrue(mediaPlan.shouldRestartSearch)
        assertEquals(ReaderMediaKind.NOVEL, safeFallback.kind)
        assertEquals("", safeFallback.submittedQuery)
        assertFalse(safeFallback.shouldRestartSearch)
    }

    @Test
    fun processDeathRestartsSubmittedNovelSearchWhenNoSessionWasRetained() {
        val plan = SearchFlowPolicy.restorationPlan(
            savedKind = "novel",
            rawInputQuery = "剑来",
            rawSubmittedQuery = "剑来",
            searchWasSubmitted = true,
            hasRetainedNovelSession = false
        )

        assertTrue(plan.shouldRestartSearch)
    }

    @Test
    fun kindChangeRestartsTheTrimmedNonBlankQuery() {
        assertEquals(
            "诡秘之主",
            SearchFlowPolicy.queryToRestart(
                currentKind = ReaderMediaKind.NOVEL,
                selectedKind = ReaderMediaKind.COMIC,
                rawQuery = " 诡秘之主 "
            )
        )
    }

    @Test
    fun sameKindOrBlankQueryDoesNotStartAnotherSearch() {
        assertNull(
            SearchFlowPolicy.queryToRestart(
                currentKind = ReaderMediaKind.COMIC,
                selectedKind = ReaderMediaKind.COMIC,
                rawQuery = "诡秘之主"
            )
        )
        assertNull(
            SearchFlowPolicy.queryToRestart(
                currentKind = ReaderMediaKind.COMIC,
                selectedKind = ReaderMediaKind.AUDIO,
                rawQuery = "   "
            )
        )
    }

    @Test
    fun onlyTheLatestResponseForTheSelectedKindMayRender() {
        assertTrue(
            SearchFlowPolicy.isCurrentMediaResponse(
                activeKind = ReaderMediaKind.AUDIO,
                responseKind = ReaderMediaKind.AUDIO,
                activeToken = 8,
                responseToken = 8
            )
        )
        assertFalse(
            SearchFlowPolicy.isCurrentMediaResponse(
                activeKind = ReaderMediaKind.AUDIO,
                responseKind = ReaderMediaKind.COMIC,
                activeToken = 8,
                responseToken = 8
            )
        )
        assertFalse(
            SearchFlowPolicy.isCurrentMediaResponse(
                activeKind = ReaderMediaKind.AUDIO,
                responseKind = ReaderMediaKind.AUDIO,
                activeToken = 9,
                responseToken = 8
            )
        )
    }

    @Test
    fun keywordResponseRequiresNovelModeNoSubmittedSearchAndTheSameInput() {
        assertTrue(
            SearchFlowPolicy.isCurrentKeywordResponse(
                activeKind = ReaderMediaKind.NOVEL,
                activeSubmittedQuery = "",
                rawInputQuery = " 诡秘之主 ",
                responseQuery = "诡秘之主"
            )
        )
        assertFalse(
            SearchFlowPolicy.isCurrentKeywordResponse(
                activeKind = ReaderMediaKind.NOVEL,
                activeSubmittedQuery = "诡秘之主",
                rawInputQuery = "诡秘之主",
                responseQuery = "诡秘之主"
            )
        )
        assertFalse(
            SearchFlowPolicy.isCurrentKeywordResponse(
                activeKind = ReaderMediaKind.COMIC,
                activeSubmittedQuery = "",
                rawInputQuery = "诡秘之主",
                responseQuery = "诡秘之主"
            )
        )
        assertFalse(
            SearchFlowPolicy.isCurrentKeywordResponse(
                activeKind = ReaderMediaKind.NOVEL,
                activeSubmittedQuery = "",
                rawInputQuery = "诡秘之主2",
                responseQuery = "诡秘之主"
            )
        )
    }

    @Test
    fun novelInputClearsTheSubmittedSearchAndStartsKeywordLookup() {
        val plan = SearchFlowPolicy.inputChange(
            kind = ReaderMediaKind.NOVEL,
            rawQuery = " 诡秘之主 "
        )

        assertEquals("诡秘之主", plan.normalizedQuery)
        assertEquals("", plan.activeSubmittedQuery)
        assertEquals("诡秘之主", plan.keywordLookupQuery)
    }

    @Test
    fun mediaInputKeepsTheTypedQueryWithoutStartingNovelKeywordLookup() {
        val plan = SearchFlowPolicy.inputChange(
            kind = ReaderMediaKind.COMIC,
            rawQuery = " 一人之下 "
        )

        assertEquals("一人之下", plan.normalizedQuery)
        assertEquals("一人之下", plan.activeSubmittedQuery)
        assertNull(plan.keywordLookupQuery)
    }

    @Test
    fun blankInputClearsBothSubmittedAndKeywordQueries() {
        val plan = SearchFlowPolicy.inputChange(
            kind = ReaderMediaKind.NOVEL,
            rawQuery = "   "
        )

        assertEquals("", plan.normalizedQuery)
        assertEquals("", plan.activeSubmittedQuery)
        assertNull(plan.keywordLookupQuery)
    }

    @Test
    fun longRunningNovelSearchWithoutResultsUsesAnHonestIndeterminateStage() {
        val presentation = SearchFlowPolicy.loadingPresentation(
            kind = ReaderMediaKind.NOVEL,
            elapsedMs = 120_000,
            resultCount = 0,
            finished = false
        )

        assertEquals("搜索仍在进行，正在查找可阅读内容", presentation.message)
        assertNull(presentation.percent)
        assertFalse(presentation.message.contains("结果较多"))
    }

    @Test
    fun newlyStartedNovelSearchOnlyReportsTheObservableSubmittedStage() {
        val presentation = SearchFlowPolicy.loadingPresentation(
            kind = ReaderMediaKind.NOVEL,
            elapsedMs = 0,
            resultCount = 0,
            finished = false
        )

        assertEquals("搜索已开始，正在查找可阅读内容", presentation.message)
        assertNull(presentation.percent)
    }

    @Test
    fun unfinishedNovelSearchPreservesItsRealResultCountWithoutInventingAPercent() {
        val presentation = SearchFlowPolicy.loadingPresentation(
            kind = ReaderMediaKind.NOVEL,
            elapsedMs = 60_000,
            resultCount = 7,
            finished = false
        )

        assertEquals("已找到 7 本，正在继续搜索和整理", presentation.message)
        assertNull(presentation.percent)
    }

    @Test
    fun everyUnfinishedNovelSearchStageIsIndeterminate() {
        listOf(0L, 4_000L, 15_000L, 45_000L, 180_000L).forEach { elapsedMs ->
            val presentation = SearchFlowPolicy.loadingPresentation(
                kind = ReaderMediaKind.NOVEL,
                elapsedMs = elapsedMs,
                resultCount = 0,
                finished = false
            )

            assertNull("elapsedMs=$elapsedMs", presentation.percent)
        }
    }

    @Test
    fun unfinishedMediaSearchUsesHonestIndeterminateProgressAtEveryStage() {
        listOf(ReaderMediaKind.COMIC, ReaderMediaKind.AUDIO).forEach { kind ->
            listOf(0L, 4_000L, 45_000L, 120_000L).forEach { elapsedMs ->
                val presentation = SearchFlowPolicy.loadingPresentation(
                    kind = kind,
                    elapsedMs = elapsedMs,
                    resultCount = 0,
                    finished = false
                )

                assertNull("kind=$kind elapsedMs=$elapsedMs", presentation.percent)
                assertFalse(presentation.message.contains("结果较多"))
            }
        }
    }

    @Test
    fun mediaResultCountUsesTheUnitVisibleToTheUser() {
        assertEquals("2 本", SearchFlowPolicy.resultCountText(ReaderMediaKind.NOVEL, 2))
        assertEquals("3 部", SearchFlowPolicy.resultCountText(ReaderMediaKind.COMIC, 3))
        assertEquals("4 个", SearchFlowPolicy.resultCountText(ReaderMediaKind.AUDIO, 4))
    }

    @Test
    fun partialMediaResultsRemainExplicitlyUnfinished() {
        listOf(ReaderMediaKind.COMIC, ReaderMediaKind.AUDIO).forEach { kind ->
            val presentation = SearchFlowPolicy.loadingPresentation(
                kind = kind,
                elapsedMs = 30_000,
                resultCount = 6,
                finished = false
            )

            assertTrue(presentation.message.contains("正在继续搜索和整理"))
            assertNull(presentation.percent)
        }
    }
}
