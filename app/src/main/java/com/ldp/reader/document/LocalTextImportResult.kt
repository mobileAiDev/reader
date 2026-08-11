package com.ldp.reader.document

import com.ldp.reader.model.bean.CollBookBean

internal sealed interface LocalTextImportResult {
    data class Success(val book: CollBookBean) : LocalTextImportResult
    data object Empty : LocalTextImportResult
    data object TooLarge : LocalTextImportResult
    data object LineTooLong : LocalTextImportResult
    data object Failure : LocalTextImportResult
}

internal object LocalTextImportUserMessage {
    fun forResult(result: LocalTextImportResult): String? = when (result) {
        is LocalTextImportResult.Success -> null
        LocalTextImportResult.Empty -> "文件中没有可阅读内容"
        LocalTextImportResult.TooLarge -> "文件过大，暂时无法导入"
        LocalTextImportResult.LineTooLong -> "文件中有一段内容过长，暂时无法打开"
        LocalTextImportResult.Failure -> "导入失败，请重试"
    }
}
