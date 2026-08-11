package com.ldp.reader.diagnostics

import android.content.Context

internal object ReaderProcessExitReporter {
    fun recordRecent(context: Context) = Unit
}

internal class ReaderProcessMemorySampler {
    fun start(context: Context, bookKey: String, sourceEngineBook: Boolean) = Unit

    fun cancel() = Unit
}
