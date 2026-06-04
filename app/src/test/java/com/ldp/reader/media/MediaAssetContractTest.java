package com.ldp.reader.media;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.ldp.reader.media.MediaLegadoRuleSet;
import com.ldp.reader.media.MediaSourceDefinition;
import com.ldp.reader.media.MediaSourceType;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class MediaAssetContractTest {
    @Test
    public void bundledSourcesContainIsolatedMediaTypes() throws Exception {
        String novelSources = readFile("src/main/assets/source-engine/book-sources.json");
        String mediaSources = readFile("src/main/assets/media-source-engine/media-sources.json");
        String seed = readFile("src/main/assets/media-source-quality-seed-v1.tsv");

        assertTrue(novelSources.contains("\"bookSourceType\": 0"));
        assertFalse(novelSources.contains("\"bookSourceType\": 1"));
        assertFalse(novelSources.contains("\"bookSourceType\": 2"));
        assertTrue(mediaSources.contains("\"bookSourceType\": 1"));
        assertTrue(mediaSources.contains("\"bookSourceType\": 2"));
        assertFalse(mediaSources.contains("\"bookSourceType\": 0"));
        assertTrue(seed.contains("kind\tsourceUrl\tsourceName\ttier\tscore\tnote"));
        assertTrue(seed.contains("comic\t"));
        assertTrue(seed.contains("audio\t"));
        assertTrue(seed.contains("builtin-media-source-legado:media-sources.json"));
        assertFalse(seed.contains("bookSources.json"));
        assertTrue(mediaSources.contains("禁漫") || mediaSources.contains("污漫天堂"));
        assertTrue(mediaSources.contains("\"bookSourceUrl\": \"http://tingshu.kuwo.cn\""));
        assertTrue(seed.contains("http://tingshu.kuwo.cn"));
    }

    @Test
    public void audioCompatibilityDoesNotBlockByPlatformName() {
        Map<String, String> searchRules = new HashMap<>();
        searchRules.put("bookList", ".book");
        searchRules.put("name", ".title");
        searchRules.put("bookUrl", "a@href");
        Map<String, String> tocRules = new HashMap<>();
        tocRules.put("chapterList", ".chapter");
        tocRules.put("chapterName", "a");
        tocRules.put("chapterUrl", "a@href");

        MediaSourceDefinition kuwo = new MediaSourceDefinition(
                "酷我听书",
                "http://tingshu.kuwo.cn",
                MediaSourceType.AUDIO,
                null,
                null,
                true,
                java.util.Collections.emptyMap(),
                "/search?q={{key}}",
                new MediaLegadoRuleSet("ruleSearch", searchRules),
                new MediaLegadoRuleSet("ruleBookInfo", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleToc", tocRules),
                new MediaLegadoRuleSet("ruleContent", java.util.Collections.emptyMap()),
                java.util.Collections.emptyList()
        );

        assertTrue(MediaSourceCompatibility.INSTANCE.isCompatible(kuwo));
    }

    @Test
    public void mediaCompatibilityOnlyFiltersDisabledSources() {
        MediaSourceDefinition missingRules = new MediaSourceDefinition(
                "69听书网",
                "https://example.invalid",
                MediaSourceType.AUDIO,
                null,
                null,
                true,
                java.util.Collections.emptyMap(),
                "",
                new MediaLegadoRuleSet("ruleSearch", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleBookInfo", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleToc", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleContent", java.util.Collections.emptyMap()),
                java.util.Collections.emptyList()
        );
        MediaSourceDefinition disabled = new MediaSourceDefinition(
                "69听书网",
                "https://example.invalid",
                MediaSourceType.AUDIO,
                null,
                null,
                false,
                java.util.Collections.emptyMap(),
                "",
                new MediaLegadoRuleSet("ruleSearch", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleBookInfo", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleToc", java.util.Collections.emptyMap()),
                new MediaLegadoRuleSet("ruleContent", java.util.Collections.emptyMap()),
                java.util.Collections.emptyList()
        );

        assertTrue(MediaSourceCompatibility.INSTANCE.isCompatible(missingRules));
        assertFalse(MediaSourceCompatibility.INSTANCE.isCompatible(disabled));
    }

    @Test
    public void mediaRepositoryUsesIndependentMediaRuntime() throws Exception {
        String repository = readFile("src/main/java/com/ldp/reader/media/MediaSourceRepository.kt");
        String runtime = readFile("src/main/java/com/ldp/reader/media/MediaSourceRuntime.kt");
        String mediaImportStore = readFile("src/main/java/com/ldp/reader/media/ImportedMediaSourceStore.kt");
        String novelImportStore = readFile("src/main/java/com/ldp/reader/source/ImportedSourceStore.kt");

        assertTrue(repository.contains("MediaSourceRuntime.compatibleSourcesForType"));
        assertTrue(repository.contains("MediaOkHttpFetcher"));
        assertFalse(repository.contains("SourceEngineRuntime.compatibleSourcesForType"));
        assertFalse(repository.contains("OkHttpSourceEngineFetcher"));
        assertTrue(runtime.contains("ImportedMediaSourceStore.loadReport()"));
        assertFalse(runtime.contains("ImportedSourceStore"));
        assertTrue(mediaImportStore.contains("user-media-sources.json"));
        assertTrue(novelImportStore.contains("user-novel-sources.json"));
        assertFalse(novelImportStore.contains("user-book-sources.json"));
    }

    private static String readFile(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(new File(relativePath).toPath()),
                StandardCharsets.UTF_8
        );
    }
}
