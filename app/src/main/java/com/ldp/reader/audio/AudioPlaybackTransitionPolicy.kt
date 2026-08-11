package com.ldp.reader.audio

import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaRequest
import java.util.concurrent.CancellationException

data class AudioAutoAdvanceTicket internal constructor(
    internal val generation: Long,
    val endedChapterRouteId: String
)

data class AudioPlaybackTransitionInvalidation(
    val generation: Long,
    val unreadyStagedChapterRouteId: String?
) {
    fun shouldStopMedia(
        currentChapterRouteId: String,
        currentMediaItemRouteId: String
    ): Boolean {
        val stagedRouteId = unreadyStagedChapterRouteId ?: return false
        return stagedRouteId.isNotBlank() &&
            stagedRouteId == currentChapterRouteId &&
            stagedRouteId == currentMediaItemRouteId
    }
}

class AudioPlaybackTransitionGate {
    private var generation = 0L
    private var stagedAutoAdvance: AudioAutoAdvanceTransition? = null

    fun invalidatePendingTransitions(): AudioPlaybackTransitionInvalidation {
        val unreadyStagedChapterRouteId = stagedAutoAdvance
            ?.takeUnless { it.wasFinalized }
            ?.stagedChapterRouteId
        generation += 1L
        stagedAutoAdvance = null
        return AudioPlaybackTransitionInvalidation(
            generation = generation,
            unreadyStagedChapterRouteId = unreadyStagedChapterRouteId
        )
    }

    fun captureAutoAdvance(endedChapterRouteId: String): AudioAutoAdvanceTicket {
        return AudioAutoAdvanceTicket(generation, endedChapterRouteId)
    }

    fun canApply(ticket: AudioAutoAdvanceTicket, currentChapterRouteId: String): Boolean {
        return canApply(
            ticket = ticket,
            expectedChapterRouteId = ticket.endedChapterRouteId,
            currentChapterRouteId = currentChapterRouteId
        )
    }

    fun canApply(
        ticket: AudioAutoAdvanceTicket,
        expectedChapterRouteId: String,
        currentChapterRouteId: String
    ): Boolean {
        return ticket.generation == generation &&
            expectedChapterRouteId.isNotBlank() &&
            expectedChapterRouteId == currentChapterRouteId
    }

    fun rememberStagedAutoAdvance(
        ticket: AudioAutoAdvanceTicket,
        advanced: AudioAutoAdvanceAttemptResult.Advanced,
        transitionFromChapterRouteId: String = ticket.endedChapterRouteId
    ) {
        stagedAutoAdvance = if (
            ticket.generation == generation &&
            advanced.candidate.routeId.isNotBlank()
        ) {
            AudioAutoAdvanceTransition(
                ticket = ticket,
                advanced = advanced,
                transitionFromChapterRouteId = transitionFromChapterRouteId
            )
        } else {
            null
        }
    }

    fun markStagedAutoAdvanceReady(
        currentChapterRouteId: String
    ): AudioAutoAdvanceTransition? {
        val transition = stagedAutoAdvance ?: return null
        if (transition.wasFinalized || !transition.matches(this, currentChapterRouteId)) return null
        stagedAutoAdvance = transition.copy(wasFinalized = true)
        return transition
    }

    fun consumeStagedAutoAdvanceFailure(
        currentChapterRouteId: String
    ): AudioAutoAdvanceTransition? {
        val transition = stagedAutoAdvance ?: return null
        if (!transition.matches(this, currentChapterRouteId)) return null
        stagedAutoAdvance = null
        return transition
    }

    fun clearStagedAutoAdvance() {
        stagedAutoAdvance = null
    }

    private fun AudioAutoAdvanceTransition.matches(
        gate: AudioPlaybackTransitionGate,
        currentChapterRouteId: String
    ): Boolean {
        return gate.canApply(
            ticket = ticket,
            expectedChapterRouteId = stagedChapterRouteId,
            currentChapterRouteId = currentChapterRouteId
        )
    }
}

data class AudioPlaybackRetryDecision(
    val resolveFreshUrl: Boolean,
    val restartFromBeginning: Boolean,
    val autoPlay: Boolean
)

object AudioPlaybackRetryPolicy {
    fun afterPlaybackFailure(): AudioPlaybackRetryDecision {
        return AudioPlaybackRetryDecision(
            resolveFreshUrl = true,
            restartFromBeginning = false,
            autoPlay = true
        )
    }
}

object AudioEpisodeNavigation {
    fun positionOf(episodes: List<MediaChapterItem>, chapterRouteId: String): Int {
        if (chapterRouteId.isBlank()) return -1
        return AudioChapterOrder.chronological(episodes)
            .indexOfFirst { it.routeId == chapterRouteId }
    }

    fun sibling(
        episodes: List<MediaChapterItem>,
        chapterRouteId: String,
        offset: Int
    ): MediaChapterItem? {
        val chronological = AudioChapterOrder.chronological(episodes)
        val currentPosition = chronological.indexOfFirst { it.routeId == chapterRouteId }
        if (currentPosition < 0) return null
        return chronological.getOrNull(currentPosition + offset)
    }
}

