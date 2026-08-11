package com.ldp.reader.ui.file

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalImportListPolicyTest {
    @Test
    fun searchMatchesFileNamesCaseInsensitively() {
        val files = listOf(
            stubFile("/storage/Books/Story.TXT"),
            stubFile("/storage/Books/notes.txt"),
            stubDirectory("/storage/Books/Archive")
        )

        val visible = LocalImportListPolicy.visibleFiles(
            files = files,
            query = "story",
            filter = LocalImportFilter.ALL,
            importedPaths = emptySet()
        )

        assertEquals(listOf("Story.TXT"), visible.map(File::getName))
    }

    @Test
    fun filtersSeparateReadyImportedAndDirectReadFilesWhileKeepingFolderNavigation() {
        val directory = stubDirectory("/storage/Books/Archive")
        val ready = stubFile("/storage/Books/ready.txt")
        val imported = stubFile("/storage/Books/imported.txt")
        val pdf = stubFile("/storage/Books/manual.pdf")
        val files = listOf(directory, ready, imported, pdf)
        val importedPaths = setOf(imported.absolutePath)

        assertEquals(
            listOf("Archive", "ready.txt"),
            LocalImportListPolicy.visibleFiles(
                files,
                "",
                LocalImportFilter.READY_TO_ADD,
                importedPaths
            ).map(File::getName)
        )
        assertEquals(
            listOf("Archive", "imported.txt"),
            LocalImportListPolicy.visibleFiles(
                files,
                "",
                LocalImportFilter.IMPORTED,
                importedPaths
            ).map(File::getName)
        )
        assertEquals(
            listOf("Archive", "manual.pdf"),
            LocalImportListPolicy.visibleFiles(
                files,
                "",
                LocalImportFilter.DIRECT_READ,
                importedPaths
            ).map(File::getName)
        )
    }

    @Test
    fun uiStateExposesSelectedTabFilterAndSearchToTheUser() {
        val state = LocalImportUiState()
            .selectTab(LocalImportTab.PHONE_DIRECTORY)
            .selectFilter(LocalImportFilter.IMPORTED)
            .updateQuery("三体")

        assertEquals("手机目录", state.toolbarTitle)
        assertEquals("已加入书架", state.filterLabel)
        assertEquals("三体", state.query)
        assertEquals(true, state.hasActiveFilter)
    }

    @Test
    fun restoredUiStateKeepsTabFilterQueryAndVisibleSearch() {
        val restored = LocalImportUiState.restore(
            tabName = LocalImportTab.PHONE_DIRECTORY.name,
            filterName = LocalImportFilter.IMPORTED.name,
            query = "  三体  ",
            searchVisible = true
        )

        assertEquals(LocalImportTab.PHONE_DIRECTORY, restored.tab)
        assertEquals(LocalImportFilter.IMPORTED, restored.filter)
        assertEquals("三体", restored.query)
        assertEquals(true, restored.searchVisible)
    }

    @Test
    fun restoredUiStateRejectsUnknownValuesAndCannotShowSearchOnSafTab() {
        val unknown = LocalImportUiState.restore(
            tabName = "removed-tab",
            filterName = "removed-filter",
            query = "笔记",
            searchVisible = true
        )
        val saf = LocalImportUiState.restore(
            tabName = LocalImportTab.PICK_FILE.name,
            filterName = LocalImportFilter.DIRECT_READ.name,
            query = "文档",
            searchVisible = true
        )

        assertEquals(LocalImportTab.RECENT_FILES, unknown.tab)
        assertEquals(LocalImportFilter.ALL, unknown.filter)
        assertEquals(true, unknown.searchVisible)
        assertEquals(LocalImportTab.PICK_FILE, saf.tab)
        assertEquals(LocalImportFilter.DIRECT_READ, saf.filter)
        assertEquals("", saf.query)
        assertEquals(false, saf.searchVisible)
    }

    private fun stubFile(path: String): File {
        return object : File(path) {
            override fun isFile(): Boolean = true
            override fun isDirectory(): Boolean = false
            override fun length(): Long = 1024L
        }
    }

    private fun stubDirectory(path: String): File {
        return object : File(path) {
            override fun isFile(): Boolean = false
            override fun isDirectory(): Boolean = true
        }
    }
}
