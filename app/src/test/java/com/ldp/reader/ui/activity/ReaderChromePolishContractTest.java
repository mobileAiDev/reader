package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ReaderChromePolishContractTest {

    @Test
    public void localComicUsesImmersiveOverlayChromeAndOneTapPageControls() throws Exception {
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt"
        );
        String layout = readFile("src/main/res/layout/activity_local_comic_read.xml");

        assertTrue(activity.contains("MediaUiChrome.prepareImmersiveReader(this)"));
        assertTrue(activity.contains("MediaUiChrome.immersiveReader(this, controlsVisible)"));
        assertTrue(activity.contains("MediaUiChrome.applyReaderInsets("));
        assertTrue(activity.contains(
            "MediaUiChrome.applyReaderProgressInsets(binding.localComicReadProgress)"
        ));
        assertTrue(activity.contains("pageAdapter.setOnItemClickListener { _, _ -> toggleControls() }"));
        assertTrue(activity.contains("binding.localComicReadProgress.visibility"));
        assertTrue(activity.contains("binding.localComicReadBottomPanel.visibility"));

        String pages = element(layout, "@+id/local_comic_read_pages", "/>");
        assertFalse(pages.contains("android:paddingTop"));
        assertFalse(pages.contains("android:paddingBottom"));
        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_bottom_panel\""));
        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_progress\""));
        assertTrue(layout.contains("@drawable/bg_reader_progress_pill"));
    }

    @Test
    public void localComicOffersAnAccessibleDirectionSwitchWithoutLosingTheVisiblePage()
        throws Exception {
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt"
        );
        String layout = readFile("src/main/res/layout/activity_local_comic_read.xml");

        String direction = element(
            layout,
            "@+id/local_comic_read_direction",
            "/>"
        );
        assertTrue(direction.contains("android:layout_height=\"48dp\""));
        assertTrue(direction.contains("android:clickable=\"true\""));
        assertTrue(direction.contains("android:focusable=\"true\""));
        assertTrue(direction.contains("android:text=\"@string/local_comic_vertical_mode\""));

        assertTrue(activity.contains("binding.localComicReadDirection.setOnClickListener"));
        assertTrue(activity.contains(
            "val retainedPage = dominantVisiblePage(binding.localComicReadPages)"
        ));
        assertTrue(activity.contains("horizontalMode = !horizontalMode"));
        assertTrue(activity.contains("applyPageOrientation(retainedPage)"));
        assertTrue(activity.contains("if (horizontalMode) child.left else child.top"));
        assertTrue(activity.contains("if (horizontalMode) child.right else child.bottom"));
        assertTrue(activity.contains("ComicReaderModePreferences.loadLocalHorizontal(directionDocumentId())"));
        assertTrue(activity.contains("ComicReaderModePreferences.saveLocalHorizontal(directionDocumentId(), horizontalMode)"));
        assertTrue(activity.contains("outState.putBoolean(STATE_HORIZONTAL_MODE, horizontalMode)"));
    }

    @Test
    public void pdfUsesPageLanguageAndRealBoundaryButtonState() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/PdfReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_pdf_read.xml");

        assertTrue(activity.contains("MediaUiChrome.applyReaderInsets("));
        assertTrue(activity.contains(
            "MediaUiChrome.applyReaderProgressInsets(binding.pdfReadProgress)"
        ));
        assertTrue(activity.contains("binding.pdfReadPrev.isEnabled = state.hasPrevious"));
        assertTrue(activity.contains("binding.pdfReadNext.isEnabled = state.hasNext"));
        assertTrue(activity.contains("binding.pdfReadPrev.alpha = if (state.hasPrevious)"));
        assertTrue(activity.contains("binding.pdfReadNext.alpha = if (state.hasNext)"));
        assertTrue(layout.contains("android:text=\"@string/reader_jump_page\""));
        assertFalse(layout.contains("android:text=\"@string/reader_catalog\""));
    }

    @Test
    public void localComicFailureUsesReadableRetrySurface() throws Exception {
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt"
        );
        String layout = readFile("src/main/res/layout/activity_local_comic_read.xml");

        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_error\""));
        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_error_message\""));
        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_retry\""));
        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_error_back\""));
        assertTrue(activity.contains("binding.localComicReadRetry.setOnClickListener { loadArchive() }"));
        assertTrue(activity.contains("binding.localComicReadErrorBack.setOnClickListener { finish() }"));
        assertTrue(activity.contains("showArchiveFailure(ComicArchiveFailureCopy.messageFor(result.reason))"));
        assertFalse(activity.contains("showArchiveFailure(result.message)"));
        assertFalse(layout.contains("android:singleLine=\"true\"\n        android:textColor=\"@color/media_reader_text_muted\""));
    }

    @Test
    public void onlineComicRoutesOnePhysicalTapThroughOneTogglePath() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(activity.contains("MediaUiChrome.applyReaderInsets("));
        assertFalse(activity.contains("binding.root.setOnClickListener { toggleControls() }"));
        assertFalse(activity.contains(
            "binding.comicReadPages.setOnClickListener { toggleControls() }"
        ));
        assertTrue(activity.contains("MotionEvent.ACTION_CANCEL -> return"));
        assertEquals(2, occurrences(activity, "toggleControls()"));
        assertTrue(activity.contains("binding.comicReadPrevChapter.isEnabled = hasPrevious"));
        assertTrue(activity.contains("binding.comicReadNextChapter.isEnabled = hasNext"));
    }

    @Test
    public void onlineComicProgressAndControlsStayClearOfNavigationGestures() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_comic_read.xml");

        String progress = element(layout, "@+id/comic_read_state", "/>");
        assertTrue(progress.contains("android:layout_height=\"28dp\""));
        assertTrue(progress.contains("android:layout_gravity=\"bottom|center_horizontal\""));
        assertTrue(progress.contains("android:layout_marginBottom=\"14dp\""));
        assertTrue(progress.contains("android:background=\"@drawable/bg_reader_progress_pill\""));
        assertTrue(progress.contains("android:maxWidth=\"240dp\""));
        assertTrue(progress.contains("android:minWidth=\"74dp\""));
        assertTrue(progress.contains("android:textSize=\"12sp\""));

        assertTrue(activity.contains(
            "MediaUiChrome.applyReaderProgressInsets(binding.comicReadState)"
        ));
        assertTrue(activity.contains(
            "binding.comicReadState.visibility = if (visible) View.GONE else View.VISIBLE"
        ));
        assertTrue(activity.contains("ViewCompat.replaceAccessibilityAction("));
        assertTrue(activity.contains("AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK"));

        String pageSeek = element(layout, "@+id/comic_read_page_seek", "/>");
        String brightness = element(layout, "@+id/comic_read_brightness", "/>");
        assertTrue(pageSeek.contains("android:contentDescription=\"@string/reader_comic_page_seek\""));
        assertTrue(brightness.contains("android:contentDescription=\"@string/reader_brightness_seek\""));
        assertTrue(layout.contains("android:layout_height=\"48dp\""));
    }

    private static String element(String source, String id, String terminator) {
        int start = source.indexOf(id);
        int end = source.indexOf(terminator, start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            count += 1;
            index += value.length();
        }
        return count;
    }

    private static String readFile(String path) throws Exception {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
