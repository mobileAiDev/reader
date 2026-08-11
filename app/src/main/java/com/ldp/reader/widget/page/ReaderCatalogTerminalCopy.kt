package com.ldp.reader.widget.page

internal enum class ReaderCatalogTerminalState {
    LOAD_FAILED,
    CONFIRMED_EMPTY
}

internal object ReaderCatalogTerminalCopy {
    fun messageFor(state: ReaderCatalogTerminalState): String {
        return when (state) {
            ReaderCatalogTerminalState.LOAD_FAILED ->
                "暂时无法加载目录，轻点屏幕左右两侧重试"
            ReaderCatalogTerminalState.CONFIRMED_EMPTY ->
                "这本书暂时没有可阅读章节"
        }
    }
}
