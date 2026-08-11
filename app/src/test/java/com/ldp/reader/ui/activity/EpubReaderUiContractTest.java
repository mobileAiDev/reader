package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class EpubReaderUiContractTest {

    @Test
    public void epubContentIsEdgeToEdgeWithImmersiveOverlayControls() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/EpubReadActivity.kt");
        String chrome = readFile("src/main/java/com/ldp/reader/ui/activity/MediaUiChrome.kt");
        String layout = readFile("src/main/res/layout/activity_epub_read.xml");

        assertTrue(layout.contains("<FrameLayout"));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_web\""));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_toolbar\""));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_bottom_panel\""));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_progress\""));
        assertTrue(activity.contains("MediaUiChrome.prepareImmersiveReader(this)"));
        assertTrue(activity.contains("MediaUiChrome.immersiveReader(this, controlsVisible)"));
        assertTrue(activity.contains("MediaUiChrome.applyReaderInsets("));
        assertTrue(activity.contains(
            "MediaUiChrome.applyReaderProgressInsets(binding.epubReadProgress)"
        ));
        assertTrue(activity.contains(
            "MediaUiChrome.applyReaderCutoutInsets(binding.epubReadWeb)"
        ));
        assertTrue(chrome.contains("fun applyReaderCutoutInsets(content: View)"));
        assertTrue(activity.contains("installCenterTapToggle()"));
        assertTrue(activity.contains("setControlsVisible(!controlsVisible)"));
        assertTrue(activity.contains("onSingleTapConfirmed(event: MotionEvent)"));
        assertTrue(activity.contains("onDoubleTap(event: MotionEvent)"));
        assertTrue(activity.contains("epubHitTargetsLink(webView.hitTestResult?.type)"));
        assertFalse(activity.contains("MediaUiChrome.light(this)"));
    }

    @Test
    public void epubOverlayOffersCatalogThemeAndExpandableFontControls() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/EpubReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_epub_read.xml");

        assertTrue(layout.contains("android:id=\"@+id/epub_read_catalog\""));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_theme\""));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_font\""));
        assertTrue(layout.contains("android:id=\"@+id/epub_read_font_panel\""));
        assertTrue(activity.contains("val catalog = currentDocument.catalogEntries"));
        assertTrue(activity.contains(".setSingleChoiceItems(labels, checkedEntryIndex)"));
        assertTrue(activity.contains("EpubReaderUiPolicy.currentSectionScript()"));
        assertTrue(activity.contains("EpubReaderUiPolicy.checkedCatalogEntryIndex("));
        assertTrue(activity.contains(
            "EpubReaderUiPolicy.catalogJumpScript(target.sectionIndex, target.targetId)"
        ));
        assertTrue(activity.contains("webView.settings.javaScriptEnabled = true"));
        assertTrue(activity.contains("webView.settings.javaScriptEnabled = false"));
        assertFalse(activity.contains("EpubPackageParser.BASE_URL}#epub-section"));
        assertTrue(activity.contains("showThemePicker()"));
        assertTrue(activity.contains("changeTextZoom(TEXT_ZOOM_STEP)"));
    }

    @Test
    public void epubLoadingFailureAndRestoredProgressStayVisibleAndStateful() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/EpubReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_epub_read.xml");

        assertTrue(layout.contains("android:id=\"@+id/epub_read_status\""));
        assertTrue(activity.contains("showLoadingState()"));
        assertTrue(activity.contains("showLoadFailure(result.message)"));
        assertTrue(activity.contains("showLoadFailure(\"文件已失效，请重新选择\")"));
        assertFalse(activity.contains("EPUB 文件地址无效"));
        assertTrue(activity.contains("pendingReadingProgress = progressBeforeReload"));
        assertTrue(activity.contains("restoredEpubScrollY("));
        assertTrue(activity.contains("updateProgressLabel()"));
        assertTrue(activity.contains("outState.putInt(STATE_READER_THEME, readerTheme.ordinal)"));
        assertTrue(activity.contains("MMKV.mmkvWithID(READER_PREFERENCES)"));
        assertFalse(activity.contains("getSharedPreferences"));
        assertFalse(activity.contains("findViewById"));
    }

    @Test
    public void epubWebViewExposesNamedAccessibilityAndKeyboardControlToggle() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/EpubReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_epub_read.xml");
        String strings = readFile("src/main/res/values/strings.xml");
        String traditionalStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertTrue(activity.contains("ViewCompat.addAccessibilityAction("));
        assertTrue(activity.contains("ViewCompat.removeAccessibilityAction("));
        assertTrue(activity.contains("binding.epubReadWeb.setOnKeyListener"));
        assertTrue(activity.contains("KeyEvent.KEYCODE_DPAD_CENTER"));
        assertTrue(activity.contains("R.string.epub_reader_show_controls"));
        assertTrue(activity.contains("R.string.epub_reader_hide_controls"));
        assertTrue(strings.contains("<string name=\"epub_reader_show_controls\">显示阅读控件</string>"));
        assertTrue(strings.contains("<string name=\"epub_reader_hide_controls\">隐藏阅读控件</string>"));
        assertTrue(activity.contains(
            "toolbar?.setNavigationContentDescription(R.string.epub_reader_navigate_back)"
        ));
        assertTrue(layout.contains("android:accessibilityLiveRegion=\"polite\""));
        assertTrue(layout.contains(
            "android:contentDescription=\"@string/epub_reader_decrease_font_size\""
        ));
        assertTrue(layout.contains(
            "android:contentDescription=\"@string/epub_reader_increase_font_size\""
        ));
        assertTrue(strings.contains(
            "<string name=\"epub_reader_navigate_back\">返回</string>"
        ));
        assertTrue(strings.contains(
            "<string name=\"epub_reader_decrease_font_size\">减小字号</string>"
        ));
        assertTrue(strings.contains(
            "<string name=\"epub_reader_increase_font_size\">增大字号</string>"
        ));
        assertTrue(traditionalStrings.contains(
            "<string name=\"epub_reader_navigate_back\">返回</string>"
        ));
        assertTrue(traditionalStrings.contains(
            "<string name=\"epub_reader_decrease_font_size\">減小字號</string>"
        ));
        assertTrue(traditionalStrings.contains(
            "<string name=\"epub_reader_increase_font_size\">增大字號</string>"
        ));
    }

    @Test
    public void epubFontActionsExposeAtLeastFortyEightDpTouchTargets() throws IOException {
        String layout = readFile("src/main/res/layout/activity_epub_read.xml");

        assertTrue(layout.matches(
            "(?s).*epub_read_font_size_label.*?android:layout_width=\"wrap_content\".*?" +
                "android:minWidth=\"72dp\".*"
        ));
        assertTrue(layout.matches(
            "(?s).*epub_read_decrease.*?android:layout_height=\"48dp\".*"
        ));
        assertTrue(layout.matches(
            "(?s).*epub_read_increase.*?android:layout_height=\"48dp\".*"
        ));
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
