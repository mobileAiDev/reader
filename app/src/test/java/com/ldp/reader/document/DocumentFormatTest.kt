package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun mapsSupportedExtensionsToIsolatedReaders() {
        assertEquals(ReaderDocumentFormat.TEXT, DocumentFormat.fromExtension("txt"))
        assertEquals(ReaderDocumentFormat.EPUB, DocumentFormat.fromExtension("epub"))
        assertEquals(ReaderDocumentFormat.PDF, DocumentFormat.fromExtension("PDF"))
        assertEquals(ReaderDocumentFormat.LOCAL_COMIC, DocumentFormat.fromExtension("cbz"))
        assertEquals(ReaderDocumentFormat.LOCAL_COMIC, DocumentFormat.fromExtension(" zip "))
    }

    @Test
    fun unsupportedExtensionsAreRejected() {
        assertEquals(ReaderDocumentFormat.UNSUPPORTED, DocumentFormat.fromExtension(""))
    }
}
