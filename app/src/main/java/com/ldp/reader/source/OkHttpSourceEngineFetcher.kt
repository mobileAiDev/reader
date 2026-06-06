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
import java.util.concurrent.atomic.AtomicLong
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

    fun rootId(): Long {
        var current = this
        while (current.parent != null) {
            current = current.parent!!
        }
        return current.id
    }

    fun rootName(): String {
        var current = this
        while (current.parent != null) {
            current = current.parent!!
        }
        return current.name
    }

    fun tracePath(): String {
        val parts = ArrayDeque<String>()
        var current: SourceRequestScope? = this
        while (current != null && parts.size < 4) {
            parts.addFirst(current.name)
            current = current.parent
        }
        return parts.joinToString(">")
    }
}

internal data class SourceNetworkPermitWait(
    val scopeWaitMs: Long,
    val globalWaitMs: Long,
    val totalWaitMs: Long,
    val scopeAvailableBefore: Int?,
    val globalAvailableBefore: Int,
    val globalAvailableAfter: Int
)

private fun String.traceToken(limit: Int = 160): String {
    return replace(Regex("""[\s=:/\\#?&]+"""), "_").take(limit)
}

internal data class SourceNetworkTimingSnapshot(
    val rootName: String,
    val startedAtMs: Long,
    val requestCount: Int,
    val errorCount: Int,
    val layerSummary: String,
    val slowSummary: String
)

internal object SourceNetworkTimingTracer {
    private const val SEARCH_SLOW_HEADERS_MS = 4_000L
    private const val SEARCH_SLOW_TOTAL_MS = 8_000L
    private const val VALIDATION_SLOW_TOTAL_MS = 1_500L
    private const val VALIDATION_SLOW_QUEUE_MS = 500L
    private const val SLOW_LOG_CAP_PER_LAYER = 12
    private const val TAG_NET_SUMMARY = "search.net.summary"
    private const val TAG_NET_SLOW = "search.net.slow"

    private val roots = ConcurrentHashMap<Long, SourceNetworkTimingRoot>()
    private val slowLogCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun record(
        scope: SourceRequestScope?,
        priority: SourceRequestPriority,
        method: String,
        url: String,
        statusCode: Int,
        responseBytes: Int,
        lowPriorityWaitMs: Long,
        permitWait: SourceNetworkPermitWait?,
        headersMs: Long,
        bodyMs: Long,
        decodeMs: Long,
        totalMs: Long,
        errorName: String?
    ) {
        if (scope == null) return
        val rootId = scope.rootId()
        val rootName = scope.rootName()
        val scopePath = scope.tracePath()
        val layer = layerFor(rootName, scopePath)
        val queueMs = lowPriorityWaitMs + (permitWait?.totalWaitMs ?: 0L)
        val urlParts = networkUrlParts(url)
        val root = roots.computeIfAbsent(rootId) { SourceNetworkTimingRoot(rootName) }
        val slow = shouldTraceSlow(layer, queueMs, headersMs, totalMs, errorName)
        root.record(
            layer = layer,
            host = urlParts.first,
            queueMs = queueMs,
            scopeWaitMs = permitWait?.scopeWaitMs ?: 0L,
            globalWaitMs = permitWait?.globalWaitMs ?: 0L,
            headersMs = headersMs,
            bodyMs = bodyMs,
            decodeMs = decodeMs,
            totalMs = totalMs,
            bytes = responseBytes,
            error = errorName,
            slow = slow
        )
        if (!slow) return
        val emitted = slowLogCounts.computeIfAbsent("$rootId:$layer") { AtomicInteger() }.incrementAndGet()
        if (emitted > SLOW_LOG_CAP_PER_LAYER) return
        AiBridgeTrace.event(
            "source_search_net_slow",
            urlParts.first.traceToken(),
            "tag_${TAG_NET_SLOW}" +
                "_layer_${layer}" +
                "_method_${method.traceToken(12)}" +
                "_status_${statusCode}" +
                "_priority_${priority.name}" +
                "_root_${rootName.traceToken(120)}" +
                "_scope_${scopePath.traceToken(220)}" +
                "_path_${urlParts.second.traceToken(100)}" +
                "_queueMs_${queueMs}" +
                "_scopeWaitMs_${permitWait?.scopeWaitMs ?: 0}" +
                "_globalWaitMs_${permitWait?.globalWaitMs ?: 0}" +
                "_headersMs_${headersMs}" +
                "_bodyMs_${bodyMs}" +
                "_decodeMs_${decodeMs}" +
                "_bytes_${responseBytes}" +
                "_totalMs_${totalMs}" +
                "_error_${errorName.orEmpty().traceToken(80)}" +
                "_capIndex_${emitted}"
        )
    }

