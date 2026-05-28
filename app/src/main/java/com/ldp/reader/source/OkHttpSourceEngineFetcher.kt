package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.HttpFetcher
import com.ldp.reader.sourceengine.legado.HttpRequest
import com.ldp.reader.sourceengine.legado.HttpResponse
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

enum class SourceRequestPriority {
    FOREGROUND,
    BACKGROUND,
    BACKGROUND_LOW
}

internal data class SourceRequestScope(
    val id: Long,
    val name: String,
    val priority: SourceRequestPriority = SourceRequestPriority.FOREGROUND,
    val parent: SourceRequestScope? = null
) {
    private val cancelled = AtomicBoolean(false)

    fun isSameOrChildOf(scope: SourceRequestScope): Boolean {
        var current: SourceRequestScope? = this
        while (current != null) {
            if (current.id == scope.id) return true
            current = current.parent
        }
        return false
    }

    fun markCancelled() {
        cancelled.set(true)
    }

    fun isCancelledInChain(): Boolean {
        var current: SourceRequestScope? = this
        while (current != null) {
            if (current.cancelled.get()) return true
            current = current.parent
        }
        return false
    }
}

internal object SourceNetworkPriorityGate {
    private const val BACKGROUND_POLL_INTERVAL_MS = 200L
    private const val LOW_PRIORITY_MAX_CONCURRENT_REQUESTS = 8
    private val foregroundRequests = AtomicInteger()
    private val backgroundRequests = AtomicInteger()
    private val lowPriorityPermits = Semaphore(LOW_PRIORITY_MAX_CONCURRENT_REQUESTS, true)
    private val backgroundPreemptors = CopyOnWriteArrayList<(SourceRequestPriority) -> Int>()

    fun enterForeground() {
        foregroundRequests.incrementAndGet()
    }

    fun exitForeground() {
        foregroundRequests.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }

    fun foregroundCount(): Int {
        return foregroundRequests.get()
    }

    fun backgroundCount(): Int {
        return backgroundRequests.get()
    }

    fun registerBackgroundPreemptor(preemptor: (SourceRequestPriority) -> Int) {
        backgroundPreemptors.add(preemptor)
    }

    fun preemptBackgroundRequests(): Int {
        return backgroundPreemptors.sumOf { preemptor -> preemptor(SourceRequestPriority.FOREGROUND) }
    }

    fun enterBackground(): Int {
        backgroundRequests.incrementAndGet()
        return backgroundPreemptors.sumOf { preemptor -> preemptor(SourceRequestPriority.BACKGROUND) }
    }

    fun exitBackground() {
        backgroundRequests.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }

    fun acquireLowPrioritySlot(priority: SourceRequestPriority = SourceRequestPriority.BACKGROUND) {
        while (true) {
            waitForHigherPriorityIdleBlocking(priority)
            lowPriorityPermits.acquire()
            if (higherPriorityCount(priority) == 0) return
            lowPriorityPermits.release()
            Thread.sleep(BACKGROUND_POLL_INTERVAL_MS)
        }
    }

    fun releaseLowPrioritySlot() {
        lowPriorityPermits.release()
    }

    fun waitForForegroundIdleBlocking() {
        waitForHigherPriorityIdleBlocking(SourceRequestPriority.BACKGROUND)
    }

    fun waitForHigherPriorityIdleBlocking(priority: SourceRequestPriority) {
        while (higherPriorityCount(priority) > 0) {
            Thread.sleep(BACKGROUND_POLL_INTERVAL_MS)
        }
    }

    fun resetForTest() {
        while (foregroundRequests.get() > 0) {
            foregroundRequests.decrementAndGet()
        }
        while (backgroundRequests.get() > 0) {
            backgroundRequests.decrementAndGet()
        }
        while (lowPriorityPermits.availablePermits() < LOW_PRIORITY_MAX_CONCURRENT_REQUESTS) {
            lowPriorityPermits.release()
        }
        backgroundPreemptors.clear()
    }

    fun higherPriorityCount(priority: SourceRequestPriority): Int {
        return when (priority) {
            SourceRequestPriority.FOREGROUND -> 0
            SourceRequestPriority.BACKGROUND -> foregroundRequests.get()
            SourceRequestPriority.BACKGROUND_LOW -> foregroundRequests.get() + backgroundRequests.get()
        }
    }
}

internal object SourceNetworkForegroundPriority {
    suspend fun <T> entered(operation: String, key: String?, block: suspend () -> T): T {
        enter(operation, key)
        return try {
            block()
        } finally {
            exit()
        }
    }

    fun enter(operation: String, key: String?): Int {
        SourceNetworkPriorityGate.enterForeground()
        val cancelled = SourceNetworkPriorityGate.preemptBackgroundRequests()
        if (cancelled <= 0) return cancelled
        runCatching {
            AiBridgeTrace.event(
                "source_background_network_preempted",
                key.orEmpty(),
                AiBridgeTrace.fields(
                    "foregroundOperation" to operation,
                    "cancelled" to cancelled,
                    "foreground" to SourceNetworkPriorityGate.foregroundCount()
                )
            )
        }
        return cancelled
    }

