package com.ldp.reader.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class AudioBoundCatalogRecoveryWiringTest {
    @Test
    public void coldBindRestoresTheBookRouteBeforeCatalogRecovery() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String readIntent = between(player, "private fun readIntent(", "private fun loadPlayer()");

        int persisted = readIntent.indexOf("AudioPlaybackStateStore.current(this)");
        int bookRoute = readIntent.indexOf("bookRouteId =", persisted);
        assertTrue(persisted >= 0);
        assertTrue(bookRoute > persisted);
        assertTrue(readIntent.contains("nowPlaying?.bookRouteId"));
    }

    @Test
    public void bindOnlyRecoveryLoadsOnlyTheCatalogAndKeepsTheCurrentEpisodeOnFailure() throws Exception {
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String bind = between(
                player,
                "private fun bindExistingPlaybackSession()",
                "private fun recoverBoundCatalogIfNeeded(");
        String recovery = between(
                player,
                "private fun recoverBoundCatalogIfNeeded(",
                "private fun consumePlaybackCommand()");

        assertTrue(bind.contains("recoverBoundCatalogIfNeeded(loadToken)"));
        assertTrue(recovery.contains("AudioBoundCatalogRecoveryPolicy.decide("));
        assertTrue(recovery.contains("MediaSourceRepository.chapters(recoveryBookRouteId)"));
        assertTrue(recovery.contains("renderCatalogState(MediaDetailLoadProgress.Step.FAILED.label, retryEnabled = true)"));
        assertTrue(recovery.contains("episodes = AudioChapterOrder.chronological(recoveredEpisodes)"));
        assertFalse(recovery.contains("episodes = emptyList()"));
        assertFalse(recovery.contains("MediaSourceRepository.audioRequest("));
        assertFalse(recovery.contains("startService("));
        assertFalse(recovery.contains("ACTION_CANCEL_AUTO_ADVANCE"));

        String tab = between(player, "private fun renderTab()", "private fun openSiblingEpisode(");
        String catalogState = between(player, "private fun renderCatalogState(", "private fun renderPlayerStatus(");
        assertTrue(tab.contains("episodes.isEmpty() || catalogRetryEnabled"));
        assertTrue(tab.contains("episodes.isNotEmpty() && !catalogRetryEnabled"));
        assertTrue(catalogState.contains("episodes.isEmpty() || retryEnabled"));
        assertTrue(catalogState.contains("episodes.isNotEmpty() && !retryEnabled"));
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
