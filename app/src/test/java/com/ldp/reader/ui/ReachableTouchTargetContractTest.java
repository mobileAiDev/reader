package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReachableTouchTargetContractTest {

    @Test
    public void bookshelfTopActionsOwnFortyEightDpTouchTargets() throws IOException {
        String layout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        String fragment = readFile(
                "src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt"
        );
        String readingChipBackground = readFile(
                "src/main/res/drawable/bg_home_reading_chip.xml"
        );

        assertHeight(layout, "home_shelf_summary", "48dp");
        assertHeight(layout, "home_reading_time_chip", "48dp");
        assertHeight(layout, "home_bookshelf_tools", "48dp");
        assertHeight(layout, "home_bookshelf_filter", "48dp");
        assertHeight(layout, "home_bookshelf_edit", "48dp");
        assertTrue(fragment.contains("homeReadingTimeChip?.setOnClickListener"));
        assertTrue(fragment.contains("homeBookshelfFilter?.setOnClickListener"));
        assertTrue(fragment.contains("homeBookshelfEdit?.setOnClickListener"));
        assertTrue(readingChipBackground.contains("<inset"));
        assertTrue(readingChipBackground.contains("android:insetTop=\"8dp\""));
        assertTrue(readingChipBackground.contains("android:insetBottom=\"8dp\""));
    }

    @Test
    public void bookshelfAudioMiniPlayerActionsOwnNamedFortyEightDpTouchTargets()
            throws IOException {
        String layout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        String fragment = readFile(
                "src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt"
        );
        String strings = readFile("src/main/res/values/strings.xml");
        String traditionalStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertSquareTarget(layout, "home_bookshelf_audio_play", "48dp");
        assertSquareTarget(layout, "home_bookshelf_audio_close", "48dp");
        assertTrue(viewTag(layout, "home_bookshelf_audio_play")
                .contains("android:contentDescription=\"@string/bookshelf_audio_play_action\""));
        assertTrue(viewTag(layout, "home_bookshelf_audio_close")
                .contains("android:contentDescription=\"@string/bookshelf_audio_close_action\""));
        assertTrue(strings.contains(
                "<string name=\"bookshelf_audio_play_action\">播放</string>"
        ));
        assertTrue(strings.contains(
                "<string name=\"bookshelf_audio_close_action\">关闭</string>"
        ));
        assertTrue(traditionalStrings.contains(
                "<string name=\"bookshelf_audio_play_action\">播放</string>"
        ));
        assertTrue(traditionalStrings.contains(
                "<string name=\"bookshelf_audio_close_action\">關閉</string>"
        ));
        assertTrue(fragment.contains("homeBookshelfAudioPlay?.setOnClickListener"));
        assertTrue(fragment.contains("homeBookshelfAudioClose?.setOnClickListener"));
    }

    @Test
    public void bookshelfFilterActionsOwnFortyEightDpTouchTargets() throws IOException {
        String sheetLayout = readFile("src/main/res/layout/sheet_bookshelf_filter.xml");
        String bookshelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        String sheet = readFile("src/main/java/com/ldp/reader/ui/widget/BookshelfFilterSheet.kt");
        String fragment = readFile(
                "src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt"
        );

        String[] options = {
                "bookshelf_filter_all",
                "bookshelf_filter_status_3_days",
                "bookshelf_filter_status_7_days",
                "bookshelf_filter_progress_unread",
                "bookshelf_filter_progress_reading",
                "bookshelf_filter_progress_finished",
                "bookshelf_filter_local"
        };
        for (String option : options) {
            assertHeight(sheetLayout, option, "48dp");
        }
        assertHeight(bookshelfLayout, "home_bookshelf_filter_empty_reset", "48dp");
        assertTrue(sheet.contains("binding.bookshelfFilterAll to FilterKey.ALL"));
        assertTrue(sheet.contains("binding.bookshelfFilterLocal to FilterKey.LOCAL"));
        assertTrue(sheet.contains("chip.setOnClickListener { select(key) }"));
        assertTrue(fragment.contains("homeBookshelfFilterEmptyReset?.setOnClickListener"));
    }

    @Test
    public void mineLoginEntryOwnsItsFortyEightDpTouchTarget() throws IOException {
        String layout = readFile("src/main/res/layout/fragment_mine.xml");
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/MineFragment.kt");

        assertHeight(layout, "mine_login_entry", "48dp");
        assertTrue(viewTag(layout, "mine_login_entry").contains("android:insetTop=\"6dp\""));
        assertTrue(viewTag(layout, "mine_profile_avatar").contains("android:layout_height=\"56dp\""));
        assertTrue(fragment.contains("mineLoginEntry?.setOnClickListener"));
        assertTrue(fragment.contains("mineProfileCard?.setOnClickListener"));
    }

    @Test
    public void localImportSelectAllRowOwnsFortyEightDpTouchTarget() throws IOException {
        String layout = readFile("src/main/res/layout/activity_file_system.xml");
        String activity = readFile(
                "src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt"
        );
        String bottomBar = viewTag(layout, "file_system_bottom_bar");

        assertHeight(layout, "file_system_cb_selected_all", "48dp");
        assertTrue(nearestLinearLayoutParentTag(layout, "file_system_cb_selected_all")
                .contains("android:layout_height=\"48dp\""));
        assertTrue(bottomBar.contains("android:layout_height=\"wrap_content\""));
        assertTrue(bottomBar.contains("android:paddingBottom=\"12dp\""));
        assertTrue(activity.contains("fileSystemCbSelectedAll.setOnClickListener"));
    }

    @Test
    public void localImportDirectoryUpActionOwnsNamedFortyEightDpTouchTarget()
            throws IOException {
        String layout = readFile("src/main/res/layout/fragment_file_category.xml");
        String fragment = readFile(
                "src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt"
        );
        String strings = readFile("src/main/res/values/strings.xml");
        String traditionalStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertHeight(layout, "file_category_tv_back_last", "48dp");
        assertTrue(nearestLinearLayoutParentTag(layout, "file_category_tv_back_last")
                .contains("android:layout_height=\"48dp\""));
        assertTrue(viewTag(layout, "file_category_tv_back_last")
                .contains("android:text=\"@string/local_import_up_one_level\""));
        assertTrue(strings.contains(
                "<string name=\"local_import_up_one_level\">上一级</string>"
        ));
        assertTrue(traditionalStrings.contains(
                "<string name=\"local_import_up_one_level\">上一層</string>"
        ));
        assertTrue(fragment.contains("mTvBackLast.setOnClickListener { navigateBack() }"));
    }

    @Test
    public void documentImportButtonOwnsFortyEightDpTouchTarget() throws IOException {
        String layout = readFile("src/main/res/layout/fragment_document_import.xml");
        String fragment = readFile(
                "src/main/java/com/ldp/reader/ui/fragment/DocumentImportFragment.kt"
        );
        String action = viewTag(layout, "document_import_open");

        assertHeight(layout, "document_import_open", "48dp");
        // The Ink button style: a full 48dp touch target with no inset.
        assertTrue(action.contains("style=\"@style/Widget.Ink.Button\""));
        assertTrue(fragment.contains("documentImportOpen?.setOnClickListener"));
    }

    @Test
    public void sourceImportInputsAndActionsOwnNamedFortyEightDpTouchTargets()
            throws IOException {
        String layout = readFile("src/main/res/layout/activity_source_import.xml");
        String activity = readFile(
                "src/main/java/com/ldp/reader/ui/activity/SourceImportActivity.kt"
        );
        String strings = readFile("src/main/res/values/strings.xml");
        String traditionalStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertHeight(layout, "source_import_url_input", "48dp");
        assertHeight(layout, "source_import_url_button", "48dp");
        assertHeight(layout, "source_import_file_button", "48dp");
        assertHeight(layout, "source_import_json_button", "48dp");
        assertTrue(viewTag(layout, "source_import_url_input")
                .contains("android:hint=\"@string/source_import_address_hint\""));
        assertTrue(viewTag(layout, "source_import_url_input")
                .contains("android:background=\"@drawable/bg_ink_input\""));
        assertTrue(viewTag(layout, "source_import_url_button")
                .contains("style=\"@style/Widget.Ink.Button\""));
        assertTrue(viewTag(layout, "source_import_file_button")
                .contains("style=\"@style/Widget.Ink.Button.Tonal\""));
        assertTrue(viewTag(layout, "source_import_json_button")
                .contains("style=\"@style/Widget.Ink.Button\""));
        assertTrue(strings.contains(
                "<string name=\"source_import_address_hint\">请输入书源地址</string>"
        ));
        assertTrue(strings.contains(
                "<string name=\"source_import_navigate_back\">返回</string>"
        ));
        assertTrue(traditionalStrings.contains(
                "<string name=\"source_import_address_hint\">請輸入書源地址</string>"
        ));
        assertTrue(traditionalStrings.contains(
                "<string name=\"source_import_navigate_back\">返回</string>"
        ));
        assertTrue(activity.contains(
                "setNavigationContentDescription(R.string.source_import_navigate_back)"
        ));
        assertTrue(activity.contains("sourceImportUrlButton.setOnClickListener"));
        assertTrue(activity.contains("sourceImportFileButton.setOnClickListener"));
        assertTrue(activity.contains("sourceImportJsonButton.setOnClickListener"));
    }

    @Test
    public void searchHotRefreshOwnsFortyEightDpTouchTarget() throws IOException {
        String layout = readFile("src/main/res/layout/activity_search.xml");
        String activity = readFile(
                "src/main/java/com/ldp/reader/ui/activity/SearchActivity.kt"
        );
        String refresh = viewTag(layout, "search_book_tv_refresh_hot");

        assertTrue(refresh.contains("android:layout_height=\"48dp\""));
        assertTrue(nearestLinearLayoutParentTag(layout, "search_book_tv_refresh_hot")
                .contains("android:layout_height=\"48dp\""));
        assertTrue(refresh.contains("android:text=\"换一批\""));
        assertTrue(activity.contains("mTvRefreshHot!!.setOnClickListener"));
    }

    @Test
    public void searchSuggestionsAndHotTermsOwnNamedFortyEightDpTouchTargets()
            throws IOException {
        String keywordLayout = readFile("src/main/res/layout/item_keyword.xml");
        String searchLayout = readFile("src/main/res/layout/activity_search.xml");
        String hotTermLayout = readFile("src/main/res/layout/item_search_hot_term.xml");
        String baseAdapter = readFile(
                "src/main/java/com/ldp/reader/ui/base/adapter/BaseListAdapter.kt"
        );
        String activity = readFile(
                "src/main/java/com/ldp/reader/ui/activity/SearchActivity.kt"
        );

        assertTrue(keywordLayout.contains("android:layout_height=\"48dp\""));
        assertTrue(baseAdapter.contains("holder.itemView.setOnClickListener"));
        assertTrue(activity.contains("mKeyWordAdapter!!.setOnItemClickListener"));

        assertTrue(searchLayout.contains(
                "<com.google.android.material.chip.ChipGroup"
        ));
        assertTrue(viewTag(searchLayout, "search_tg_hot")
                .contains("app:chipSpacingHorizontal=\"10dp\""));
        assertTrue(hotTermLayout.contains("android:layout_height=\"48dp\""));
        assertTrue(hotTermLayout.contains("android:minWidth=\"48dp\""));
        assertTrue(hotTermLayout.contains("style=\"@style/Widget.Ink.Chip.Action\""));
        assertTrue(hotTermLayout.contains(
                "app:chipMinTouchTargetSize=\"48dp\""
        ));
        assertTrue(hotTermLayout.contains(
                "app:ensureMinTouchTargetSize=\"true\""
        ));
        assertTrue(activity.contains("renderHotTerms(tags)"));
        assertTrue(activity.contains("chip.contentDescription = tag"));
        assertTrue(activity.contains("chip.setOnClickListener { submitHotTerm(tag) }"));
    }

    private static void assertHeight(String layout, String id, String height) {
        assertTrue(
                "Expected @+id/" + id + " to have height " + height,
                viewTag(layout, id).contains("android:layout_height=\"" + height + "\"")
        );
    }

    private static void assertSquareTarget(String layout, String id, String size) {
        String tag = viewTag(layout, id);
        assertTrue(
                "Expected @+id/" + id + " to have width " + size,
                tag.contains("android:layout_width=\"" + size + "\"")
        );
        assertTrue(
                "Expected @+id/" + id + " to have height " + size,
                tag.contains("android:layout_height=\"" + size + "\"")
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

    private static String nearestLinearLayoutParentTag(String layout, String childId) {
        return nearestParentTag(layout, childId, "LinearLayout");
    }

    private static String nearestParentTag(String layout, String childId, String parentType) {
        int childOffset = layout.indexOf("android:id=\"@+id/" + childId + "\"");
        assertTrue("Missing view @+id/" + childId, childOffset >= 0);
        int parentStart = layout.lastIndexOf("<" + parentType, childOffset);
        int parentEnd = layout.indexOf('>', parentStart);
        assertTrue("Missing " + parentType + " parent for @+id/" + childId, parentStart >= 0);
        assertTrue("Malformed " + parentType + " parent for @+id/" + childId,
                parentEnd < childOffset);
        return layout.substring(parentStart, parentEnd + 1);
    }

    private static String readFile(String path) throws IOException {
        File source = new File(path);
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }
}
