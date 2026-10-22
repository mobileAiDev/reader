package com.ldp.reader.ui.adapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemShelfHeroBinding
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.ui.adapter.view.CollBookHolder
import com.ldp.reader.ui.home.BookshelfLocalProgressStore
import com.ldp.reader.ui.image.BookClothCover
import com.ldp.reader.ui.theme.InkThemeTraits
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.ui.image.BookshelfCoverCandidates

/** The "continue reading" card: the most recently read book, its progress and one action. */
class ShelfHeroHolder(private val binding: ItemShelfHeroBinding) : RecyclerView.ViewHolder(binding.root) {
    private val bookCovers = InkThemeTraits.of(binding.root.context).bookCovers

    init {
        if (!bookCovers) BookClothCover.styleAsJacket(binding.shelfHeroTextCoverTitle)
    }

    val card: View get() = binding.shelfHeroCard
    val cover: View get() = binding.shelfHeroCover

    fun bind(book: CollBookBean, record: BookRecordBean?) {
        val context = binding.root.context
        binding.shelfHeroTitle.text = book.title
        val coverTitle = CollBookHolder.coverTitle(book.title)
        binding.shelfHeroTextCover.background = BookClothCover.background(coverTitle)
        binding.shelfHeroTextCoverTitle.text = BookClothCover.titleText(coverTitle, bookCovers)
        if (book.isLocal()) {
            binding.shelfHeroCover.visibility = View.GONE
        } else {
            binding.shelfHeroCover.visibility = View.VISIBLE
            BookCoverLoader.load(
                BookshelfCoverCandidates.forBook(book),
                binding.shelfHeroCover,
                R.color.transparent
            )
        }
        val tenths = CollBookHolder.progressTenths(
            book,
            record,
            BookshelfLocalProgressStore.getProgressTenths(book.get_id())
        )
        val author = book.author.orEmpty().trim()
        val progress = if (tenths < 0) {
            context.getString(R.string.shelf_hero_unread)
        } else {
            context.getString(R.string.shelf_hero_progress, formatPercent(tenths))
        }
        binding.shelfHeroMeta.text = if (author.isEmpty()) progress else "$progress · $author"
        binding.shelfHeroProgress.setProgressCompat(tenths.coerceAtLeast(0), false)
        binding.shelfHeroAction.setText(if (tenths < 0) R.string.shelf_hero_start else R.string.shelf_hero_continue)
    }

    private fun formatPercent(tenths: Int): String {
        val whole = tenths / 10
        val decimal = tenths % 10
        return if (decimal == 0) "$whole%" else "$whole.$decimal%"
    }
}
