package com.ldp.reader.media

object MediaDetailLoadProgress {
    enum class Step(val label: String) {
        STARTED("正在加载作品信息"),
        DETAIL("正在加载作品信息"),
        CATALOG("正在加载目录"),
        READY("加载完成"),
        FAILED("暂时无法加载目录，点击重试")
    }
}
