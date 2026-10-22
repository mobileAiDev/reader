package com.ldp.reader.ui.adapter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.ui.theme.GlareGuard
import com.ldp.reader.databinding.ItemPdfPageBinding
import com.ldp.reader.document.pdf.PdfReadPolicy
import com.ldp.reader.document.pdf.PdfRenderSize
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class PdfPageAdapter : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {
    private var renderer: PdfRenderer? = null
    private var renderDispatcher: SerialPdfRenderDispatcher<PdfPageRenderResult>? = null
    private var pageCount = 0
    private var targetWidth: Int = 1
    private val mainHandler = Handler(Looper.getMainLooper())
    var onPageTap: (() -> Unit)? = null
    var onPageRenderFailure: ((position: Int, message: String) -> Unit)? = null

    fun attach(renderer: PdfRenderer, pageCount: Int, targetWidth: Int) {
        detach()
        this.renderer = renderer
        renderDispatcher = SerialPdfRenderDispatcher(
            worker = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "reader-pdf-render")
            },
            resultExecutor = Executor { command -> mainHandler.post(command) }
        )
        this.pageCount = pageCount
        this.targetWidth = targetWidth.coerceAtLeast(1)
        notifyDataSetChanged()
    }

    fun detach() {
        val rendererToClose = renderer
        val dispatcherToClose = renderDispatcher
        renderer = null
        renderDispatcher = null
        pageCount = 0
        dispatcherToClose?.close {
            if (rendererToClose != null) {
                try {
                    rendererToClose.close()
                } catch (_: Exception) {
                    // Rendering has stopped and there is no useful recovery during teardown.
                }
            }
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        return PageHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val bindingGeneration = holder.beginBinding()
        val pdfRenderer = renderer
        val dispatcher = renderDispatcher
        if (pdfRenderer == null || dispatcher == null) return
        if (position !in 0 until pageCount) {
            Log.w(TAG, "PDF page index is outside the document: index=$position count=$pageCount")
            showFailure(holder, position, PdfReadPolicy.pageDisplayFailure(position))
            return
        }
        val request = dispatcher.submit(
            work = { renderPage(pdfRenderer, position, targetWidth) },
            onResult = { result ->
                if (!holder.isCurrent(bindingGeneration)) {
                    result.recycle()
                    return@submit
                }
                holder.complete(bindingGeneration)
                when (result) {
                    is PdfPageRenderResult.Ready -> {
                        holder.showBitmap(result.bitmap) { onPageTap?.invoke() }
                    }
                    is PdfPageRenderResult.Failed -> {
                        showFailure(holder, position, result.message)
                    }
                }
            },
            onDiscard = PdfPageRenderResult::recycle
        )
        holder.attach(bindingGeneration, request) { dispatcher.cancel(it) }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    private fun renderPage(
        pdfRenderer: PdfRenderer,
        position: Int,
        requestedWidth: Int
    ): PdfPageRenderResult {
        var bitmap: Bitmap? = null
        try {
            pdfRenderer.openPage(position).use { page ->
                when (val size = PdfReadPolicy.renderSize(requestedWidth, page.width, page.height)) {
                    is PdfRenderSize.Rejected -> {
                        Log.w(TAG, "PDF page rejected: index=$position detail=${size.message}")
                        return PdfPageRenderResult.Failed(PdfReadPolicy.pageDisplayFailure(position))
                    }
                    is PdfRenderSize.Ready -> {
                        bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return PdfPageRenderResult.Ready(bitmap).also { bitmap = null }
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "PDF page display failed: index=$position", error)
            return PdfPageRenderResult.Failed(PdfReadPolicy.pageRenderFailure(position, error))
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "PDF page display ran out of memory: index=$position", error)
            return PdfPageRenderResult.Failed(PdfReadPolicy.pageRenderFailure(position, error))
        } finally {
            bitmap?.recycle()
        }
    }

    private fun showFailure(holder: PageHolder, position: Int, message: String) {
        holder.showError(message) { onPageTap?.invoke() }
        onPageRenderFailure?.invoke(position, message)
    }

    private companion object {
        const val TAG = "PdfPageAdapter"
    }

    class PageHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        private var renderedBitmap: Bitmap? = null
        private var bindingGeneration = 0L
        private var cancelRender: (() -> Unit)? = null
        private val errorView = TextView(binding.root.context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.media_reader_text))
            setBackgroundColor(ContextCompat.getColor(context, R.color.ink_reader_panel))
            val horizontalPadding = (24 * resources.displayMetrics.density + 0.5f).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            visibility = View.GONE
        }

        init {
            val height = (240 * binding.root.resources.displayMetrics.density + 0.5f).toInt()
            binding.root.addView(
                errorView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            )
        }

        internal fun beginBinding(): Long {
            invalidateBinding()
            clearPresentation()
            return bindingGeneration
        }

        internal fun attach(
            generation: Long,
            request: SerialPdfRenderDispatcher.Request,
            cancel: (SerialPdfRenderDispatcher.Request) -> Unit
        ) {
            if (generation != bindingGeneration) {
                cancel(request)
                return
            }
            cancelRender = { cancel(request) }
        }

        internal fun isCurrent(generation: Long): Boolean = generation == bindingGeneration

        internal fun complete(generation: Long) {
            if (generation == bindingGeneration) cancelRender = null
        }

        fun showBitmap(bitmap: Bitmap, onTap: () -> Unit) {
            clearBitmap()
            renderedBitmap = bitmap
            errorView.text = null
            errorView.contentDescription = null
            errorView.setOnClickListener(null)
            errorView.visibility = View.GONE
            binding.pdfPageImage.visibility = View.VISIBLE
            binding.pdfPageImage.contentDescription = null
            binding.pdfPageImage.setImageBitmap(bitmap)
            GlareGuard.apply(binding.pdfPageImage, bitmap)
            binding.pdfPageImage.minimumScale = 1f
            binding.pdfPageImage.mediumScale = 2.25f
            binding.pdfPageImage.maximumScale = 5f
            binding.pdfPageImage.setOnViewTapListener { _, _, _ -> onTap() }
        }

        fun showError(message: String, onTap: () -> Unit) {
            clearBitmap()
            binding.pdfPageImage.visibility = View.GONE
            binding.pdfPageImage.contentDescription = message
            errorView.text = message
            errorView.contentDescription = message
            errorView.setOnClickListener { onTap() }
            errorView.visibility = View.VISIBLE
        }

        fun clear() {
            invalidateBinding()
            clearPresentation()
        }

        private fun invalidateBinding() {
            bindingGeneration += 1
            cancelRender?.invoke()
            cancelRender = null
        }

        private fun clearPresentation() {
            clearBitmap()
            binding.pdfPageImage.visibility = View.VISIBLE
            binding.pdfPageImage.contentDescription = null
            errorView.setOnClickListener(null)
            errorView.contentDescription = null
            errorView.visibility = View.GONE
            errorView.text = null
        }

        private fun clearBitmap() {
            binding.pdfPageImage.setOnViewTapListener(null)
            binding.pdfPageImage.setImageDrawable(null)
            renderedBitmap?.takeUnless { it.isRecycled }?.recycle()
            renderedBitmap = null
        }
    }
}

