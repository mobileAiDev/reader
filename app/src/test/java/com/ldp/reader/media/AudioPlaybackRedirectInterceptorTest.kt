package com.ldp.reader.media

import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioPlaybackRedirectInterceptorTest {
    @Test
    fun playbackRedirectKeepsSameOriginHeadersAndUsesFinalUrl() {
        val seen = mutableListOf<Request>()
        val initial = request("https://audio.example/start")

        val response = AudioPlaybackRedirectInterceptor().follow(initial) { outgoing ->
            seen += outgoing
            if (seen.size == 1) {
                response(outgoing, 302, mapOf("Location" to "/final.mp3"))
            } else {
                response(outgoing, 206)
            }
        }

        response.use {
            assertEquals("https://audio.example/final.mp3", response.request.url.toString())
        }
        assertEquals(2, seen.size)
        assertEquals("Bearer token", seen[1].header("Authorization"))
        assertEquals("session=secret", seen[1].header("Cookie"))
        assertEquals("https://audio.example/book", seen[1].header("Referer"))
        assertEquals("signed", seen[1].header("X-Signature"))
        assertEquals("bytes=128-", seen[1].header("Range"))
    }

    @Test
    fun playbackRedirectDropsSensitiveCrossOriginHeadersButKeepsTransportHeaders() {
        val seen = mutableListOf<Request>()
        val response = AudioPlaybackRedirectInterceptor().follow(
            request("https://audio.example/start")
        ) { outgoing ->
            seen += outgoing
            if (seen.size == 1) {
                response(
                    outgoing,
                    302,
                    mapOf("Location" to "https://cdn.example/final.mp3")
                )
            } else {
                response(outgoing, 206)
            }
        }

        response.close()
        assertEquals(2, seen.size)
        assertEquals("https://cdn.example/final.mp3", seen[1].url.toString())
        assertEquals("ReaderAgent/1.0", seen[1].header("User-Agent"))
        assertEquals("audio/*", seen[1].header("Accept"))
        assertEquals("bytes=128-", seen[1].header("Range"))
        assertNull(seen[1].header("Authorization"))
        assertNull(seen[1].header("Cookie"))
        assertNull(seen[1].header("Referer"))
        assertNull(seen[1].header("Origin"))
        assertNull(seen[1].header("X-Signature"))
    }

    @Test
    fun playbackRedirectRejectsHttpsDowngradeBeforeSecondRequest() {
        var calls = 0
        try {
            AudioPlaybackRedirectInterceptor().follow(request("https://audio.example/start")) { outgoing ->
                calls += 1
                response(
                    outgoing,
                    302,
                    mapOf("Location" to "http://audio.example/final.mp3")
                )
            }
            fail("Expected HTTPS downgrade to be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("redirect_rejected"))
        }
        assertEquals(1, calls)
    }

    @Test
    fun playbackRedirectStopsAtConfiguredLimit() {
        var calls = 0
        try {
            AudioPlaybackRedirectInterceptor(maxRedirects = 2).follow(
                request("https://audio.example/start")
            ) { outgoing ->
                calls += 1
                response(outgoing, 302, mapOf("Location" to "/hop-$calls.mp3"))
            }
            fail("Expected redirect limit failure")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("redirect_limit"))
        }
        assertEquals(3, calls)
    }

    private fun request(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer token")
            .header("Cookie", "session=secret")
            .header("Referer", "https://audio.example/book")
            .header("Origin", "https://audio.example")
            .header("X-Signature", "signed")
            .header("User-Agent", "ReaderAgent/1.0")
            .header("Accept", "audio/*")
            .header("Range", "bytes=128-")
            .build()
    }

    private fun response(
        request: Request,
        statusCode: Int,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .body(ByteArray(0).toResponseBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}
