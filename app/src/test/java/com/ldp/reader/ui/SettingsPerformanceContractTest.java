package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class SettingsPerformanceContractTest {
    @Test
    public void cacheSizeTraversalRunsOffTheMainThreadAndStopsWithTheActivity() throws IOException {
        String settings = readFile(
            "src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt"
        );

        assertTrue(settings.contains("withContext(Dispatchers.IO)"));
        assertTrue(settings.contains("cacheSizeScope.cancel()"));

        int initWidget = settings.indexOf("override fun initWidget()");
        int initClick = settings.indexOf("override fun initClick()", initWidget);
        String initWidgetBody = settings.substring(initWidget, initClick);
        assertFalse(initWidgetBody.contains("CacheUtils.getAppCacheSizeLabel"));

        int refresh = settings.indexOf("private fun refreshCacheSize()");
        int clearCache = settings.indexOf("private fun clearCache()", refresh);
        String refreshBody = settings.substring(refresh, clearCache);
        assertTrue(
            refreshBody.indexOf("withContext(Dispatchers.IO)") <
                refreshBody.indexOf("CacheUtils.getAppCacheSizeLabel")
        );
    }

    private static String readFile(String path) throws IOException {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
