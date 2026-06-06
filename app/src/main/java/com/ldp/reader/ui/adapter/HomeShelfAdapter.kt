package com.ldp.reader.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.audio.AudioPlaybackProgressStore
import com.ldp.reader.databinding.ItemHomeShelfMediaBinding
import com.ldp.reader.media.ComicReadingProgressStore
import com.ldp.reader.media.MediaShelfItem
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.ui.adapter.view.CollBookHolder
import com.ldp.reader.ui.base.adapter.BaseViewHolder
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.ui.image.BookCoverLoader

class HomeShelfAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), BookSelectionState {
    private val shelfItems = ArrayList<HomeShelfItem>()
    private val selectedBookKeys = HashSet<String>()
    override var isEditMode = false
        private set
    var onBookClick: ((View?, CollBookBean) -> Unit)? = null
    var onBookLongClick: ((View?, CollBookBean) -> Boolean)? = null
    var onMediaClick: ((View?, MediaShelfItem) -> Unit)? = null
    var onMediaLongClick: ((View?, MediaShelfItem) -> Boolean)? = null

    val items: List<HomeShelfItem>
        get() = shelfItems.toList()

    val bookItems: List<CollBookBean>
        get() = shelfItems.mapNotNull { (it as? HomeShelfItem.Book)?.book }

    val selectedBooks: List<CollBookBean>
        get() = bookItems.filter { selectedBookKeys.contains(CollBookAdapter.selectionKey(it)) }

    val selectedCount: Int
        get() = selectedBooks.size

    val visibleBookCount: Int
        get() = bookItems.size

    val isAllVisibleSelected: Boolean
        get() {
            val books = bookItems
            return books.isNotEmpty() && books.all { selectedBookKeys.contains(CollBookAdapter.selectionKey(it)) }
        }

    override fun getItemCount(): Int = shelfItems.size

