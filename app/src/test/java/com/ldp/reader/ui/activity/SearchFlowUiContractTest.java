package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

public class SearchFlowUiContractTest {

    @Test
    public void changingMediaKindRestartsTheNonBlankQueryAndKeepsStaleResultGuards() throws IOException {
        String activity = readSource("SearchActivity.kt");

        assertTrue(activity.contains("SearchFlowPolicy.queryToRestart"));
        assertTrue(activity.contains("beginBookSearch(restartQuery)"));
        assertTrue(activity.contains("mediaSearchToken += 1"));
        assertTrue(activity.contains("SearchFlowPolicy.isCurrentMediaResponse"));
    }

    @Test
    public void failedSearchUsesTheRetryableErrorStateInsteadOfTheEmptyState() throws IOException {
        String activity = readSource("SearchActivity.kt");
        String errorLayout = readFile("src/main/res/layout/view_net_error.xml");

        assertTrue(activity.contains("setOnReloadingListener(object : RefreshLayout.OnReloadingListener"));
        assertTrue(activity.contains("retryActiveSearch()"));
        assertTrue(activity.contains("private fun renderMediaSearchError"));
        assertTrue(activity.contains("mRlRefresh!!.showError()"));
        assertTrue(containsCallWithFirstArgument(activity, "traceSearchUi", "media_search_ui_error"));
        assertTrue(errorLayout.contains("暂时无法加载，点击重试"));
    }

    @Test
    public void mediaSearchCancellationReachesTheBlockingRepositoryWork() throws IOException {
        String activity = readSource("SearchActivity.kt");
        String repository = readFile("src/main/java/com/ldp/reader/media/MediaSourceRepository.kt");
        String execution = readFile("src/main/java/com/ldp/reader/media/MediaSearchExecution.kt");
        String displayGate = readFile("src/main/java/com/ldp/reader/media/MediaSearchDisplayGate.kt");
        String fetcher = readFile("src/main/java/com/ldp/reader/media/engine/MediaOkHttpFetcher.kt");

        assertTrue(activity.contains("MediaSearchCancellationHandle"));
        assertTrue(activity.contains("runInterruptible(Dispatchers.IO)"));
        assertTrue(activity.contains("cancellationHandle = cancellationHandle"));
        assertTrue(activity.contains("private fun cancelMediaSearch()"));
        assertFalse(repository.contains("private val searchExecutor"));
        assertTrue(repository.contains("MediaSearchBatchRunner.run"));
        assertTrue(repository.contains("executionControl = executionControl"));
        assertTrue(repository.contains("retryWhenNoValidationSucceeded = true"));
        assertTrue(repository.contains("MediaSearchValidationOutcomePolicy.requireSuccessfulValidationOrTrueEmpty"));
        assertTrue(execution.contains("ArrayBlockingQueue<Runnable>"));
        assertTrue(displayGate.contains("throw CancellationException(\"Media catalog validation was interrupted\")"));
        assertTrue(fetcher.contains("registerCancellation"));
    }

    @Test
    public void novelInputChangeUsesTheResetPlanBeforeKeywordLookup() throws IOException {
        String activity = readSource("SearchActivity.kt");

        assertTrue(activity.contains("SearchFlowPolicy.inputChange"));
        assertTrue(activity.contains("activeBookSearchQuery = inputChange.activeSubmittedQuery"));
        assertTrue(activity.contains("cancelActiveSearchForInputChange()"));
        assertTrue(activity.contains("mRlRefresh!!.showLoading()"));
        assertTrue(activity.contains("viewModel.searchKeyWord(keywordQuery)"));
        assertFalse(activity.contains("beginBookSearch(keywordQuery)"));
    }

