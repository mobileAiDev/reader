package com.ldp.reader.ui.activity

import com.ldp.reader.audio.AudioRepositoryCallResult
import com.ldp.reader.audio.AudioRepositoryCallRunner

internal sealed interface MediaDetailCallOutcome<out T> {
    data class Succeeded<T>(val value: T) : MediaDetailCallOutcome<T>

    data class Failed(val error: Throwable?) : MediaDetailCallOutcome<Nothing>
}

internal suspend fun <T> runMediaDetailCallSafely(
    load: () -> T
): MediaDetailCallOutcome<T> {
    return when (val result = AudioRepositoryCallRunner.runMediaDetailResult(load)) {
        is AudioRepositoryCallResult.Success -> MediaDetailCallOutcome.Succeeded(result.value)
        is AudioRepositoryCallResult.Failure -> MediaDetailCallOutcome.Failed(result.error)
        AudioRepositoryCallResult.Busy -> MediaDetailCallOutcome.Failed(error = null)
    }
}
