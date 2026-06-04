package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ldp.reader.document.DocumentFormat
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.document.LocalTextImportStore
import com.ldp.reader.document.ReaderDocumentFormat
import com.ldp.reader.utils.ToastUtils

class DocumentOpenRouterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: intent.getParcelableExtra(EXTRA_URI)
        if (uri == null) {
            ToastUtils.show("未找到文件")
            finish()
            return
        }
        persistReadPermission(uri)
        val extension = DocumentFileName.extension(this, uri)
        when (DocumentFormat.fromExtension(extension)) {
            ReaderDocumentFormat.TEXT -> {
                val collBook = LocalTextImportStore.importUri(this, uri)
                if (collBook == null) {
                    ToastUtils.show("导入失败")
                } else {
                    ReadActivity.startActivity(this, collBook, true)
                }
            }
            ReaderDocumentFormat.PDF -> PdfReadActivity.start(this, uri)
            ReaderDocumentFormat.EPUB -> EpubReadActivity.start(this, uri)
            ReaderDocumentFormat.LOCAL_COMIC -> LocalComicReadActivity.start(this, uri)
            ReaderDocumentFormat.UNSUPPORTED -> ToastUtils.show("暂不支持该文件格式")
        }
        finish()
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, DocumentOpenRouterActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(EXTRA_URI, uri)
            )
        }
    }
}
