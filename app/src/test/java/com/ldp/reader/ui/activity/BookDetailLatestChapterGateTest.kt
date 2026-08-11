package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDetailLatestChapterGateTest {
    @Test
    fun `source detail waits for verified catalog before publishing a stable latest chapter`() {
        val gate = BookDetailLatestChapterGate()

        assertEquals(
            BookDetailLatestChapterUiState.Loading,
            gate.beginSession(routeId = "source-route", sourceRoute = true)
        )
        assertEquals(
            BookDetailLatestChapterUiState.Loading,
            gate.acceptUnverifiedMetadata(
                routeId = "source-route",
                candidate = "第一千五百九十三章 路由末章"
            )
        )
        assertEquals(
            BookDetailLatestChapterUiState.Loading,
            gate.acceptUnverifiedMetadata(
                routeId = "source-route",
                candidate = "第一千六百二十三章 详情末章"
            )
        )
        assertEquals(
            BookDetailLatestChapterUiState.Ready("第一千六百二十三章 目录末章"),
            gate.acceptVerifiedCatalog(
                routeId = "source-route",
                candidate = "第一千六百二十三章 目录末章"
            )
        )
        assertEquals(
            BookDetailLatestChapterUiState.Ready("第一千六百二十三章 目录末章"),
            gate.acceptUnverifiedMetadata(
                routeId = "source-route",
                candidate = "第一千五百九十三章 迟到的路由末章"
            )
        )
    }

    @Test
    fun `resolved legacy detail without a latest chapter is no longer presented as loading`() {
        val gate = BookDetailLatestChapterGate()

        gate.beginSession(routeId = "legacy-book-id", sourceRoute = false)

        assertEquals(
            BookDetailLatestChapterUiState.Unavailable,
            gate.acceptUnverifiedMetadata(
                routeId = "legacy-book-id",
                candidate = "  "
            )
        )
    }
}
