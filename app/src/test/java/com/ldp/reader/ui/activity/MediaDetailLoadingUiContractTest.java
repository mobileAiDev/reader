package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class MediaDetailLoadingUiContractTest {
    @Test
    public void mediaDetailsUseIndeterminateLoadingWithoutInventedPercentages() throws IOException {
        String progress = readFile("src/main/java/com/ldp/reader/media/MediaDetailLoadProgress.kt");
        String comicActivity = readSource("ComicDetailActivity.kt");
        String audioActivity = readSource("AudioDetailActivity.kt");
        String comicLayout = readFile("src/main/res/layout/activity_comic_detail.xml");
        String audioLayout = readFile("src/main/res/layout/activity_audio_detail.xml");

        assertTrue(comicLayout.contains("android:indeterminate=\"true\""));
        assertTrue(audioLayout.contains("android:indeterminate=\"true\""));
        assertFalse(comicLayout.contains("comic_detail_loading_percent"));
        assertFalse(audioLayout.contains("audio_detail_loading_percent"));
        assertFalse(progress.contains("val percent: Int"));
        assertFalse(comicActivity.contains("step.percent"));
        assertFalse(audioActivity.contains("step.percent"));
    }

    @Test
    public void mediaDetailsOnlyExposePlainLoadingAndRetryCopy() throws IOException {
        String combined =
                readFile("src/main/java/com/ldp/reader/media/MediaDetailLoadProgress.kt")
                        + readSource("MediaDetailFailureCopy.kt")
                        + readSource("ComicDetailActivity.kt")
                        + readSource("AudioDetailActivity.kt")
                        + readFile("src/main/res/layout/activity_comic_detail.xml")
                        + readFile("src/main/res/layout/activity_audio_detail.xml");

        assertTrue(combined.contains("正在加载作品信息"));
        assertTrue(combined.contains("正在加载目录"));
        assertTrue(combined.contains("暂时无法加载作品信息，点击重试"));
        assertTrue(combined.contains("暂时无法加载目录，点击重试"));
        assertFalse(combined.contains("智能引擎分析中"));
        assertFalse(combined.contains("分析作品信息"));
        assertFalse(combined.contains("整理章节目录"));
        assertFalse(combined.contains("目录解析失败"));
        assertFalse(combined.contains("分析未完成"));
    }

    @Test
    public void audioPlayerUsesPlainFailureCopyClassifiedByCause() throws IOException {
        String activity = readSource("AudioPlayerActivity.kt");
        String copy = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackFailureCopy.kt");

        assertTrue(copy.contains("音频文件无效或格式暂不支持，请重试其他章节或内容"));
        assertTrue(copy.contains("请检查网络后重试"));
        assertTrue(copy.contains("暂时无法播放，请稍后重试"));
        assertTrue(activity.contains("AudioPlaybackFailureCopy.messageForPlayerError(error.errorCode)"));
        assertFalse(activity.contains("音频解析未完成"));
        assertFalse(activity.contains("播放器连接失败"));
        assertFalse(activity.contains("renderPlayerFailure(\"播放失败\")"));
    }

    @Test
    public void addToShelfWaitStateUsesProductLanguageInsteadOfCatalogInternals() throws IOException {
        String comicActivity = readSource("ComicDetailActivity.kt");
        String audioActivity = readSource("AudioDetailActivity.kt");

        assertTrue(comicActivity.contains("作品信息还在加载，请稍后再试"));
        assertTrue(audioActivity.contains("作品信息还在加载，请稍后再试"));
        assertFalse(comicActivity.contains("ToastUtils.show(\"目录未准备好\")"));
        assertFalse(audioActivity.contains("ToastUtils.show(\"目录未准备好\")"));
    }

    @Test
    public void resolvedCatalogCountsDescribeTotalContentInsteadOfUpdateInternals() throws IOException {
        String comicActivity = readSource("ComicDetailActivity.kt");
        String audioActivity = readSource("AudioDetailActivity.kt");

        assertTrue(comicActivity.contains("\"共 ${chapters.size} 话\""));
        assertTrue(audioActivity.contains("\"共 ${episodes.size} 集\""));
        assertFalse(comicActivity.contains("\"已更新 ${chapters.size} 话\""));
        assertFalse(audioActivity.contains("\"已更新 ${episodes.size} 集\""));
    }

    private static String readSource(String fileName) throws IOException {
        return readFile("src/main/java/com/ldp/reader/ui/activity/" + fileName);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
