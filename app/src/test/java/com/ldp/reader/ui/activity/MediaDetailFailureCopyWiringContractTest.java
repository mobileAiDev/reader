package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class MediaDetailFailureCopyWiringContractTest {
    @Test
    public void comicAndAudioDetailsPreserveTheFailingStageWhenRenderingCopy() throws IOException {
        String comic = readSource("ComicDetailActivity.kt");
        String audio = readSource("AudioDetailActivity.kt");

        assertTrue(comic.contains("MediaDetailFailureStage.DETAIL"));
        assertTrue(comic.contains("MediaDetailFailureStage.CATALOG"));
        assertTrue(audio.contains("MediaDetailFailureStage.DETAIL"));
        assertTrue(audio.contains("MediaDetailFailureStage.CATALOG"));
        assertTrue(comic.contains("MediaDetailFailureCopy.messageFor(stage)"));
        assertTrue(audio.contains("MediaDetailFailureCopy.messageFor(stage)"));
        assertFalse(comic.contains("val text = MediaDetailLoadProgress.Step.FAILED.label"));
        assertFalse(audio.contains("val text = MediaDetailLoadProgress.Step.FAILED.label"));
    }

    private static String readSource(String fileName) throws IOException {
        return new String(
                Files.readAllBytes(new File("src/main/java/com/ldp/reader/ui/activity/" + fileName).toPath()),
                StandardCharsets.UTF_8);
    }
}