    @Test
    public void currentKeywordDeliveryLeavesOldErrorOrEmptyState() throws IOException {
        String activity = readSource("SearchActivity.kt");
        String viewModel = readSource("SearchViewModel.kt");

        assertTrue(viewModel.contains("requestVersion == keywordRequestVersion"));
        assertTrue(viewModel.contains("KeywordSearchState("));
        assertTrue(activity.contains("SearchFlowPolicy.isCurrentKeywordResponse"));
        assertTrue(activity.contains("state.suggestions.isEmpty()"));
        assertTrue(activity.contains("mRlRefresh!!.showEmpty()"));
        assertTrue(activity.contains("mRlRefresh!!.showFinish()"));
    }

    @Test
    public void partialMediaResultsStayVisibleWithoutEndingTheirProgress() throws IOException {
        String activity = readSource("SearchActivity.kt");

        assertTrue(activity.contains("books.isNotEmpty() && !finished"));
        assertTrue(activity.contains("showMediaSearchProgressBar("));
        assertTrue(activity.contains("SearchFlowPolicy.resultCountText(kind, resultCount)"));
        assertTrue(activity.contains("binding.searchBookProgressSpinner.visibility = if (finished)"));
        String partialBranch = activity.substring(
                activity.indexOf("books.isNotEmpty() && !finished"),
                activity.indexOf("books.isNotEmpty() ->", activity.indexOf("books.isNotEmpty() && !finished"))
        );
        assertFalse(partialBranch.contains("stopSearchLoadingProgress()"));
    }

    @Test
    public void novelProgressCopyComesFromTheUserFacingPolicy() throws IOException {
        String activity = readSource("SearchActivity.kt");

        assertTrue(activity.contains("SearchFlowPolicy.novelProgressMessage(progress)"));
        assertFalse(activity.contains("正在验证第"));
        assertFalse(activity.contains("本阅读源（第"));
        assertFalse(activity.contains("等待更多来源返回"));
    }

    @Test
    public void readablePublishedNovelControlsProgressBeforeBackgroundTierState() throws IOException {
        String viewModel = readSource("SearchViewModel.kt");

        assertTrue(viewModel.contains("SearchFlowPolicy.novelProgressPhase("));
        assertTrue(viewModel.contains(
                "hasReadableResult = latestBookResults.any { book -> book.readingReady }"
        ));
    }

    @Test
    public void commonAndSearchLoadingCopyAvoidsEngineJargonAndFakeDefaultProgress() throws IOException {
        String activity = readSource("SearchActivity.kt");
        String loadingLayout = readFile("src/main/res/layout/view_loading.xml");
        String strings = readFile("src/main/res/values/strings.xml");

        assertFalse(activity.contains("title = \"智能引擎分析中\""));
        assertTrue(activity.contains("title = \"正在搜索\""));
        assertFalse(loadingLayout.contains("android:text=\"智能引擎分析中\""));
        assertTrue(loadingLayout.contains("android:text=\"正在加载\""));
        assertTrue(loadingLayout.contains("android:id=\"@+id/loading_step_progress\""));
        assertTrue(loadingLayout.contains("android:id=\"@+id/loading_percent\""));
        assertTrue(countOccurrences(loadingLayout, "android:visibility=\"gone\"") >= 2);
        assertFalse(strings.contains("部分信息刷新失败，点击重试"));
        assertTrue(strings.contains("部分书籍信息暂未加载，点击重试"));
    }

    @Test
    public void novelResultsRecheckRequestIdentityOnMainBeforeSynchronousDelivery() throws IOException {
        String viewModel = readSource("SearchViewModel.kt");

        assertTrue(viewModel.contains("withContext(Dispatchers.Main.immediate)"));
        assertTrue(viewModel.contains("requestVersion != bookRequestVersion || activeBookQuery != query"));
        assertTrue(viewModel.contains("_books.value = visibleBooks"));
        assertTrue(viewModel.contains("_bookSearchErrors.value = ++bookSearchErrorVersion"));
        assertFalse(viewModel.contains("_books.postValue"));
        assertFalse(viewModel.contains("_bookSearchErrors.postValue"));
    }

