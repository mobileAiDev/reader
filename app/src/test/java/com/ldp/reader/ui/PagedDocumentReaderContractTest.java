package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PagedDocumentReaderContractTest {

    @Test
    public void pdfPersistsTheDominantPageAcrossLifecycleAndReopen() throws IOException {
        String source = readFile("src/main/java/com/ldp/reader/ui/activity/PdfReadActivity.kt");

        assertTrue(source.contains("LocalDocumentPageProgressPersistence.store"));
        assertTrue(source.contains("override fun onPause()"));
        assertTrue(source.contains("override fun onStop()"));
        assertTrue(source.contains("override fun onSaveInstanceState"));
        assertTrue(source.contains("updateVisiblePage(binding.pdfReadPages)"));
        assertTrue(source.contains("persistReadingProgress()"));
        assertTrue(source.contains("restoredPageIndex?.let { outState.putInt(STATE_PAGE_INDEX, it) }"));
    }

    @Test
    public void localComicPersistsDominantPageAndShowsCurrentOverTotal() throws IOException {
        String source = readFile("src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt");

        assertTrue(source.contains("LocalDocumentPageProgressPersistence.store"));
        assertTrue(source.contains("override fun onPause()"));
        assertTrue(source.contains("override fun onStop()"));
        assertTrue(source.contains("override fun onSaveInstanceState"));
        assertTrue(source.contains("updateVisiblePage(binding.localComicReadPages)"));
        assertTrue(source.contains("dominantLocalDocumentPageIndex"));
        assertTrue(source.contains("localDocumentPageLabel"));
        assertTrue(source.contains("restoredPageIndex?.let { outState.putInt(STATE_PAGE_INDEX, it) }"));
    }

    private static String readFile(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
