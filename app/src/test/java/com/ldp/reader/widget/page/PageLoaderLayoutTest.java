package com.ldp.reader.widget.page;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PageLoaderLayoutTest {

    @Test
    public void readingContentMarginsKeepStatusBarSafeAndCompactFooter() {
        int displayHeight = 2780;
        int statusBarHeight = 140;
        int contentPadding = 28;

        int topMargin = PageLoader.calculateContentTopMargin(statusBarHeight, contentPadding);
        int bottomMargin = PageLoader.calculateContentBottomMargin(contentPadding);

        assertEquals(168, topMargin);
        assertEquals(28, bottomMargin);
        assertEquals(2584, PageLoader.calculateVisibleContentHeight(displayHeight, topMargin, bottomMargin));
    }

    @Test
    public void prepareDisplayOpensChapterWhenLocalParsingFinishedBeforeViewSizeReady() throws IOException {
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        int prepareDisplay = pageLoader.indexOf("fun prepareDisplay(w: Int, h: Int)");
        int notChapterOpen = pageLoader.indexOf("if (!isChapterOpen)", prepareDisplay);
        int preparedGuard = pageLoader.indexOf("if (isChapterListPrepare)", notChapterOpen);
        int openChapter = pageLoader.indexOf("openChapter()", preparedGuard);
        int returnIndex = pageLoader.indexOf("return", openChapter);

        assertTrue(prepareDisplay > 0);
        assertTrue(notChapterOpen > prepareDisplay);
        assertTrue(preparedGuard > notChapterOpen);
        assertTrue(openChapter > preparedGuard);
        assertTrue(returnIndex > openChapter);
    }

    @Test
    public void errorEdgeTapRetriesCurrentChapterBeforeTurningPage() throws IOException {
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        int prev = pageLoader.indexOf("fun prev(): Boolean");
        int prevRetry = pageLoader.indexOf("retryCurrentChapter()", prev);
        int prevCanTurn = pageLoader.indexOf("canTurnPage()", prev);
        int next = pageLoader.indexOf("fun next(): Boolean");
        int nextRetry = pageLoader.indexOf("retryCurrentChapter()", next);
        int nextCanTurn = pageLoader.indexOf("canTurnPage()", next);
        int retryMethod = pageLoader.indexOf("private fun retryCurrentChapter()");
        int retryOpen = pageLoader.indexOf("openChapter()", retryMethod);

        assertTrue(prev > 0);
        assertTrue(prevRetry > prev);
        assertTrue(prevRetry < prevCanTurn);
        assertTrue(next > 0);
        assertTrue(nextRetry > next);
        assertTrue(nextRetry < nextCanTurn);
        assertTrue(retryMethod > 0);
        assertTrue(retryOpen > retryMethod);
    }

    @Test
    public void finishedChapterEventReopensErrorPage() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");

        int finishChapter = readActivity.indexOf("private fun finishChapter(isRefresh: Boolean)");
        int loadingCheck = readActivity.indexOf("PageLoader.STATUS_LOADING", finishChapter);
        int errorCheck = readActivity.indexOf("PageLoader.STATUS_ERROR", finishChapter);
        int sendOpenChapter = readActivity.indexOf("mHandler.sendEmptyMessage(WHAT_CHAPTER)", finishChapter);

        assertTrue(finishChapter > 0);
        assertTrue(loadingCheck > finishChapter);
        assertTrue(errorCheck > loadingCheck);
        assertTrue(sendOpenChapter > errorCheck);
    }

    @Test
    public void readerCatalogKeepsWrongToggleWithoutPermanentAnalysisState() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_read.xml");

        assertTrue(layout.contains("@+id/read_ll_wrong_analysis_loading"));
        assertTrue(layout.contains("@+id/read_pb_wrong_analysis"));
        assertTrue(layout.contains("@+id/read_tv_wrong_analysis_status"));
        assertTrue(layout.contains("AI智能错章分析中"));
        assertTrue(readActivity.contains("supportActionBar?.title = title"));
        assertTrue(readActivity.contains("binding!!.toolbar.title = mCollBook?.title.orEmpty()"));

        int updater = readActivity.indexOf("private fun updateWrongChapterControl");
        int analysisGone = readActivity.indexOf("readLlWrongAnalysisLoading.visibility = View.GONE", updater);
        int toggleVisible = readActivity.indexOf("readCbShowWrongChapters.visibility = if (showToggle) View.VISIBLE else View.GONE", updater);
        int nextFunction = readActivity.indexOf("private fun", updater + 1);
        int analysisVisible = readActivity.indexOf("readLlWrongAnalysisLoading.visibility = View.VISIBLE", updater);

        assertTrue(updater > 0);
        assertTrue(analysisGone > updater);
        assertTrue(toggleVisible > updater);
        assertTrue(analysisVisible < 0 || analysisVisible > nextFunction);
    }

    @Test
    public void cachedCatalogRefreshesWrongChapterControlAfterDatabaseLoad() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");

        int processLogic = readActivity.indexOf("override fun processLogic()");
        int cachedCatalog = readActivity.indexOf("mPageLoader!!.collBook.bookChapters = bookChapterBeen", processLogic);
        int refreshList = readActivity.indexOf("mPageLoader!!.refreshChapterList()", cachedCatalog);
        int updateControl = readActivity.indexOf("updateWrongChapterControl()", refreshList);
        int remoteReload = readActivity.indexOf("viewModel.loadCategory(mBookId, mCollBook!!)", refreshList);

        assertTrue(processLogic > 0);
        assertTrue(cachedCatalog > processLogic);
        assertTrue(refreshList > cachedCatalog);
        assertTrue(updateControl > refreshList);
        assertTrue(updateControl < remoteReload);
    }

    @Test
    public void chapterWaterfallKeepsCurrentChapterHighPriorityAndLimitsPrefetch() throws IOException {
        String readViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt");
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        assertFalse(readViewModel.contains("chapterJob?.cancel()"));
        assertFalse(readViewModel.contains("private var chapterJob: Job?"));
        assertTrue(readViewModel.contains("private var currentChapterJob: Job? = null"));
        assertTrue(readViewModel.contains("private val prefetchJobs = LinkedHashMap<String, Job>()"));
        assertTrue(readViewModel.contains("pendingPrefetchChapters"));
        assertTrue(readViewModel.contains("titleInBiquge == currentChapterTitle"));
        assertTrue(readViewModel.contains("notifyError = true"));
        assertTrue(readViewModel.contains("notifyError = false"));
        assertTrue(readViewModel.contains("private const val PREFETCH_CHAPTER_LIMIT = 5"));
        assertTrue(readViewModel.contains("private const val MAX_PREFETCH_CONCURRENT_CHAPTERS = 1"));
        assertTrue(readViewModel.contains("catch (t: CancellationException)"));
        assertTrue(readViewModel.contains("cancelPrefetchLoads()"));
        assertTrue(readActivity.contains("mPageLoader!!.currentChapterTitle"));
        assertTrue(pageLoader.contains("val currentChapterTitle: String?"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
