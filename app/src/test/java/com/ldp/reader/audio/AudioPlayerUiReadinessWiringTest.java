package com.ldp.reader.audio;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class AudioPlayerUiReadinessWiringTest {
    @Test
    public void unresolvedPlayerDisablesRealControlsInsteadOfDroppingClicks() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String readiness = between(
                player,
                "private fun renderControlReadiness()",
                "private fun formatMillis(");

        assertTrue(readiness.contains("AudioPlayerUiReadinessPolicy.decide("));
        assertTrue(readiness.contains("audioPlayerPlayPause.isEnabled = readiness.primaryEnabled"));
        assertTrue(readiness.contains("audioPlayerRewind.isEnabled = readiness.sessionControlsEnabled"));
        assertTrue(readiness.contains("audioPlayerForward.isEnabled = readiness.sessionControlsEnabled"));
        assertTrue(readiness.contains("audioPlayerProgress.isEnabled = readiness.sessionControlsEnabled"));
        assertTrue(readiness.contains("audioPlayerSpeed.isEnabled = readiness.sessionControlsEnabled"));
        assertTrue(readiness.contains("audioPlayerSleep.isEnabled = readiness.sessionControlsEnabled"));
    }

    @Test
    public void centralPrimaryActionOwnsFailureRetryBeforeControllerAccess() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String clicks = between(player, "override fun initClick()", "override fun processLogic()");
        String primary = between(clicks, "audioPlayerPlayPause.setOnClickListener", "audioPlayerRewind.setOnClickListener");
        String state = between(clicks, "audioPlayerState.setOnClickListener", "audioPlayerProgress.setOnSeekBarChangeListener");
        String retry = between(player, "private fun retryFailedPlayback()", "private fun renderControlReadiness()");

        assertTrue(primary.indexOf("retryFailedPlayback()") < primary.indexOf("controller ?: return"));
        assertTrue(state.contains("retryFailedPlayback()"));
        assertTrue(retry.contains("if (!playerRetryEnabled) return false"));
        assertTrue(retry.contains("resolveFreshPlayback = retry.resolveFreshUrl"));
        assertTrue(retry.contains("loadPlayer()"));
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
