package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityLocalComicReadBinding
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.media.MediaRequest
import com.ldp.reader.ui.adapter.ComicPageAdapter
import com.ldp.reader.ui.base.BaseActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class LocalComicReadActivity : BaseActivity<ActivityLocalComicReadBinding>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pageAdapter = ComicPageAdapter()
    private lateinit var uri: Uri

    override fun getViewBinding(): ActivityLocalComicReadBinding {
        return ActivityLocalComicReadBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.localComicReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.darkReader(this)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        uri = intent.data ?: Uri.EMPTY
    }

    override fun initWidget() {
        super.initWidget()
        binding.localComicReadTitle.text = DocumentFileName.displayName(this, uri)
        binding.localComicReadPages.layoutManager = LinearLayoutManager(this)
        binding.localComicReadPages.adapter = pageAdapter
    }

    override fun processLogic() {
        super.processLogic()
        binding.localComicReadState.text = "加载中..."
        scope.launch {
            val images = withContext(Dispatchers.IO) { unzipImages().map { MediaRequest(it) } }
            pageAdapter.refreshItems(images)
            binding.localComicReadState.text = if (images.isEmpty()) "图片加载失败" else "${images.size} 页"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun unzipImages(): List<String> {
        val outDir = File(cacheDir, "local-comic/${System.currentTimeMillis()}").apply { mkdirs() }
        val imageFiles = ArrayList<Pair<String, File>>()
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val lower = name.lowercase()
                    if (entry.isDirectory || !isImage(lower)) continue
                    val outFile = File(outDir, name.substringAfterLast('/'))
                    outFile.outputStream().use { output -> zip.copyTo(output) }
                    imageFiles.add(name to outFile)
                }
            }
        }
        return imageFiles.sortedBy { it.first }.map { it.second.absolutePath }
    }

    private fun isImage(name: String): Boolean {
        return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp") ||
            name.endsWith(".gif")
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, LocalComicReadActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}
