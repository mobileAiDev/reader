package com.ldp.reader.model.objectbox

import android.util.Log
import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.CollBookBean
import io.objectbox.Box
import io.objectbox.BoxStore

internal data class ChapterCatalogWriteToken(
    val bookId: String,
    val generation: Long,
    val fingerprint: String
)

internal data class ChapterCatalogWriteRequest(
    val bookId: String,
    val generation: Long
)

internal class StaleChapterCatalogWriteException : IllegalStateException(
    "Chapter catalog write was superseded before commit"
)

class ObjectBoxBookStore(private val boxStore: BoxStore) {
    private val collBookBox: Box<ObjectBoxCollBookEntity> =
        boxStore.boxFor(ObjectBoxCollBookEntity::class.java)
    private val chapterBox: Box<ObjectBoxBookChapterEntity> =
        boxStore.boxFor(ObjectBoxBookChapterEntity::class.java)
    private val chapterCatalogStateBox: Box<ObjectBoxChapterCatalogStateEntity> =
        boxStore.boxFor(ObjectBoxChapterCatalogStateEntity::class.java)
    private val chapterCatalogWrites = LatestChapterCatalogWrites()

    fun runInTx(action: () -> Unit) {
        boxStore.runInTx(action)
    }

    internal fun beginChapterCatalogWriteRequest(bookId: String): ChapterCatalogWriteRequest {
        require(bookId.isNotBlank()) { "bookId must not be blank" }
        var generation = 0L
        boxStore.runInTx {
            val state = findChapterCatalogState(bookId)
                ?: ObjectBoxChapterCatalogStateEntity().apply { this.bookId = bookId }
            val latestPersistedGeneration = maxOf(
                state.requestedGeneration,
                state.activeGeneration
            )
            check(latestPersistedGeneration < Long.MAX_VALUE) {
                "Chapter catalog generation exhausted"
            }
            generation = latestPersistedGeneration + 1L
            state.requestedGeneration = generation
            state.requestedFingerprint = null
            chapterCatalogStateBox.put(state)
        }
        return chapterCatalogWrites.beginRequest(bookId, generation)
    }

    internal fun bindChapterCatalogWrite(
        request: ChapterCatalogWriteRequest,
        fingerprint: String
    ): ChapterCatalogWriteToken? {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        if (!chapterCatalogWrites.isLatest(request)) {
            return null
        }
        var persisted = false
        boxStore.runInTx {
            val state = findChapterCatalogState(request.bookId)
            if (
                state != null &&
                state.requestedGeneration == request.generation
            ) {
                state.requestedFingerprint = fingerprint
                chapterCatalogStateBox.put(state)
                persisted = true
            }
        }
        return if (persisted) {
            chapterCatalogWrites.bind(request, fingerprint)
        } else {
            null
        }
    }

    internal fun replaceBookChaptersIfLatest(
        token: ChapterCatalogWriteToken,
        chapters: List<BookChapterBean>
    ): Boolean {
        if (!chapterCatalogWrites.isLatest(token)) {
            return false
        }
        if (!isChapterCatalogRequested(token)) {
            return false
        }
        replaceBookChapters(token, chapters)
        if (!chapterCatalogWrites.isLatest(token)) {
            throw StaleChapterCatalogWriteException()
        }
        return true
    }

    internal fun finalizeChapterCatalogWriteAfterCommit(token: ChapterCatalogWriteToken): Boolean {
        if (
            chapterCatalogWrites.isLatest(token) &&
            isChapterCatalogActiveAndRequested(token)
        ) {
            return true
        }
        boxStore.runInTx {
            removeBookChaptersOwnedBy(token)
        }
        return false
    }

    internal fun runIfChapterCatalogWriteLatest(
        token: ChapterCatalogWriteToken,
        action: () -> Unit
    ): Boolean {
        if (!isChapterCatalogActiveAndRequested(token)) {
            return false
        }
        var applied = false
        val latest = chapterCatalogWrites.runIfLatest(token) {
            if (isChapterCatalogActiveAndRequested(token)) {
                action()
                applied = true
            }
        }
        return latest && applied
    }

    fun runInTxAsync(runnable: Runnable, onComplete: ((Throwable?) -> Unit)? = null) {
        boxStore.runInTxAsync(runnable) { _, error ->
            if (error != null) {
                Log.e(TAG, "ObjectBox transaction failed", error)
            }
            onComplete?.invoke(error)
        }
    }

    fun saveCollBook(book: CollBookBean) {
        val entity = ObjectBoxCollBookEntity.from(book)
        val existing = findCollBookEntity(book.get_id())
        if (existing != null) {
            entity.objectBoxId = existing.objectBoxId
        }
        collBookBox.put(entity)
    }

    fun saveCollBooks(books: List<CollBookBean>) {
        for (book in books) {
            saveCollBook(book)
        }
    }

