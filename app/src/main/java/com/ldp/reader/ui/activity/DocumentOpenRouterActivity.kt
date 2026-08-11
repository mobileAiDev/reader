package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.appcompat.app.AppCompatActivity
import com.ldp.reader.R
import com.ldp.reader.document.DocumentFormat
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.document.LocalTextImportStore
import com.ldp.reader.document.LocalTextImportResult
import com.ldp.reader.document.LocalTextImportUserMessage
import com.ldp.reader.document.ReaderDocumentFormat
import com.ldp.reader.utils.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class DocumentOpenRouterActivity : AppCompatActivity() {
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_open_router)
        val uri = intent.data ?: intent.getParcelableExtra(EXTRA_URI)
        if (uri == null) {
            ToastUtils.show("未找到文件")
            finish()
            return
        }
        persistDocumentPermission(uri)
        routeDocument(uri)
    }

    override fun onDestroy() {
        importScope.cancel()
        super.onDestroy()
    }

    private fun importText(uri: Uri) {
        importScope.launch {
            val importResult = try {
                runInterruptible(Dispatchers.IO) {
                    LocalTextImportStore.importUri(applicationContext, uri)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LocalTextImportResult.Failure
            }
            currentCoroutineContext().ensureActive()
            when (importResult) {
                is LocalTextImportResult.Success -> {
                    ReadActivity.startActivity(
                        this@DocumentOpenRouterActivity,
                        importResult.book,
                        true
                    )
                }
                else -> ToastUtils.show(LocalTextImportUserMessage.forResult(importResult))
            }
            finish()
        }
    }

    private fun routeDocument(uri: Uri) {
        importScope.launch {
            val routeContext = currentCoroutineContext()
            val cancellationSignal = CancellationSignal()
            val cancellationWatcher = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    cancellationSignal.cancel()
                }
            }
            val extension = try {
                withContext(Dispatchers.IO) {
                    DocumentFileName.extension(applicationContext, uri, cancellationSignal)
                }
            } catch (_: OperationCanceledException) {
                routeContext.ensureActive()
                ToastUtils.show("无法识别文件格式")
                finish()
                return@launch
            } finally {
                cancellationWatcher.cancel()
            }
            routeContext.ensureActive()
            if (extension.isBlank()) {
                ToastUtils.show("无法识别文件格式")
                finish()
                return@launch
            }
            when (DocumentFormat.fromExtension(extension)) {
                ReaderDocumentFormat.TEXT -> {
                    importText(uri)
                    return@launch
                }
                ReaderDocumentFormat.PDF -> PdfReadActivity.start(this@DocumentOpenRouterActivity, uri)
                ReaderDocumentFormat.EPUB -> EpubReadActivity.start(this@DocumentOpenRouterActivity, uri)
                ReaderDocumentFormat.LOCAL_COMIC -> LocalComicReadActivity.start(this@DocumentOpenRouterActivity, uri)
                ReaderDocumentFormat.UNSUPPORTED -> ToastUtils.show("暂不支持该文件格式")
            }
            finish()
        }
    }

    private fun persistDocumentPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, DocumentOpenRouterActivity::class.java)
                    .setData(uri)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    .putExtra(EXTRA_URI, uri)
            )
        }
    }
}
