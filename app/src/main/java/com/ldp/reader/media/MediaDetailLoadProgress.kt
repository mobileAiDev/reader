package com.ldp.reader.media

object MediaDetailLoadProgress {
    enum class Step(val percent: Int, val label: String) {
        STARTED(8, "准备作品信息"),
        DETAIL(34, "分析作品信息"),
        CATALOG(72, "整理章节目录"),
        READY(100, "目录准备完成"),
        FAILED(100, "分析失败")
    }
}