    fun getCollBook(bookId: String?): CollBookBean? {
        val entity = findCollBookEntity(bookId) ?: return null
        val book = entity.toCollBook()
        book.setBookChapters(getBookChapters(bookId))
        return book
    }

    fun getCollBooks(): List<CollBookBean> {
        val query = collBookBox
            .query()
            .orderDesc(ObjectBoxCollBookEntity_.lastRead)
            .build()
        return try {
            val entities = query.find()
            val books = ArrayList<CollBookBean>(entities.size)
            for (entity in entities) {
                books.add(entity.toCollBook())
            }
            books
        } finally {
            query.close()
        }
    }

    fun getExistingCollBookIds(bookIds: Collection<String?>): Set<String> {
        val ids = bookIds.filterNotNull().distinct()
        if (ids.isEmpty()) {
            return emptySet()
        }
        val query = collBookBox
            .query(ObjectBoxCollBookEntity_.bookId.oneOf(ids.toTypedArray()))
            .build()
        return try {
            val entities = query.find()
            val existingIds = LinkedHashSet<String>(entities.size)
            for (entity in entities) {
                val bookId = entity.bookId ?: continue
                existingIds.add(bookId)
            }
            existingIds
        } finally {
            query.close()
        }
    }

    fun getBookChapters(bookId: String?): List<BookChapterBean> {
        val query = chapterBox
            .query(ObjectBoxBookChapterEntity_.bookId.equal(bookId))
            .order(ObjectBoxBookChapterEntity_.start)
            .build()
        return try {
            val entities = query.find()
            if (!areChapterRowsActive(entities)) {
                return emptyList()
            }
            val chapters = ArrayList<BookChapterBean>(entities.size)
            for (entity in entities) {
                chapters.add(entity.toBookChapter())
            }
            chapters
        } finally {
            query.close()
        }
    }

    fun replaceBookChapters(bookId: String?, chapters: List<BookChapterBean>) {
        deleteBookChapterRows(bookId)
        deleteChapterCatalogState(bookId)
        val entities = ArrayList<ObjectBoxBookChapterEntity>(chapters.size)
        for (chapter in chapters) {
            chapter.bookId = bookId
            entities.add(ObjectBoxBookChapterEntity.from(chapter))
        }
        chapterBox.put(entities)
    }

    private fun replaceBookChapters(
        token: ChapterCatalogWriteToken,
        chapters: List<BookChapterBean>
    ) {
        deleteBookChapterRows(token.bookId)
        val entities = ArrayList<ObjectBoxBookChapterEntity>(chapters.size)
        for (chapter in chapters) {
            chapter.bookId = token.bookId
            entities.add(ObjectBoxBookChapterEntity.from(chapter).apply {
                catalogWriteGeneration = token.generation
                catalogWriteFingerprint = token.fingerprint
            })
        }
        chapterBox.put(entities)
        val state = findChapterCatalogState(token.bookId)
            ?: throw StaleChapterCatalogWriteException()
        if (
            state.requestedGeneration != token.generation ||
            state.requestedFingerprint != token.fingerprint
        ) {
            throw StaleChapterCatalogWriteException()
        }
        state.activeGeneration = token.generation
        state.activeFingerprint = token.fingerprint
        chapterCatalogStateBox.put(state)
    }

    private fun removeBookChaptersOwnedBy(token: ChapterCatalogWriteToken) {
        val query = chapterBox
            .query(ObjectBoxBookChapterEntity_.bookId.equal(token.bookId))
            .build()
        try {
            val ownedRows = query.find().filter { entity ->
                entity.catalogWriteGeneration == token.generation &&
                    entity.catalogWriteFingerprint == token.fingerprint
            }
            if (ownedRows.isNotEmpty()) {
                chapterBox.remove(ownedRows)
            }
            val state = findChapterCatalogState(token.bookId)
            if (
                state != null &&
                state.activeGeneration == token.generation &&
                state.activeFingerprint == token.fingerprint &&
                (
                    state.requestedGeneration != token.generation ||
                    state.requestedFingerprint != token.fingerprint
                )
            ) {
                state.activeGeneration = 0L
                state.activeFingerprint = null
                chapterCatalogStateBox.put(state)
            }
        } finally {
            query.close()
        }
    }

    fun deleteBookChapters(bookId: String?) {
        deleteBookChapterRows(bookId)
        deleteChapterCatalogState(bookId)
    }

    private fun deleteBookChapterRows(bookId: String?) {
        val query = chapterBox
            .query(ObjectBoxBookChapterEntity_.bookId.equal(bookId))
            .build()
        try {
            query.remove()
        } finally {
            query.close()
        }
    }

    private fun deleteChapterCatalogState(bookId: String?) {
        if (bookId.isNullOrBlank()) return
        findChapterCatalogState(bookId)?.let(chapterCatalogStateBox::remove)
    }

