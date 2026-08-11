package com.ldp.reader.ui.file

/**
 * Configuration-safe navigation state for the manual phone-directory browser.
 *
 * The UI owns file loading and selection. This class only owns the current path and the exact
 * back-stack entries (including the scroll offset to restore when returning to a parent).
 */
internal class LocalDirectoryNavigationState private constructor(
    val rootPath: String,
    currentPath: String,
    history: List<LocalDirectoryHistoryEntry>
) {
    var currentPath: String = currentPath
        private set

    private val history = ArrayDeque(history)

    val canNavigateBack: Boolean
        get() = history.isNotEmpty()

    fun enter(path: String, currentScrollOffset: Int): Boolean {
        val normalized = path.trim()
        if (normalized.isEmpty() || normalized == currentPath) return false
        history.addFirst(
            LocalDirectoryHistoryEntry(
                path = currentPath,
                scrollOffset = currentScrollOffset.coerceAtLeast(0)
            )
        )
        currentPath = normalized
        return true
    }

    fun navigateBack(): LocalDirectoryHistoryEntry? {
        if (history.isEmpty()) return null
        val destination = history.removeFirst()
        currentPath = destination.path
        return destination
    }

    fun historySnapshot(): List<LocalDirectoryHistoryEntry> = history.toList()

    companion object {
        fun create(rootPath: String): LocalDirectoryNavigationState {
            return LocalDirectoryNavigationState(
                rootPath = rootPath,
                currentPath = rootPath,
                history = emptyList()
            )
        }

        fun restore(
            rootPath: String,
            currentPath: String?,
            historyPaths: List<String>?,
            historyScrollOffsets: IntArray?
        ): LocalDirectoryNavigationState {
            val restoredCurrent = currentPath?.trim()?.takeIf(String::isNotBlank) ?: rootPath
            val paths = historyPaths.orEmpty()
            val offsets = historyScrollOffsets ?: intArrayOf()
            val restoredHistory = paths.mapIndexedNotNull { index, path ->
                path.trim().takeIf(String::isNotBlank)?.let {
                    LocalDirectoryHistoryEntry(
                        path = it,
                        scrollOffset = offsets.getOrElse(index) { 0 }.coerceAtLeast(0)
                    )
                }
            }
            return LocalDirectoryNavigationState(
                rootPath = rootPath,
                currentPath = restoredCurrent,
                history = restoredHistory
            )
        }
    }
}

internal data class LocalDirectoryHistoryEntry(
    val path: String,
    val scrollOffset: Int
)
