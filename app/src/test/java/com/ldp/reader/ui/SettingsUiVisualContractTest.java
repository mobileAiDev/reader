package com.ldp.reader.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.R;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SettingsUiVisualContractTest {
    @Test
    public void settingsKeepsExistingInteractiveBindingIds() {
        assertNotEquals(0, R.layout.activity_settings);
        assertNotEquals(0, R.id.settings_back);
        assertNotEquals(0, R.id.settings_title);
        assertNotEquals(0, R.id.settings_account_entry);
        assertNotEquals(0, R.id.settings_sync_bookshelf);
        assertNotEquals(0, R.id.settings_local_import);
        assertNotEquals(0, R.id.settings_clean_content);
        assertNotEquals(0, R.id.settings_clean_content_switch);
        assertNotEquals(0, R.id.settings_clean_intro);
        assertNotEquals(0, R.id.settings_clean_intro_switch);
        assertNotEquals(0, R.id.settings_smart_wrong_chapter);
        assertNotEquals(0, R.id.settings_smart_wrong_chapter_switch);
        assertNotEquals(0, R.id.settings_clear_cache);
        assertNotEquals(0, R.id.settings_cache_summary);
        assertNotEquals(0, R.id.settings_about_entry);
    }

    @Test
    public void settingsUsesMineStyleCardsAndClearHierarchy() throws IOException {
        String layout = readFile("src/main/res/layout/activity_settings.xml");

        assertTrue(layout.contains("android:background=\"@color/mine_bg\""));
        assertTrue(layout.contains("android:text=\"@string/settings_section_account\""));
        assertTrue(layout.contains("android:text=\"@string/settings_section_content\""));
        assertTrue(layout.contains("android:text=\"@string/settings_section_storage\""));
        assertEquals(3, countOccurrences(layout, "android:background=\"@drawable/bg_settings_card\""));
        assertTrue(layout.contains("android:text=\"@string/settings_account_summary\""));
        assertTrue(layout.contains("android:text=\"@string/settings_local_import_summary\""));
        assertTrue(layout.contains("android:text=\"@string/settings_clear_cache_summary\""));
        assertFalse(layout.contains("android:background=\"@drawable/bg_settings_section_gap\""));
    }

    @Test
    public void settingsSwitchesUseReaderBlueInsteadOfPlatformPink() throws IOException {
        String layout = readFile("src/main/res/layout/activity_settings.xml");
        String track = readFile("src/main/res/color/settings_switch_track.xml");

        assertEquals(3, countOccurrences(layout, "app:thumbTint=\"@color/settings_switch_thumb\""));
        assertEquals(3, countOccurrences(layout, "app:trackTint=\"@color/settings_switch_track\""));
        assertTrue(track.contains("@color/home_primary"));
        assertFalse(track.contains("#FF4081"));
        assertFalse(layout.contains("#FF4081"));
    }

    @Test
    public void settingsSystemBarsContinueTheSoftPageSurface() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt");

        assertTrue(activity.contains("ContextCompat.getColor(this, R.color.mine_bg)"));
        assertTrue(activity.contains("window.statusBarColor = background"));
        assertTrue(activity.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O"));
        assertTrue(activity.contains("R.color.media_reader_dark"));
        assertTrue(activity.contains("WindowCompat.getInsetsController(window, window.decorView)"));
        assertTrue(activity.contains("isAppearanceLightStatusBars = true"));
        assertTrue(activity.contains("isAppearanceLightNavigationBars = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O"));
        assertFalse(activity.contains("SYSTEM_UI_FLAG_LIGHT_STATUS_BAR"));
    }

    @Test
    public void settingsVisibleTitlesComeFromLocalizedResources() throws IOException {
        String layout = readFile("src/main/res/layout/activity_settings.xml");

        assertTrue(layout.contains("android:text=\"@string/settings_title\""));
        assertTrue(layout.contains("android:text=\"@string/settings_account_title\""));
        assertTrue(layout.contains("android:text=\"@string/settings_sync_title\""));
        assertTrue(layout.contains("android:text=\"@string/settings_local_import_title\""));
        assertTrue(layout.contains("android:text=\"@string/settings_clear_cache_title\""));
        assertTrue(layout.contains("android:text=\"@string/settings_about_title\""));
    }

    @Test
    public void settingsUsesReaderFacingSyncAndChapterCheckLanguage() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt");
        String strings = readFile("src/main/res/values/strings.xml");

        assertTrue(activity.contains("ToastUtils.show(\"正在同步书架…\")"));
        assertTrue(strings.contains(
                "<string name=\"settings_smart_wrong_chapter\">自动检查异常章节</string>"
        ));
        assertTrue(strings.contains(
                "<string name=\"settings_smart_wrong_chapter_summary\">自动标记可能有问题的章节</string>"
        ));
        assertFalse(activity.contains("已发起书架同步"));
        assertFalse(strings.contains("智能分析错章"));
    }

    @Test
    public void contentSettingsUseReaderLanguageInSimplifiedAndTraditionalChinese() throws IOException {
        String simplified = readFile("src/main/res/values/strings.xml");
        String traditional = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertTrue(simplified.contains("<string name=\"settings_section_content\">阅读内容</string>"));
        assertTrue(simplified.contains("<string name=\"settings_clean_content\">过滤正文杂项</string>"));
        assertTrue(simplified.contains("<string name=\"settings_clean_intro\">清理作品简介</string>"));
        assertTrue(traditional.contains("<string name=\"settings_section_content\">閱讀內容</string>"));
        assertTrue(traditional.contains("<string name=\"settings_clean_content\">過濾正文雜項</string>"));
        assertTrue(traditional.contains("<string name=\"settings_clean_intro\">清理作品簡介</string>"));
    }

    private static String readFile(String path) throws IOException {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }

    private static int countOccurrences(String source, String pattern) {
        int count = 0;
        int index = source.indexOf(pattern);
        while (index >= 0) {
            count++;
            index = source.indexOf(pattern, index + pattern.length());
        }
        return count;
    }
}
