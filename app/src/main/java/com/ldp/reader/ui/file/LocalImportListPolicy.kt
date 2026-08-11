package com.ldp.reader.ui.file

import com.ldp.reader.utils.LocalBookImportFiles
import java.io.File

enum class LocalImportFilter(val label: String) {
    ALL("全部文件"),
    READY_TO_ADD("可加入书架"),
    IMPORTED("已加入书架"),
    DIRECT_READ("直接阅读")
}

enum class LocalImportTab(val pageIndex: Int, val title: String) {
    RECENT_FILES(0, "智能导入"),
    PHONE_DIRECTORY(1, "手机目录"),
    PICK_FILE(2, "选择文件");

    companion object {
        fun fromPageIndex(pageIndex: Int): LocalImportTab {
            return entries.firstOrNull { it.pageIndex == pageIndex } ?: RECENT_FILES
        }
    }
}

data class LocalImportUiState(
    val tab: LocalImportTab = LocalImportTab.RECENT_FILES,
    val filter: LocalImportFilter = LocalImportFilter.ALL,
    val query: String = "",
    val searchVisible: Boolean = false
) {
    val toolbarTitle: String
        get() = tab.title

    val filterLabel: String
        get() = filter.label

    val hasActiveFilter: Boolean
        get() = filter != LocalImportFilter.ALL

    fun selectTab(tab: LocalImportTab): LocalImportUiState {
        return if (tab == LocalImportTab.PICK_FILE) {
            copy(tab = tab, query = "", searchVisible = false)
        } else {
            copy(tab = tab)
        }
    }

    fun selectFilter(filter: LocalImportFilter): LocalImportUiState = copy(filter = filter)

    fun updateQuery(query: String): LocalImportUiState = copy(query = query.trim())

    fun showSearch(): LocalImportUiState {
        return if (tab == LocalImportTab.PICK_FILE) this else copy(searchVisible = true)
    }

    fun clearSearch(): LocalImportUiState = copy(query = "", searchVisible = false)

    companion object {
        fun restore(
            tabName: String?,
            filterName: String?,
            query: String?,
            searchVisible: Boolean
        ): LocalImportUiState {
            val tab = LocalImportTab.entries.firstOrNull { it.name == tabName }
                ?: LocalImportTab.RECENT_FILES
            val filter = LocalImportFilter.entries.firstOrNull { it.name == filterName }
                ?: LocalImportFilter.ALL
            return LocalImportUiState(
                tab = tab,
                filter = filter,
                query = query.orEmpty().trim(),
                searchVisible = searchVisible
            ).selectTab(tab)
        }
    }
}

object LocalImportListPolicy {
    fun visibleFiles(
        files: List<File>,
        query: String,
        filter: LocalImportFilter,
        importedPaths: Set<String>
    ): List<File> {
        val normalizedQuery = query.trim()
        return files.filter { file ->
            val matchesQuery = normalizedQuery.isEmpty() ||
                file.name.contains(normalizedQuery, ignoreCase = true)
            matchesQuery && matchesFilter(file, filter, importedPaths)
        }
    }

    private fun matchesFilter(
        file: File,
        filter: LocalImportFilter,
        importedPaths: Set<String>
    ): Boolean {
        if (file.isDirectory) {
            return true
        }
        val imported = file.absolutePath in importedPaths
        return when (filter) {
            LocalImportFilter.ALL -> LocalBookImportFiles.isImportableFile(file)
            LocalImportFilter.READY_TO_ADD -> LocalBookImportFiles.isTextFile(file) && !imported
            LocalImportFilter.IMPORTED -> LocalBookImportFiles.isTextFile(file) && imported
            LocalImportFilter.DIRECT_READ -> LocalBookImportFiles.isOpenableDocument(file)
        }
    }
}
