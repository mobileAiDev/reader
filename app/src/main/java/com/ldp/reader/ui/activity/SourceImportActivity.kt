package com.ldp.reader.ui.activity

import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivitySourceImportBinding
import com.ldp.reader.media.ImportedMediaSourceStore
import com.ldp.reader.media.MediaSourceRuntime
import com.ldp.reader.source.ImportedSourceStore
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.utils.ToastUtils
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceImportActivity : BaseActivity<ActivitySourceImportBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jsonPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importFromUri(uri)
        }
    }

    override fun getViewBinding(): ActivitySourceImportBinding {
        return ActivitySourceImportBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar {
        return binding.sourceImportToolbar
    }

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.light(this)
    }

    override fun initClick() {
        super.initClick()
        binding.sourceImportUrlButton.setOnClickListener {
            val url = binding.sourceImportUrlInput.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                ToastUtils.show("请输入源地址")
            } else {
                runImport("正在从网络导入...") {
                    appendSources(readUrlText(url))
                }
            }
        }
        binding.sourceImportJsonButton.setOnClickListener {
            val json = binding.sourceImportJsonInput.text?.toString().orEmpty()
            if (json.isBlank()) {
                ToastUtils.show("请粘贴源 JSON")
            } else {
                runImport("正在导入粘贴内容...") {
                    appendSources(json)
                }
            }
        }
        binding.sourceImportFileButton.setOnClickListener {
            jsonPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
    }

    override fun processLogic() {
        super.processLogic()
        renderIdle()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun importFromUri(uri: Uri) {
        runImport("正在导入本地文件...") {
            val json = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            appendSources(json)
        }
    }

    private fun runImport(
        progressText: String,
        block: () -> SourceImportUiSummary
    ) {
        binding.sourceImportProgress.visibility = View.VISIBLE
        binding.sourceImportResult.text = progressText
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }
            }
            binding.sourceImportProgress.visibility = View.GONE
            result.fold(
                onSuccess = { summary ->
                    MediaSourceRuntime.invalidate()
                    binding.sourceImportResult.text = summary.toDisplayText()
                    ToastUtils.show("源导入完成")
                },
                onFailure = { error ->
                    binding.sourceImportResult.text = "导入失败：${error.message ?: error.javaClass.simpleName}"
                }
            )
        }
    }

    private fun renderIdle() {
        binding.sourceImportProgress.visibility = View.GONE
        binding.sourceImportResult.text =
            "可以导入小说、听书、漫画和文件源。"
    }

    private fun appendSources(json: String): SourceImportUiSummary {
        val novelSummary = ImportedSourceStore.appendFromJson(json)
        val mediaSummary = ImportedMediaSourceStore.appendFromJson(json)
        return SourceImportUiSummary(
            acceptedCount = novelSummary.acceptedCount + mediaSummary.acceptedCount,
            importedCount = novelSummary.importedCount + mediaSummary.importedCount,
            duplicateCount = novelSummary.duplicateCount + mediaSummary.duplicateCount,
            rejectedCount = maxOf(novelSummary.rejectedCount, mediaSummary.rejectedCount),
            textCount = novelSummary.textCount,
            audioCount = mediaSummary.audioCount,
            comicCount = mediaSummary.comicCount,
            fileCount = novelSummary.fileCount
        )
    }

    private fun readUrlText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Reader/SourceImport"
        )
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        connection.setRequestProperty("Referer", "https://www.yckceo.com/")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                error("HTTP $code: ${body.take(120)}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun SourceImportUiSummary.toDisplayText(): String {
        return buildString {
            appendLine("导入完成")
            appendLine("新增：$importedCount")
            appendLine("重复：$duplicateCount")
            appendLine("无效：$rejectedCount")
            appendLine("小说源：$textCount")
            appendLine("听书源：$audioCount")
            appendLine("漫画源：$comicCount")
            appendLine("文件源：$fileCount")
            appendLine()
            append("新增内容已加入搜索。")
        }
    }

    private data class SourceImportUiSummary(
        val acceptedCount: Int,
        val importedCount: Int,
        val duplicateCount: Int,
        val rejectedCount: Int,
        val textCount: Int,
        val audioCount: Int,
        val comicCount: Int,
        val fileCount: Int
    )
}
