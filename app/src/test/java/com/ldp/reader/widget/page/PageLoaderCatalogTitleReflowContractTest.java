package com.ldp.reader.widget.page;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class PageLoaderCatalogTitleReflowContractTest {

    @Test
    public void publicCatalogRefreshRebuildsTheCanvasPageFromCachedText() throws Exception {
        String netLoader = readFile("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt");
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        int refreshStart = netLoader.indexOf("override fun refreshChapterList()");
        int refreshEnd = netLoader.indexOf("override fun getChapterReader", refreshStart);
        String refresh = netLoader.substring(refreshStart, refreshEnd);
        assertTrue(refresh.contains("val previousCurrentChapter = currentChapterBeforeCatalogRefresh()"));
        assertTrue(
                refresh.indexOf("previousCurrentChapter")
                        < refresh.indexOf("mChapterList = convertTxtChapter(bookChapters)")
        );
        assertTrue(refresh.contains(
                "repageCurrentChapterAfterSafeTitleUpgrade(previousCurrentChapter)"
        ));

        int seamStart = pageLoader.indexOf(
                "protected fun repageCurrentChapterAfterSafeTitleUpgrade("
        );
        int seamEnd = pageLoader.indexOf("protected fun restoreCurrentChapterFromAnchor()", seamStart);
        String seam = pageLoader.substring(seamStart, seamEnd);
        assertTrue(seam.contains("ReaderCatalogPageRefreshPolicy.shouldRepage("));
        assertTrue(seam.contains("ReaderPaginationReflowPolicy.captureRelativeProgress("));
        assertTrue(seam.contains("repageCurrentChapterFromCachedText(relativeProgress)"));
        assertTrue(seam.contains("\"previousPagePos\" to previousPagePosition"));
        assertTrue(seam.contains("\"previousPages\" to previousPageCount"));
        assertFalse(seam.contains("openChapter("));
        assertFalse(seam.contains("requestCurrentChapterInBackground("));
    }

    @Test
    public void cachedRepaginationRefreshesBothCanvasTitleSourcesAndPreservesProgress() throws Exception {
        String source = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        int repageStart = source.indexOf("private fun repageCurrentChapterFromCachedText(");
        int repageEnd = source.indexOf("private fun clearAdjacentPageCaches()", repageStart);
        String repage = source.substring(repageStart, repageEnd);
        assertTrue(repage.contains("invalidateCurrentChapterLoad()"));
        assertTrue(repage.contains("dealLoadPageList(mCurChapterPos)"));
        assertTrue(repage.contains("ReaderPaginationReflowPolicy.restorePagePosition("));
        assertTrue(repage.contains("mCurPage = getCurPage(restoredPosition)"));
        assertTrue(repage.contains("mPageView!!.drawCurPage(false)"));
        assertFalse(repage.contains("requestChapters("));
        assertFalse(repage.contains("openChapter("));

        int backgroundStart = source.indexOf("private fun drawBackground(");
        int contentStart = source.indexOf("private fun drawContent(", backgroundStart);
        String background = source.substring(backgroundStart, contentStart);
        int contentEnd = source.indexOf("fun prepareDisplay(", contentStart);
        String content = source.substring(contentStart, contentEnd);
        assertTrue(background.contains("mCurPage!!.title!!"));
        assertTrue(content.contains("mCurPage!!.titleLines"));
        assertTrue(content.contains("mCurPage!!.lines!![i]"));
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
