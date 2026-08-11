package com.ldp.reader.audio

import androidx.media3.common.PlaybackException

internal object AudioPlaybackFailureCopy {
    private const val INVALID_OR_UNSUPPORTED =
        "音频文件无效或格式暂不支持，请重试其他章节或内容"
    private const val NETWORK = "请检查网络后重试"
    private const val UNKNOWN = "暂时无法播放，请稍后重试"

    fun missingOrRejectedRequestMessage(): String = INVALID_OR_UNSUPPORTED

    fun unknownFailureMessage(): String = UNKNOWN

    fun messageForRepositoryFailure(error: Throwable): String {
        return messageForRecordedFailure(
            AudioPlaybackFailure(
                chapterRouteId = "",
                reason = "repository_failure",
                errorType = error.javaClass.simpleName
            )
        )
    }

    fun messageForPlayerError(errorCode: Int): String {
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> NETWORK

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> INVALID_OR_UNSUPPORTED

            else -> UNKNOWN
        }
    }

    fun messageForRecordedFailure(failure: AudioPlaybackFailure): String {
        return when (failure.errorType) {
            "UnknownHostException",
            "SocketTimeoutException",
            "ConnectException",
            "NoRouteToHostException" -> NETWORK

            "IllegalArgumentException",
            "MalformedURLException",
            "ParserException",
            "UnrecognizedInputFormatException",
            "UnsupportedOperationException" -> INVALID_OR_UNSUPPORTED

            else -> UNKNOWN
        }
    }
}
