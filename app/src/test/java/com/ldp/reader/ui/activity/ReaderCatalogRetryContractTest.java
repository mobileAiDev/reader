package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class ReaderCatalogRetryContractTest {
    @Test
    public void viewModelStopsTheCatalogCycleAndPublishesAnErrorAfterRetryExhaustion()
        throws IOException {
        String viewModel = loadCategoryBody();

        assertTrue(viewModel.contains("ReaderCatalogRetryPolicy.afterFailure("));
        assertTrue(viewModel.contains("ReaderCatalogRetryAction.RETRY_AFTER_DELAY ->"));
        assertTrue(viewModel.contains("ReaderCatalogRetryAction.PUBLISH_ERROR ->"));
        assertTrue(viewModel.contains("source_read_catalog_retry_exhausted"));
        assertTrue(viewModel.contains("_chapterErrorEvents.value = ++chapterErrorVersion"));
    }

    @Test
    public void cancellationAndExistingCatalogRetentionRemainIntact() throws IOException {
        String viewModel = loadCategoryBody();
        String fullViewModel = readFile(
            "src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt"
        );

        assertTrue(viewModel.contains("catch (e: CancellationException)"));
        assertTrue(viewModel.contains("throw e"));
        assertTrue(fullViewModel.contains("ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog("));
    }

    @Test
    public void catalogErrorOffersAManualRetryThatStartsANewCycle() throws IOException {
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt"
        );

        assertTrue(activity.contains("private fun retryCatalogIfNeeded(): Boolean"));
        assertTrue(activity.contains("ReaderCatalogRetryPolicy.manualRetry("));
        assertTrue(activity.contains("ReaderCatalogManualRetryAction.RELOAD_REMOTE_CATALOG"));
        assertTrue(activity.contains("source_read_catalog_manual_retry"));
        assertTrue(activity.contains(
            "viewModel.loadCategory(mBookId, collBook, persistToShelf = isCollected)"
        ));
    }

    @Test
    public void localCatalogFailuresRetryTheParserFromEdgesAndCatalogAction() throws IOException {
        String localLoader = readFile(
            "src/main/java/com/ldp/reader/widget/page/LocalPageLoader.kt"
        );
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt"
        );
        String pageLoader = readFile(
            "src/main/java/com/ldp/reader/widget/page/PageLoader.kt"
        );

        int refreshStart = localLoader.indexOf("override fun refreshChapterList()");
        int refreshEnd = localLoader.indexOf("override fun getChapterReader", refreshStart);
        String localRefresh = localLoader.substring(refreshStart, refreshEnd);
        assertTrue("blank book ids and parse exceptions must both become retryable catalog errors",
            occurrences(localRefresh, "showCatalogError()") >= 2);
        assertTrue("a catalog failure must not enter the current-chapter retry state",
            !localRefresh.contains("chapterError()"));

        int retryStart = activity.indexOf("private fun retryCatalogIfNeeded(): Boolean");
        int retryEnd = activity.indexOf("override fun onBackPressed()", retryStart);
        String manualRetry = activity.substring(retryStart, retryEnd);
        assertTrue(manualRetry.contains("ReaderCatalogRetryPolicy.manualRetry("));
        assertTrue(manualRetry.contains("ReaderCatalogManualRetryAction.REFRESH_LOCAL_CATALOG"));
        assertTrue(manualRetry.contains("pageLoader.refreshChapterList()"));
        assertTrue(manualRetry.contains("ReaderCatalogManualRetryAction.RELOAD_REMOTE_CATALOG"));
        assertTrue(manualRetry.contains(
            "viewModel.loadCategory(mBookId, collBook, persistToShelf = isCollected)"
        ));

        assertTrue("left and right edge actions must enter the shared catalog retry seam",
            occurrences(activity, "retryCatalogIfNeeded()") >= 4);
        assertTrue("current chapter failures must still retry only the chapter",
            pageLoader.contains("if (mStatus != STATUS_ERROR)") &&
                pageLoader.contains("private fun retryCurrentChapter()") &&
                pageLoader.contains("openChapter()"));
    }

    @Test
    public void loadFailureOffersRetryWhileConfirmedEmptyKeepsTheNoChaptersMessage()
        throws IOException {
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt"
        );
        String pageLoader = readFile(
            "src/main/java/com/ldp/reader/widget/page/PageLoader.kt"
        );

        assertTrue(pageLoader.contains("fun showCatalogError()"));
        assertTrue(pageLoader.contains("mStatus = STATUS_CATALOG_ERROR"));
        assertTrue(pageLoader.contains("fun showCatalogEmpty()"));
        assertTrue(pageLoader.contains("mStatus = STATUS_CATEGORY_EMPTY"));
        assertTrue(pageLoader.contains(
            "ReaderCatalogTerminalState.LOAD_FAILED"
        ));
        assertTrue(pageLoader.contains(
            "ReaderCatalogTerminalState.CONFIRMED_EMPTY"
        ));

        int emptyCatalog = activity.indexOf(
            "if (!ReaderCatalogStartupPolicy.canDisplayRemoteCatalog(bookChapters.size))"
        );
        int emptyState = activity.indexOf("mPageLoader!!.showCatalogEmpty()", emptyCatalog);
        int catalogAction = activity.indexOf("binding!!.readTvCategory.setOnClickListener");
        int emptyMessage = activity.indexOf(
            "ReaderCatalogTerminalState.CONFIRMED_EMPTY",
            catalogAction
        );

        assertTrue(emptyCatalog > 0);
        assertTrue(emptyState > emptyCatalog);
        assertTrue(catalogAction > 0);
        assertTrue(emptyMessage > catalogAction);
    }

    private static String loadCategoryBody() throws IOException {
        String viewModel = readFile(
            "src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt"
        );
        int start = viewModel.indexOf("    fun loadCategory(");
        int end = viewModel.indexOf("    private fun publishCachedReadingCatalog(", start);
        return viewModel.substring(start, end);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count += 1;
            offset += token.length();
        }
        return count;
    }
}
