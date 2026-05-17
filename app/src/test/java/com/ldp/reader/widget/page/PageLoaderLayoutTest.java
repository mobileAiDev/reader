package com.ldp.reader.widget.page;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PageLoaderLayoutTest {

    @Test
    public void readingContentMarginsKeepStatusBarSafeAndCompactFooter() {
        int displayHeight = 2780;
        int statusBarHeight = 140;
        int contentPadding = 28;

        int topMargin = PageLoader.calculateContentTopMargin(statusBarHeight, contentPadding);
        int bottomMargin = PageLoader.calculateContentBottomMargin(contentPadding);

        assertEquals(168, topMargin);
        assertEquals(28, bottomMargin);
        assertEquals(2584, PageLoader.calculateVisibleContentHeight(displayHeight, topMargin, bottomMargin));
    }

    @Test
    public void prepareDisplayOpensChapterWhenLocalParsingFinishedBeforeViewSizeReady() throws IOException {
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        int prepareDisplay = pageLoader.indexOf("fun prepareDisplay(w: Int, h: Int)");
        int notChapterOpen = pageLoader.indexOf("if (!isChapterOpen)", prepareDisplay);
        int preparedGuard = pageLoader.indexOf("if (isChapterListPrepare)", notChapterOpen);
        int openChapter = pageLoader.indexOf("openChapter()", preparedGuard);
        int returnIndex = pageLoader.indexOf("return", openChapter);

        assertTrue(prepareDisplay > 0);
        assertTrue(notChapterOpen > prepareDisplay);
        assertTrue(preparedGuard > notChapterOpen);
        assertTrue(openChapter > preparedGuard);
        assertTrue(returnIndex > openChapter);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