    fun deleteCollBook(book: CollBookBean) {
        val entity = findCollBookEntity(book.get_id())
        if (entity != null) {
            collBookBox.remove(entity)
        }
    }

    private fun areChapterRowsActive(entities: List<ObjectBoxBookChapterEntity>): Boolean {
        if (entities.isEmpty()) return true
        val first = entities.first()
        val tokenOwned = first.catalogWriteGeneration != 0L ||
            !first.catalogWriteFingerprint.isNullOrBlank()
        if (!tokenOwned) {
            val allRowsAreLegacyOrRegular = entities.all { entity ->
                entity.catalogWriteGeneration == 0L &&
                    entity.catalogWriteFingerprint.isNullOrBlank()
            }
            val bookId = first.bookId
            return allRowsAreLegacyOrRegular &&
                (bookId.isNullOrBlank() || findChapterCatalogState(bookId) == null)
        }
        val bookId = first.bookId ?: return false
        val fingerprint = first.catalogWriteFingerprint ?: return false
        if (
            entities.any { entity ->
                entity.bookId != bookId ||
                    entity.catalogWriteGeneration != first.catalogWriteGeneration ||
                    entity.catalogWriteFingerprint != fingerprint
            }
        ) {
            return false
        }
        return isChapterCatalogActiveAndRequested(
            ChapterCatalogWriteToken(bookId, first.catalogWriteGeneration, fingerprint)
        )
    }

    private fun isChapterCatalogRequested(token: ChapterCatalogWriteToken): Boolean {
        val state = findChapterCatalogState(token.bookId) ?: return false
        return state.requestedGeneration == token.generation &&
            state.requestedFingerprint == token.fingerprint
    }

    private fun isChapterCatalogActiveAndRequested(token: ChapterCatalogWriteToken): Boolean {
        val state = findChapterCatalogState(token.bookId) ?: return false
        return state.requestedGeneration == token.generation &&
            state.requestedFingerprint == token.fingerprint &&
            state.activeGeneration == token.generation &&
            state.activeFingerprint == token.fingerprint
    }

    private fun findChapterCatalogState(bookId: String): ObjectBoxChapterCatalogStateEntity? {
        val query = chapterCatalogStateBox
            .query(ObjectBoxChapterCatalogStateEntity_.bookId.equal(bookId))
            .build()
        return try {
            query.findFirst()
        } finally {
            query.close()
        }
    }

    private fun findCollBookEntity(bookId: String?): ObjectBoxCollBookEntity? {
        val query = collBookBox
            .query(ObjectBoxCollBookEntity_.bookId.equal(bookId))
            .build()
        return try {
            query.findFirst()
        } finally {
            query.close()
        }
    }

    companion object {
        private const val TAG = "ObjectBoxBookStore"
    }
}

private class LatestChapterCatalogWrites {
    private val lock = Any()
    private val latestByBookId = HashMap<String, LatestChapterCatalogWrite>()

    fun beginRequest(bookId: String, generation: Long): ChapterCatalogWriteRequest {
        require(bookId.isNotBlank()) { "bookId must not be blank" }
        require(generation > 0L) { "generation must be positive" }
        return synchronized(lock) {
            ChapterCatalogWriteRequest(bookId, generation).also { request ->
                val latest = latestByBookId[bookId]
                if (latest == null || request.generation > latest.generation) {
                    latestByBookId[bookId] = LatestChapterCatalogWrite(request.generation, null)
                }
            }
        }
    }

    fun isLatest(request: ChapterCatalogWriteRequest): Boolean {
        return synchronized(lock) {
            latestByBookId[request.bookId]?.generation == request.generation
        }
    }

    fun bind(
        request: ChapterCatalogWriteRequest,
        fingerprint: String
    ): ChapterCatalogWriteToken? {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        return synchronized(lock) {
            val latest = latestByBookId[request.bookId]
            if (latest?.generation != request.generation) {
                null
            } else {
                ChapterCatalogWriteToken(
                    request.bookId,
                    request.generation,
                    fingerprint
                ).also { token ->
                    latestByBookId[request.bookId] = LatestChapterCatalogWrite(
                        request.generation,
                        token.fingerprint
                    )
                }
            }
        }
    }

    fun isLatest(token: ChapterCatalogWriteToken): Boolean {
        return synchronized(lock) {
            latestByBookId[token.bookId] == LatestChapterCatalogWrite(
                token.generation,
                token.fingerprint
            )
        }
    }

    fun runIfLatest(token: ChapterCatalogWriteToken, action: () -> Unit): Boolean {
        return synchronized(lock) {
            if (
                latestByBookId[token.bookId] != LatestChapterCatalogWrite(
                    token.generation,
                    token.fingerprint
                )
            ) {
                false
            } else {
                action()
                true
            }
        }
    }

    private data class LatestChapterCatalogWrite(
        val generation: Long,
        val fingerprint: String?
    )
}
