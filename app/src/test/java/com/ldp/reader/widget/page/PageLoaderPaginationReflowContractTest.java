package com.ldp.reader.widget.page;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class PageLoaderPaginationReflowContractTest {

    @Test
    public void viewportChangesInvalidateEveryOldPaginationGenerationBeforeReload() throws Exception {
        String source = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");
        int prepare = source.indexOf("fun prepareDisplay(w: Int, h: Int)");
        int reflow = source.indexOf("private fun reflowForPaginationChange(", prepare);
        int cachedRepage = source.indexOf("private fun repageCurrentChapterFromCachedText(", reflow);
        String prepareBody = source.substring(prepare, reflow);
        String body = source.substring(reflow, cachedRepage);
        String cachedRepageBody = source.substring(
                cachedRepage,
                source.indexOf("private fun clearAdjacentPageCaches()", cachedRepage)
        );

        assertTrue(prepare > 0);
        assertTrue(reflow > prepare);
        assertTrue(prepareBody.contains("ReaderPaginationReflowPolicy.hasChanged("));
        assertTrue(prepareBody.contains("ReaderPaginationReflowPolicy.plan("));
        assertTrue(prepareBody.contains("if (reflowPlan.invalidateStalePagination)"));
        assertTrue(prepareBody.contains("reflowForPaginationChange(reflowPlan.action)"));
        assertTrue(body.contains("invalidateCurrentChapterLoad()"));
        assertTrue(body.contains("clearAdjacentPageCaches()"));
        assertTrue(body.contains("captureRelativeProgress("));
        assertTrue(body.contains("ReaderPaginationReflowAction.RELOAD_CURRENT_IN_BACKGROUND"));
        assertTrue(body.contains("requestCurrentChapterInBackground("));
        assertTrue(body.contains("resolvePagePosition = { pageCount ->"));
        assertTrue(body.contains("ReaderPaginationReflowAction.RELOAD_CURRENT_SYNCHRONOUSLY"));
        assertTrue(body.contains("repageCurrentChapterFromCachedText(relativeProgress)"));
        assertTrue(cachedRepageBody.contains("invalidateCurrentChapterLoad()"));
        assertTrue(cachedRepageBody.contains("clearAdjacentPageCaches()"));
        assertTrue(cachedRepageBody.contains("dealLoadPageList(mCurChapterPos)"));
        assertTrue(cachedRepageBody.contains("preLoadNextChapter()"));
    }

    @Test
    public void currentPageLookupClampsAnyStaleNumericPosition() throws Exception {
        String source = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");
        int method = source.indexOf("private fun getCurPage(pos: Int)");
        int nextMethod = source.indexOf("private fun getPrevPage()", method);
        String body = source.substring(method, nextMethod);

        assertTrue(body.contains("pos.coerceIn(0, pages.lastIndex)"));
        assertTrue(body.contains("onPageChange(clampedPosition)"));
        assertTrue(body.contains("return pages[clampedPosition]"));
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
