package com.ldp.reader.ui.home

import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.utils.BookIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookshelfPinStoreTest {
    @Test
    fun localIdentityFollowsTheSourceFileAcrossRefreshedBeans() {
        val beforeRefresh = book(
            id = "old-local-id",
            title = "本地书旧标题",
            cover = "/storage/emulated/0/Books/story.txt",
            local = true
        )
        val afterRefresh = book(
            id = "new-local-id",
            title = "本地书新标题",
            cover = "/storage/emulated/0/Books/story.txt",
            local = true
        )

        assertEquals(
            BookshelfPinIdentity.key(beforeRefresh),
            BookshelfPinIdentity.key(afterRefresh)
        )
    }

    @Test
    fun localAndOnlineBooksNeverShareAPinIdentity() {
        val local = book("same-id", "同名书", "/books/story.txt", local = true)
        val online = book("same-id", "同名书", "https://img.test/story.jpg", local = false)

        assertNotEquals(BookshelfPinIdentity.key(local), BookshelfPinIdentity.key(online))
    }

    @Test
    fun sourceEngineIdentitySurvivesRouteIdNormalization() {
        val routeBean = book(
            id = "source_engine_book_encoded-route",
            title = "《诡秘之主》",
            cover = "https://img.test/old.jpg",
            local = false,
            author = "爱潜水的乌贼"
        )
        val shelfBean = book(
            id = BookIdentity.sourceEngineShelfId("诡秘之主", "爱潜水的乌贼"),
            title = "诡秘之主",
            cover = "https://img.test/new.jpg",
            local = false,
            author = "爱潜水的乌贼"
        )

        assertEquals(
            BookshelfPinIdentity.key(routeBean),
            BookshelfPinIdentity.key(shelfBean)
        )
    }

    @Test
    fun pinnedPartitionIsStableAcrossRefreshAndFiltering() {
        val regularOne = book("regular-1", "普通一", "", local = false)
        val pinnedOne = book("pinned-1", "置顶一", "", local = false)
        val regularTwo = book("regular-2", "普通二", "", local = false)
        val pinnedTwo = book("pinned-2", "置顶二", "", local = false)
        val pinnedKeys = setOf(
            BookshelfPinIdentity.key(pinnedOne),
            BookshelfPinIdentity.key(pinnedTwo)
        )

        val first = BookshelfPinOrdering.partition(
            listOf(regularOne, pinnedOne, regularTwo, pinnedTwo),
            pinnedKeys
        )
        val filtered = BookshelfPinOrdering.partition(
            listOf(pinnedOne, regularTwo, pinnedTwo),
            pinnedKeys
        )

        assertEquals(listOf(pinnedOne, pinnedTwo), first.pinned)
        assertEquals(listOf(regularOne, regularTwo), first.regular)
        assertEquals(listOf(pinnedOne, pinnedTwo), filtered.pinned)
        assertEquals(listOf(regularTwo), filtered.regular)
    }

    private fun book(
        id: String,
        title: String,
        cover: String,
        local: Boolean,
        author: String = ""
    ): CollBookBean {
        return CollBookBean().apply {
            set_id(id)
            this.title = title
            this.author = author
            this.cover = cover
            setLocal(local)
        }
    }
}
