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
    public void readerCatalogKeepsWrongAnalysisTerminalOutcomeVisible() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_read.xml");
        String strings = readFile("src/main/res/values/strings.xml");

        assertTrue(layout.contains("@+id/read_ll_wrong_analysis_loading"));
        assertTrue(layout.contains("@+id/read_pb_wrong_analysis"));
        assertTrue(layout.contains("@+id/read_tv_wrong_analysis_status"));
        assertTrue(layout.contains("@string/reader_wrong_chapter_analysis_running"));
        assertTrue(layout.contains("@string/reader_show_suspected_wrong_chapters"));
        assertTrue(strings.contains("正在检查异常章节…"));
        assertTrue(strings.contains("正在检查异常章节（%1$d/%2$d）"));
        assertTrue(strings.contains("检查完成，未发现疑似异常章节"));
        assertTrue(strings.contains("检查完成，发现%1$d章疑似异常章节"));
        assertTrue(strings.contains("已使用最近检查结果"));
        assertTrue(strings.contains("检查失败，请重试"));
        assertTrue(strings.contains("检查已取消，可稍后重试"));
        assertTrue(strings.contains("显示疑似异常章节"));
        assertTrue(readActivity.contains("supportActionBar?.title = title"));
        assertTrue(readActivity.contains("binding!!.toolbar.title = mCollBook?.title.orEmpty()"));
        assertTrue(readActivity.contains("viewModel.v8AnalysisStatus.observe(this)"));
        assertTrue(readActivity.contains("wrongAnalysisRunning = status.running"));
        assertTrue(readActivity.contains("wrongAnalysisWrong = status.wrong"));
        assertTrue(readActivity.contains("wrongAnalysisPhase = status.phase"));

        int updater = readActivity.indexOf("private fun updateWrongChapterControl");
        int policy = readActivity.indexOf("ReaderWrongChapterUiPolicy.resolve(", updater);
        int sourceCapability = readActivity.indexOf("sourceBackedNovel = isSourceBackedNovel()", policy);
        int analysisVisibility = readActivity.indexOf("readLlWrongAnalysisLoading.visibility = if (uiState.showStatus) View.VISIBLE else View.GONE", updater);
        int progressVisibility = readActivity.indexOf("readPbWrongAnalysis.visibility = if (uiState.showLoading) View.VISIBLE else View.GONE", updater);
        int terminalCount = readActivity.indexOf("getString(uiState.statusTextRes, uiState.statusWrong)", updater);
        int toggleVisible = readActivity.indexOf("readCbShowWrongChapters.visibility = if (uiState.showToggle) View.VISIBLE else View.GONE", updater);

        assertTrue(updater > 0);
        assertTrue(policy > updater);
        assertTrue(sourceCapability > policy);
        assertTrue(analysisVisibility > updater);
        assertTrue(progressVisibility > updater);
        assertTrue(terminalCount > updater);
        assertTrue(toggleVisible > updater);
        assertFalse(readActivity.contains("analysisPercent"));
        assertFalse(readActivity.contains("已分析${analyzed}章"));
        assertFalse(readActivity.contains("显示已标记章节"));
    }

    @Test
    public void readerFullScreenStatesUsePlainChapterLanguage() throws IOException {
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        assertTrue(pageLoader.contains("STATUS_LOADING -> \"正在加载本章…\""));
        assertTrue(pageLoader.contains("STATUS_CATALOG_LOADING -> \"正在准备阅读内容…\""));
        assertTrue(pageLoader.contains(
                "STATUS_ERROR -> mPageLoadErrorMessage ?: \"本章加载失败，轻点屏幕左右两侧重试\""
        ));
        assertTrue(pageLoader.contains("STATUS_EMPTY -> \"本章暂无可阅读内容\""));
        assertTrue(pageLoader.contains("STATUS_PARING -> \"正在准备阅读内容…\""));
        assertTrue(pageLoader.contains("STATUS_CATEGORY_EMPTY -> ReaderCatalogTerminalCopy.messageFor("));
        assertTrue(pageLoader.contains("ReaderCatalogTerminalState.CONFIRMED_EMPTY"));
        assertTrue(pageLoader.contains("\"本章内容过长，暂时无法打开\""));
        assertTrue(pageLoader.contains("ReaderStatusTextLayout.wrap("));
        assertTrue(pageLoader.contains("mStatusPaint.breakText(remaining, true, width, null)"));
        assertTrue(pageLoader.contains("canvas.drawText(line, x, baseline, mStatusPaint)"));
        assertFalse(pageLoader.contains("canvas.drawText(tip, pivotX, pivotY, mTextPaint)"));

        assertFalse(pageLoader.contains("正在拼命加载中"));
        assertFalse(pageLoader.contains("加载失败(点击边缘重试)"));
        assertFalse(pageLoader.contains("文章内容为空"));
        assertFalse(pageLoader.contains("正在排版请等待"));
        assertFalse(pageLoader.contains("章节过大，已停止排版"));
    }

    @Test
    public void cachedCatalogRefreshesWrongChapterControlAfterDatabaseLoad() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");

        int processLogic = readActivity.indexOf("override fun processLogic()");
        int replayMarks = readActivity.indexOf("BookContentProviderRouter.restoreCachedV8MarksForBook", processLogic);
        int applyMarks = readActivity.indexOf("SourceEngineCatalogMarkRegistry.applyToBookChaptersWithStats(bookChapterBeen)", replayMarks);
        int cachedCatalog = readActivity.indexOf("mPageLoader!!.collBook.bookChapters = bookChapterBeen", processLogic);
        int refreshList = readActivity.indexOf("mPageLoader!!.refreshChapterList()", cachedCatalog);
        int updateControl = readActivity.indexOf("updateWrongChapterControl()", refreshList);
        int remoteReload = readActivity.indexOf("viewModel.loadCategory(mBookId, mCollBook!!, persistToShelf = true)", refreshList);

        assertTrue(processLogic > 0);
        assertTrue(replayMarks > processLogic);
        assertTrue(applyMarks > replayMarks);
        assertTrue(applyMarks < cachedCatalog);
        assertTrue(cachedCatalog > processLogic);
        assertTrue(refreshList > cachedCatalog);
        assertTrue(updateControl > refreshList);
        assertTrue(updateControl < remoteReload);
    }

    @Test
    public void emptyCollectedNetworkCatalogWaitsForRemoteWithoutRenderingCategoryEmpty() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");
        String detailActivity = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt");

        int startupDecision = readActivity.indexOf("ReaderCatalogStartupPolicy.decide");
        int waitBranch = readActivity.indexOf("ReaderCatalogStartupAction.WAIT_FOR_REMOTE_CATALOG", startupDecision);
        int loadingState = readActivity.indexOf("mPageLoader!!.showCatalogLoading()", waitBranch);
        int remoteReload = readActivity.indexOf(
                "viewModel.loadCategory(mBookId, mCollBook!!, persistToShelf = true)",
                loadingState
        );

        assertTrue(startupDecision > 0);
        assertTrue(waitBranch > startupDecision);
        assertTrue(loadingState > waitBranch);
        assertTrue(remoteReload > loadingState);
        assertTrue(pageLoader.contains("fun showCatalogLoading()"));
        assertTrue(pageLoader.contains("mStatus = STATUS_CATALOG_LOADING"));
        assertTrue(pageLoader.contains("STATUS_CATALOG_LOADING -> \"正在准备阅读内容…\""));
        assertTrue(pageLoader.contains("fun showCatalogError()"));
        assertTrue(pageLoader.contains("fun showCatalogEmpty()"));
        assertTrue(pageLoader.contains("ReaderCatalogTerminalState.LOAD_FAILED"));
        assertTrue(pageLoader.contains("ReaderCatalogTerminalState.CONFIRMED_EMPTY"));
        assertTrue(readActivity.contains("ReaderCatalogStartupPolicy.canDisplayRemoteCatalog"));
        assertTrue(readActivity.contains("source_read_catalog_empty_retryable"));
        assertTrue(readActivity.contains("private fun retryCatalogIfNeeded(): Boolean"));
        assertTrue(readActivity.contains("source_read_catalog_manual_retry"));
        assertTrue(detailActivity.contains("viewModel.prepareForReading()"));
        assertFalse(detailActivity.contains("viewModel.cancelActiveBookWork()"));
    }

    @Test
    public void uncollectedSourceEngineReadDoesNotUseIntentCatalogBeforeRemoteReload() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String detailActivity = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt");

        int helper = readActivity.indexOf("fun createIntentBookPayload(collBook: CollBookBean?)");
        int helperReturn = readActivity.indexOf("return CollBookBean(", helper);
        int startActivity = readActivity.indexOf("fun startActivity(context: Context, collBook: CollBookBean?, isCollected: Boolean)");
        int helperUsed = readActivity.indexOf("createIntentBookPayload(collBook)", startActivity);
        int remoteReload = readActivity.indexOf("viewModel.loadCategory(mBookId, mCollBook!!, persistToShelf = false)");
        int detailHelperUsed = detailActivity.indexOf("ReadActivity.createIntentBookPayload(collBook)");

        assertTrue(helper > 0);
        assertTrue(helperReturn > helper);
        assertTrue(helperUsed > startActivity);
        assertTrue(remoteReload > 0);
        assertTrue(detailHelperUsed > 0);
        assertFalse(readActivity.contains("showParcelCatalogIfAvailable"));
        assertFalse(readActivity.contains("source_read_parcel_catalog_loaded"));
        assertFalse(readActivity.substring(helper, startActivity).contains("bookChapters"));
    }

    @Test
    public void detailReadCarriesFreshCatalogIntoExistingShelfBook() throws IOException {
        String detailActivity = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt");

        int mergeMethod = detailActivity.indexOf("private fun updateExistingBookFromDetail");
        int freshChapters = detailActivity.indexOf("val freshChapters = fresh.getBookChapters()", mergeMethod);
        int assignChapters = detailActivity.indexOf("existing.bookChapters = freshChapters", freshChapters);
        int assignCount = detailActivity.indexOf("existing.chaptersCount = freshChapters.size", assignChapters);
        int assignLast = detailActivity.indexOf("freshChapters.lastOrNull()?.title", assignCount);
        int saveExisting = detailActivity.indexOf("BookRepository.getInstance().saveCollBook(existing)", assignLast);

        assertTrue(mergeMethod > 0);
        assertTrue(freshChapters > mergeMethod);
        assertTrue(assignChapters > freshChapters);
        assertTrue(assignCount > assignChapters);
        assertTrue(assignLast > assignCount);
        assertTrue(saveExisting > assignLast);
    }

    @Test
    public void sourceEngineReadingUsesSessionCatalogBeforeBootstrap() throws IOException {
        String readViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt");
        String router = readFile("src/main/java/com/ldp/reader/source/BookContentProviderRouter.kt");

        int categoryJob = readViewModel.indexOf("categoryJob = viewModelScope.launch");
        int sessionCatalog = readViewModel.indexOf("publishCachedReadingCatalog(bookId, collBookBean, startedAt)", categoryJob);
        int bootstrap = readViewModel.indexOf("publishReadingBootstrapCatalog(bookId, collBookBean, startedAt)", sessionCatalog);
        int cachedMethod = readViewModel.indexOf("private fun publishCachedReadingCatalog");
        int routerCall = readViewModel.indexOf("BookContentProviderRouter.getCachedReadingCatalog", cachedMethod);

        assertTrue(categoryJob > 0);
        assertTrue(sessionCatalog > categoryJob);
        assertTrue(bootstrap > sessionCatalog);
        assertTrue(cachedMethod > 0);
        assertTrue(routerCall > cachedMethod);
        assertTrue(router.contains("fun getCachedReadingCatalog"));
        assertTrue(router.contains("sourceEngineProvider.getCachedReadingCatalog(routeBookId, collBookBean)"));
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
