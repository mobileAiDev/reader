package com.ldp.reader.widget.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTxtSnapshotDirectoryStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun firstSessionInProcessRemovesSnapshotFilesLeftByOlderProcess() {
        val cacheDirectory = temporaryFolder.newFolder("cache-${System.nanoTime()}")
        val root = File(cacheDirectory, "local-txt-snapshots").apply { mkdirs() }
        val staleProcess = File(root, "process-crashed").apply { mkdirs() }
        val staleSnapshot = File(staleProcess, "reader-txt-old.snapshot").apply {
            writeBytes(ByteArray(1024))
        }

        val currentProcess = LocalTxtSnapshotDirectoryStore.forCacheDirectory(cacheDirectory)

        assertFalse(staleSnapshot.exists())
        assertFalse(staleProcess.exists())
        assertTrue(currentProcess.isDirectory)
        assertTrue(currentProcess.parentFile!!.canonicalFile == root.canonicalFile)
    }

    @Test
    fun repeatedSessionSetupNeverDeletesCurrentProcessSnapshots() {
        val cacheDirectory = temporaryFolder.newFolder("active-cache-${System.nanoTime()}")
        val currentProcess = LocalTxtSnapshotDirectoryStore.forCacheDirectory(cacheDirectory)
        val activeSnapshot = File(currentProcess, "reader-txt-active.snapshot").apply {
            writeBytes(ByteArray(1024))
        }

        val sameProcessDirectory = LocalTxtSnapshotDirectoryStore.forCacheDirectory(cacheDirectory)

        assertTrue(currentProcess.canonicalFile == sameProcessDirectory.canonicalFile)
        assertTrue(activeSnapshot.exists())
    }
}
