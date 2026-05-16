package com.ldp.reader.model.bean

import kotlin.jvm.JvmName

class LoginResultBean {
    @get:JvmName("isStatus")
    var status: Boolean = false
    var message: String? = null
    var code: Int = 0
    var data: String? = null
}
