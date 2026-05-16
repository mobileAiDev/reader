package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ViewBindingMigrationContractTest {

    @Test
    public void mainSourcesDoNotUseManualViewLookupOrButterKnife() throws IOException {
        List<File> sourceFiles = new ArrayList<>();
        collectSourceFiles(new File("src/main/java"), sourceFiles);

        for (File file : sourceFiles) {
            String source = readFile(file);
            assertFalse(file.getPath(), source.contains("findViewById"));
            assertFalse(file.getPath(), source.contains("ButterKnife"));
            assertFalse(file.getPath(), source.contains("@BindView"));
            assertFalse(file.getPath(), source.contains("@OnClick"));
        }
    }

    @Test
    public void baseActivityGetsToolbarFromViewBindingContract() throws IOException {
        String baseActivity = readFile(new File("src/main/java/com/ldp/reader/ui/base/BaseActivity.kt"));
        assertTrue(baseActivity.contains("protected open fun toolbarView(): Toolbar? = null"));
        assertTrue(baseActivity.contains("mToolbar = toolbarView()"));
        assertFalse(baseActivity.contains("R.id.toolbar"));
    }

    private static void collectSourceFiles(File root, List<File> sourceFiles) throws IOException {
        Files.walk(root.toPath())
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.toString();
                    return name.endsWith(".java") || name.endsWith(".kt");
                })
                .forEach(path -> sourceFiles.add(path.toFile()));
    }

    private static String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