internal sealed interface PdfPageRenderResult {
    data class Ready(val bitmap: Bitmap) : PdfPageRenderResult
    data class Failed(val message: String) : PdfPageRenderResult

    fun recycle() {
        if (this is Ready && !bitmap.isRecycled) bitmap.recycle()
    }
}

internal class SerialPdfRenderDispatcher<T>(
    private val worker: ExecutorService,
    private val resultExecutor: Executor
) {
    private val lock = Any()
    private val requests = HashSet<Request>()
    private var open = true

    fun submit(
        work: () -> T,
        onResult: (T) -> Unit,
        onDiscard: (T) -> Unit
    ): Request {
        val request = Request()
        synchronized(lock) {
            check(open) { "PDF render dispatcher is closed" }
            requests += request
        }
        val future = worker.submit {
            if (request.isCancelled()) return@submit
            val result = work()
            resultExecutor.execute {
                val deliver = synchronized(lock) {
                    requests.remove(request) && open && !request.isCancelled()
                }
                if (deliver) onResult(result) else onDiscard(result)
            }
        }
        request.attach(future)
        return request
    }

    fun cancel(request: Request) {
        synchronized(lock) {
            requests.remove(request)
        }
        request.cancel()
    }

    fun close(closeAction: () -> Unit) {
        val pending = synchronized(lock) {
            if (!open) return
            open = false
            requests.toList().also { requests.clear() }
        }
        pending.forEach(Request::cancel)
        worker.execute {
            try {
                closeAction()
            } finally {
                worker.shutdown()
            }
        }
    }

    internal class Request {
        private val cancelled = AtomicBoolean(false)

        @Volatile
        private var future: Future<*>? = null

        fun attach(future: Future<*>) {
            this.future = future
            if (cancelled.get()) future.cancel(false)
        }

        fun cancel() {
            cancelled.set(true)
            future?.cancel(false)
        }

        fun isCancelled(): Boolean = cancelled.get()
    }
}
