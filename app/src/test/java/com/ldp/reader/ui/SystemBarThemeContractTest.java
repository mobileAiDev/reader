package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class SystemBarThemeContractTest {

    @Test
    public void lightPagesContinueTheirSurfaceThroughGestureNavigation() throws Exception {
        String api21AppTheme = styleBody(
                readFile("src/main/res/values-v21/styles.xml"),
                "AppTheme"
        );
        String api26AppTheme = styleBody(
                readFile("src/main/res/values-v26/styles.xml"),
                "AppTheme"
        );

        // API 21-25 cannot draw dark navigation icons, so retain a dark, readable fallback.
        assertTrue(api21AppTheme.contains(
                "<item name=\"android:navigationBarColor\">@color/media_reader_dark</item>"
        ));

        // Gesture-navigation devices use the page surface and a contrasting dark handle.
        assertTrue(api26AppTheme.contains(
                "<item name=\"android:navigationBarColor\">@color/home_surface</item>"
        ));
        assertTrue(api26AppTheme.contains(
                "<item name=\"android:windowLightNavigationBar\">true</item>"
        ));
    }

    @Test
    public void bottomDialogsUseTheSameSingleSystemDecorInsetOwner() throws Exception {
        String api26Theme = readFile("src/main/res/values-v26/styles.xml");
        String commonDialog = styleBody(api26Theme, "CommonDialog");
        String removeSheet = styleBody(
                api26Theme,
                "ReaderBookshelfRemoveBottomSheetDialog"
        );

        assertTrue(commonDialog.contains(
                "<item name=\"android:navigationBarColor\">@color/home_surface</item>"
        ));
        assertTrue(commonDialog.contains(
                "<item name=\"android:windowLightNavigationBar\">true</item>"
        ));
        assertTrue(removeSheet.contains(
                "<item name=\"android:navigationBarColor\">@color/home_surface</item>"
        ));
        assertTrue(removeSheet.contains(
                "<item name=\"android:windowLightNavigationBar\">true</item>"
        ));
    }

    @Test
    public void darkReaderSettingsContinueTheirOwnPanelSurface() throws Exception {
        String readSettings = styleBody(
                readFile("src/main/res/values-v26/styles.xml"),
                "ReadSettingDialog"
        );

        assertTrue(readSettings.contains(
                "<item name=\"android:navigationBarColor\">@color/nb.read.menu.bg</item>"
        ));
        assertTrue(readSettings.contains(
                "<item name=\"android:windowLightNavigationBar\">false</item>"
        ));
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
