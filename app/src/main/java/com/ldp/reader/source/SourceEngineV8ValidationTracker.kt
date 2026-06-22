package com.ldp.reader.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class SourceEngineV8ValidationTracker {
    data class ActiveBook(
        val sourceBookKey: String?,
        val bookName: String?,
        val author: String?
    )

    private val activeKeys = LinkedHashSet<String>()
    private val jobsByKey = LinkedHashMap<String, Job>()
    private val activeBooksByKey = LinkedHashMap<String, ActiveBook>()

    @Synchronized
    fun start(
        key: String,
        job: Job,
        activeBook: ActiveBook? = null
    ): Boolean {
        if (activeKeys.contains(key)) return false
        if (job.isCompleted) {
            return false
        }
        activeKeys.add(key)
        jobsByKey[key] = job
        activeBook?.let { activeBooksByKey[key] = it }
        return true
    }

    @Synchronized
    fun finish(key: String) {
        activeKeys.remove(key)
        jobsByKey.remove(key)
        activeBooksByKey.remove(key)
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
    fun hasActiveBook(
        sourceBookKeys: Collection<String>,
        bookName: String?,
        author: String?
    ): Boolean {
        val normalizedSourceKeys = sourceBookKeys
            .map { key -> key.trim() }
            .filter { key -> key.isNotBlank() }
            .toSet()
        val normalizedBookName = bookName.normalizedIdentityPart()
        val normalizedAuthor = author.normalizedIdentityPart()
        return jobsByKey.any { (key, job) ->
            if (job.isCompleted) return@any false
            val activeBook = activeBooksByKey[key]
            val sourceMatches = normalizedSourceKeys.any { sourceBookKey ->
                activeBook?.sourceBookKey?.trim() == sourceBookKey ||
                    key.startsWith(sourceBookKey + "\n")
            }
            val identityMatches =
                normalizedBookName.isNotBlank() &&
                    normalizedAuthor.isNotBlank() &&
                    activeBook?.bookName.normalizedIdentityPart() == normalizedBookName &&
                    activeBook?.author.normalizedIdentityPart() == normalizedAuthor
            sourceMatches || identityMatches
        }
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
            activeBooksByKey.remove(entry.key)
            iterator.remove()
            cancelled += 1
        }
        return cancelled
    }

    private fun String?.normalizedIdentityPart(): String {
        return this?.trim().orEmpty()
    }
}
