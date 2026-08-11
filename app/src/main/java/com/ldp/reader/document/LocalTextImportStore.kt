package com.ldp.reader.document

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.StringUtils
import java.io.File

object LocalTextImportStore {
    internal fun importUri(context: Context, uri: Uri): LocalTextImportResult {
        val displayName = DocumentFileName.displayName(context, uri).ifBlank { "本地书籍.txt" }
        val target = File(
            context.filesDir,
            LocalTextImportIdentity.relativeTarget(displayName, uri.toString())
        )
        val result = context.contentResolver.openInputStream(uri)?.use { input ->
            LocalTextImportWriter.replaceWith(input, target)
        } ?: return LocalTextImportResult.Failure
        when (result) {
            LocalTextImportWriteResult.Empty -> return LocalTextImportResult.Empty
            LocalTextImportWriteResult.TooLarge -> return LocalTextImportResult.TooLarge
            LocalTextImportWriteResult.LineTooLong -> return LocalTextImportResult.LineTooLong
            is LocalTextImportWriteResult.Success -> Unit
        }
        val repository = BookRepository.getInstance()
        val uriScopedBookId = requireNotNull(MD5Utils.strToMd5By16(target.absolutePath))
        val legacyUnscopedTarget = File(
            context.filesDir,
            LocalTextImportIdentity.legacyRelativeTarget(displayName)
        )
        val legacyUnscopedBookId = requireNotNull(
            MD5Utils.strToMd5By16(legacyUnscopedTarget.absolutePath)
        )
        val directPathBookId = LocalTextImportIdentity.directFilePath(
            sourceIdentity = uri.toString(),
            primaryStorageRoot = Environment.getExternalStorageDirectory().absolutePath
        )?.let { path -> requireNotNull(MD5Utils.strToMd5By16(path)) }
        val discoveredExistingBookIds = repository.getExistingCollBookIds(
            listOf(uriScopedBookId, legacyUnscopedBookId, directPathBookId)
        )
        val existingBookIds = if (
            legacyUnscopedBookId in discoveredExistingBookIds &&
            !LocalTextImportIdentity.sameFileContent(legacyUnscopedTarget, target)
        ) {
            discoveredExistingBookIds - legacyUnscopedBookId
        } else {
            discoveredExistingBookIds
        }
        // Reusing any known identity is more important than changing to today's preferred scheme:
        // it keeps the existing shelf row, BookRecord and local progress together instead of
        // creating a second copy after an upgrade or a different import entry point. The old
        // name-only identity participates only when its retained copy matches this source.
        val bookId = LocalTextImportIdentity.preferredBookId(
            uriScopedBookId = uriScopedBookId,
            legacyUnscopedBookId = legacyUnscopedBookId,
            directPathBookId = directPathBookId,
            existingBookIds = existingBookIds
        )
        val collBook = target.toCollBook(displayName, repository, bookId)
        repository.saveCollBook(collBook)
        LocalTextSourceReferencePersistence.store.save(collBook.get_id(), uri.toString())
        return LocalTextImportResult.Success(collBook)
    }

    private fun File.toCollBook(
        displayName: String,
        repository: BookRepository,
        bookId: String
    ): CollBookBean {
        val existingBook = repository.getCollBook(bookId)
        if (existingBook == null) {
            BookshelfLocalProgressStore.clear(bookId)
        }
        return (existingBook ?: CollBookBean()).apply {
            _id = bookId
            bookIdInBiquge = bookId
            title = displayName.substringBeforeLast('.', displayName).ifBlank {
                name.substringBeforeLast('.', name)
            }
            author = ""
            shortIntro = "本地导入"
            cover = absolutePath
            setLocal(true)
            if (existingBook == null) {
                lastChapter = "开始阅读"
            }
            updated = StringUtils.dateConvert(lastModified(), Constant.FORMAT_BOOK_DATE)
            lastRead = StringUtils.dateConvert(System.currentTimeMillis(), Constant.FORMAT_BOOK_DATE)
        }
    }
}
