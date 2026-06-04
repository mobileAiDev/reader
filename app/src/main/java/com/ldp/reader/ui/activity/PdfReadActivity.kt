package com.ldp.reader.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityPdfReadBinding
import com.ldp.reader.document.DocumentFileName
import com.ldp.reader.ui.adapter.PdfPageAdapter
import com.ldp.reader.ui.base.BaseActivity

class PdfReadActivity : BaseActivity<ActivityPdfReadBinding>() {
    private lateinit var uri: Uri
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private var controlsVisible = false
    private val pageAdapter = PdfPageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        MediaUiChrome.prepareImmersiveReader(this)
        super.onCreate(savedInstanceState)
    }

    override fun getViewBinding(): ActivityPdfReadBinding {
        return ActivityPdfReadBinding.inflate(layoutInflater)
    }

    override fun toolbarView(): Toolbar = binding.pdfReadToolbar

    override fun setUpToolbar(toolbar: Toolbar?) {
        super.setUpToolbar(toolbar)
        supportActionBar?.title = ""
        toolbar?.setNavigationIcon(R.drawable.ic_book_detail_back_24)
        MediaUiChrome.immersiveReader(this, controlsVisible)
    }

    override fun initData(savedInstanceState: android.os.Bundle?) {
        super.initData(savedInstanceState)
        uri = intent.data ?: Uri.EMPTY
    }

    override fun initClick() {
        super.initClick()
        binding.pdfReadPrev.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex -= 1
                binding.pdfReadPages.smoothScrollToPosition(pageIndex)
                updatePageState()
            }
        }
        binding.pdfReadNext.setOnClickListener {
            val count = renderer?.pageCount ?: return@setOnClickListener
            if (pageIndex < count - 1) {
                pageIndex += 1
                binding.pdfReadPages.smoothScrollToPosition(pageIndex)
                updatePageState()
            }
        }
        binding.pdfReadCatalog.setOnClickListener { showPageCatalog() }
        binding.root.setOnClickListener { setControlsVisible(!controlsVisible) }
    }

    override fun initWidget() {
        super.initWidget()
        binding.pdfReadPages.layoutManager = LinearLayoutManager(this)
        binding.pdfReadPages.adapter = pageAdapter
        pageAdapter.onPageTap = { setControlsVisible(!controlsVisible) }
        binding.pdfReadPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = manager.findFirstVisibleItemPosition()
                if (firstVisible >= 0) {
                    pageIndex = firstVisible
                    updatePageState()
                }
            }
        })
        setControlsVisible(false)
    }

    override fun processLogic() {
        super.processLogic()
        binding.pdfReadTitle.text = DocumentFileName.displayName(this, uri)
        descriptor = contentResolver.openFileDescriptor(uri, "r")
        renderer = descriptor?.let { PdfRenderer(it) }
        val pdfRenderer = renderer ?: return
        pageAdapter.attach(pdfRenderer, resources.displayMetrics.widthPixels)
        updatePageState()
    }

    override fun onDestroy() {
        renderer?.close()
        descriptor?.close()
        super.onDestroy()
    }

    private fun updatePageState() {
        val pdfRenderer = renderer ?: return
        binding.pdfReadPageNumber.text = "${pageIndex + 1} / ${pdfRenderer.pageCount}"
        binding.pdfReadProgress.text = "${pageIndex + 1} / ${pdfRenderer.pageCount}"
        binding.pdfReadPrev.alpha = if (pageIndex > 0) 1f else 0.42f
        binding.pdfReadNext.alpha = if (pageIndex < pdfRenderer.pageCount - 1) 1f else 0.42f
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val topInset = statusBarHeight()
        val toolbarHeight = dp(56) + topInset
        binding.pdfReadToolbar.layoutParams = binding.pdfReadToolbar.layoutParams.apply {
            height = toolbarHeight
        }
        binding.pdfReadToolbar.setPadding(0, topInset, 0, 0)
        binding.pdfReadToolbar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.pdfReadBottomPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.pdfReadProgress.visibility = if (visible) View.GONE else View.VISIBLE
        binding.pdfReadPages.setPadding(
            0,
            if (visible) toolbarHeight else dp(8),
            0,
            if (visible) dp(76) else dp(32)
        )
        MediaUiChrome.immersiveReader(this, visible)
    }

    private fun showPageCatalog() {
        val pageCount = renderer?.pageCount ?: return
        if (pageCount <= 0) return
        val items = Array(pageCount) { index -> "第 ${index + 1} 页" }
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(items) { dialog, which ->
                pageIndex = which
                binding.pdfReadPages.scrollToPosition(which)
                updatePageState()
                dialog.dismiss()
                setControlsVisible(false)
            }
            .show()
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, PdfReadActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}
