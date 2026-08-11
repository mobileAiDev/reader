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
    public void settingPanelOwnsAndPaintsItsNavigationInset() throws Exception {
        String dialog = readFile("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt");
        String chrome = readFile("src/main/java/com/ldp/reader/ui/activity/MediaUiChrome.kt");
        String layout = readFile("src/main/res/layout/dialog_read_setting.xml");

        assertTrue(dialog.contains(
                "MediaUiChrome.prepareReaderBottomDialog(window, binding.readSettingLlMenu)"
        ));
        assertTrue(chrome.contains("fun prepareReaderBottomDialog(window: Window, panel: View)"));
        assertTrue(chrome.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"));
        assertTrue(chrome.contains("windowAttributes.setFitInsetsTypes("));
        assertTrue(chrome.contains("WindowInsets.Type.navigationBars()"));
        assertTrue(chrome.contains("window.attributes = windowAttributes"));
        assertTrue(chrome.contains("ReaderBottomDialogInsetPolicy.bottomPadding("));
        assertTrue(chrome.contains("addOnAttachStateChangeListener"));
        assertTrue(layout.contains("android:id=\"@+id/read_setting_ll_menu\""));
        assertTrue(layout.contains("android:background=\"@color/nb.read.menu.bg\""));
    }

    private static String readFile(String path) throws Exception {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
