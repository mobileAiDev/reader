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

    @Test
    public void clearCacheRequiresExplicitConfirmationBeforeDeletion() throws IOException {
        String settings = readFile(
            "src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt"
        );

        int clearCacheClick = settings.indexOf("binding.settingsClearCache.setOnClickListener");
        int nextClick = settings.indexOf(
            "binding.settingsCleanContent.setOnClickListener",
            clearCacheClick
        );
        String clearCacheClickBody = settings.substring(clearCacheClick, nextClick);
        assertTrue(clearCacheClickBody.contains("showClearCacheConfirmation()"));
        assertFalse(clearCacheClickBody.contains("clearCache()"));

        int confirmation = settings.indexOf("private fun showClearCacheConfirmation()");
        int deletion = settings.indexOf("private fun clearCache()", confirmation);
        String confirmationBody = settings.substring(confirmation, deletion);
        String dialogs = readFile("src/main/java/com/ldp/reader/ui/theme/InkDialogs.kt");
        assertTrue(confirmationBody.contains("InkDialogs.confirm("));
        assertTrue(confirmationBody.contains("confirmText = \"清除\""));
        assertTrue(confirmationBody.contains("destructive = true"));
        assertTrue(confirmationBody.contains("clearCache()"));
        assertTrue(dialogs.contains(".setNegativeButton(R.string.nb_common_cancel, null)"));
        assertTrue(confirmationBody.contains("不会删除书架或本机书籍"));
    }

    @Test
    public void cacheDeletionUsesTheLifecycleScopeAndReportsPartialFailure() throws IOException {
        String settings = readFile(
            "src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt"
        );

        int clearCache = settings.indexOf("private fun clearCache()");
        int nextMethod = settings.indexOf("private fun requestBookShelfSyncAndFinish()", clearCache);
        String clearBody = settings.substring(clearCache, nextMethod);
        assertTrue(clearBody.contains("cacheSizeScope.launch"));
        assertTrue(clearBody.contains("withContext(Dispatchers.IO)"));
        assertTrue(clearBody.contains("CacheUtils.clearAppCache(applicationContext)"));
        assertTrue(clearBody.contains("部分缓存清理失败，请重试"));
        assertFalse(clearBody.contains("Thread {"));
        assertFalse(clearBody.contains("runOnUiThread"));
    }

    private static String readFile(String path) throws IOException {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
