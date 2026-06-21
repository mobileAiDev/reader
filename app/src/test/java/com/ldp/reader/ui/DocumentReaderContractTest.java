package com.ldp.reader.ui;

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
        assertTrue(activity.contains("DocumentFileName.displayName(applicationContext, uri) to readReadableHtml()"));
        assertTrue(activity.contains("binding.epubReadWeb.loadDataWithBaseURL"));
        assertTrue(activity.contains("scope.cancel()"));
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
