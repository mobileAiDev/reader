package com.ldp.reader.audio

import androidx.media3.common.PlaybackException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackFailureCopyTest {
    @Test
    fun rejectedRequestsExplainThatTheContentIsInvalidOrUnsupported() {
        assertEquals(
            "音频文件无效或格式暂不支持，请重试其他章节或内容",
            AudioPlaybackFailureCopy.missingOrRejectedRequestMessage()
        )
    }

    @Test
    fun invalidContentAndUnsupportedFormatErrorsUseTheContentMessage() {
        listOf(
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ).forEach { errorCode ->
            assertEquals(
                "音频文件无效或格式暂不支持，请重试其他章节或内容",
                AudioPlaybackFailureCopy.messageForPlayerError(errorCode)
            )
        }
    }

    @Test
    fun onlyConnectionFailuresTellTheUserToCheckTheNetwork() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ).forEach { errorCode ->
            assertEquals(
                "请检查网络后重试",
                AudioPlaybackFailureCopy.messageForPlayerError(errorCode)
            )
        }

        assertEquals(
            "暂时无法播放，请稍后重试",
            AudioPlaybackFailureCopy.messageForPlayerError(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            )
        )
    }

    @Test
    fun recordedSetupFailuresUseTheirTypedCauseWithoutExposingTechnicalDetails() {
        assertEquals(
            "请检查网络后重试",
            AudioPlaybackFailureCopy.messageForRecordedFailure(
                AudioPlaybackFailure("episode-1", "media_setup_failed", "UnknownHostException")
            )
        )
        assertEquals(
            "音频文件无效或格式暂不支持，请重试其他章节或内容",
            AudioPlaybackFailureCopy.messageForRecordedFailure(
                AudioPlaybackFailure("episode-1", "media_setup_failed", "IllegalArgumentException")
            )
        )
        assertEquals(
            "暂时无法播放，请稍后重试",
            AudioPlaybackFailureCopy.messageForRecordedFailure(
                AudioPlaybackFailure("episode-1", "media_setup_failed", "IllegalStateException")
            )
        )
    }

    @Test
    fun repositoryNetworkFailuresAreNotMislabeledAsInvalidAudio() {
        assertEquals(
            "请检查网络后重试",
            AudioPlaybackFailureCopy.messageForRepositoryFailure(UnknownHostException("offline"))
        )
    }
}
