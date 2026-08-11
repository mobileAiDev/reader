package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class AudioPlaybackFailureCopyWiringContractTest {
    @Test
    public void playerRendersCopyFromTheFailureThatActuallyOccurred() throws IOException {
        String player = readSource("AudioPlayerActivity.kt");

        assertTrue(player.contains("AudioPlaybackFailureCopy.missingOrRejectedRequestMessage()"));
        assertTrue(player.contains("AudioPlaybackFailureCopy.messageForPlayerError(error.errorCode)"));
        assertTrue(player.contains("AudioPlaybackFailureCopy.messageForRecordedFailure(failure)"));
        assertTrue(player.contains("AudioRepositoryCallRunner.runForegroundPlayerResult"));
        assertTrue(player.contains("AudioPlaybackFailureCopy.messageForRepositoryFailure"));
        assertTrue(player.contains("private fun renderPlayerFailure(message: String)"));
        assertFalse(player.contains("PLAYER_FAILURE_MESSAGE"));
    }

    private static String readSource(String fileName) throws IOException {
        return new String(
                Files.readAllBytes(new File("src/main/java/com/ldp/reader/ui/activity/" + fileName).toPath()),
                StandardCharsets.UTF_8);
    }
}
