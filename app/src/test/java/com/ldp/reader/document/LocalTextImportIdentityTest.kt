package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalTextImportIdentityTest {
    @Test
    fun sameNamedFilesFromDifferentProvidersUseDifferentTargets() {
        val first = LocalTextImportIdentity.relativeTarget(
            displayName = "同名小说.txt",
            sourceIdentity = "content://downloads/document/101"
        )
        val second = LocalTextImportIdentity.relativeTarget(
            displayName = "同名小说.txt",
            sourceIdentity = "content://cloud/document/202"
        )

        assertNotEquals(first, second)
        assertTrue(first.endsWith("/同名小说.txt"))
        assertTrue(second.endsWith("/同名小说.txt"))
    }

    @Test
    fun choosingTheSameDocumentAgainKeepsTheSameIdentity() {
        val first = LocalTextImportIdentity.relativeTarget(
            displayName = "book.txt",
            sourceIdentity = "content://downloads/document/101"
        )
        val second = LocalTextImportIdentity.relativeTarget(
            displayName = "book.txt",
            sourceIdentity = "content://downloads/document/101"
        )

        assertEquals(first, second)
    }

    @Test
    fun legacyTargetKeepsTheExactPreUriScopedLocation() {
        assertEquals(
            "imports/text/同名小说.txt",
            LocalTextImportIdentity.legacyRelativeTarget("同名小说.txt")
        )
        assertEquals(
            "imports/text/.._旧书.txt",
            LocalTextImportIdentity.legacyRelativeTarget("../旧书.txt")
        )
    }

    @Test
    fun existingIdentityPriorityPreventsReimportFromForkingTheShelfBook() {
        assertEquals(
            "uri-scoped",
            LocalTextImportIdentity.preferredBookId(
                uriScopedBookId = "uri-scoped",
                legacyUnscopedBookId = "legacy-unscoped",
                directPathBookId = "direct-path",
                existingBookIds = setOf("uri-scoped", "legacy-unscoped", "direct-path")
            )
        )
        assertEquals(
            "legacy-unscoped",
            LocalTextImportIdentity.preferredBookId(
                uriScopedBookId = "uri-scoped",
                legacyUnscopedBookId = "legacy-unscoped",
                directPathBookId = "direct-path",
                existingBookIds = setOf("legacy-unscoped", "direct-path")
            )
        )
        assertEquals(
            "direct-path",
            LocalTextImportIdentity.preferredBookId(
                uriScopedBookId = "uri-scoped",
                legacyUnscopedBookId = "legacy-unscoped",
                directPathBookId = "direct-path",
                existingBookIds = setOf("direct-path")
            )
        )
        assertEquals(
            "direct-path",
            LocalTextImportIdentity.preferredBookId(
                uriScopedBookId = "uri-scoped",
                legacyUnscopedBookId = "legacy-unscoped",
                directPathBookId = "direct-path",
                existingBookIds = emptySet()
            )
        )
        assertEquals(
            "uri-scoped",
            LocalTextImportIdentity.preferredBookId(
                uriScopedBookId = "uri-scoped",
                legacyUnscopedBookId = "legacy-unscoped",
                directPathBookId = null,
                existingBookIds = emptySet()
            )
        )
    }

    @Test
    fun legacyMigrationRequiresTheRetainedCopyToMatchTheSelectedSource() {
        val directory = Files.createTempDirectory("legacy-local-text-identity").toFile()
        try {
            val legacy = directory.resolve("legacy.txt").apply { writeText("same book") }
            val sameSource = directory.resolve("same.txt").apply { writeText("same book") }
            val sameNameOtherSource = directory.resolve("other.txt").apply {
                writeText("different book")
            }

            assertTrue(LocalTextImportIdentity.sameFileContent(legacy, sameSource))
            assertEquals(
                false,
                LocalTextImportIdentity.sameFileContent(legacy, sameNameOtherSource)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsafeDisplayNameCannotEscapeTheImportDirectory() {
        val target = LocalTextImportIdentity.relativeTarget(
            displayName = "../bad:name",
            sourceIdentity = "content://downloads/document/303"
        )

        assertTrue(target.startsWith("imports/text/"))
        assertTrue(target.endsWith("/_bad_name.txt"))
        assertEquals(false, target.contains("../"))
    }

    @Test
    fun standardExternalStorageSafIdentityResolvesToDirectDirectoryPath() {
        assertEquals(
            "/storage/emulated/0/Download/Books/story.txt",
            LocalTextImportIdentity.directFilePath(
                sourceIdentity =
                    "content://com.android.externalstorage.documents/document/" +
                        "primary%3ADownload%2FBooks%2Fstory.txt",
                primaryStorageRoot = "/storage/emulated/0"
            )
        )
    }

    @Test
    fun externalStoragePathDecodingNeverTreatsPlusAsFormEncodedSpace() {
        assertEquals(
            "/storage/emulated/0/Download/A+B.txt",
            LocalTextImportIdentity.directFilePath(
                sourceIdentity =
                    "content://com.android.externalstorage.documents/document/" +
                        "primary%3ADownload%2FA+B.txt",
                primaryStorageRoot = "/storage/emulated/0"
            )
        )
        assertEquals(
            "/storage/emulated/0/Download/A+B.txt",
            LocalTextImportIdentity.directFilePath(
                sourceIdentity =
                    "content://com.android.externalstorage.documents/document/" +
                        "primary%3ADownload%2FA%2BB.txt",
                primaryStorageRoot = "/storage/emulated/0"
            )
        )
    }

    @Test
    fun unrelatedProviderAndUnsafePathAreNeverGuessedAsDirectFiles() {
        assertEquals(
            null,
            LocalTextImportIdentity.directFilePath(
                sourceIdentity = "content://downloads/document/101",
                primaryStorageRoot = "/storage/emulated/0"
            )
        )
        assertEquals(
            null,
            LocalTextImportIdentity.directFilePath(
                sourceIdentity =
                    "content://com.android.externalstorage.documents/document/" +
                        "primary%3A..%2Fsecret.txt",
                primaryStorageRoot = "/storage/emulated/0"
            )
        )
    }
}