    @Test
    public void provisionalNovelResultReturnsBeforeCancellingSearchOrOpeningDetail() throws IOException {
        String activity = readSource("SearchActivity.kt");
        int gate = activity.indexOf("SearchFlowPolicy.novelResultClickAction(book.readingReady)");
        int cancellation = activity.indexOf("viewModel.cancelActiveBookWork()", gate);

        assertTrue(gate >= 0);
        assertTrue(cancellation > gate);
        assertTrue(
                activity.substring(gate, cancellation)
                        .contains("return@setOnItemClickListener")
        );
    }

    @Test
    public void choosingKeywordSynchronizesVisibleInputWithRetryQuery() throws IOException {
        String activity = readSource("SearchActivity.kt");

        assertTrue(activity.contains("val book = mKeyWordAdapter!!.getItem(pos).trim()"));
        assertTrue(activity.contains("mEtInput!!.setText(book)"));
        assertTrue(activity.contains("beginBookSearch(book)"));
    }

    @Test
    public void bookDetailErrorViewRetriesTheSameBook() throws IOException {
        String activity = readSource("BookDetailActivity.kt");

        assertTrue(activity.contains("setOnReloadingListener(object : RefreshLayout.OnReloadingListener"));
        assertTrue(activity.contains("private fun retryBookDetail()"));
        assertTrue(activity.contains("viewModel.refreshBookDetail(mBookId)"));
    }

    @Test
    public void bookDetailLabelsAnUnresolvedLatestChapterAsLoading() throws IOException {
        String activity = readSource("BookDetailActivity.kt");

        assertTrue(activity.contains("\"最新章节加载中…\""));
        assertFalse(activity.contains("\"目录刷新中\""));
    }

    @Test
    public void smartFindEntryHasARealInputAction() throws IOException {
        String activity = readSource("SearchActivity.kt");

        assertTrue(activity.contains("searchAssistantEntry.setOnClickListener"));
        assertTrue(activity.contains("focusSearchInput()"));
    }

    @Test
    public void recreationRestoresSearchIdentityAndOnlyCancelsNovelWorkWhenReallyLeaving() throws IOException {
        String activity = readSource("SearchActivity.kt");
        String viewModel = readSource("SearchViewModel.kt");

        assertTrue(activity.contains("override fun onSaveInstanceState(outState: Bundle)"));
        assertTrue(activity.contains("STATE_SEARCH_KIND"));
        assertTrue(activity.contains("STATE_INPUT_QUERY"));
        assertTrue(activity.contains("STATE_SUBMITTED_QUERY"));
        assertTrue(activity.contains("mEtInput?.isSaveEnabled = false"));
        assertTrue(activity.contains("restoreSavedSearchState()"));
        assertTrue(activity.contains("viewModel.retainedBookSearchState(restoredSubmittedQuery)"));
        assertTrue(activity.contains("viewModel.searchKeyWord(plan.inputQuery)"));
        assertTrue(activity.contains("if (!isChangingConfigurations)"));
        assertTrue(viewModel.contains("results = latestBookResults"));
        assertTrue(activity.contains("SearchFlowPolicy.isCurrentMediaResponse"));
    }

    private static boolean containsCallWithFirstArgument(
            String source,
            String methodName,
            String firstArgument
    ) {
        Pattern call = Pattern.compile(
                "\\b" + Pattern.quote(methodName) + "\\s*\\(\\s*\\\"" +
                        Pattern.quote(firstArgument) + "\\\""
        );
        return call.matcher(source).find();
    }

    private static int countOccurrences(String value, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(token, cursor)) >= 0) {
            count += 1;
            cursor += token.length();
        }
        return count;
    }

    private static String readSource(String fileName) throws IOException {
        return readFile("src/main/java/com/ldp/reader/ui/activity/" + fileName);
    }

    private static String readFile(String path) throws IOException {
        File source = new File(path);
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }
}