    override fun getItemViewType(position: Int): Int {
        return when (shelfItems[position]) {
            is HomeShelfItem.Book -> TYPE_BOOK
            is HomeShelfItem.Media -> TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_BOOK) {
            val holder = CollBookHolder(this)
            BaseViewHolder(holder.createItemView(parent), holder)
        } else {
            MediaHolder(
                ItemHomeShelfMediaBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = shelfItems[position]) {
            is HomeShelfItem.Book -> bindBook(holder, item.book, position)
            is HomeShelfItem.Media -> bindMedia(holder as MediaHolder, item.item)
        }
    }

    fun refreshItems(books: List<CollBookBean>, mediaItems: List<MediaShelfItem>) {
        selectedBookKeys.retainAll(books.map { CollBookAdapter.selectionKey(it) }.toSet())
        shelfItems.clear()
        shelfItems.addAll(mediaItems.map { HomeShelfItem.Media(it) })
        shelfItems.addAll(books.map { HomeShelfItem.Book(it) })
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): HomeShelfItem = shelfItems[position]

    fun setEditMode(editMode: Boolean) {
        isEditMode = editMode
        if (!editMode) {
            selectedBookKeys.clear()
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(book: CollBookBean?) {
        val key = CollBookAdapter.selectionKey(book)
        if (key.isBlank()) return
        if (selectedBookKeys.contains(key)) {
            selectedBookKeys.remove(key)
        } else {
            selectedBookKeys.add(key)
        }
        notifyDataSetChanged()
    }

    override fun isSelected(book: CollBookBean?): Boolean {
        return selectedBookKeys.contains(CollBookAdapter.selectionKey(book))
    }

    fun selectAllVisible() {
        bookItems.forEach { selectedBookKeys.add(CollBookAdapter.selectionKey(it)) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedBookKeys.clear()
        notifyDataSetChanged()
    }

    fun removeBook(book: CollBookBean) {
        selectedBookKeys.remove(CollBookAdapter.selectionKey(book))
        shelfItems.removeAll { (it as? HomeShelfItem.Book)?.book == book }
        notifyDataSetChanged()
    }

    fun removeBooks(books: List<CollBookBean>) {
        val keys = books.map { CollBookAdapter.selectionKey(it) }.toSet()
        selectedBookKeys.removeAll(keys)
        shelfItems.removeAll { item -> (item as? HomeShelfItem.Book)?.book?.let { keys.contains(CollBookAdapter.selectionKey(it)) } == true }
        notifyDataSetChanged()
    }

    fun removeMedia(itemId: String) {
        shelfItems.removeAll { (it as? HomeShelfItem.Media)?.item?.id == itemId }
        notifyDataSetChanged()
    }

    private fun bindBook(holder: RecyclerView.ViewHolder, book: CollBookBean, position: Int) {
        @Suppress("UNCHECKED_CAST")
        val bookHolder = (holder as BaseViewHolder<*>).holder as IViewHolder<CollBookBean>
        bookHolder.onBind(book, position)
        holder.itemView.setOnClickListener { view ->
            bookHolder.onClick()
            onBookClick?.invoke(view, book)
        }
        holder.itemView.setOnLongClickListener { view ->
            onBookLongClick?.invoke(view, book) == true
        }
    }

    private fun bindMedia(holder: MediaHolder, item: MediaShelfItem) {
        holder.bind(item, isEditMode)
        holder.itemView.setOnClickListener { view -> onMediaClick?.invoke(view, item) }
        holder.itemView.setOnLongClickListener { view -> onMediaLongClick?.invoke(view, item) == true }
    }

    private class MediaHolder(
        private val binding: ItemHomeShelfMediaBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaShelfItem, editMode: Boolean) {
            val context = binding.root.context
            val kind = item.mediaKind
            binding.homeShelfMediaTitle.text = item.title
            binding.homeShelfMediaProgress.text = mediaProgressText(item, context)
            binding.homeShelfMediaBadge.text = when (kind) {
                ReaderMediaKind.AUDIO -> "▶ 听书"
                ReaderMediaKind.COMIC -> "漫画"
                else -> "媒体"
            }
            binding.homeShelfMediaBadge.background = ContextCompat.getDrawable(
                context,
                when (kind) {
                    ReaderMediaKind.AUDIO -> R.drawable.bg_home_shelf_badge_audio
                    ReaderMediaKind.COMIC -> R.drawable.bg_home_shelf_badge_comic
                    else -> R.drawable.bg_home_shelf_badge_comic
                }
            )
            BookCoverLoader.load(
                listOfNotNull(item.coverUrl.takeIf { it.isNotBlank() }),
                binding.homeShelfMediaCover,
                R.drawable.ic_book_cover_placeholder
            )
            binding.homeShelfMediaEditMask.visibility = if (editMode) View.VISIBLE else View.GONE
            binding.root.alpha = if (editMode) 0.56f else 1f
        }

        private fun mediaProgressText(item: MediaShelfItem, context: android.content.Context): String {
            return when (item.mediaKind) {
                ReaderMediaKind.AUDIO -> {
                    val position = AudioPlaybackProgressStore.position(context, item.currentChapterRouteId)
                    val duration = AudioPlaybackProgressStore.duration(context, item.currentChapterRouteId)
                    if (position > 0L && duration > 0L) {
                        "${formatMediaMillis(position)} / ${formatMediaMillis(duration)}"
                    } else {
                        item.currentChapterTitle.ifBlank { item.sourceName }
                    }
                }
                ReaderMediaKind.COMIC -> {
                    val savedPage = ComicReadingProgressStore.page(context, item.currentChapterRouteId)
                    val page = savedPage.takeIf { it >= 0 }?.plus(1) ?: 1
                    "第 ${page.coerceAtLeast(1)} 页"
                }
                else -> item.sourceName
            }
        }

        private fun formatMediaMillis(value: Long): String {
            val seconds = (value / 1_000L).coerceAtLeast(0L)
            val minutes = seconds / 60L
            val remain = seconds % 60L
            return "%02d:%02d".format(minutes, remain)
        }
    }

    companion object {
        private const val TYPE_BOOK = 1
        private const val TYPE_MEDIA = 2
    }
}

sealed class HomeShelfItem {
    data class Book(val book: CollBookBean) : HomeShelfItem()
    data class Media(val item: MediaShelfItem) : HomeShelfItem()
}
