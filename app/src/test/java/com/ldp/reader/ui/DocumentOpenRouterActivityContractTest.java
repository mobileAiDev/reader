package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DocumentOpenRouterActivityContractTest {

    @Test
    public void textImportRunsOnIoBeforeOpeningReader() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/DocumentOpenRouterActivity.kt");

        assertTrue(activity.contains("CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"));
        assertTrue(activity.contains("ReaderDocumentFormat.TEXT -> {"));
        assertTrue(activity.contains("importText(uri)"));
        assertTrue(activity.contains("withContext(Dispatchers.IO)"));
        assertTrue(activity.contains("LocalTextImportStore.importUri(applicationContext, uri)"));
        assertTrue(activity.contains("ReadActivity.startActivity(this@DocumentOpenRouterActivity, collBook, true)"));
        assertTrue(activity.contains("importScope.cancel()"));
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
