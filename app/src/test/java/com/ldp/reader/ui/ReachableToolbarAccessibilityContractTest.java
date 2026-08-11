package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ReachableToolbarAccessibilityContractTest {

    @Test
    public void baseToolbarProvidesLocalizedBackNameAndPlayerOverridesCloseAction()
            throws IOException {
        String base = readFile("src/main/java/com/ldp/reader/ui/base/BaseActivity.kt");
        String player = readFile(
                "src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt"
        );
        String simplified = readFile("src/main/res/values/strings.xml");
        String traditional = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertTrue(base.contains(
                "setNavigationContentDescription(R.string.common_navigate_back)"
        ));
        assertTrue(player.contains(
                "setNavigationContentDescription(R.string.audio_player_close_page)"
        ));
        assertTrue(simplified.contains(
                "<string name=\"common_navigate_back\">返回</string>"
        ));
        assertTrue(traditional.contains(
                "<string name=\"common_navigate_back\">返回</string>"
        ));
        assertTrue(simplified.contains(
                "<string name=\"audio_player_close_page\">关闭播放器页面</string>"
        ));
        assertTrue(traditional.contains(
                "<string name=\"audio_player_close_page\">關閉播放器頁面</string>"
        ));
    }

    @Test
    public void releaseReachableToolbarPagesInheritTheBaseContract()
            throws IOException {
        String[] activities = {
                "ReadActivity",
                "AudioDetailActivity",
                "ComicDetailActivity",
                "ComicReadActivity",
                "LocalComicReadActivity",
                "MediaCatalogActivity"
        };
        for (String activity : activities) {
            String source = readFile(
                    "src/main/java/com/ldp/reader/ui/activity/" + activity + ".kt"
            );
            assertTrue(activity + " must inherit BaseActivity",
                    source.contains(": BaseActivity<"));
        }
    }

    private static String readFile(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) file = new File("app", path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
