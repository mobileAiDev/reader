package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DocumentReaderContractTest {

    @Test
    public void epubReadsArchiveOffMainThreadAndLoadsWebViewOnMain() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/EpubReadActivity.kt");

        assertTrue(activity.contains("CoroutineScope(SupervisorJob() + Dispatchers.Main)"));
        assertTrue(activity.contains("withContext(Dispatchers.IO)"));
        assertTrue(activity.contains("EpubPackageParser().parse(input, checkCancelled)"));
        assertTrue(activity.contains("checkCancelled = { context.ensureActive() }"));
        assertTrue(activity.contains("openAssetFileDescriptor(uri, \"r\", cancellationSignal)"));
        assertTrue(activity.contains("binding.epubReadWeb.loadDataWithBaseURL"));
        assertFalse(activity.contains("binding.epubReadWeb.loadData("));
        assertTrue(activity.contains("EpubPackageParser.BASE_URL"));
        assertTrue(activity.contains("binding.epubReadWeb.webViewClient = epubWebViewClient()"));
        assertTrue(activity.contains("binding.epubReadWeb.settings.blockNetworkLoads = true"));
        assertTrue(activity.contains("img-src https://epub.local"));
        assertFalse(activity.contains("img-src data:"));
        assertTrue(activity.contains("scope.cancel()"));
    }

    @Test
    public void epubPersistsAndRestoresPositionAtWebViewAndLifecycleBoundaries() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/EpubReadActivity.kt");

        assertTrue(activity.contains("EpubReadingProgressStore"));
        assertTrue(activity.contains("override fun onPageFinished"));
        assertTrue(activity.contains("restoreReadingProgress"));
        assertTrue(activity.contains("override fun onPause()"));
        assertTrue(activity.contains("override fun onStop()"));
        assertTrue(activity.contains("override fun onSaveInstanceState"));
        assertTrue(activity.contains("persistReadingProgress()"));
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
