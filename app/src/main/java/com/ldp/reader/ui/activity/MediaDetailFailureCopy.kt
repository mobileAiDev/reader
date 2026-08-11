package com.ldp.reader.ui.activity

internal enum class MediaDetailFailureStage {
    DETAIL,
    CATALOG
}

internal object MediaDetailFailureCopy {
    fun messageFor(stage: MediaDetailFailureStage): String {
        return when (stage) {
            MediaDetailFailureStage.DETAIL -> "暂时无法加载作品信息，点击重试"
            MediaDetailFailureStage.CATALOG -> "暂时无法加载目录，点击重试"
        }
    }
}
