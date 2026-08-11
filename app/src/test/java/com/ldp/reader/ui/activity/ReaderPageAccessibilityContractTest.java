package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class ReaderPageAccessibilityContractTest {

    @Test
    public void canvasReaderPublishesNavigableVirtualTextNodes() throws IOException {
        String pageView = readFile(
                "src/main/java/com/ldp/reader/widget/page/PageView.kt"
        );
        String pageLoader = readFile(
                "src/main/java/com/ldp/reader/widget/page/PageLoader.kt"
        );

        assertTrue(pageView.contains("ExploreByTouchHelper"));
        assertTrue(pageView.contains("ViewCompat.setAccessibilityDelegate(this"));
        assertTrue(pageView.contains("override fun onPopulateNodeForHost"));
        assertTrue(pageView.contains("node.isClickable = false"));
        assertTrue(pageView.contains("node.isFocusable = false"));
        assertTrue(pageView.contains("override fun dispatchHoverEvent"));
        assertTrue(pageView.contains("override fun dispatchKeyEvent"));
        assertTrue(pageView.contains("accessibilityHelper.invalidateRoot()"));
        assertTrue(pageLoader.contains("ReaderPageSemantics.fromPage("));
        assertTrue(pageLoader.contains("publishAccessibilitySnapshot("));
        assertTrue(pageLoader.contains("private fun publishCurrentAccessibilitySnapshot()"));
        assertTrue(pageLoader.contains(
                "mPageView?.publishAccessibilitySnapshot(currentAccessibilitySnapshot())"
        ));
        String pageCancel = pageLoader.substring(
                pageLoader.indexOf("fun pageCancel()"),
                pageLoader.indexOf("private fun cancelPendingAsyncChapterTransition")
        );
        assertTrue(pageCancel.contains("publishCurrentAccessibilitySnapshot()"));
        assertFalse(pageView.contains("contentDescription = snapshot.nodes.joinToString"));
    }

    @Test
    public void bookDetailActionsUseNamedFortyEightDpAccessibilityTargets() throws IOException {
        String layout = readFile("src/main/res/layout/activity_book_detail.xml");
        String activity = readFile(
                "src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt"
        );
        String simplified = readFile("src/main/res/values/strings.xml");
        String traditional = readFile("src/main/res/values-zh-rTW/strings.xml");
        String back = openingTagWithId(layout, "book_detail_nav_back");
        String shelf = openingTagWithId(layout, "book_list_ll_chase");
        String animation = openingTagWithId(layout, "book_list_av_chase");
        String shelfLabel = openingTagWithId(layout, "book_list_tv_chase");
        String read = openingTagWithId(layout, "book_detail_tv_read");

        assertTrue(back.contains("android:layout_width=\"48dp\""));
        assertTrue(back.contains("android:layout_height=\"48dp\""));
        assertTrue(back.contains(
                "android:contentDescription=\"@string/book_detail_navigate_back\""
        ));
        assertTrue(shelf.contains("android:layout_height=\"48dp\""));
        assertTrue(shelf.contains("android:clickable=\"true\""));
        assertTrue(shelf.contains(
                "android:contentDescription=\"@string/nb.book_detail.chase_update\""
        ));
        assertTrue(shelf.contains("android:focusable=\"true\""));
        assertTrue(animation.contains("android:importantForAccessibility=\"no\""));
        assertTrue(shelfLabel.contains("android:importantForAccessibility=\"no\""));
        assertTrue(read.contains("android:layout_height=\"48dp\""));
        assertTrue(simplified.contains(
                "<string name=\"book_detail_navigate_back\">返回上一页</string>"
        ));
        assertTrue(traditional.contains(
                "<string name=\"book_detail_navigate_back\">返回上一頁</string>"
        ));
        assertTrue(activity.contains(
                "bookListLlChase.contentDescription = bookListTvChase.text"
        ));
        assertFalse(activity.contains("bookListAvChase.setOnClickListener"));
    }

    private static String openingTagWithId(String xml, String id) {
        Pattern pattern = Pattern.compile(
                "<[^>]+android:id=\"@\\+id/" + Pattern.quote(id) + "\"[^>]*>",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(xml);
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
