package com.ldp.reader.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class SourceEngineV8ValidationTracker {
    private val activeKeys = LinkedHashSet<String>()
    private val jobsByKey = LinkedHashMap<String, Job>()

    @Synchronized
    fun start(key: String, job: Job): Boolean {
        if (activeKeys.contains(key)) return false
        if (job.isCompleted) {
            return false
        }
        activeKeys.add(key)
        jobsByKey[key] = job
        return true
    }

    @Synchronized
    fun finish(key: String) {
        activeKeys.remove(key)
        jobsByKey.remove(key)
    }

    @Synchronized
    fun isActive(key: String): Boolean {
        return activeKeys.contains(key)
    }

    @Synchronized
    fun activeJob(key: String): Job? {
        return jobsByKey[key]?.takeUnless { job -> job.isCompleted }
    }

    @Synchronized
    fun cancelStaleExcept(currentKey: String, cause: CancellationException): Int {
        var cancelled = 0
        val iterator = jobsByKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == currentKey) continue
            entry.value.cancel(cause)
            activeKeys.remove(entry.key)
            iterator.remove()
            cancelled += 1
        }
        return cancelled
    }
}
