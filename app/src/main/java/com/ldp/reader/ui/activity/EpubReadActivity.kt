package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.widget.Toolbar
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityEpubReadBinding
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.ui.base.BaseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class EpubReadActivity : BaseActivity<ActivityEpubReadBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var uri: Uri
    private var textZoom = 100

    override fun getViewBinding(): ActivityEpubReadBinding {
        return ActivityEpubReadBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.epubReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.light(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        uri = intent.data ?: Uri.EMPTY
    }

    override fun initClick() {
        super.initClick()
        binding.epubReadDecrease.setOnClickListener {
            textZoom = (textZoom - 10).coerceAtLeast(80)
            applyTextZoom()
        }
        binding.epubReadIncrease.setOnClickListener {
            textZoom = (textZoom + 10).coerceAtMost(160)
            applyTextZoom()
        }
    }

    override fun processLogic() {
        super.processLogic()
        binding.epubReadWeb.settings.javaScriptEnabled = false
        binding.epubReadWeb.settings.builtInZoomControls = true
        binding.epubReadWeb.settings.displayZoomControls = false
        binding.epubReadWeb.settings.loadWithOverviewMode = true
        binding.epubReadWeb.settings.useWideViewPort = false
        binding.epubReadWeb.settings.textZoom = textZoom
        binding.epubReadWeb.isVerticalScrollBarEnabled = true
        scope.launch {
            val (title, html) = withContext(Dispatchers.IO) {
                DocumentFileName.displayName(applicationContext, uri) to readReadableHtml()
            }
            binding.epubReadTitle.text = title
            if (html.isBlank()) {
                binding.epubReadWeb.loadData(readableHtml("<p>未找到正文</p>"), "text/html", "UTF-8")
            } else {
                binding.epubReadWeb.loadDataWithBaseURL(null, readableHtml(html), "text/html", "UTF-8", null)
            }
            applyTextZoom()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        binding.epubReadWeb.destroy()
        super.onDestroy()
    }

    private fun readReadableHtml(): String {
        val sections = ArrayList<String>()
        return contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory &&
                        (name.endsWith(".xhtml") || name.endsWith(".html")) &&
                        !name.endsWith("nav.xhtml") &&
                        !name.endsWith("toc.xhtml")
                    ) {
                        sections.add(zip.bufferedReader(Charsets.UTF_8).readText())
                    }
                }
                sections.joinToString(separator = "\n<hr />\n")
            }
        }.orEmpty()
    }

    private fun applyTextZoom() {
        binding.epubReadWeb.settings.textZoom = textZoom
        binding.epubReadFontSize.text = "$textZoom%"
    }

    private fun readableHtml(body: String): String {
        return """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <style>
                body { background:#F7F3EA; color:#302820; line-height:1.72; padding:18px; font-family: sans-serif; }
                img { max-width:100%; height:auto; }
                p { margin:0 0 1em; }
              </style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, EpubReadActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}
