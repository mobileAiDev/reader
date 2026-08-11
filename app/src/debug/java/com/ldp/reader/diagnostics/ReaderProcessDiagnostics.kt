package com.ldp.reader.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ldp.reader.source.AiBridgeTrace

internal object ReaderProcessExitReporter {
    fun recordRecent(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "kind=process_exit_history available=false api=${Build.VERSION.SDK_INT}")
            return
        }
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            reportFailure("process_exit_history", "activity_manager_unavailable")
            return
        }
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                ReaderProcessDiagnosticsPolicy.exitHistoryLimit
            )
        }.getOrElse { error ->
            reportFailure("process_exit_history", error.javaClass.simpleName)
            return
        }
        if (exits.isEmpty()) {
            val fields = linkedMapOf<String, Any?>("available" to false, "count" to 0)
            Log.i(TAG, ReaderProcessDiagnosticsMapper.structuredLine("process_exit_history", fields))
            AiBridgeTrace.state(
                EXIT_STATE_NAME,
                "latest",
                traceFields(fields)
            )
            return
        }
        exits.take(ReaderProcessDiagnosticsPolicy.exitHistoryLimit).forEachIndexed { index, exit ->
            val fields = linkedMapOf<String, Any?>("index" to index).apply {
                putAll(
                    ReaderProcessDiagnosticsMapper.exitFields(
                        ReaderExitInfoSnapshot(
                            reason = exit.reason,
                            status = exit.status,
                            importance = exit.importance,
                            pssKb = exit.pss,
                            rssKb = exit.rss,
                            timestampMs = exit.timestamp,
                            description = exit.description
                        )
                    )
                )
            }
            Log.i(TAG, ReaderProcessDiagnosticsMapper.structuredLine("process_exit", fields))
            AiBridgeTrace.event(
                EXIT_EVENT_NAME,
                "index_$index",
                traceFields(fields)
            )
            if (index == 0) {
                AiBridgeTrace.state(
                    EXIT_STATE_NAME,
                    "latest",
                    traceFields(fields)
                )
            }
        }
    }

    private fun reportFailure(kind: String, failure: String) {
        val fields = linkedMapOf<String, Any?>("failure" to failure)
        Log.w(TAG, ReaderProcessDiagnosticsMapper.structuredLine(kind, fields))
        AiBridgeTrace.event(DIAGNOSTIC_FAILURE_EVENT_NAME, kind, traceFields(fields))
    }
}

internal class ReaderProcessMemorySampler {
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<Runnable>()
    private var started = false
    private var cancelled = false

    fun start(context: Context, bookKey: String, sourceEngineBook: Boolean) {
        if (started) return
        started = true
        cancelled = false
        val appContext = context.applicationContext
        val startedAtMs = SystemClock.elapsedRealtime()
        ReaderProcessDiagnosticsPolicy.memorySampleDelaysMs.forEach { delayMs ->
            if (delayMs == 0L) {
                recordSample(appContext, bookKey, sourceEngineBook, delayMs, startedAtMs)
            } else {
                val callback = Runnable {
                    if (!cancelled) {
                        recordSample(appContext, bookKey, sourceEngineBook, delayMs, startedAtMs)
                    }
                }
                callbacks += callback
                handler.postDelayed(callback, delayMs)
            }
        }
    }

    fun cancel() {
        cancelled = true
        callbacks.forEach(handler::removeCallbacks)
        callbacks.clear()
    }

    private fun recordSample(
        context: Context,
        bookKey: String,
        sourceEngineBook: Boolean,
        scheduledDelayMs: Long,
        startedAtMs: Long
    ) {
        runCatching {
            val runtime = Runtime.getRuntime()
            val processMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val systemMemory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(systemMemory)
            val snapshot = ReaderMemorySnapshot(
                sample = sampleName(scheduledDelayMs),
                elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                javaMaxBytes = runtime.maxMemory(),
                nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
                totalPssKb = processMemory.totalPss,
                summaryJavaHeapKb = processMemory.memoryStatKb("summary.java-heap"),
                summaryNativeHeapKb = processMemory.memoryStatKb("summary.native-heap"),
                summaryGraphicsKb = processMemory.memoryStatKb("summary.graphics"),
                availMemBytes = systemMemory.availMem,
                thresholdBytes = systemMemory.threshold,
                lowMemory = systemMemory.lowMemory,
                sourceEngineBook = sourceEngineBook
            )
            val fields = linkedMapOf<String, Any?>("bookKey" to bookKey).apply {
                putAll(ReaderProcessDiagnosticsMapper.memoryFields(snapshot))
            }
            Log.i(TAG, ReaderProcessDiagnosticsMapper.structuredLine("reader_memory", fields))
            AiBridgeTrace.event(
                MEMORY_EVENT_NAME,
                bookKey,
                traceFields(fields)
            )
            AiBridgeTrace.state(
                MEMORY_STATE_NAME,
                "${bookKey}_${snapshot.sample}",
                traceFields(fields)
            )
        }.onFailure { error ->
            val fields = linkedMapOf<String, Any?>(
                "sample" to sampleName(scheduledDelayMs),
                "failure" to error.javaClass.simpleName
            )
            Log.w(TAG, ReaderProcessDiagnosticsMapper.structuredLine("reader_memory", fields))
            AiBridgeTrace.event(
                DIAGNOSTIC_FAILURE_EVENT_NAME,
                "reader_memory",
                traceFields(fields)
            )
        }
    }

    private fun Debug.MemoryInfo.memoryStatKb(key: String): Int {
        return getMemoryStat(key).toIntOrNull() ?: -1
    }

    private fun sampleName(delayMs: Long): String {
        return when (delayMs) {
            0L -> "enter"
            5_000L -> "5s"
            15_000L -> "15s"
            30_000L -> "30s"
            else -> "${delayMs}ms"
        }
    }
}

private fun traceFields(fields: Map<String, Any?>): String {
    return AiBridgeTrace.fields(*fields.map { (name, value) -> name to value }.toTypedArray())
}

private const val TAG = "ReaderProcessDiag"
private const val EXIT_EVENT_NAME = "reader_process_exit_history"
private const val EXIT_STATE_NAME = "reader_process_last_exit"
private const val MEMORY_EVENT_NAME = "reader_process_memory_sample"
private const val MEMORY_STATE_NAME = "reader_process_memory"
private const val DIAGNOSTIC_FAILURE_EVENT_NAME = "reader_process_diagnostic_failure"
