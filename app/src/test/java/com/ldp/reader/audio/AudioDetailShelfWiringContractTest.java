package com.ldp.reader.audio;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class AudioDetailShelfWiringContractTest {
    @Test
    public void tappingAlreadyShelvedAudioDoesNotRewriteItsSavedEpisode() throws Exception {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/AudioDetailActivity.kt");
        String add = between(activity, "private fun addToMediaShelf()", "private fun renderShelfButton()");

        int decision = add.indexOf("AudioDetailShelfAction.decide(");
        int keep = add.indexOf("AudioDetailShelfAction.KEEP_EXISTING", decision);
        int write = add.indexOf("MediaShelfStore.addOrUpdate(", decision);
        assertTrue(decision >= 0);
        assertTrue(keep > decision);
        assertTrue(write > keep);
        assertTrue(add.substring(keep, write).contains("return"));
    }

    private static String between(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
