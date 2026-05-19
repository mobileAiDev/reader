package com.ldp.reader.sourceengine.model

data class ChapterOrdinalRange(
    val start: Int,
    val end: Int
) {
    override fun toString(): String {
        return if (start == end) start.toString() else "$start-$end"
    }
}