data class AudioPlaybackLoadIntent(
    val forceStart: Boolean,
    val resolveFresh: Boolean,
    val autoPlay: Boolean
)

object AudioPlaybackLoadIntentPolicy {
    fun afterServiceDispatch(dispatched: AudioPlaybackLoadIntent): AudioPlaybackLoadIntent {
        return dispatched.copy(forceStart = false, resolveFresh = false, autoPlay = false)
    }
}

sealed interface AudioPlaybackCommandResult {
    data object Success : AudioPlaybackCommandResult

    data class Failure(val error: Exception) : AudioPlaybackCommandResult
}

/**
 * Last-resort boundary around synchronous Media3 setup calls. Media source
 * selection and player preparation can reject malformed or unsupported media
 * before Media3 has a chance to report the failure through Player.Listener.
 */
object AudioPlaybackCommandBoundary {
    fun execute(command: () -> Unit): AudioPlaybackCommandResult {
        return try {
            command()
            AudioPlaybackCommandResult.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AudioPlaybackCommandResult.Failure(error)
        }
    }
}

data class AudioAutoAdvanceCandidate(
    val routeId: String,
    val title: String
)

data class AudioAutoAdvanceTransition(
    val ticket: AudioAutoAdvanceTicket,
    val advanced: AudioAutoAdvanceAttemptResult.Advanced,
    val transitionFromChapterRouteId: String,
    val wasFinalized: Boolean = false
) {
    val stagedChapterRouteId: String
        get() = advanced.candidate.routeId

    val remainingCandidates: List<AudioAutoAdvanceCandidate>
        get() = advanced.remainingCandidates
}

sealed interface AudioAutoAdvanceAttemptResult {
    data class Advanced(
        val candidate: AudioAutoAdvanceCandidate,
        val request: MediaRequest,
        val attemptedCount: Int,
        val remainingCandidates: List<AudioAutoAdvanceCandidate>
    ) : AudioAutoAdvanceAttemptResult

    data class Exhausted(
        val attemptedCount: Int
    ) : AudioAutoAdvanceAttemptResult

    data class Failed(
        val reason: String,
        val attemptedCount: Int,
        val error: Throwable? = null
    ) : AudioAutoAdvanceAttemptResult
}

/**
 * Walks forward through a finite, de-duplicated set of episodes. A candidate is only staged after
 * its request has resolved and Media3 has accepted synchronous source setup; the service waits for
 * Media3 READY before making that stage persistent and user-visible outside the active player.
 */
class AudioAutoAdvanceCandidateStateMachine(
    private val maxCandidateAttempts: Int
) {
    init {
        require(maxCandidateAttempts > 0) { "maxCandidateAttempts must be positive" }
    }

    suspend fun advance(
        candidates: List<AudioAutoAdvanceCandidate>,
        resolveRequest: suspend (AudioAutoAdvanceCandidate) -> AudioRepositoryCallResult<MediaRequest?>,
        prepare: (AudioAutoAdvanceCandidate, MediaRequest) -> AudioPlaybackCommandResult,
        stage: (AudioAutoAdvanceCandidate, MediaRequest) -> Unit
    ): AudioAutoAdvanceAttemptResult {
        val boundedCandidates = candidates
            .asSequence()
            .filter { it.routeId.isNotBlank() }
            .distinctBy { it.routeId }
            .take(maxCandidateAttempts)
            .toList()
        var attemptedCount = 0
        for ((candidateIndex, candidate) in boundedCandidates.withIndex()) {
            attemptedCount += 1
            val request = when (val resolved = resolveRequest(candidate)) {
                AudioRepositoryCallResult.Busy -> {
                    return AudioAutoAdvanceAttemptResult.Failed(
                        reason = "repository_busy",
                        attemptedCount = attemptedCount
                    )
                }
                is AudioRepositoryCallResult.Failure -> {
                    val error = resolved.error
                    if (error is CancellationException) throw error
                    continue
                }
                is AudioRepositoryCallResult.Success -> resolved.value
            }
            if (request == null || request.url.isBlank()) continue
            when (prepare(candidate, request)) {
                is AudioPlaybackCommandResult.Failure -> continue
                AudioPlaybackCommandResult.Success -> {
                    stage(candidate, request)
                    return AudioAutoAdvanceAttemptResult.Advanced(
                        candidate = candidate,
                        request = request,
                        attemptedCount = attemptedCount,
                        remainingCandidates = boundedCandidates.drop(candidateIndex + 1)
                    )
                }
            }
        }
        return AudioAutoAdvanceAttemptResult.Exhausted(attemptedCount)
    }
}

data class AudioPlaybackFailure(
    val chapterRouteId: String,
    val reason: String,
    val errorType: String
)

/** Process-local handoff for setup failures which happen before Media3 can emit onPlayerError. */
object AudioPlaybackFailureStore {
    @Volatile
    private var latest: AudioPlaybackFailure? = null

    @Synchronized
    fun record(chapterRouteId: String, reason: String, error: Exception) {
        latest = AudioPlaybackFailure(
            chapterRouteId = chapterRouteId,
            reason = reason,
            errorType = error.javaClass.simpleName
        )
    }

    fun current(): AudioPlaybackFailure? = latest

    @Synchronized
    fun clear() {
        latest = null
    }
}
