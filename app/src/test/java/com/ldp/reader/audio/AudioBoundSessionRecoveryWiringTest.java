package com.ldp.reader.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class AudioBoundSessionRecoveryWiringTest {
    @Test
    public void bindOnlyConnectionRecoversOnlyAnEmptyPreviouslyReadySession() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String recovery = between(
                player,
                "private fun recoverBoundSessionIfNeeded(",
                "private fun handleEmptyBoundSessionPrimaryAction(");

        assertTrue(recovery.contains("player.mediaItemCount > 0"));
        assertTrue(recovery.contains("persisted?.playbackReady == true"));
        assertTrue(recovery.contains("persisted?.playbackEnded == true"));
        assertTrue(recovery.contains(
                "dispatchPersistedPlayback(persisted, forceStart = false, autoPlay = false)"));
        assertFalse(recovery.contains("ACTION_CANCEL_AUTO_ADVANCE"));
    }

    @Test
    public void bindOnlyConnectionPassesAnExistingPlayerErrorIntoRecoveryPolicy() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String recovery = between(
                player,
                "private fun recoverBoundSessionIfNeeded(",
                "private fun handleEmptyBoundSessionPrimaryAction(");

        assertTrue(recovery.contains("player.playerError != null"));
        assertTrue(recovery.contains("AudioBoundSessionRecoveryPolicy.decide("));

        String clicks = between(player, "override fun initClick()", "override fun processLogic()");
        String primary = between(
                clicks,
                "audioPlayerPlayPause.setOnClickListener",
                "audioPlayerRewind.setOnClickListener");
        assertTrue(primary.contains("if (handleEmptyBoundSessionPrimaryAction())"));
        assertFalse(primary.contains("player.mediaItemCount == 0 &&"));

        String readiness = between(
                player,
                "private fun renderControlReadiness()",
                "private fun formatMillis(");
        assertTrue(readiness.contains("controller?.playerError == null"));
    }

    @Test
    public void endedAndFailedColdSessionsWaitForAnExplicitUserAction() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String primaryAction = between(
                player,
                "private fun handleEmptyBoundSessionPrimaryAction(",
                "private fun dispatchPersistedPlayback(");

        assertTrue(primaryAction.contains(
                "dispatchPersistedPlayback(persisted, forceStart = true, autoPlay = true)"));
        assertTrue(primaryAction.contains("resolveFreshPlayback = true"));
        assertTrue(primaryAction.contains("autoPlayPlayback = true"));
        assertFalse(primaryAction.contains("ACTION_CANCEL_AUTO_ADVANCE"));
    }

    @Test
    public void servicePersistsReadyEndedAndFailedPlaybackLifecycle() throws Exception {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String stateStore = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackStateStore.kt");

        assertTrue(service.contains("AudioPlaybackStateStore.markPreparing(this, currentChapterRouteId)"));
        assertTrue(service.contains("AudioPlaybackStateStore.markReady("));
        assertTrue(service.contains("AudioPlaybackStateStore.markEnded("));
        assertTrue(service.contains("AudioPlaybackStateStore.markFailed("));
        assertTrue(stateStore.contains("playbackReady = state.playbackReady"));
        assertTrue(stateStore.contains("playbackEnded = state.playbackEnded"));
    }

    private static String between(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static String readFile(String path) throws IOException {
        return new String(
                Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8);
    }
}
