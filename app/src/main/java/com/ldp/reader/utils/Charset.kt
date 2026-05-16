package com.ldp.reader.utils

enum class Charset(private val charsetName: String) {
    UTF8("UTF-8"),
    UTF16LE("UTF-16LE"),
    UTF16BE("UTF-16BE"),
    GBK("GBK");

    fun getName(): String = charsetName

    companion object {
        const val BLANK: Byte = 0x0a
    }
}
