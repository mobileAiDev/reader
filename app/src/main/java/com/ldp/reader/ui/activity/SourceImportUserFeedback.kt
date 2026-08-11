package com.ldp.reader.ui.activity

internal enum class SourceImportInput {
    NETWORK,
    CONTENT
}

internal object SourceImportUserFeedback {
    fun failureMessage(input: SourceImportInput): String {
        return when (input) {
            SourceImportInput.NETWORK -> "导入失败，请检查地址或网络后重试"
            SourceImportInput.CONTENT -> "没有识别到可用书源"
        }
    }

    fun noUsableSourcesMessage(): String = "没有识别到可用书源"
}
