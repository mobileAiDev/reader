package com.ldp.reader.ui.adapter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.databinding.ItemPdfPageBinding

class PdfPageAdapter : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {
    private var renderer: PdfRenderer? = null
    private var targetWidth: Int = 1
    var onPageTap: (() -> Unit)? = null

    fun attach(renderer: PdfRenderer, targetWidth: Int) {
        this.renderer = renderer
        this.targetWidth = targetWidth.coerceAtLeast(1)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = renderer?.pageCount ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        return PageHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val pdfRenderer = renderer ?: return
        pdfRenderer.openPage(position).use { page ->
            val targetHeight = (targetWidth * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            holder.binding.pdfPageImage.setImageBitmap(bitmap)
            holder.binding.pdfPageImage.minimumScale = 1f
            holder.binding.pdfPageImage.mediumScale = 2.25f
            holder.binding.pdfPageImage.maximumScale = 5f
            holder.binding.pdfPageImage.setOnViewTapListener { _, _, _ -> onPageTap?.invoke() }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.binding.pdfPageImage.setOnViewTapListener(null)
        holder.binding.pdfPageImage.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    class PageHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root)
}
