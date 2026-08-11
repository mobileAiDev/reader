package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

class SourceNetworkPriorityGateTest {
    @After
    fun tearDown() {
        SourceNetworkPriorityGate.resetForTest()
    }

    @Test
    fun lowPrioritySlotWaitsWhileForegroundRequestIsActive() {
        SourceNetworkPriorityGate.enterForeground()
        val acquired = CountDownLatch(1)
        val thread = Thread {
            SourceNetworkPriorityGate.acquireLowPrioritySlot()
            acquired.countDown()
            SourceNetworkPriorityGate.releaseLowPrioritySlot()
        }

        thread.start()

        assertTrue(!acquired.await(100, TimeUnit.MILLISECONDS))
        SourceNetworkPriorityGate.exitForeground()
        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        assertEquals(0, SourceNetworkPriorityGate.foregroundCount())
    }

    @Test
    fun lowestPrioritySlotWaitsWhileBackgroundRequestIsActive() {
        SourceNetworkPriorityGate.enterBackground()
        val acquired = CountDownLatch(1)
        val thread = Thread {
            SourceNetworkPriorityGate.acquireLowPrioritySlot(SourceRequestPriority.BACKGROUND_LOW)
            acquired.countDown()
            SourceNetworkPriorityGate.releaseLowPrioritySlot()
        }

        thread.start()

        assertTrue(!acquired.await(100, TimeUnit.MILLISECONDS))
        SourceNetworkPriorityGate.exitBackground()
        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        assertEquals(0, SourceNetworkPriorityGate.backgroundCount())
    }

    @Test
    fun foregroundNetworkSlotsAreLimitedPerRootScope() {
        val scope = SourceRequestScope(id = 10, name = "search", priority = SourceRequestPriority.FOREGROUND)
        val maxPerScope = SourceNetworkPriorityGate.foregroundScopeMaxConcurrentRequestsForTest()
        repeat(maxPerScope) {
            SourceNetworkPriorityGate.acquireNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        }
        val acquired = CountDownLatch(1)
        val thread = Thread {
            SourceNetworkPriorityGate.acquireNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
            acquired.countDown()
            SourceNetworkPriorityGate.releaseNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        }

        thread.start()

        assertFalse(acquired.await(100, TimeUnit.MILLISECONDS))
        SourceNetworkPriorityGate.releaseNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        repeat(maxPerScope - 1) {
            SourceNetworkPriorityGate.releaseNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        }
    }

    @Test
    fun sourceEngineNetworkSlotsAreLimitedGloballyAcrossScopes() {
        val maxGlobal = SourceNetworkPriorityGate.globalMaxConcurrentRequestsForTest()
        val scopes = (1..maxGlobal).map { index ->
            SourceRequestScope(id = index.toLong(), name = "scope-$index", priority = SourceRequestPriority.FOREGROUND)
        }
        scopes.forEach { scope ->
            SourceNetworkPriorityGate.acquireNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        }
        val acquired = CountDownLatch(1)
        val waitingScope = SourceRequestScope(id = 10_000, name = "waiting", priority = SourceRequestPriority.FOREGROUND)
        val thread = Thread {
            SourceNetworkPriorityGate.acquireNetworkSlot(waitingScope, SourceRequestPriority.FOREGROUND)
            acquired.countDown()
            SourceNetworkPriorityGate.releaseNetworkSlot(waitingScope, SourceRequestPriority.FOREGROUND)
        }

        thread.start()

        assertFalse(acquired.await(100, TimeUnit.MILLISECONDS))
        SourceNetworkPriorityGate.releaseNetworkSlot(scopes.first(), SourceRequestPriority.FOREGROUND)
        assertTrue(acquired.await(1, TimeUnit.SECONDS))
        scopes.drop(1).forEach { scope ->
            SourceNetworkPriorityGate.releaseNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        }
    }

    @Test
    fun cancelledScopeStopsWaitingForGlobalNetworkSlotBeforeCreatingCall() = runBlocking {
        val maxGlobal = SourceNetworkPriorityGate.globalMaxConcurrentRequestsForTest()
        val holderScopes = (1..maxGlobal).map { index ->
            SourceRequestScope(
                id = 20_000L + index,
                name = "holder-$index",
                priority = SourceRequestPriority.FOREGROUND
            )
        }
        holderScopes.forEach { scope ->
            SourceNetworkPriorityGate.acquireNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
        }
        val waitingScope = SourceRequestScope(
            id = 30_000L,
            name = "cancelled-slot-waiter",
            priority = SourceRequestPriority.FOREGROUND
        )
        val callCreations = AtomicInteger(0)
        val callExecuted = CountDownLatch(1)
        val enteredFetch = CountDownLatch(1)
        val fetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = { request ->
                callCreations.incrementAndGet()
                SuccessfulCall(request, callExecuted)
            }
        )
        val result = async(Dispatchers.IO + fetcher.requestScopeContext(waitingScope)) {
            enteredFetch.countDown()
            runCatching { fetcher.fetch(HttpRequest("https://example.test/cancelled-slot-waiter")) }
        }

