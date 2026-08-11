package com.ldp.reader.audio

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class AudioPlaybackTransitionPolicyTest {
    @Test
    fun invalidationStopsOnlyTheExactStagedCandidateThatHasNotReachedReady() {
        val unreadyGate = AudioPlaybackTransitionGate()
        val unreadyTicket = unreadyGate.captureAutoAdvance("episode-1")
        unreadyGate.rememberStagedAutoAdvance(
            unreadyTicket,
            advanced("episode-2")
        )

        val unreadyInvalidation = unreadyGate.invalidatePendingTransitions()

        assertTrue(unreadyInvalidation.shouldStopMedia("episode-2", "episode-2"))
        assertFalse(unreadyInvalidation.shouldStopMedia("episode-1", "episode-2"))
        assertFalse(unreadyInvalidation.shouldStopMedia("episode-2", "episode-3"))

        val readyGate = AudioPlaybackTransitionGate()
        val readyTicket = readyGate.captureAutoAdvance("episode-1")
        readyGate.rememberStagedAutoAdvance(
            readyTicket,
            advanced("episode-2")
        )
        readyGate.markStagedAutoAdvanceReady("episode-2")

        val readyInvalidation = readyGate.invalidatePendingTransitions()

        assertFalse(
            "a candidate that reached READY is the committed current playback and must not be stopped",
            readyInvalidation.shouldStopMedia("episode-2", "episode-2")
        )
    }

    @Test
    fun manualSelectionInvalidationCancelsStagedAutoContinuation() {
        val gate = AudioPlaybackTransitionGate()
        val ticket = gate.captureAutoAdvance("episode-1")
        gate.rememberStagedAutoAdvance(
            ticket,
            AudioAutoAdvanceAttemptResult.Advanced(
                candidate = AudioAutoAdvanceCandidate("episode-2", "第2集"),
                request = MediaRequest("https://audio.example/episode-2.mp3"),
                attemptedCount = 1,
                remainingCandidates = listOf(
                    AudioAutoAdvanceCandidate("episode-3", "第3集")
                )
            )
        )

        gate.invalidatePendingTransitions()

        assertNull(gate.consumeStagedAutoAdvanceFailure("episode-2"))
        assertFalse(gate.canApply(ticket, "episode-1"))
    }

    @Test
    fun failedManualResolutionStillRejectsTheLateAutomaticCandidate() {
        val gate = AudioPlaybackTransitionGate()
        val automaticTicket = gate.captureAutoAdvance("episode-1")

        gate.invalidatePendingTransitions()
        val failedManualRequest: MediaRequest? = null
        gate.rememberStagedAutoAdvance(
            automaticTicket,
            AudioAutoAdvanceAttemptResult.Advanced(
                candidate = AudioAutoAdvanceCandidate("episode-2", "第2集"),
                request = MediaRequest("https://audio.example/episode-2.mp3"),
                attemptedCount = 1,
                remainingCandidates = emptyList()
            )
        )

        assertNull(failedManualRequest)
        assertFalse(gate.canApply(automaticTicket, "episode-1"))
        assertNull(gate.markStagedAutoAdvanceReady("episode-2"))
        assertNull(gate.consumeStagedAutoAdvanceFailure("episode-2"))
    }

    @Test
    fun stagedAutoEpisodeIsFinalizedOnlyOnceAfterItsOwnReadySignal() {
        val gate = AudioPlaybackTransitionGate()
        val ticket = gate.captureAutoAdvance("episode-1")
        gate.rememberStagedAutoAdvance(
            ticket,
            AudioAutoAdvanceAttemptResult.Advanced(
                candidate = AudioAutoAdvanceCandidate("episode-2", "第2集"),
                request = MediaRequest("https://audio.example/episode-2.mp3"),
                attemptedCount = 1,
                remainingCandidates = listOf(AudioAutoAdvanceCandidate("episode-3", "第3集"))
            )
        )

        assertNull(gate.markStagedAutoAdvanceReady("another-route"))
        val ready = gate.markStagedAutoAdvanceReady("episode-2")

        assertEquals("episode-2", ready?.stagedChapterRouteId)
        assertFalse(ready?.wasFinalized == true)
        assertNull("duplicate READY must not persist twice", gate.markStagedAutoAdvanceReady("episode-2"))
    }

    @Test
    fun asyncFailureBeforeReadyConsumesUnfinalizedStage() {
        val gate = AudioPlaybackTransitionGate()
        val ticket = gate.captureAutoAdvance("episode-1")
        gate.rememberStagedAutoAdvance(
            ticket,
            AudioAutoAdvanceAttemptResult.Advanced(
                candidate = AudioAutoAdvanceCandidate("episode-2", "第2集"),
                request = MediaRequest("https://audio.example/episode-2.mp3"),
                attemptedCount = 1,
                remainingCandidates = listOf(AudioAutoAdvanceCandidate("episode-3", "第3集"))
            )
        )

        val failed = gate.consumeStagedAutoAdvanceFailure("episode-2")

        assertFalse(failed?.wasFinalized == true)
        assertNull(gate.markStagedAutoAdvanceReady("episode-2"))
    }

    @Test
    fun persistedEpisodeChangesOnlyWhenTheNextPlayableStageBecomesReady() {
        val gate = AudioPlaybackTransitionGate()
        val ticket = gate.captureAutoAdvance("episode-1")
        var persistedChapterRouteId = "episode-1"

        gate.rememberStagedAutoAdvance(
            ticket,
            AudioAutoAdvanceAttemptResult.Advanced(
                candidate = AudioAutoAdvanceCandidate("episode-2", "第2集"),
                request = MediaRequest("https://audio.example/episode-2.mp3"),
                attemptedCount = 1,
                remainingCandidates = listOf(AudioAutoAdvanceCandidate("episode-3", "第3集"))
            )
        )
        assertEquals("episode-1", persistedChapterRouteId)

        val failed = checkNotNull(gate.consumeStagedAutoAdvanceFailure("episode-2"))
        assertEquals("episode-1", persistedChapterRouteId)

        gate.rememberStagedAutoAdvance(
            failed.ticket,
            AudioAutoAdvanceAttemptResult.Advanced(
                candidate = AudioAutoAdvanceCandidate("episode-3", "第3集"),
                request = MediaRequest("https://audio.example/episode-3.mp3"),
                attemptedCount = 1,
                remainingCandidates = emptyList()
            ),
            transitionFromChapterRouteId = "episode-2"
        )
        assertEquals("episode-1", persistedChapterRouteId)

        gate.markStagedAutoAdvanceReady("episode-3")?.let { ready ->
            persistedChapterRouteId = ready.stagedChapterRouteId
        }

        assertEquals("episode-3", persistedChapterRouteId)
    }

    @Test
    fun autoAdvanceResultIsRejectedAfterExplicitPlaybackTransition() {
        val gate = AudioPlaybackTransitionGate()
        gate.invalidatePendingTransitions()
        val ticket = gate.captureAutoAdvance("episode-1")

        gate.invalidatePendingTransitions()

        assertFalse(gate.canApply(ticket, "episode-1"))
    }

    @Test
    fun autoAdvanceResultIsAcceptedOnlyForTheEpisodeThatEnded() {
        val gate = AudioPlaybackTransitionGate()
        val ticket = gate.captureAutoAdvance("episode-1")

        assertTrue(gate.canApply(ticket, "episode-1"))
        assertFalse(gate.canApply(ticket, "episode-2"))
    }

    @Test
    fun playbackFailureRetryRefreshesUrlWithoutResettingListeningProgress() {
        val retry = AudioPlaybackRetryPolicy.afterPlaybackFailure()

        assertTrue(retry.resolveFreshUrl)
        assertTrue(retry.autoPlay)
        assertFalse(retry.restartFromBeginning)
    }

    @Test
    fun siblingNavigationUsesRoutePositionInsteadOfOneBasedSourceIndex() {
        val episodes = listOf(
            episode("route-1", index = 1),
            episode("route-2", index = 2),
            episode("route-3", index = 3)
        )

        assertNull(AudioEpisodeNavigation.sibling(episodes, "route-1", -1))
        assertEquals("route-2", AudioEpisodeNavigation.sibling(episodes, "route-1", 1)?.routeId)
        assertEquals("route-1", AudioEpisodeNavigation.sibling(episodes, "route-2", -1)?.routeId)
    }

    @Test
    fun siblingNavigationSupportsSparseSourceIndices() {
        val episodes = listOf(
            episode("route-10", index = 10),
            episode("route-30", index = 30),
            episode("route-90", index = 90)
        )

        assertEquals(1, AudioEpisodeNavigation.positionOf(episodes, "route-30"))
        assertEquals("route-90", AudioEpisodeNavigation.sibling(episodes, "route-30", 1)?.routeId)
    }

    @Test
    fun siblingNavigationUsesChronologicalNeighborsForReversedSource() {
        val episodes = listOf(
            episode("route-3", index = 3),
            episode("route-2", index = 2),
            episode("route-1", index = 1)
        )

        assertEquals(0, AudioEpisodeNavigation.positionOf(episodes, "route-1"))
        assertEquals("route-1", AudioEpisodeNavigation.sibling(episodes, "route-2", -1)?.routeId)
        assertEquals("route-3", AudioEpisodeNavigation.sibling(episodes, "route-2", 1)?.routeId)
    }

    @Test
    fun serviceDispatchConsumesOneShotAutoPlayIntent() {
        val consumed = AudioPlaybackLoadIntentPolicy.afterServiceDispatch(
            AudioPlaybackLoadIntent(forceStart = true, resolveFresh = true, autoPlay = true)
        )

        assertFalse(consumed.forceStart)
        assertFalse(consumed.resolveFresh)
        assertFalse(consumed.autoPlay)
    }

    @Test
    fun synchronousMediaSetupFailureIsContainedWithoutBlockingTheNextEpisode() {
        var validEpisodePrepared = false

        val rejected = AudioPlaybackCommandBoundary.execute {
            throw IllegalStateException("No suitable media source factory found for content type: 2")
        }
        val accepted = AudioPlaybackCommandBoundary.execute {
            validEpisodePrepared = true
        }

        assertTrue(rejected is AudioPlaybackCommandResult.Failure)
        assertEquals(
            "No suitable media source factory found for content type: 2",
            (rejected as AudioPlaybackCommandResult.Failure).error.message
        )
        assertEquals(AudioPlaybackCommandResult.Success, accepted)
        assertTrue(validEpisodePrepared)
    }

    @Test
    fun servicePlaybackFailureCanBeObservedAndClearedByTheNextPlaybackAttempt() {
        AudioPlaybackFailureStore.clear()

        AudioPlaybackFailureStore.record(
            chapterRouteId = "episode-5",
            reason = "media_setup_failed",
            error = IllegalStateException("unsupported playlist")
        )

        assertEquals("episode-5", AudioPlaybackFailureStore.current()?.chapterRouteId)
        assertEquals("IllegalStateException", AudioPlaybackFailureStore.current()?.errorType)

        AudioPlaybackFailureStore.clear()

        assertNull(AudioPlaybackFailureStore.current())
    }

    @Test
    fun lifecycleCancellationIsNotConvertedIntoAPlaybackFailure() {
        val cancellation = CancellationException("service stopping")

        try {
            AudioPlaybackCommandBoundary.execute { throw cancellation }
            fail("CancellationException must escape the media-failure boundary")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun episode(routeId: String, index: Int): MediaChapterItem {
        return MediaChapterItem(routeId = routeId, title = "第${index}集", index = index)
    }

    private fun advanced(routeId: String): AudioAutoAdvanceAttemptResult.Advanced {
        return AudioAutoAdvanceAttemptResult.Advanced(
            candidate = AudioAutoAdvanceCandidate(routeId, "候选集"),
            request = MediaRequest("https://audio.example/$routeId.mp3"),
            attemptedCount = 1,
            remainingCandidates = emptyList()
        )
    }
}
