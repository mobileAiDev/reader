package com.ldp.reader.ui;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class FileImportPerformanceContractTest {
    @Test
    public void directoryTraversalRunsOffTheMainThreadAndCancelsStaleLoads() throws IOException {
        String fragment = readFile(
            "src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt"
        );

        int toggle = fragment.indexOf("private fun toggleFileTree(file: File)");
        int fileCount = fragment.indexOf("override val fileCount", toggle);
        String toggleBody = fragment.substring(toggle, fileCount);

        assertTrue(toggleBody.contains("directoryLoadJob?.cancel()"));
        assertTrue(toggleBody.contains("viewLifecycleOwner.lifecycleScope.launch"));
        assertTrue(
            toggleBody.indexOf("withContext(Dispatchers.IO)") <
                toggleBody.indexOf("LocalBookImportFiles.listVisibleChildren(file)")
        );
    }

    @Test
    public void returningToParentRestoresTheVerticalScrollOffset() throws IOException {
        String fragment = readFile(
            "src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt"
        );

        assertTrue(fragment.contains("computeVerticalScrollOffset()"));
        assertTrue(!fragment.contains("computeHorizontalScrollOffset()"));
    }

    @Test
    public void folderRowsDoNotReadTheFileSystemOnTheMainThread() throws IOException {
        String holder = readFile(
            "src/main/java/com/ldp/reader/ui/adapter/view/FileHolder.kt"
        );

        assertTrue(!holder.contains("LocalBookImportFiles.visibleChildCount(folder)"));
    }

    private static String readFile(String path) throws IOException {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
