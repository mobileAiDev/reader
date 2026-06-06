package com.ldp.reader.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

public class SharedPreUtilsStorageContractTest {

    @Test
    public void sharedPreUtilsUsesMmkvOnly() throws IOException {
        String sharedPreUtils = readFile("src/main/java/com/ldp/reader/utils/SharedPreUtils.kt");

        assertTrue(sharedPreUtils.contains("import com.tencent.mmkv.MMKV"));
        assertTrue(sharedPreUtils.contains("MMKV.mmkvWithID(SHARED_NAME)"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeString(key, \"\")"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeInt(key, def)"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeLong(key, def)"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeBool(key, def)"));
        assertTrue(sharedPreUtils.contains("mmkv.encode(key, value)"));

        assertFalse(sharedPreUtils.contains("SharedPreferences"));
        assertFalse(sharedPreUtils.contains("getSharedPreferences"));
        assertFalse(sharedPreUtils.contains("MODE_MULTI_PROCESS"));
        assertFalse(sharedPreUtils.contains(".commit()"));
        assertFalse(sharedPreUtils.contains(".apply()"));
    }

    @Test
    public void appInitializesMmkvBeforeStorageUse() throws IOException {
        String app = readFile("src/main/java/com/ldp/reader/App.kt");

        assertTrue(app.contains("import com.tencent.mmkv.MMKV"));
        assertTrue(app.contains("MMKV.initialize(this)"));
        assertTrue(app.contains("LegacyXmlPrefsCleaner.clearMediaPrefs(this)"));
    }

    @Test
    public void gradleKeepsMmkvDependency() throws IOException {
        String buildGradle = readFile("build.gradle");

        assertTrue(buildGradle.contains("implementation 'com.tencent:mmkv-static:2.4.0'"));
    }

    @Test
    public void mediaStoresDoNotPersistFullObjectsAsSingleJsonFields() throws IOException {
        String mediaShelfStore = readFile("src/main/java/com/ldp/reader/media/MediaShelfStore.kt");
        String mediaSourceRepository = readFile("src/main/java/com/ldp/reader/media/MediaSourceRepository.kt");
        String audioPlaybackStateStore = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackStateStore.kt");
        String legacyXmlPrefsCleaner = readFile("src/main/java/com/ldp/reader/utils/LegacyXmlPrefsCleaner.kt");

        assertTrue(mediaShelfStore.contains("KEY_IDS"));
        assertTrue(mediaShelfStore.contains("KEY_ITEM_PREFIX"));
        assertTrue(mediaShelfStore.contains("MAX_ITEM_JSON_BYTES"));
        assertTrue(mediaShelfStore.contains("reader_media_shelf"));
        assertFalse(mediaShelfStore.contains("TypeToken<List<MediaShelfItem>>"));
        assertFalse(mediaShelfStore.contains("gson.toJson(items"));
        assertFalse(mediaShelfStore.contains("fromJson<List<MediaShelfItem>>"));
        assertFalse(mediaShelfStore.contains("audioPositionMs"));
        assertFalse(mediaShelfStore.contains("audioDurationMs"));
        assertFalse(mediaShelfStore.contains("comicPageIndexByChapter"));
        assertFalse(mediaShelfStore.contains("audioPositionMsByChapter"));
        assertFalse(mediaShelfStore.contains("audioDurationMsByChapter"));

        String audioProgressStore = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackProgressStore.kt");
        String comicProgressStore = readFile("src/main/java/com/ldp/reader/media/ComicReadingProgressStore.kt");
        String audioPlaybackService = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String audioPlayerActivity = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");

        assertTrue(audioProgressStore.contains("reader_audio_progress"));
        assertTrue(audioProgressStore.contains("MMKV.mmkvWithID(STORE_NAME)"));
        assertTrue(comicProgressStore.contains("reader_comic_progress"));
        assertTrue(comicProgressStore.contains("MMKV.mmkvWithID(STORE_NAME)"));
        assertTrue(legacyXmlPrefsCleaner.contains("reader_media_shelf"));
        assertTrue(legacyXmlPrefsCleaner.contains("reader_audio_now_playing"));
        assertTrue(legacyXmlPrefsCleaner.contains("reader_audio_progress"));
        assertFalse(audioPlaybackService.contains("MediaShelfStore.updateAudioProgress"));
        assertFalse(audioPlayerActivity.contains("MediaShelfStore.updateAudioProgress"));
        assertTrue(mediaSourceRepository.contains("registeredChapters.size > 1"));
        assertTrue(mediaSourceRepository.contains("media_chapters_refresh_needed"));

        assertTrue(audioPlaybackStateStore.contains("PersistedAudioNowPlaying"));
        assertTrue(audioPlaybackStateStore.contains("MAX_STATE_JSON_BYTES"));
        assertFalse(audioPlaybackStateStore.contains("gson.toJson(state)"));
        assertFalse(audioPlaybackStateStore.contains("fromJson(json, AudioNowPlaying::class.java)"));
    }

    @Test
    public void mainCodeDoesNotUseSharedPreferences() throws IOException {
        Path root = new File("src/main/java").toPath();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                        if (content.contains("getSharedPreferences") || content.contains("SharedPreferences")) {
                            offenders.add(root.relativize(path).toString());
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }

        assertTrue("Main code must not use direct SharedPreferences: " + offenders, offenders.isEmpty());
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