    fun exit() {
        SourceNetworkPriorityGate.exitForeground()
    }
}

internal object SourceNetworkDispatchers {
    private const val FOREGROUND_THREADS = 16
    private const val BACKGROUND_THREADS = 8
    private const val BACKGROUND_LOW_THREADS = 4
    private val threadIds = AtomicInteger()
    val foreground: CoroutineDispatcher = Executors.newFixedThreadPool(
        FOREGROUND_THREADS,
        namedThreadFactory("source-foreground")
    ).asCoroutineDispatcher()
    val background: CoroutineDispatcher = Executors.newFixedThreadPool(
        BACKGROUND_THREADS,
        namedThreadFactory("source-background")
    ).asCoroutineDispatcher()
    val backgroundLow: CoroutineDispatcher = Executors.newFixedThreadPool(
        BACKGROUND_LOW_THREADS,
        namedThreadFactory("source-background-low")
    ).asCoroutineDispatcher()

    fun forScope(scope: SourceRequestScope?): CoroutineDispatcher {
        return when (scope?.priority) {
            SourceRequestPriority.BACKGROUND -> background
            SourceRequestPriority.BACKGROUND_LOW -> backgroundLow
            else -> foreground
        }
    }

    private fun namedThreadFactory(prefix: String): ThreadFactory {
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${threadIds.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
}

internal class OkHttpSourceEngineFetcher(
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
    private val callFactory: ((Request) -> Call)? = null
) : HttpFetcher {
    private val requestScope = ThreadLocal<SourceRequestScope?>()
    private val activeCalls = ConcurrentHashMap<SourceRequestScope, MutableSet<Call>>()
    private val preemptedCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
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

    init {
        SourceNetworkPriorityGate.registerBackgroundPreemptor(::cancelCallsBelowPriority)
    }

    fun requestScopeContext(scope: SourceRequestScope): CoroutineContext {
        return requestScope.asContextElement(scope)
    }

    fun currentRequestScope(): SourceRequestScope? {
        return requestScope.get()
    }

    fun cancel(scope: SourceRequestScope) {
        scope.markCancelled()
        activeCalls.keys
            .filter { activeScope -> activeScope.isSameOrChildOf(scope) }
            .forEach { activeScope ->
                activeScope.markCancelled()
                activeCalls.remove(activeScope)?.forEach { call -> call.cancel() }
            }
    }

    fun cancelBackgroundCalls(): Int {
        return cancelCallsBelowPriority(SourceRequestPriority.FOREGROUND)
    }

    private fun cancelCallsBelowPriority(priority: SourceRequestPriority): Int {
        var cancelled = 0
        activeCalls.keys
            .filter { activeScope -> activeScope.priority.isLowerThan(priority) }
            .forEach { activeScope ->
                activeCalls[activeScope]?.forEach { call ->
                    if (preemptedCalls.add(call)) {
                        call.cancel()
                        cancelled += 1
                    }
                }
            }
        return cancelled
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

        val okHttpRequest = builder.build()
        while (true) {
            val scope = requestScope.get()
            val priority = scope?.priority ?: SourceRequestPriority.FOREGROUND
            val lowPriority = priority != SourceRequestPriority.FOREGROUND
            var lowPrioritySlot = false
            var backgroundEntered = false
            if (priority == SourceRequestPriority.BACKGROUND) {
                backgroundEntered = true
                val cancelled = SourceNetworkPriorityGate.enterBackground()
                if (cancelled > 0) {
                    runCatching {
                        AiBridgeTrace.event(
                            "source_lowest_background_network_preempted",
                            scope?.name.orEmpty(),
                            AiBridgeTrace.fields(
                                "backgroundOperation" to scope?.name.orEmpty(),
                                "cancelled" to cancelled,
                                "background" to SourceNetworkPriorityGate.backgroundCount()
                            )
                        )
                    }
                }
            }
            if (lowPriority) {
                SourceNetworkPriorityGate.acquireLowPrioritySlot(priority)
                lowPrioritySlot = true
            }
            val call = callFactory?.invoke(okHttpRequest) ?: client.newCall(okHttpRequest)
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
            } catch (error: IOException) {
                if (lowPriority && preemptedCalls.remove(call)) {
                    SourceNetworkPriorityGate.waitForHigherPriorityIdleBlocking(priority)
                    if (scope?.isCancelledInChain() != true) {
                        continue
                    }
                }
                throw error
            } finally {
                unregister(scope, call)
                preemptedCalls.remove(call)
                if (lowPrioritySlot) {
                    SourceNetworkPriorityGate.releaseLowPrioritySlot()
                }
                if (backgroundEntered) {
                    SourceNetworkPriorityGate.exitBackground()
                }
            }
        }
    }

    private fun SourceRequestPriority.isLowerThan(priority: SourceRequestPriority): Boolean {
        return ordinal > priority.ordinal
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
