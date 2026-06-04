package com.ldp.reader.utils

import java.io.File
import java.io.FileFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBookImportFilesTest {
    @Test
    fun unreadableDirectoryIsRejectedWithoutThrowing() {
        val directory = stubDirectory("blocked", children = null)

        assertFalse(LocalBookImportFiles.importFileFilter.accept(directory))
        assertEquals(0, LocalBookImportFiles.visibleChildCount(directory))
        assertTrue(LocalBookImportFiles.listVisibleChildren(directory).isEmpty())
    }

    @Test
    fun emptyDirectoryIsRejected() {
        val directory = stubDirectory("empty", children = emptyArray())

        assertFalse(LocalBookImportFiles.importFileFilter.accept(directory))
    }

    @Test
    fun nonEmptyDirectoryAndSupportedFilesAreAccepted() {
        val directory = stubDirectory("books", children = arrayOf("book.txt"))
        val txt = stubFile("book.txt", length = 8L)
        val pdf = stubFile("book.pdf", length = 8L)

        assertTrue(LocalBookImportFiles.importFileFilter.accept(directory))
        assertTrue(LocalBookImportFiles.importFileFilter.accept(txt))
        assertTrue(LocalBookImportFiles.importFileFilter.accept(pdf))
        assertTrue(LocalBookImportFiles.isTextFile(txt))
        assertTrue(LocalBookImportFiles.isOpenableDocument(pdf))
    }

    @Test
    fun emptyOrUnsupportedFilesAreRejected() {
        assertFalse(LocalBookImportFiles.importFileFilter.accept(stubFile("empty.txt", length = 0L)))
        assertFalse(LocalBookImportFiles.importFileFilter.accept(stubFile("book.log", length = 8L)))
    }

    private fun stubDirectory(name: String, children: Array<String>?): File {
        return object : File(name) {
            override fun getName(): String = name
            override fun isDirectory(): Boolean = true
            override fun isFile(): Boolean = false
            override fun list(): Array<String>? = children
            override fun listFiles(filter: FileFilter?): Array<File>? {
                return children?.map { child -> stubFile(child, length = 8L) }
                    ?.filter { child -> filter?.accept(child) != false }
                    ?.toTypedArray()
            }
        }
    }

    private fun stubFile(name: String, length: Long): File {
        return object : File(name) {
            override fun getName(): String = name
            override fun isDirectory(): Boolean = false
            override fun isFile(): Boolean = true
            override fun length(): Long = length
        }
    }
}
