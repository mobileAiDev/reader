package com.ldp.reader.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioBoundSessionRecoveryPolicyTest {
    @Test
    public void liveSessionKeepsItsCurrentMediaWithoutRedispatching() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.KEEP_LIVE,
                AudioBoundSessionRecoveryPolicy.decide(
                        true,
                        false,
                        "chapter-2",
                        "chapter-2",
                        "https://audio.example/chapter-2.mp3",
                        true,
                        false));
    }

    @Test
    public void coldEmptySessionRestoresAPreviouslyReadyPausedChapter() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.RESTORE_PAUSED,
                AudioBoundSessionRecoveryPolicy.decide(
                        false,
                        false,
                        "chapter-2",
                        "chapter-2",
                        "https://audio.example/chapter-2.mp3",
                        true,
                        false));
    }

    @Test
    public void coldEndedChapterIsShownAsEndedInsteadOfRestartingOnEntry() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.SHOW_ENDED,
                AudioBoundSessionRecoveryPolicy.decide(
                        false,
                        false,
                        "chapter-2",
                        "chapter-2",
                        "https://audio.example/chapter-2.mp3",
                        true,
                        true));
    }

    @Test
    public void failedUrlIsNotAutomaticallyRedispatchedAfterProcessRebuild() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH,
                AudioBoundSessionRecoveryPolicy.decide(
                        false,
                        false,
                        "chapter-2",
                        "chapter-2",
                        "https://audio.example/chapter-2.mp3",
                        false,
                        false));
    }

    @Test
    public void persistedStateForAnotherRouteCannotPopulateTheRequestedPlayer() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH,
                AudioBoundSessionRecoveryPolicy.decide(
                        false,
                        false,
                        "chapter-2",
                        "chapter-1",
                        "https://audio.example/chapter-1.mp3",
                        true,
                        false));
    }

    @Test
    public void liveErroredMediaResolvesFreshWhenTheErrorPredatesThePlayerListener() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH,
                AudioBoundSessionRecoveryPolicy.decide(
                        true,
                        true,
                        "chapter-2",
                        "chapter-2",
                        "https://audio.example/stale.mp3",
                        true,
                        false));
    }

    @Test
    public void liveMediaWithPersistedUnreadyStateDoesNotReplayItsStaleUrl() {
        assertEquals(
                AudioBoundSessionRecoveryPolicy.Action.RESOLVE_FRESH,
                AudioBoundSessionRecoveryPolicy.decide(
                        true,
                        false,
                        "chapter-2",
                        "chapter-2",
                        "https://audio.example/stale.mp3",
                        false,
                        false));
    }
}
