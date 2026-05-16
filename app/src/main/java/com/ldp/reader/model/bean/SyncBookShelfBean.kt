package com.ldp.reader.model.bean

import kotlin.jvm.JvmName

class SyncBookShelfBean {
    @get:JvmName("isStatus")
    var status: Boolean = false
    var message: String? = null
    var code: Int = 0
    var data: DataBean? = null

    class DataBean {
        var userId: Int = 0
        var username: String? = null
        var userBookList: List<UserBookListBean>? = null

        class UserBookListBean {
            var userId: Int = 0
            var bookId: Int = 0
        }
    }
}
