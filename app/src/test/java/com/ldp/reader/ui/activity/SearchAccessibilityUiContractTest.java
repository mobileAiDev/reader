package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchAccessibilityUiContractTest {

    @Test
    public void navigationAndClearActionsHaveNamedFortyEightDpTouchTargets() throws IOException {
        String layout = readFile("src/main/res/layout/activity_search.xml");
        String back = viewTag(layout, "search_iv_back");
        String clear = viewTag(layout, "search_iv_delete");
        String inputContainer = viewTag(layout, "search_input_container");
        String inputVisual = viewTag(layout, "search_input_visual");
        String input = viewTag(layout, "search_et_input");
        String strings = readFile("src/main/res/values/strings.xml");

        assertTrue(back.contains("android:contentDescription=\"@string/search_navigate_back\""));
        assertTrue(back.contains("android:layout_width=\"48dp\""));
        assertTrue(back.contains("android:layout_height=\"48dp\""));
        assertTrue(clear.contains("android:contentDescription=\"@string/search_clear_input\""));
        assertTrue(clear.contains("android:layout_width=\"48dp\""));
        assertTrue(clear.contains("android:layout_height=\"48dp\""));
        assertTrue(inputContainer.startsWith("<FrameLayout"));
        assertTrue(inputContainer.contains("android:layout_height=\"48dp\""));
        assertTrue(inputVisual.contains("android:layout_height=\"36dp\""));
        assertTrue(inputVisual.contains("android:background=\"@drawable/bg_search_input_pill\""));
        assertTrue(input.contains("android:paddingRight=\"48dp\""));
        assertTrue(clear.contains("android:layout_gravity=\"end|center_vertical\""));
        assertTrue(strings.contains("<string name=\"search_navigate_back\">返回上一页</string>"));
        assertTrue(strings.contains("<string name=\"search_clear_input\">清除搜索内容</string>"));
    }

    @Test
    public void searchTabsKeepTheirCompactVisualsInsideFortyEightDpTouchTargets() throws IOException {
        String layout = readFile("src/main/res/layout/activity_search.xml");
        String tabContainer = viewTag(layout, "search_type_tabs");

        assertTrue(tabContainer.contains("android:layout_height=\"48dp\""));
        assertFortyEightDpTab(layout, "search_tab_novel");
        assertFortyEightDpTab(layout, "search_tab_comic");
        assertFortyEightDpTab(layout, "search_tab_audio");

        assertCompactTabBackground(readFile("src/main/res/drawable/bg_search_tab_normal.xml"));
        assertCompactTabBackground(readFile("src/main/res/drawable/bg_search_tab_selected.xml"));
    }

    @Test
    public void searchSubmissionAndTextInputOwnTheirFortyEightDpInteractionBounds() throws IOException {
        String layout = readFile("src/main/res/layout/activity_search.xml");
        String input = viewTag(layout, "search_et_input");
        String inputVisual = viewTag(layout, "search_input_visual");
        String submit = viewTag(layout, "search_iv_search");
        String clear = viewTag(layout, "search_iv_delete");

        assertTrue(input.contains("android:layout_height=\"48dp\""));
        assertTrue(input.contains("android:background=\"@color/transparent\""));
        assertTrue(input.contains("android:gravity=\"center_vertical\""));
        assertTrue(input.contains("android:paddingRight=\"48dp\""));
        assertTrue(inputVisual.contains("android:layout_height=\"36dp\""));
        assertTrue(submit.contains("android:layout_width=\"48dp\""));
        assertTrue(submit.contains("android:layout_height=\"48dp\""));
        assertTrue(clear.contains("android:layout_gravity=\"end|center_vertical\""));
    }

    @Test
    public void activityPublishesTheCurrentTabAsSelectedNotOnlyAsAColor() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/SearchActivity.kt");

        assertTrue(activity.contains("SearchFlowPolicy.tabStates(activeSearchKind)"));
        assertTrue(activity.contains("tab.isSelected = state.selected"));
    }

    private static void assertFortyEightDpTab(String layout, String id) {
        String tab = viewTag(layout, id);
        assertTrue(tab.contains("android:layout_height=\"48dp\""));
    }

    private static void assertCompactTabBackground(String drawable) {
        assertTrue(drawable.contains("<inset"));
        assertTrue(drawable.contains("android:insetTop=\"8dp\""));
        assertTrue(drawable.contains("android:insetBottom=\"8dp\""));
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

    private static String readFile(String path) throws IOException {
        File source = new File(path);
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }
}
