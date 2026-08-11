package com.ldp.reader.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class AudioMiniPlayerActionWiringContractTest {
    @Test
    public void endedMiniPlaybackRestartsFromZeroInDetailAndBookshelf() throws Exception {
        assertReplayWiring(
                between(
                        readFile("src/main/java/com/ldp/reader/ui/activity/AudioDetailActivity.kt"),
                        "private fun handleMiniPlayerAction()",
                        "private fun renderEpisodes()"));
        assertReplayWiring(
                between(
                        readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt"),
                        "private fun handleAudioFloatPlayClick()",
                        "private fun traceAudioFloat("));
    }

    @Test
    public void failedMiniPlaybackOpensFreshResolutionInsteadOfResendingItsUrl() throws Exception {
        String policy = readFile("src/main/java/com/ldp/reader/audio/AudioMiniPlayerAction.kt");
        assertTrue(policy.contains("!nowPlaying.playbackReady"));
        assertTrue(policy.contains("AudioMiniPlayerCommand.OPEN_PLAYER"));
        assertFalse(policy.contains("!nowPlaying.playbackReady -> AudioMiniPlayerCommand.RESUME_SERVICE"));
    }

    @Test
    public void replayFlagRestartsBothLiveAndColdServiceSessionsFromZero() throws Exception {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String reuse = between(service, "if (\n                chapterRouteId.isNotBlank()", "saveCurrentProgress()");
        String playCurrent = between(service, "private fun playCurrent(", "private fun createAudioMediaSource(");

        assertTrue(reuse.contains("if (forceStart)"));
        assertTrue(reuse.contains("AudioPlaybackProgressStore.clear(this, chapterRouteId)"));
        assertTrue(reuse.contains("player?.seekTo(0L)"));
        assertTrue(playCurrent.contains("val resumePosition = if (forceStart)"));
        assertTrue(playCurrent.contains("AudioPlaybackProgressStore.clear("));
        assertTrue(playCurrent.contains("0L"));
    }

    private static void assertReplayWiring(String handler) {
        int replay = handler.indexOf("AudioMiniPlayerCommand.REPLAY_SERVICE");
        int open = handler.indexOf("AudioMiniPlayerCommand.OPEN_PLAYER", replay);
        assertTrue(replay >= 0);
        assertTrue(open > replay);
        String branch = handler.substring(replay, open);
        assertTrue(branch.contains("AudioPlaybackService.EXTRA_FORCE_START, true"));
        assertTrue(branch.contains("AudioPlaybackService.EXTRA_AUTO_PLAY, true"));
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
