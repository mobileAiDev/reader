package com.ldp.reader.document

import android.content.Context
import android.net.Uri
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.local.BookRepository
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.utils.Constant
import com.ldp.reader.utils.MD5Utils
import com.ldp.reader.utils.StringUtils
import java.io.File

object LocalTextImportStore {
    fun importUri(context: Context, uri: Uri): CollBookBean? {
        val displayName = DocumentFileName.displayName(context, uri).ifBlank { "本地书籍.txt" }
        val target = File(context.filesDir, "imports/text/${safeFileName(displayName)}").apply {
            parentFile?.mkdirs()
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (!target.exists() || target.length() <= 0L) return null
        val collBook = target.toCollBook()
        BookRepository.getInstance().saveCollBook(collBook)
        return collBook
    }

    private fun File.toCollBook(): CollBookBean {
        val bookId = MD5Utils.strToMd5By16(absolutePath)
        BookshelfLocalProgressStore.clear(bookId)
        return CollBookBean().apply {
            _id = bookId
            bookIdInBiquge = bookId
            title = name.substringBeforeLast('.')
            author = ""
            shortIntro = "本地导入"
            cover = absolutePath
            setLocal(true)
            lastChapter = "开始阅读"
            updated = StringUtils.dateConvert(lastModified(), Constant.FORMAT_BOOK_DATE)
            lastRead = StringUtils.dateConvert(System.currentTimeMillis(), Constant.FORMAT_BOOK_DATE)
        }
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "本地书籍.txt" }
        return if (cleaned.endsWith(".txt", ignoreCase = true)) cleaned else "$cleaned.txt"
    }
}
