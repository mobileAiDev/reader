package com.ldp.reader.widget.page

internal data class LocalTxtCatalogFailureDecision(
    val retryPreparation: Boolean,
    val userMessage: String?
)

internal object LocalTxtCatalogFailurePolicy {
    fun after(error: LocalTxtParseException): LocalTxtCatalogFailureDecision {
        return when (error.failure) {
            LocalTxtParseFailure.SOURCE_CHANGED -> LocalTxtCatalogFailureDecision(
                retryPreparation = true,
                userMessage = null
            )
            LocalTxtParseFailure.LINE_TOO_LONG -> LocalTxtCatalogFailureDecision(
                retryPreparation = false,
                userMessage = "文件中有一段内容过长，暂时无法打开"
            )
        }
    }
}
