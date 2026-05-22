package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.HttpFetcher
import com.ldp.reader.sourceengine.legado.HttpRequest
import com.ldp.reader.sourceengine.legado.HttpResponse
import kotlinx.coroutines.asContextElement
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

internal data class SourceRequestScope(
    val id: Long,
    val name: String,
    val parent: SourceRequestScope? = null
) {
    fun isSameOrChildOf(scope: SourceRequestScope): Boolean {
        var current: SourceRequestScope? = this
        while (current != null) {
            if (current.id == scope.id) return true
            current = current.parent
        }
        return false
    }
}

internal class OkHttpSourceEngineFetcher(
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int
) : HttpFetcher {
    private val requestScope = ThreadLocal<SourceRequestScope?>()
    private val activeCalls = ConcurrentHashMap<SourceRequestScope, MutableSet<Call>>()
    private val dispatcher = Dispatcher().apply {
        maxRequests = MAX_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }
    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((connectTimeoutMillis + readTimeoutMillis).toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    fun requestScopeContext(scope: SourceRequestScope): CoroutineContext {
        return requestScope.asContextElement(scope)
    }

    fun currentRequestScope(): SourceRequestScope? {
        return requestScope.get()
    }

    fun cancel(scope: SourceRequestScope) {
        activeCalls.keys
            .filter { activeScope -> activeScope.isSameOrChildOf(scope) }
            .forEach { activeScope ->
                activeCalls.remove(activeScope)?.forEach { call -> call.cancel() }
            }
    }

    override fun fetch(request: HttpRequest): HttpResponse {
        val builder = Request.Builder()
            .url(request.url)
            .header(
                "User-Agent",
                request.headers["User-Agent"]
                    ?: request.headers["user-agent"]
                    ?: DEFAULT_USER_AGENT
            )
            .header("Accept", "*/*")
        request.headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                builder.header(name, value)
            }
        }

        val method = request.method.uppercase(Locale.ROOT)
        val body = request.body?.toRequestBody(
            "application/x-www-form-urlencoded; charset=${request.charset ?: "UTF-8"}".toMediaType()
        )
        if (body == null) {
            builder.method(method, null)
        } else {
            builder.method(method, body)
        }

        val call = client.newCall(builder.build())
        val scope = requestScope.get()
        register(scope, call)
        try {
            call.execute().use { response ->
                val responseBody = response.body ?: error("empty response body ${request.url}")
                val bytes = responseBody.bytes()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} ${response.request.url}")
                }
                val charset = request.charset
                    ?: responseBody.contentType()?.charset()?.name()
                    ?: htmlCharset(bytes)
                    ?: "UTF-8"
                return HttpResponse(response.request.url.toString(), String(bytes, Charset.forName(charset)))
            }
        } finally {
            unregister(scope, call)
        }
    }

    private fun register(scope: SourceRequestScope?, call: Call) {
        if (scope == null) return
        activeCalls.computeIfAbsent(scope) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(call)
    }

    private fun unregister(scope: SourceRequestScope?, call: Call) {
        if (scope == null) return
        val calls = activeCalls[scope] ?: return
        calls.remove(call)
        if (calls.isEmpty()) {
            activeCalls.remove(scope, calls)
        }
    }

    private fun htmlCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return Regex("""(?i)<meta[^>]+charset=["']?\s*([A-Za-z0-9_\-]+)""")
            .find(head)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
    }

    private companion object {
        private const val MAX_REQUESTS = 64
        private const val MAX_REQUESTS_PER_HOST = 16
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
    }
}
