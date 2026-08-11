package com.ldp.reader.audio

import com.ldp.reader.media.MediaRequest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioAutoAdvanceCandidateStateMachineTest {
    @Test
    fun asyncFailureOfStagedAutoEpisodeContinuesWithoutResolvingThatEpisodeTwice() = runBlocking {
        val stateMachine = AudioAutoAdvanceCandidateStateMachine(maxCandidateAttempts = 8)
        val transitionGate = AudioPlaybackTransitionGate()
        val ticket = transitionGate.captureAutoAdvance("route-1")
        val resolvedRoutes = mutableListOf<String>()
        val stagedRoutes = mutableListOf<String>()
        val candidates = listOf(
            AudioAutoAdvanceCandidate("route-2", "第2集"),
            AudioAutoAdvanceCandidate("route-3", "第3集")
        )
        val resolve: suspend (AudioAutoAdvanceCandidate) -> AudioRepositoryCallResult<MediaRequest?> = { candidate ->
            resolvedRoutes += candidate.routeId
            AudioRepositoryCallResult.Success(
                MediaRequest("https://audio.example/${candidate.routeId}.mp3")
            )
        }
        val stage: (AudioAutoAdvanceCandidate, MediaRequest) -> Unit = { candidate, _ ->
            stagedRoutes += candidate.routeId
        }

        val first = stateMachine.advance(
            candidates = candidates,
            resolveRequest = resolve,
            prepare = { _, _ -> AudioPlaybackCommandResult.Success },
            stage = stage
        ) as AudioAutoAdvanceAttemptResult.Advanced
        transitionGate.rememberStagedAutoAdvance(ticket, first)

        val continuation = transitionGate.consumeStagedAutoAdvanceFailure("route-2")
        val second = stateMachine.advance(
            candidates = checkNotNull(continuation).remainingCandidates,
            resolveRequest = resolve,
            prepare = { _, _ -> AudioPlaybackCommandResult.Success },
            stage = stage
        ) as AudioAutoAdvanceAttemptResult.Advanced

        assertEquals("route-3", second.candidate.routeId)
        assertEquals(listOf("route-2", "route-3"), resolvedRoutes)
        assertEquals(listOf("route-2", "route-3"), stagedRoutes)
        assertNull(
            "the same async failure must be consumed once so it cannot trigger duplicate probes",
            transitionGate.consumeStagedAutoAdvanceFailure("route-2")
        )
    }

    @Test
    fun autoAdvanceSkipsConsecutiveBadEpisodesAndCommitsOnlyTheFirstPlayableEpisode() = runBlocking {
        val resolvedRoutes = mutableListOf<String>()
        val preparedRoutes = mutableListOf<String>()
        val committedRoutes = mutableListOf<String>()
        val candidates = listOf(
            AudioAutoAdvanceCandidate("route-2", "第2集"),
            AudioAutoAdvanceCandidate("route-3", "第3集"),
            AudioAutoAdvanceCandidate("route-4", "第4集")
        )

        val result = AudioAutoAdvanceCandidateStateMachine(maxCandidateAttempts = 4).advance(
            candidates = candidates,
            resolveRequest = { candidate ->
                resolvedRoutes += candidate.routeId
                when (candidate.routeId) {
                    "route-2" -> AudioRepositoryCallResult.Success(null)
                    else -> AudioRepositoryCallResult.Success(
                        MediaRequest("https://audio.example/${candidate.routeId}.mp3")
                    )
                }
            },
            prepare = { candidate, _ ->
                preparedRoutes += candidate.routeId
                if (candidate.routeId == "route-3") {
                    AudioPlaybackCommandResult.Failure(
                        IllegalStateException("unsupported media source")
                    )
                } else {
                    AudioPlaybackCommandResult.Success
                }
            },
            stage = { candidate, _ -> committedRoutes += candidate.routeId }
        )

        assertTrue(result is AudioAutoAdvanceAttemptResult.Advanced)
        assertEquals("route-4", (result as AudioAutoAdvanceAttemptResult.Advanced).candidate.routeId)
        assertEquals(listOf("route-2", "route-3", "route-4"), resolvedRoutes)
        assertEquals(listOf("route-3", "route-4"), preparedRoutes)
        assertEquals(listOf("route-4"), committedRoutes)
    }

    @Test
    fun autoAdvanceIsBoundedAndNeverResolvesTheSameCandidateTwice() = runBlocking {
        val resolvedRoutes = mutableListOf<String>()
        val committedRoutes = mutableListOf<String>()
        val candidates = (2..10).map { index ->
            AudioAutoAdvanceCandidate("route-$index", "第${index}集")
        } + AudioAutoAdvanceCandidate("route-2", "重复第2集")

        val result = AudioAutoAdvanceCandidateStateMachine(maxCandidateAttempts = 3).advance(
            candidates = candidates,
            resolveRequest = { candidate ->
                resolvedRoutes += candidate.routeId
                AudioRepositoryCallResult.Success(null)
            },
            prepare = { _, _ ->
                fail("blank requests must not reach Media3 preparation")
                AudioPlaybackCommandResult.Success
            },
            stage = { candidate, _ -> committedRoutes += candidate.routeId }
        )

        assertTrue(result is AudioAutoAdvanceAttemptResult.Exhausted)
        assertEquals(listOf("route-2", "route-3", "route-4"), resolvedRoutes)
        assertEquals(resolvedRoutes.distinct(), resolvedRoutes)
        assertTrue(committedRoutes.isEmpty())
    }

    @Test
    fun cancellationEscapesWithoutTryingOrCommittingLaterEpisodes() = runBlocking {
        val cancellation = CancellationException("service stopped")
        val resolvedRoutes = mutableListOf<String>()
        val committedRoutes = mutableListOf<String>()

        try {
            AudioAutoAdvanceCandidateStateMachine(maxCandidateAttempts = 4).advance(
                candidates = listOf(
                    AudioAutoAdvanceCandidate("route-2", "第2集"),
                    AudioAutoAdvanceCandidate("route-3", "第3集")
                ),
                resolveRequest = { candidate ->
                    resolvedRoutes += candidate.routeId
                    throw cancellation
                },
                prepare = { _, _ -> AudioPlaybackCommandResult.Success },
                stage = { candidate, _ -> committedRoutes += candidate.routeId }
            )
            fail("CancellationException must escape auto advance")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(listOf("route-2"), resolvedRoutes)
        assertTrue(committedRoutes.isEmpty())
    }

    @Test
    fun cancellationWrappedByTheRepositoryBoundaryIsRethrownWithoutTryingLaterEpisodes() = runBlocking {
        val cancellation = CancellationException("repository call cancelled")
        val resolvedRoutes = mutableListOf<String>()

        try {
            AudioAutoAdvanceCandidateStateMachine(maxCandidateAttempts = 4).advance(
                candidates = listOf(
                    AudioAutoAdvanceCandidate("route-2", "第2集"),
                    AudioAutoAdvanceCandidate("route-3", "第3集")
                ),
                resolveRequest = { candidate ->
                    resolvedRoutes += candidate.routeId
                    AudioRepositoryCallResult.Failure(cancellation)
                },
                prepare = { _, _ -> AudioPlaybackCommandResult.Success },
                stage = { _, _ -> fail("cancelled candidates must never be staged") }
            )
            fail("wrapped CancellationException must escape auto advance")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(listOf("route-2"), resolvedRoutes)
    }
}