    fun traceSummary(keyword: String, stage: String, scope: SourceRequestScope?) {
        val snapshot = snapshot(scope) ?: return
        AiBridgeTrace.event(
            "source_search_net_summary",
            keyword,
            "tag_${TAG_NET_SUMMARY}" +
                "_stage_${stage.traceToken(60)}" +
                "_root_${snapshot.rootName.traceToken(120)}" +
                "_ageMs_${System.currentTimeMillis() - snapshot.startedAtMs}" +
                "_requests_${snapshot.requestCount}" +
                "_errors_${snapshot.errorCount}" +
                "_layers_${snapshot.layerSummary.traceToken(900)}" +
                "_slow_${snapshot.slowSummary.traceToken(360)}"
        )
    }

    fun clear(scope: SourceRequestScope?) {
        val rootId = scope?.rootId() ?: return
        roots.remove(rootId)
        slowLogCounts.keys.removeIf { key -> key.startsWith("$rootId:") }
    }

    private fun snapshot(scope: SourceRequestScope?): SourceNetworkTimingSnapshot? {
        val rootId = scope?.rootId() ?: return null
        return roots[rootId]?.snapshot()
    }

    private fun shouldTraceSlow(
        layer: String,
        queueMs: Long,
        headersMs: Long,
        totalMs: Long,
        errorName: String?
    ): Boolean {
        if (errorName != null && !errorName.startsWith("SocketException")) return true
        return when (layer) {
            "validation" -> queueMs >= VALIDATION_SLOW_QUEUE_MS || totalMs >= VALIDATION_SLOW_TOTAL_MS || errorName != null
            "search" -> headersMs >= SEARCH_SLOW_HEADERS_MS || totalMs >= SEARCH_SLOW_TOTAL_MS || errorName?.contains("Timeout") == true
            else -> headersMs >= SEARCH_SLOW_HEADERS_MS || totalMs >= SEARCH_SLOW_TOTAL_MS || errorName?.contains("Timeout") == true
        }
    }

    private fun layerFor(rootName: String, scopePath: String): String {
        if (scopePath.contains("searchValidationGroup")) return "validation"
        if (rootName.startsWith("searchProgressive") || rootName.startsWith("search:")) return "search"
        if (rootName.startsWith("contentTier")) return "contentTier"
        return rootName.substringBefore(':').ifBlank { "unknown" }.traceToken(40)
    }

    private fun networkUrlParts(url: String): Pair<String, String> {
        val rest = url.substringAfter("://", url)
        val host = rest.substringBefore('/').take(80).ifBlank { "unknown" }
        val pathTail = rest.substringAfter('/', "").substringBefore('?')
        val path = if (pathTail.isBlank()) "/" else "/${pathTail.take(80)}"
        return host to path
    }
}

private class SourceNetworkTimingRoot(private val rootName: String) {
    private val startedAt = System.currentTimeMillis()
    private val layers = linkedMapOf<String, SourceNetworkTimingLayer>()

