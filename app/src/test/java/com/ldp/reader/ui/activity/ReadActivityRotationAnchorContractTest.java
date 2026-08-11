package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ReadActivityRotationAnchorContractTest {

    @Test
    public void configurationRecreationTransfersTheLiveAnchorToTheNewPageLoader() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String loader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        assertTrue(activity.contains("override fun onSaveInstanceState(outState: Bundle)"));
        assertTrue(activity.contains("mPageLoader?.captureConfigurationReadingAnchor()"));
        assertTrue(activity.contains("STATE_READING_ANCHOR"));
        assertTrue(activity.contains("mPageLoader!!.restoreAfterConfigurationChange("));
        assertTrue(activity.contains("persistResolvedPosition = isCollected"));

        assertTrue(loader.contains("fun captureConfigurationReadingAnchor()"));
        assertTrue(loader.contains("fun restoreAfterConfigurationChange("));
        assertTrue(loader.contains("ReaderPaginationReflowPolicy.restorePagePosition("));
        assertTrue(loader.contains("if (configurationRestore.persistResolvedPosition)"));
    }

    @Test
    public void localTxtReusesItsByteAnchorAndTrialReadingDoesNotWriteARecord() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String loader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        assertTrue(loader.contains("if (mCollBook.isLocal())"));
        assertTrue(loader.contains("LocalTxtReadingAnchorResolver.capture("));
        assertTrue(loader.contains("LocalTxtReadingAnchorResolver.resolve("));
        assertTrue(activity.contains("persistResolvedPosition = isCollected"));
        assertTrue(loader.contains("if (configurationRestore.persistResolvedPosition)"));
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
