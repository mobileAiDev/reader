package com.ldp.reader.model.bean

import kotlin.jvm.JvmName

class DirectLoginResultBean {
    var error: Any? = null
    var res: ResBean? = null
    var status: Int = 0

    class ResBean {
        @get:JvmName("getIsValid")
        @set:JvmName("setIsValid")
        var isValid: Int = 0
        var phone: String? = null
        var nickName: String? = null
        var openId: String? = null
        var userIconUrl: String? = null
        var userIconUrl2: String? = null
        var userIconUrl3: String? = null
        var email: String? = null
        var operator: String? = null
        var mobileToken: String? = null
    }
}
