package com.ldp.reader.kotlin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class KotlinMigrationContractTest {

    @Test
    public void firstDataModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/event/BookSyncEvent");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BaseBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BookIdBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/ChapterBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/ContentBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/packages/HotWordPackage");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/packages/KeyWordPackage");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/local/Void");
    }

    @Test
    public void loginAndShelfSyncModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/DirectLoginResultBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/LoginResultBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/SmsLoginBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/SyncBookShelfBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/DirectSycBookShelfBean");
    }

    @Test
    public void kotlinMigrationCountsMovedForward() {
        assertTrue(countFiles(new File("src/main/java"), ".kt") >= 34);
        assertTrue(countFiles(new File("src/main/java"), ".java") <= 120);
    }

    private static void assertKotlinOnly(String pathWithoutExtension) {
        assertTrue(new File(pathWithoutExtension + ".kt").exists());
        assertFalse(new File(pathWithoutExtension + ".java").exists());
    }

    private static int countFiles(File root, String extension) {
        File[] files = root.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += countFiles(file, extension);
            } else if (file.getName().endsWith(extension)) {
                count++;
            }
        }
        return count;
    }
}