    @Synchronized
    fun record(
        layer: String,
        host: String,
        queueMs: Long,
        scopeWaitMs: Long,
        globalWaitMs: Long,
        headersMs: Long,
        bodyMs: Long,
        decodeMs: Long,
        totalMs: Long,
        bytes: Int,
        error: String?,
        slow: Boolean
    ) {
        layers.getOrPut(layer) { SourceNetworkTimingLayer() }.record(
            host = host,
            queueMs = queueMs,
            scopeWaitMs = scopeWaitMs,
            globalWaitMs = globalWaitMs,
            headersMs = headersMs,
            bodyMs = bodyMs,
            decodeMs = decodeMs,
            totalMs = totalMs,
            bytes = bytes,
            error = error,
            slow = slow
        )
    }

    @Synchronized
    fun snapshot(): SourceNetworkTimingSnapshot {
        val layerParts = layers.map { (name, layer) -> layer.summary(name) }
        val slowParts = layers.flatMap { (name, layer) -> layer.slowSamples(name) }
            .sortedByDescending { sample -> sample.totalMs }
            .take(6)
            .joinToString("|") { sample -> sample.debugValue() }
        return SourceNetworkTimingSnapshot(
            rootName = rootName,
            startedAtMs = startedAt,
            requestCount = layers.values.sumOf { layer -> layer.requests },
            errorCount = layers.values.sumOf { layer -> layer.errors },
            layerSummary = layerParts.joinToString("|"),
            slowSummary = slowParts
        )
    }
}

private class SourceNetworkTimingLayer {
    var requests = 0
        private set
    var errors = 0
        private set
    private var queued = 0
    private var slow = 0
    private var totalQueueMs = 0L
    private var totalScopeWaitMs = 0L
    private var totalGlobalWaitMs = 0L
    private var totalHeadersMs = 0L
    private var totalBodyMs = 0L
    private var totalDecodeMs = 0L
    private var totalRequestMs = 0L
    private var totalBytes = 0L
    private var maxQueueMs = 0L
    private var maxHeadersMs = 0L
    private var maxBodyMs = 0L
    private var maxRequestMs = 0L
    private val slowSamplesList = ArrayList<SourceNetworkSlowSample>()

    fun record(
        host: String,
        queueMs: Long,
        scopeWaitMs: Long,
        globalWaitMs: Long,
        headersMs: Long,
        bodyMs: Long,
        decodeMs: Long,
        totalMs: Long,
        bytes: Int,
        error: String?,
        slow: Boolean
    ) {
        requests += 1
        if (error != null) errors += 1
        if (queueMs > 0L) queued += 1
        if (slow) this.slow += 1
        totalQueueMs += queueMs
        totalScopeWaitMs += scopeWaitMs
        totalGlobalWaitMs += globalWaitMs
        totalHeadersMs += headersMs
        totalBodyMs += bodyMs
        totalDecodeMs += decodeMs
        totalRequestMs += totalMs
        totalBytes += bytes
        maxQueueMs = maxOf(maxQueueMs, queueMs)
        maxHeadersMs = maxOf(maxHeadersMs, headersMs)
        maxBodyMs = maxOf(maxBodyMs, bodyMs)
        maxRequestMs = maxOf(maxRequestMs, totalMs)
        if (slow || error != null) {
            slowSamplesList.add(
                SourceNetworkSlowSample(
                    host = host,
                    totalMs = totalMs,
                    queueMs = queueMs,
                    headersMs = headersMs,
                    error = error.orEmpty()
                )
            )
            slowSamplesList.sortByDescending { sample -> sample.totalMs }
            while (slowSamplesList.size > 8) {
                slowSamplesList.removeAt(slowSamplesList.lastIndex)
            }
        }
    }

