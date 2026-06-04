package com.ldp.reader.document

enum class ReaderDocumentFormat {
    TEXT,
    EPUB,
    PDF,
    LOCAL_COMIC,
    UNSUPPORTED
}

object DocumentFormat {
    fun fromExtension(extension: String): ReaderDocumentFormat {
        return when (extension.trim().lowercase()) {
            "txt" -> ReaderDocumentFormat.TEXT
            "epub" -> ReaderDocumentFormat.EPUB
            "pdf" -> ReaderDocumentFormat.PDF
            "cbz", "zip" -> ReaderDocumentFormat.LOCAL_COMIC
            else -> ReaderDocumentFormat.UNSUPPORTED
        }
    }
}
