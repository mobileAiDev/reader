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
    private val fullCatalogProgressByKey = LinkedHashMap<String, FullCatalogProgressRecord>()

    @Synchronized
    fun start(
        key: String,
        job: Job,
        activeBook: ActiveBook? = null,
        initialFullCatalogProgress: SourceEngineV8ScanProgress? = null
    ): Boolean {
        if (activeKeys.contains(key)) return false
        if (job.isCompleted) {
            return false
        }
        activeKeys.add(key)
        jobsByKey[key] = job
        activeBook?.let { activeBooksByKey[key] = it }
        if (activeBook != null && initialFullCatalogProgress != null) {
            fullCatalogProgressByKey.remove(key)
            fullCatalogProgressByKey[key] = FullCatalogProgressRecord(activeBook, initialFullCatalogProgress)
        }
        return true
    }

    @Synchronized
    fun finish(key: String, expectedJob: Job? = null) {
        if (expectedJob != null && jobsByKey[key] !== expectedJob) return
        activeKeys.remove(key)
        jobsByKey.remove(key)
        activeBooksByKey.remove(key)
    }

    @Synchronized
    fun updateFullCatalogProgress(
        key: String,
        progress: SourceEngineV8ScanProgress,
        expectedJob: Job? = null
    ) {
        if (expectedJob != null && jobsByKey[key] !== expectedJob) return
        val previous = fullCatalogProgressByKey[key] ?: return
        val monotonic = progress.copy(
            processed = maxOf(previous.progress.processed, progress.processed),
            target = maxOf(previous.progress.target, progress.target),
            wrong = maxOf(previous.progress.wrong, progress.wrong)
        )
        fullCatalogProgressByKey.remove(key)
        fullCatalogProgressByKey[key] = previous.copy(progress = monotonic)
    }

    @Synchronized
    fun recordFullCatalogCacheHit(
        key: String,
        activeBook: ActiveBook,
        progress: SourceEngineV8ScanProgress
    ) {
        require(progress.cacheHit) { "cache-hit progress must use CACHE_HIT phase" }
        fullCatalogProgressByKey.remove(key)
        fullCatalogProgressByKey[key] = FullCatalogProgressRecord(activeBook, progress)
    }

    @Synchronized
    fun latestFullCatalogProgress(
        sourceBookKeys: Collection<String>,
        bookName: String?,
        author: String?
    ): SourceEngineV8ScanProgress? {
        return fullCatalogProgressByKey.values
            .toList()
            .asReversed()
            .firstOrNull { record ->
                matchesBook(record.activeBook, sourceBookKeys, bookName, author)
            }
            ?.progress
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
        return jobsByKey.any { (key, job) ->
            if (job.isCompleted) return@any false
            val activeBook = activeBooksByKey[key]
            matchesBook(activeBook, sourceBookKeys, bookName, author) ||
                sourceBookKeys.any { sourceBookKey ->
                    sourceBookKey.isNotBlank() && key.startsWith(sourceBookKey.trim() + "\n")
                }
        }
    }

    @Synchronized
    fun cancelBook(
        sourceBookKeys: Collection<String>,
        bookName: String?,
        author: String?,
        cause: CancellationException
    ): Int {
        val matchingKeys = jobsByKey.keys.filter { key ->
            matchesBook(activeBooksByKey[key], sourceBookKeys, bookName, author)
        }
        matchingKeys.forEach { key ->
            jobsByKey[key]?.cancel(cause)
            fullCatalogProgressByKey[key]?.let { record ->
                if (record.progress.running) {
                    fullCatalogProgressByKey[key] = record.copy(
                        progress = record.progress.copy(phase = SourceEngineV8ScanPhase.CANCELLED)
                    )
                }
            }
            activeKeys.remove(key)
            jobsByKey.remove(key)
            activeBooksByKey.remove(key)
        }
        return matchingKeys.size
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

    private fun matchesBook(
        activeBook: ActiveBook?,
        sourceBookKeys: Collection<String>,
        bookName: String?,
        author: String?
    ): Boolean {
        if (activeBook == null) return false
        val normalizedSourceKeys = sourceBookKeys
            .map { key -> key.trim() }
            .filter { key -> key.isNotBlank() }
            .toSet()
        val sourceMatches = normalizedSourceKeys.any { sourceBookKey ->
            activeBook.sourceBookKey?.trim() == sourceBookKey
        }
        val normalizedBookName = bookName.normalizedIdentityPart()
        val normalizedAuthor = author.normalizedIdentityPart()
        val activeBookName = activeBook.bookName.normalizedIdentityPart()
        val activeAuthor = activeBook.author.normalizedIdentityPart()
        val identityMatches =
            normalizedBookName.isNotBlank() &&
                activeBookName == normalizedBookName &&
                (normalizedAuthor.isBlank() || activeAuthor.isBlank() || activeAuthor == normalizedAuthor)
        return sourceMatches || identityMatches
    }

    private fun String?.normalizedIdentityPart(): String {
        return this?.trim().orEmpty()
    }

    private data class FullCatalogProgressRecord(
        val activeBook: ActiveBook,
        val progress: SourceEngineV8ScanProgress
    )
}