    fun summary(layer: String): String {
        return "$layer" +
            ":r${requests}" +
            "/e${errors}" +
            "/queued${queued}" +
            "/qAvg${avg(totalQueueMs)}" +
            "/qMax${maxQueueMs}" +
            "/scopeAvg${avg(totalScopeWaitMs)}" +
            "/globalAvg${avg(totalGlobalWaitMs)}" +
            "/hAvg${avg(totalHeadersMs)}" +
            "/hMax${maxHeadersMs}" +
            "/bodyMax${maxBodyMs}" +
            "/decodeAvg${avg(totalDecodeMs)}" +
            "/tAvg${avg(totalRequestMs)}" +
            "/tMax${maxRequestMs}" +
            "/kb${totalBytes / 1024}" +
            "/slow${slow}"
    }

    fun slowSamples(layer: String): List<SourceNetworkSlowSample> {
        return slowSamplesList.map { sample -> sample.copy(layer = layer) }
    }

    private fun avg(total: Long): Long {
        return if (requests == 0) 0L else total / requests
    }
}

private data class SourceNetworkSlowSample(
    val host: String,
    val totalMs: Long,
    val queueMs: Long,
    val headersMs: Long,
    val error: String,
    val layer: String = ""
) {
    fun debugValue(): String {
        return "${layer}:${host.traceToken(60)}/t${totalMs}/q${queueMs}/h${headersMs}/e${error.traceToken(30)}"
    }
}

internal object SourceNetworkPriorityGate {
    private const val BACKGROUND_POLL_INTERVAL_MS = 200L
    private const val GLOBAL_MAX_CONCURRENT_REQUESTS = 48
    private const val LOW_PRIORITY_MAX_CONCURRENT_REQUESTS = 16
    private val foregroundRequests = AtomicInteger()
    private val backgroundRequests = AtomicInteger()
    private val globalNetworkPermits = Semaphore(GLOBAL_MAX_CONCURRENT_REQUESTS, true)
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

    fun globalMaxConcurrentRequestsForTest(): Int {
        return GLOBAL_MAX_CONCURRENT_REQUESTS
    }

