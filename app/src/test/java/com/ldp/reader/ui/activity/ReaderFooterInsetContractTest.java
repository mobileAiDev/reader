package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ReaderFooterInsetContractTest {

    @Test
    public void pageCanvasUsesStableGestureInsetsAndOneSharedContentGeometry() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String pageView = readFile("src/main/java/com/ldp/reader/widget/page/PageView.kt");
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");
        String scroll = readFile("src/main/java/com/ldp/reader/widget/animation/ScrollPageAnim.kt");

        int bindInsets = activity.indexOf("binding!!.readPvPage.bindReaderSafeInsets()");
        int createLoader = activity.indexOf("binding!!.readPvPage.getPageLoader(mCollBook!!)");
        assertTrue(bindInsets > 0);
        assertTrue(createLoader > bindInsets);

        assertTrue(pageView.contains("WindowInsetsCompat.Type.mandatorySystemGestures()"));
        assertTrue(pageView.contains("WindowInsetsCompat.Type.tappableElement()"));
        assertTrue(pageView.contains("WindowInsetsCompat.Type.displayCutout()"));
        assertTrue(pageView.contains("readerSafeInsetsResolved"));
        assertTrue(pageView.contains("ReaderPageGeometry.canPrepareDisplay("));
        assertTrue(pageView.contains("requestReaderSafeInsetsWhenAttached()"));
        assertTrue(pageView.contains("mPageLoader?.setReaderSafeInsets(readerSafeInsets)"));
        assertTrue(pageLoader.contains("ReaderPageGeometry.resolveVertical("));
        assertTrue(pageLoader.contains("invalidateCurrentChapterLoad()"));
        assertTrue(pageLoader.contains("mPageGeometry.footerBottom"));
        assertTrue(pageLoader.contains("mPageGeometry.contentHeight"));
        assertTrue(pageLoader.contains("val contentTop: Int"));
        assertTrue(pageLoader.contains("val visibleContentHeight: Int"));
        assertTrue(scroll.contains("contentTop: Int"));
        assertTrue(scroll.contains("contentHeight: Int"));
    }

    @Test
    public void floatingChromeStaysClearOfSystemBarsWithoutShiftingWhenTheyShow() throws Exception {
        String chrome = readFile("src/main/java/com/ldp/reader/ui/reader/FloatingReaderChrome.kt");
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");

        assertTrue(chrome.contains("insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())"));
        assertTrue(chrome.contains("WindowInsetsCompat.Type.displayCutout()"));
        // Bottom bars float above the page footer, so it never shows at their round corners.
        assertTrue(chrome.contains("max(bars.bottom, cutout.bottom) + clearancePx"));
        assertTrue(panel.contains("clearancePx = binding.root.resources.getDimensionPixelSize(R.dimen.reader_footer_height)"));
        assertTrue(activity.contains("clearancePx = resources.getDimensionPixelSize(R.dimen.reader_footer_height)"));
        assertTrue(activity.contains("FloatingReaderChrome.keepClear(binding!!.readTopMenu, atTop = true)"));
        assertTrue(activity.contains("binding!!.readBottomMenu,\n            atTop = false,"));
    }

    private static String readFile(String path) throws Exception {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
