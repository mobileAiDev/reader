package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PdfReaderChromeContractTest {

    @Test
    public void pdfPageActionsExposeAtLeastFortyEightDpInteractionBounds() throws IOException {
        String layout = readFile("src/main/res/layout/activity_pdf_read.xml");

        assertMinimumHeightDp(layout, "pdf_read_prev", 48);
        assertMinimumHeightDp(layout, "pdf_read_catalog", 48);
        assertMinimumHeightDp(layout, "pdf_read_next", 48);
    }

    @Test
    public void pdfBackActionUsesTheAppsLocalizedAccessibleName() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/PdfReadActivity.kt");
        String simplifiedStrings = readFile("src/main/res/values/strings.xml");
        String traditionalStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertTrue(activity.contains(
                "toolbar?.setNavigationContentDescription(R.string.pdf_reader_navigate_back)"
        ));
        assertTrue(simplifiedStrings.contains(
                "<string name=\"pdf_reader_navigate_back\">返回</string>"
        ));
        assertTrue(traditionalStrings.contains(
                "<string name=\"pdf_reader_navigate_back\">返回</string>"
        ));
    }

    private static void assertMinimumHeightDp(String layout, String id, int minimumDp) {
        String tag = viewTag(layout, id);
        Matcher height = Pattern.compile("android:layout_height=\"(\\d+)dp\"").matcher(tag);
        assertTrue("Missing fixed dp height for @+id/" + id, height.find());
        int actualDp = Integer.parseInt(height.group(1));
        assertTrue(
                "@+id/" + id + " must be at least " + minimumDp + "dp high, was " + actualDp,
                actualDp >= minimumDp
        );
    }

    private static String viewTag(String layout, String id) {
        Pattern pattern = Pattern.compile(
                "<[^>]+android:id=\"@\\+id/" + Pattern.quote(id) + "\"[^>]*>",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(layout);
        assertTrue("Missing view @+id/" + id, matcher.find());
        return matcher.group();
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
                Files.readAllBytes(new File(relativePath).toPath()),
                StandardCharsets.UTF_8
        );
    }
}