    fun foregroundScopeMaxConcurrentRequestsForTest(): Int {
        return GLOBAL_MAX_CONCURRENT_REQUESTS
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

    fun acquireNetworkSlot(scope: SourceRequestScope?, priority: SourceRequestPriority): SourceNetworkPermitWait {
        val startedAt = System.currentTimeMillis()
        val globalAvailableBefore = globalNetworkPermits.availablePermits()
        val globalStartedAt = System.currentTimeMillis()
        globalNetworkPermits.acquire()
        val globalWaitMs = System.currentTimeMillis() - globalStartedAt
        return SourceNetworkPermitWait(
            scopeWaitMs = 0L,
            globalWaitMs = globalWaitMs,
            totalWaitMs = System.currentTimeMillis() - startedAt,
            scopeAvailableBefore = null,
            globalAvailableBefore = globalAvailableBefore,
            globalAvailableAfter = globalNetworkPermits.availablePermits()
        )
    }

    fun releaseNetworkSlot(scope: SourceRequestScope?, priority: SourceRequestPriority) {
        globalNetworkPermits.release()
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
        while (globalNetworkPermits.availablePermits() < GLOBAL_MAX_CONCURRENT_REQUESTS) {
            globalNetworkPermits.release()
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
            val fetchStartedAt = System.currentTimeMillis()
            val scope = requestScope.get()
            val priority = scope?.priority ?: SourceRequestPriority.FOREGROUND
            val lowPriority = priority != SourceRequestPriority.FOREGROUND
            var networkSlot = false
            var lowPrioritySlot = false
            var backgroundEntered = false
            var lowPriorityWaitMs = 0L
            var permitWait: SourceNetworkPermitWait? = null
            var statusCode = -1
            var responseBytes = 0
            var headersMs = 0L
            var bodyMs = 0L
            var decodeMs = 0L
            var finalUrl = request.url
            var errorName: String? = null
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
                val lowPriorityStartedAt = System.currentTimeMillis()
                SourceNetworkPriorityGate.acquireLowPrioritySlot(priority)
                lowPriorityWaitMs = System.currentTimeMillis() - lowPriorityStartedAt
                lowPrioritySlot = true
            }
            permitWait = SourceNetworkPriorityGate.acquireNetworkSlot(scope, priority)
            networkSlot = true
            val call = callFactory?.invoke(okHttpRequest) ?: client.newCall(okHttpRequest)
            register(scope, call)
            try {
                val executeStartedAt = System.currentTimeMillis()
                call.execute().use { response ->
                    headersMs = System.currentTimeMillis() - executeStartedAt
                    statusCode = response.code
                    finalUrl = response.request.url.toString()
                    val responseBody = response.body ?: error("empty response body ${request.url}")
                    val bodyStartedAt = System.currentTimeMillis()
                    val bytes = responseBody.bytes()
                    bodyMs = System.currentTimeMillis() - bodyStartedAt
                    responseBytes = bytes.size
                    if (!response.isSuccessful) {
                        errorName = "HTTP_${response.code}"
                        error("HTTP ${response.code} ${response.request.url}")
                    }
                    val charset = request.charset
                        ?: responseBody.contentType()?.charset()?.name()
                        ?: htmlCharset(bytes)
                        ?: "UTF-8"
                    val decodeStartedAt = System.currentTimeMillis()
                    val text = String(bytes, Charset.forName(charset))
                    decodeMs = System.currentTimeMillis() - decodeStartedAt
                    return HttpResponse(response.request.url.toString(), text)
                }
            } catch (error: IOException) {
                errorName = error.javaClass.simpleName
                if (lowPriority && preemptedCalls.remove(call)) {
                    SourceNetworkPriorityGate.waitForHigherPriorityIdleBlocking(priority)
                    if (scope?.isCancelledInChain() != true) {
                        continue
                    }
                }
                throw error
            } finally {
                val totalMs = System.currentTimeMillis() - fetchStartedAt
                traceNetworkRequestTiming(
                    scope = scope,
                    priority = priority,
                    method = method,
                    url = finalUrl,
                    statusCode = statusCode,
                    responseBytes = responseBytes,
                    lowPriorityWaitMs = lowPriorityWaitMs,
                    permitWait = permitWait,
                    headersMs = headersMs,
                    bodyMs = bodyMs,
                    decodeMs = decodeMs,
                    totalMs = totalMs,
                    errorName = errorName
                )
                unregister(scope, call)
                preemptedCalls.remove(call)
                if (networkSlot) {
                    SourceNetworkPriorityGate.releaseNetworkSlot(scope, priority)
                }
                if (lowPrioritySlot) {
                    SourceNetworkPriorityGate.releaseLowPrioritySlot()
                }
                if (backgroundEntered) {
                    SourceNetworkPriorityGate.exitBackground()
                }
            }
        }
    }

    private fun traceNetworkRequestTiming(
        scope: SourceRequestScope?,
        priority: SourceRequestPriority,
        method: String,
        url: String,
        statusCode: Int,
        responseBytes: Int,
        lowPriorityWaitMs: Long,
        permitWait: SourceNetworkPermitWait?,
        headersMs: Long,
        bodyMs: Long,
        decodeMs: Long,
        totalMs: Long,
        errorName: String?
    ) {
        runCatching {
            SourceNetworkTimingTracer.record(
                scope = scope,
                priority = priority,
                method = method,
                url = url,
                statusCode = statusCode,
                responseBytes = responseBytes,
                lowPriorityWaitMs = lowPriorityWaitMs,
                permitWait = permitWait,
                headersMs = headersMs,
                bodyMs = bodyMs,
                decodeMs = decodeMs,
                totalMs = totalMs,
                errorName = errorName
            )
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
        private const val MAX_REQUESTS = 48
        private const val MAX_REQUESTS_PER_HOST = 16
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108 Mobile Safari/537.36"
    }
}
