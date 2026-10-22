package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class SystemBarThemeContractTest {

    @Test
    public void lightPagesContinueTheirSurfaceThroughGestureNavigation() throws Exception {
        String appTheme = styleBody(readFile("src/main/res/values/styles.xml"), "BaseAppTheme");
        String colors = readFile("src/main/res/values/colors.xml");
        String api27Colors = readFile("src/main/res/values-v27/colors.xml");

        assertTrue(appTheme.contains(
                "<item name=\"android:navigationBarColor\">@color/ink_system_nav</item>"
        ));
        assertTrue(appTheme.contains(
                "<item name=\"android:windowLightNavigationBar\" tools:targetApi=\"27\">@bool/ink_light_system_bars</item>"
        ));
        // API 25-26 cannot draw dark navigation icons, so retain a dark, readable fallback.
        assertTrue(colors.contains("<color name=\"ink_system_nav\">#141414</color>"));
        // Newer devices continue the page into the gesture area; the window paper is fixed because
        // the system draws it before the app's theme exists.
        assertTrue(api27Colors.contains("<color name=\"ink_system_nav\">@color/window_paper</color>"));
    }

    @Test
    public void bottomDialogsUseTheSameSingleSystemDecorInsetOwner() throws Exception {
        String styles = readFile("src/main/res/values/styles.xml");
        String sheetTheme = styleBody(styles, "ThemeOverlay.Ink.BottomSheetDialog");
        String api27Colors = readFile("src/main/res/values-v27/colors.xml");

        assertTrue(sheetTheme.contains(
                "<item name=\"android:navigationBarColor\">@color/ink_system_nav_raised</item>"
        ));
        assertTrue(sheetTheme.contains(
                "<item name=\"android:windowLightNavigationBar\" tools:targetApi=\"27\">@bool/ink_light_system_bars</item>"
        ));
        assertTrue(styles.contains(
                "<style name=\"ReaderBookshelfRemoveBottomSheetDialog\" parent=\"ThemeOverlay.Ink.BottomSheetDialog\" />"
        ));
        assertTrue(api27Colors.contains(
                "<color name=\"ink_system_nav_raised\">@color/ink_surface_raised</color>"
        ));
    }

    @Test
    public void readerSettingsSheetIsTintedFromThePageItFloatsOver() throws Exception {
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        String glass = readFile("src/main/java/com/ldp/reader/ui/glass/GlassStyle.kt");

        assertTrue(panel.contains("GlassStyle.readerPanel(palette.panel, palette.isDark)"));
        assertTrue(glass.contains("fun readerPanel(panel: Int, dark: Boolean): GlassStyle"));
    }

    private static String styleBody(String xml, String styleName) {
        String marker = "<style name=\"" + styleName + "\"";
        int start = xml.indexOf(marker);
        assertTrue("Missing style " + styleName, start >= 0);
        int end = xml.indexOf("</style>", start);
        assertTrue("Unclosed style " + styleName, end >= 0);
        return xml.substring(start, end);
    }

    private static String readFile(String path) throws Exception {
        return new String(
                Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8
        );
    }
}