        try {
            assertTrue(enteredFetch.await(1, TimeUnit.SECONDS))
            Thread.sleep(100L)
            val cancelledAt = System.currentTimeMillis()
            fetcher.cancel(waitingScope)

            val outcome = withTimeout(1_000L) { result.await() }
            val cancelLatencyMs = System.currentTimeMillis() - cancelledAt

            assertTrue("cancelled permit waiter must fail", outcome.isFailure)
            assertTrue(
                "cancelled permit waiter must exit promptly; cancelLatencyMs=$cancelLatencyMs",
                cancelLatencyMs < 500L
            )
            assertEquals("cancelled permit waiter must not create a call", 0, callCreations.get())
            assertFalse("cancelled permit waiter must not execute a call", callExecuted.await(50, TimeUnit.MILLISECONDS))
        } finally {
            holderScopes.forEach { scope ->
                SourceNetworkPriorityGate.releaseNetworkSlot(scope, SourceRequestPriority.FOREGROUND)
            }
        }
    }

    @Test
    fun cancelledBetweenPermitAcquireAndCallRegistrationNeverExecutesCall() = runBlocking {
        val scope = SourceRequestScope(
            id = 40_000L,
            name = "cancelled-before-register",
            priority = SourceRequestPriority.FOREGROUND
        )
        val callExecuted = CountDownLatch(1)
        lateinit var fetcher: OkHttpSourceEngineFetcher
        fetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = { request ->
                fetcher.cancel(scope)
                SuccessfulCall(request, callExecuted)
            }
        )

        val outcome = async(Dispatchers.IO + fetcher.requestScopeContext(scope)) {
            runCatching { fetcher.fetch(HttpRequest("https://example.test/cancelled-before-register")) }
        }.await()

        assertTrue("scope cancelled before registration must fail", outcome.isFailure)
        assertFalse("scope cancelled before registration must not execute", callExecuted.await(50, TimeUnit.MILLISECONDS))
    }

    @Test
    fun preemptedBackgroundFetchRetriesAfterForegroundRequestsClear() = runBlocking {
        val calls = ScriptedCalls()
        val fetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = calls::next
        )
        val backgroundScope = SourceRequestScope(
            id = 1,
            name = "v8-background",
            priority = SourceRequestPriority.BACKGROUND
        )
        try {
            val deferred = async(Dispatchers.IO + fetcher.requestScopeContext(backgroundScope)) {
                fetcher.fetch(HttpRequest("https://example.test/content")).body
            }

            assertTrue(calls.firstStarted.await(2, TimeUnit.SECONDS))

            assertEquals(1, SourceNetworkForegroundPriority.enter("content", "chapter"))
            assertFalse(calls.secondStarted.await(150, TimeUnit.MILLISECONDS))

            SourceNetworkForegroundPriority.exit()

            assertEquals("ok", withTimeout(2_000) { deferred.await() })
            assertTrue(calls.secondStarted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            SourceNetworkPriorityGate.exitForeground()
        }
    }

    @Test
    fun cancelledBackgroundScopeDoesNotRetryPreemptedFetch() = runBlocking {
        val calls = ScriptedCalls()
        val fetcher = OkHttpSourceEngineFetcher(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 10_000,
            callFactory = calls::next
        )
        val backgroundScope = SourceRequestScope(
            id = 2,
            name = "cancelled-background",
            priority = SourceRequestPriority.BACKGROUND
        )
        try {
            val deferred = async(Dispatchers.IO + fetcher.requestScopeContext(backgroundScope)) {
                runCatching { fetcher.fetch(HttpRequest("https://example.test/content")).body }
            }

            assertTrue(calls.firstStarted.await(2, TimeUnit.SECONDS))

            assertEquals(1, SourceNetworkForegroundPriority.enter("content", "chapter"))
            fetcher.cancel(backgroundScope)
            SourceNetworkForegroundPriority.exit()

            assertTrue(withTimeout(2_000) { deferred.await().isFailure })
            assertFalse(calls.secondStarted.await(150, TimeUnit.MILLISECONDS))
        } finally {
            SourceNetworkPriorityGate.exitForeground()
        }
    }

    private class ScriptedCalls {
        private val requests = CopyOnWriteArrayList<Request>()
        val firstAccepted = CountDownLatch(1)
        val firstStarted = firstAccepted
        val secondStarted = CountDownLatch(1)

        fun next(request: Request): Call {
            requests.add(request)
            return if (requests.size == 1) {
                BlockingCall(request, firstStarted)
            } else {
                SuccessfulCall(request, secondStarted)
            }
        }
    }

    private class BlockingCall(
        private val request: Request,
        private val started: CountDownLatch
    ) : Call {
        private val cancelled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            started.countDown()
            while (!cancelled.get()) {
                Thread.sleep(10)
            }
            throw IOException("Canceled")
        }

        override fun enqueue(responseCallback: Callback) = error("not used")

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isExecuted(): Boolean = started.count == 0L

        override fun isCanceled(): Boolean = cancelled.get()

        override fun clone(): Call = error("not used")

        override fun timeout(): Timeout = Timeout()

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }

    private class SuccessfulCall(
        private val request: Request,
        private val started: CountDownLatch
    ) : Call {
        private val cancelled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            started.countDown()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        override fun enqueue(responseCallback: Callback) = error("not used")

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isExecuted(): Boolean = started.count == 0L

        override fun isCanceled(): Boolean = cancelled.get()

        override fun clone(): Call = error("not used")

        override fun timeout(): Timeout = Timeout()

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}
