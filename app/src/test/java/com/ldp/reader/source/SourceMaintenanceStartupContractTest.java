package com.ldp.reader.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class SourceMaintenanceStartupContractTest {
    @Test
    public void appStartupDoesNotAutoRunV8Maintenance() throws IOException {
        String app = readFile("src/main/java/com/ldp/reader/App.kt");
        assertFalse(app.contains("startLowPriorityV8Maintenance()"));
    }

    @Test
    public void mediaReadersStopLowPriorityV8Maintenance() throws IOException {
        String audioPlayer = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String comicReader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(audioPlayer.contains("stopLowPriorityV8Maintenance(\"audio-player\")"));
        assertTrue(comicReader.contains("stopLowPriorityV8Maintenance(\"comic-reader\")"));
    }

    @Test
    public void settingsStillOffersManualV8MaintenanceStart() throws IOException {
        String settings = readFile("src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt");
        assertTrue(settings.contains("BookContentProviderRouter.startLowPriorityV8Maintenance()"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
