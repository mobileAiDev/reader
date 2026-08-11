package com.ldp.reader.media

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackProbeTest {
    @Test
    fun initialProbeHeadersDropTransportControlledHeaders() {
        val headers = AudioPlaybackProbe.initialRequestHeaders(
            mapOf(
                "Authorization" to "Bearer token",
                "Cookie" to "session=secret",
                "Referer" to "https://audio.example/book",
                "X-Signature" to "signed",
                "Host" to "attacker.example",
                "Connection" to "keep-alive",
                "Content-Length" to "999",
                "Range" to "bytes=100-200",
                "Proxy-Authorization" to "Basic secret",
                "User-Agent" to "ReaderAgent/1.0"
            )
        )

        assertEquals("Bearer token", headers["Authorization"])
        assertEquals("session=secret", headers["Cookie"])
        assertEquals("https://audio.example/book", headers["Referer"])
        assertEquals("signed", headers["X-Signature"])
        assertEquals("ReaderAgent/1.0", headers["User-Agent"])
        assertNull(headers["Host"])
        assertNull(headers["Connection"])
        assertNull(headers["Content-Length"])
        assertNull(headers["Range"])
        assertNull(headers["Proxy-Authorization"])
    }

    @Test
    fun sameOriginRedirectKeepsFilteredOriginHeaders() {
        val headers = AudioPlaybackProbe.initialRequestHeaders(
            mapOf(
                "Authorization" to "Bearer token",
                "Cookie" to "session=secret",
                "Referer" to "https://audio.example/book",
                "X-Signature" to "signed",
                "User-Agent" to "ReaderAgent/1.0"
            )
        )

        val redirected = AudioPlaybackProbe.headersForRedirect(
            headers,
            "https://audio.example/start".toHttpUrl(),
            "https://audio.example:443/cdn/final.mp3".toHttpUrl()
        )

        assertEquals(headers, redirected)
    }

    @Test
    fun crossOriginRedirectDropsOriginHeaders() {
        val headers = AudioPlaybackProbe.initialRequestHeaders(
            mapOf(
                "Authorization" to "Bearer token",
                "Cookie" to "session=secret",
                "Referer" to "https://audio.example/book?token=secret",
                "X-Signature" to "signed",
                "User-Agent" to "ReaderAgent/1.0"
            )
        )

        val redirected = AudioPlaybackProbe.headersForRedirect(
            headers,
            "https://audio.example/start".toHttpUrl(),
            "https://cdn.example/final.mp3".toHttpUrl()
        )

        assertEquals(mapOf("User-Agent" to "ReaderAgent/1.0"), redirected)
    }

    @Test
    fun sameOriginAndCrossOriginHttpsRedirectsAreAllowed() {
        assertTrue(
            AudioPlaybackProbe.isRedirectAllowed(
                "https://audio.example/start".toHttpUrl(),
                "https://audio.example/final.mp3".toHttpUrl()
            )
        )
        assertTrue(
            AudioPlaybackProbe.isRedirectAllowed(
                "https://audio.example/start".toHttpUrl(),
                "https://cdn.example/final.mp3".toHttpUrl()
            )
        )
    }

    @Test
    fun httpsToHttpRedirectIsRejected() {
        assertFalse(
            AudioPlaybackProbe.isRedirectAllowed(
                "https://audio.example/start".toHttpUrl(),
                "http://audio.example/final.mp3".toHttpUrl()
            )
        )
    }

    @Test
    fun probeReturnsFinalSameOriginRequestWithOriginalSafeHeaders() {
        val seen = mutableListOf<Request>()
        val originalHeaders = mapOf(
            "Authorization" to "Bearer token",
            "Cookie" to "session=secret",
            "Referer" to "https://audio.example/book",
            "X-Signature" to "signed",
            "User-Agent" to "ReaderAgent/1.0"
        )

        val resolved = AudioPlaybackProbe.resolvePlayableRequest(
            MediaRequest("https://audio.example/start", originalHeaders)
        ) { outgoing ->
            seen += outgoing
            if (seen.size == 1) {
                response(outgoing, 302, mapOf("Location" to "/final.mp3"), ByteArray(0))
            } else {
                response(
                    outgoing,
                    206,
                    mapOf(
                        "Content-Type" to "audio/mpeg",
                        "Content-Range" to "bytes 0-7/128",
                        "Content-Length" to "8"
                    ),
                    ByteArray(8) { 0x2A }
                )
            }
        }

        assertEquals("https://audio.example/final.mp3", resolved?.url)
        assertEquals(originalHeaders, resolved?.headers)
        assertEquals(2, seen.size)
        assertEquals("bytes=0-1023", seen[1].header("Range"))
        assertEquals("Bearer token", seen[1].header("Authorization"))
        assertEquals("session=secret", seen[1].header("Cookie"))
    }

    @Test
    fun probeReturnsFinalCrossOriginRequestWithoutSensitiveHeaders() {
        val seen = mutableListOf<Request>()
        val resolved = AudioPlaybackProbe.resolvePlayableRequest(
            MediaRequest(
                "https://audio.example/start",
                mapOf(
                    "Authorization" to "Bearer token",
                    "Cookie" to "session=secret",
                    "Referer" to "https://audio.example/book",
                    "Origin" to "https://audio.example",
                    "X-Signature" to "signed",
                    "User-Agent" to "ReaderAgent/1.0"
                )
            )
        ) { outgoing ->
            seen += outgoing
            if (seen.size == 1) {
                response(
                    outgoing,
                    302,
                    mapOf("Location" to "https://cdn.example/final.mp3"),
                    ByteArray(0)
                )
            } else {
                response(
                    outgoing,
                    206,
                    mapOf(
                        "Content-Type" to "audio/mpeg",
                        "Content-Range" to "bytes 0-7/128",
                        "Content-Length" to "8"
                    ),
                    ByteArray(8) { 0x2A }
                )
            }
        }

        assertEquals("https://cdn.example/final.mp3", resolved?.url)
        assertEquals(mapOf("User-Agent" to "ReaderAgent/1.0"), resolved?.headers)
        assertEquals(2, seen.size)
        assertEquals("ReaderAgent/1.0", seen[1].header("User-Agent"))
        assertEquals("bytes=0-1023", seen[1].header("Range"))
        assertNull(seen[1].header("Authorization"))
        assertNull(seen[1].header("Cookie"))
        assertNull(seen[1].header("Referer"))
        assertNull(seen[1].header("Origin"))
        assertNull(seen[1].header("X-Signature"))
    }

    @Test
    fun probeRejectsDowngradeWithoutRequestingHttpTarget() {
        var calls = 0
        val resolved = AudioPlaybackProbe.resolvePlayableRequest(
            MediaRequest("https://audio.example/start")
        ) { outgoing ->
            calls += 1
            response(
                outgoing,
                302,
                mapOf("Location" to "http://audio.example/final.mp3"),
                ByteArray(0)
            )
        }

        assertNull(resolved)
        assertEquals(1, calls)
    }

    @Test
    fun probeStopsAfterBoundedRedirects() {
        var calls = 0
        val resolved = AudioPlaybackProbe.resolvePlayableRequest(
            MediaRequest("https://audio.example/start")
        ) { outgoing ->
            calls += 1
            response(
                outgoing,
                302,
                mapOf("Location" to "/hop-$calls.mp3"),
                ByteArray(0)
            )
        }

        assertNull(resolved)
        assertEquals(AudioPlaybackRedirectPolicy.MAX_REDIRECTS + 1, calls)
    }

    @Test
    fun finalResponseUrlExtensionDeterminesGenericTypePlayback() {
        val request = MediaRequest("https://audio.example/resolve")
        val response = response(
            request = request,
            responseUrl = "https://cdn.example/final.mp3",
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to "64"
            ),
            body = ByteArray(64) { 0x2A }
        )

        response.use {
            assertTrue(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun initialUrlExtensionDoesNotClassifyGenericFinalResponseAsAudio() {
        val request = MediaRequest("https://audio.example/resolve.mp3")
        val response = response(
            request = request,
            responseUrl = "https://login.example/session",
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to "64"
            ),
            body = ByteArray(64) { 0x2A }
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun shortAudioResponseIsPlayable() {
        val request = MediaRequest("https://audio.example/stream")
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "audio/mpeg",
                "Content-Length" to "128"
            ),
            body = ByteArray(128) { 0x2A }
        )

        response.use {
            assertTrue(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun partialHtmlResponseDisguisedAsAudioIsRejected() {
        val request = MediaRequest("https://audio.example/clip.mp3")
        val body = "<html>not audio</html>".toByteArray()
        val response = response(
            request = request,
            statusCode = 206,
            headers = mapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Range" to "bytes 0-${body.lastIndex}/${body.size}",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun emptyAudioResponseWithoutLengthHeaderIsRejected() {
        val request = MediaRequest("https://audio.example/empty.mp3")
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf("Content-Type" to "audio/mpeg"),
            body = ByteArray(0)
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun shortResponseWithSupportedAudioExtensionIsPlayable() {
        val request = MediaRequest("https://audio.example/clip.flac")
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to "64"
            ),
            body = ByteArray(64) { 0x2A }
        )

        response.use {
            assertTrue(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun partialShortAudioUsesContentRangeTotalAndIsPlayable() {
        val request = MediaRequest("https://audio.example/stream")
        val response = response(
            request = request,
            statusCode = 206,
            headers = mapOf(
                "Content-Type" to "audio/aac",
                "Content-Range" to "bytes 0-15/128",
                "Content-Length" to "16"
            ),
            body = ByteArray(16) { 0x2A }
        )

        response.use {
            assertTrue(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun zeroContentRangeTotalIsRejected() {
        val request = MediaRequest("https://audio.example/stream.mp3")
        val response = response(
            request = request,
            statusCode = 206,
            headers = mapOf(
                "Content-Type" to "audio/mpeg",
                "Content-Range" to "bytes 0-0/0",
                "Content-Length" to "1"
            ),
            body = byteArrayOf(0x2A)
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun jsonResponseDisguisedAsAudioIsRejected() {
        val request = MediaRequest("https://audio.example/stream.mp3")
        val body = "{\"error\":\"login required\"}".toByteArray()
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/problem+json; charset=utf-8",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun errorStatusIsRejectedEvenWithAudioPayload() {
        val request = MediaRequest("https://audio.example/missing.mp3")
        val response = response(
            request = request,
            statusCode = 404,
            headers = mapOf(
                "Content-Type" to "audio/mpeg",
                "Content-Length" to "8"
            ),
            body = ByteArray(8) { 0x2A }
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun partialBinaryResponseWithoutAudioEvidenceIsRejected() {
        val request = MediaRequest("https://audio.example/download")
        val response = response(
            request = request,
            statusCode = 206,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Range" to "bytes 0-7/8",
                "Content-Length" to "8"
            ),
            body = ByteArray(8) { 0x2A }
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun rangeIgnoredByServerStillAcceptsShortAudio() {
        val request = MediaRequest("https://audio.example/stream.m4a")
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to "32"
            ),
            body = ByteArray(32) { 0x2A }
        )

        response.use {
            assertTrue(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun shortHlsPlaylistIsPlayable() {
        val request = MediaRequest("https://audio.example/live.m3u8")
        val body = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/vnd.apple.mpegurl",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        response.use {
            assertTrue(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun htmlHlsPlaylistIsRejected() {
        val request = MediaRequest("https://audio.example/live.m3u8")
        val body = "<html>login required</html>".toByteArray()
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "text/html",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun htmlBodyWithGenericTypeAndAudioExtensionIsRejected() {
        val request = MediaRequest("https://audio.example/stream.mp3")
        val body = "<!doctype html><html>login required</html>".toByteArray()
        val response = response(
            request = request,
            statusCode = 200,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun jsonBodyWithGenericTypeAndAudioExtensionIsRejected() {
        val request = MediaRequest("https://audio.example/stream.aac")
        val body = "[ { \"error\": \"expired\" } ]".toByteArray()
        val response = response(
            request = request,
            statusCode = 206,
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Range" to "bytes 0-${body.lastIndex}/${body.size}",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    @Test
    fun emptyPartialAudioResponseIsRejected() {
        val request = MediaRequest("https://audio.example/stream.mp3")
        val response = response(
            request = request,
            statusCode = 206,
            headers = mapOf(
                "Content-Type" to "audio/mpeg",
                "Content-Range" to "bytes 0-0/128",
                "Content-Length" to "0"
            ),
            body = ByteArray(0)
        )

        response.use {
            assertFalse(AudioPlaybackProbe.isPlayable(request, it))
        }
    }

    private fun response(
        request: MediaRequest,
        responseUrl: String = request.url,
        statusCode: Int,
        headers: Map<String, String>,
        body: ByteArray
    ): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url(responseUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .body(body.toResponseBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun response(
        request: Request,
        statusCode: Int,
        headers: Map<String, String>,
        body: ByteArray
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .body(body.toResponseBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}
