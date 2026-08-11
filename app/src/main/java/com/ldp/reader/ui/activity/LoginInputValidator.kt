package com.ldp.reader.ui.activity

internal object LoginInputValidator {

    private val mainlandMobilePattern = Regex("^1[3-9]\\d{9}$")
    private val smsCodePattern = Regex("^[0-9]{4,8}$")

    fun isMainlandMobile(phone: String): Boolean {
        return mainlandMobilePattern.matches(phone)
    }

    fun isSmsCode(code: String): Boolean {
        return smsCodePattern.matches(code)
    }
}
