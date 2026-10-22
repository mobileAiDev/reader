package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ReaderImmersiveWindowContractTest {

    @Test
    public void sharedReaderChromeUsesInsetsControllerAndSafeAreaPadding() throws Exception {
        String source = readFile(
            "src/main/java/com/ldp/reader/ui/activity/MediaUiChrome.kt"
        );

        assertTrue(source.contains("WindowCompat.setDecorFitsSystemWindows"));
        assertTrue(source.contains("WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"));
        assertTrue(source.contains("hide(WindowInsetsCompat.Type.systemBars())"));
        assertTrue(source.contains("show(WindowInsetsCompat.Type.systemBars())"));
        assertTrue(source.contains("WindowInsetsCompat.Type.displayCutout()"));
        assertTrue(source.contains("WindowInsetsCompat.Type.mandatorySystemGestures()"));
        assertTrue(source.contains("fun applyReaderContentInsets("));
        assertTrue(source.contains("fun applyReaderProgressInsets("));
        assertTrue(source.contains("initialPaddings.getOrPut(this)"));
        assertTrue(source.contains("initialMargins.getOrPut(this)"));
        assertFalse(source.contains("FLAG_LAYOUT_NO_LIMITS"));
    }

    @Test
    public void textReaderHidesSystemBarsWithContentAndRestoresThemForChrome() throws Exception {
        String source = readFile(
            "src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt"
        );

        assertTrue(source.contains("MediaUiChrome.prepareImmersiveReader(this)"));
        assertTrue(source.contains("FloatingReaderChrome.keepClear(binding!!.readTopMenu, atTop = true)"));
        assertTrue(source.contains(
            "MediaUiChrome.applyReaderContentInsets(binding!!.readLlCatalogDrawer)"
        ));
        assertFalse(source.contains("ScreenUtils.getStatusBarHeight()"));
        assertTrue(source.contains("applySystemUi(controlsVisible = false)"));
        assertTrue(source.contains("applySystemUi(controlsVisible = true)"));
        assertTrue(source.contains("MediaUiChrome.immersiveReader(this, controlsVisible, lightBars)"));
        assertFalse(source.contains("BarUtils.transparentStatusBar(this)"));
        assertFalse(source.contains("if (!ReadSettingManager.getInstance().isFullScreen)"));
    }

    private static String readFile(String path) throws Exception {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
