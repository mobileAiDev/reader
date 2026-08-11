package com.ldp.reader.ui.fragment

import com.ldp.reader.document.LocalTextSourceReferenceStore
import java.io.File
import java.net.URI

internal enum class BookshelfLocalFileDeleteOutcome {
    DELETED,
    ALREADY_MISSING,
    FAILED
}

internal class BookshelfLocalFileDeleter(
    private val contentUriDeleter: (String) -> Boolean
) {
    fun delete(target: String?): BookshelfLocalFileDeleteOutcome {
        if (target.isNullOrBlank()) return BookshelfLocalFileDeleteOutcome.FAILED
        if (target.startsWith(CONTENT_URI_PREFIX)) {
            return if (runCatching { contentUriDeleter(target) }.getOrDefault(false)) {
                BookshelfLocalFileDeleteOutcome.DELETED
            } else {
                BookshelfLocalFileDeleteOutcome.FAILED
            }
        }
        val file = if (target.startsWith(FILE_URI_PREFIX)) {
            runCatching { File(URI(target)) }.getOrNull()
                ?: return BookshelfLocalFileDeleteOutcome.FAILED
        } else {
            File(target)
        }
        if (!file.exists()) return BookshelfLocalFileDeleteOutcome.ALREADY_MISSING
        return if (file.isFile && file.delete() && !file.exists()) {
            BookshelfLocalFileDeleteOutcome.DELETED
        } else {
            BookshelfLocalFileDeleteOutcome.FAILED
        }
    }

    private companion object {
        const val CONTENT_URI_PREFIX = "content://"
        const val FILE_URI_PREFIX = "file:"
    }
}

internal fun bookshelfOriginalFileTarget(
    shelfPath: String?,
    savedSourceReference: String?,
    isManagedCopy: Boolean
): String? {
    return if (isManagedCopy) {
        savedSourceReference?.takeIf { it.isNotBlank() }
    } else {
        shelfPath?.takeIf { it.isNotBlank() }
    }
}

internal data class BookshelfLocalFileEntry(
    val bookId: String,
    val shelfPath: String?,
    val isManagedCopy: Boolean
)

internal data class BookshelfLocalFileRemovalResult(
    val removableBookIds: List<String>,
    val failedBookIds: List<String>
)

internal class BookshelfLocalFileRemovalExecutor(
    private val sourceReferences: LocalTextSourceReferenceStore,
    private val fileDeleter: BookshelfLocalFileDeleter,
    private val encodingOverrideClearer: (String) -> Unit
) {
    fun execute(
        entries: List<BookshelfLocalFileEntry>,
        deleteLocalFiles: Boolean
    ): BookshelfLocalFileRemovalResult {
        val removableBookIds = ArrayList<String>(entries.size)
        val failedBookIds = ArrayList<String>()
        entries.forEach { entry ->
            val outcome = if (deleteLocalFiles) {
                fileDeleter.delete(
                    bookshelfOriginalFileTarget(
                        shelfPath = entry.shelfPath,
                        savedSourceReference = sourceReferences.sourceFor(entry.bookId),
                        isManagedCopy = entry.isManagedCopy
                    )
                )
            } else {
                BookshelfLocalFileDeleteOutcome.ALREADY_MISSING
            }
            if (outcome == BookshelfLocalFileDeleteOutcome.FAILED) {
                failedBookIds += entry.bookId
            } else {
                removableBookIds += entry.bookId
                sourceReferences.clear(entry.bookId)
                encodingOverrideClearer(entry.bookId)
            }
        }
        return BookshelfLocalFileRemovalResult(
            removableBookIds = removableBookIds,
            failedBookIds = failedBookIds
        )
    }
}
