package com.ldp.reader.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ldp.reader.R
import com.ldp.reader.audio.AudioPlaybackProgressStore
import com.ldp.reader.databinding.ItemCollBookBinding
import com.ldp.reader.databinding.ItemHomeShelfMediaBinding
import com.ldp.reader.databinding.ItemShelfHeroBinding
import com.ldp.reader.media.ComicReadingProgressStore
import com.ldp.reader.media.MediaShelfItem
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.ui.adapter.view.CollBookHolder
import com.ldp.reader.ui.base.adapter.BaseViewHolder
import com.ldp.reader.ui.base.adapter.IViewHolder
import com.ldp.reader.ui.home.BookshelfPinIdentity
import com.ldp.reader.ui.home.BookshelfPinOrdering
import com.ldp.reader.ui.image.BookCoverLoader
import com.ldp.reader.ui.motion.Press
import com.ldp.reader.ui.widget.CoverFrameLayout

class HomeShelfAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), BookSelectionState {
    private val shelfItems = ArrayList<HomeShelfItem>()
    private val selectedBookKeys = HashSet<String>()
    private val selectedMediaIds = HashSet<String>()
    private var bookRecordsById: Map<String, BookRecordBean> = emptyMap()
    private var nullIdBookRecord: BookRecordBean? = null
    private var pinnedBookKeys: Set<String> = emptySet()
    override var isEditMode = false
        private set
    var onBookClick: ((View?, CollBookBean) -> Unit)? = null
    var onBookLongClick: ((View?, CollBookBean) -> Boolean)? = null
    var onMediaClick: ((View?, MediaShelfItem) -> Unit)? = null
    var onMediaLongClick: ((View?, MediaShelfItem) -> Boolean)? = null
    private var hero: CollBookBean? = null

    /** Whether titles and progress show under the covers; themes with real shelves hide them. */
    var shelfCaptions = true
    private val heroOffset: Int get() = if (hero == null) 0 else 1

    val items: List<HomeShelfItem>
        get() = shelfItems.toList()

    val bookItems: List<CollBookBean>
        get() = shelfItems.mapNotNull { (it as? HomeShelfItem.Book)?.book }

    val selectedBooks: List<CollBookBean>
        get() = bookItems.filter { selectedBookKeys.contains(CollBookAdapter.selectionKey(it)) }

    val selectedMediaItems: List<MediaShelfItem>
        get() = shelfItems.mapNotNull { (it as? HomeShelfItem.Media)?.item }
            .filter { selectedMediaIds.contains(it.id) }

    val selectedCount: Int
        get() = selectedBooks.size + selectedMediaItems.size

    val visibleItemCount: Int
        get() = shelfItems.size

    val isAllVisibleSelected: Boolean
        get() {
            return shelfItems.isNotEmpty() && shelfItems.all { item ->
                when (item) {
                    is HomeShelfItem.Book ->
                        selectedBookKeys.contains(CollBookAdapter.selectionKey(item.book))
                    is HomeShelfItem.Media -> selectedMediaIds.contains(item.item.id)
                }
            }
        }

    override fun getItemCount(): Int = shelfItems.size + heroOffset

    override fun getItemViewType(position: Int): Int {
        if (position < heroOffset) return TYPE_HERO
        return when (shelfItems[position - heroOffset]) {
            is HomeShelfItem.Book -> TYPE_BOOK
            is HomeShelfItem.Media -> TYPE_MEDIA
        }
    }

    /** The hero card spans the whole grid row. */
    fun isFullSpan(position: Int): Boolean = position < heroOffset

    /** Shows [book] as the "continue reading" card above the grid, or hides the card. */
    fun setHero(book: CollBookBean?) {
        hero = book
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> ShelfHeroHolder(ItemShelfHeroBinding.inflate(inflater, parent, false)).also {
                Press.attach(it.card, Press.CARD)
            }
            TYPE_BOOK -> {
                val holder = CollBookHolder(this) { book -> bookRecordFor(book) }
                BaseViewHolder(holder.createItemView(parent), holder).also {
                    ItemCollBookBinding.bind(it.itemView).collBookCaptions.visibility =
                        if (shelfCaptions) View.VISIBLE else View.GONE
                    Press.attach(it.itemView)
                }
            }
            else -> {
                val binding = ItemHomeShelfMediaBinding.inflate(inflater, parent, false)
                binding.homeShelfMediaCaptions.visibility = if (shelfCaptions) View.VISIBLE else View.GONE
                MediaHolder(binding).also { Press.attach(it.itemView) }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ShelfHeroHolder) {
            val book = checkNotNull(hero)
            holder.bind(book, bookRecordFor(book))
            holder.card.setOnClickListener { onBookClick?.invoke(holder.cover, book) }
            return
        }
        when (val item = shelfItems[position - heroOffset]) {
            is HomeShelfItem.Book -> bindBook(holder, item.book, position)
            is HomeShelfItem.Media -> bindMedia(holder as MediaHolder, item.item)
        }
    }

    fun refreshItems(
        books: List<CollBookBean>,
        mediaItems: List<MediaShelfItem>,
        recordsByBookId: Map<String, BookRecordBean> = emptyMap(),
        nullIdRecord: BookRecordBean? = null
    ) {
        selectedBookKeys.retainAll(books.map { CollBookAdapter.selectionKey(it) }.toSet())
        selectedMediaIds.retainAll(mediaItems.map { it.id }.toSet())
        bookRecordsById = recordsByBookId
        nullIdBookRecord = nullIdRecord
        val pinPartition = BookshelfPinOrdering.partition(books, pinnedBookKeys)
        shelfItems.clear()
        shelfItems.addAll(pinPartition.pinned.map { HomeShelfItem.Book(it) })
        shelfItems.addAll(mediaItems.map { HomeShelfItem.Media(it) })
        shelfItems.addAll(pinPartition.regular.map { HomeShelfItem.Book(it) })
        notifyDataSetChanged()
    }

    fun setPinnedBookKeys(keys: Set<String>) {
        pinnedBookKeys = keys.toSet()
    }

    fun itemAt(position: Int): HomeShelfItem = shelfItems[position - heroOffset]

    fun setEditMode(editMode: Boolean) {
        isEditMode = editMode
        if (!editMode) {
            selectedBookKeys.clear()
            selectedMediaIds.clear()
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

    fun selectBook(book: CollBookBean?) {
        val key = CollBookAdapter.selectionKey(book)
        if (key.isBlank() || !selectedBookKeys.add(key)) return
        notifyDataSetChanged()
    }

    fun toggleMediaSelection(item: MediaShelfItem?) {
        val id = item?.id.orEmpty()
        if (id.isBlank()) return
        if (selectedMediaIds.contains(id)) {
            selectedMediaIds.remove(id)
        } else {
            selectedMediaIds.add(id)
        }
        notifyDataSetChanged()
    }

    fun selectMediaItem(item: MediaShelfItem?) {
        val id = item?.id.orEmpty()
        if (id.isBlank() || !selectedMediaIds.add(id)) return
        notifyDataSetChanged()
    }

    override fun isSelected(book: CollBookBean?): Boolean {
        return selectedBookKeys.contains(CollBookAdapter.selectionKey(book))
    }

    override fun isPinned(book: CollBookBean): Boolean {
        return pinnedBookKeys.contains(BookshelfPinIdentity.key(book))
    }

    fun selectAllVisible() {
        bookItems.forEach { selectedBookKeys.add(CollBookAdapter.selectionKey(it)) }
        shelfItems.mapNotNull { (it as? HomeShelfItem.Media)?.item?.id }
            .forEach { selectedMediaIds.add(it) }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedBookKeys.clear()
        selectedMediaIds.clear()
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
        selectedMediaIds.remove(itemId)
        shelfItems.removeAll { (it as? HomeShelfItem.Media)?.item?.id == itemId }
        notifyDataSetChanged()
    }

    fun removeMediaItems(items: List<MediaShelfItem>) {
        val ids = items.map { it.id }.toSet()
        selectedMediaIds.removeAll(ids)
        shelfItems.removeAll { item ->
            (item as? HomeShelfItem.Media)?.item?.id?.let { ids.contains(it) } == true
        }
        notifyDataSetChanged()
    }

    private fun bindBook(holder: RecyclerView.ViewHolder, book: CollBookBean, position: Int) {
        @Suppress("UNCHECKED_CAST")
        val bookHolder = (holder as BaseViewHolder<*>).holder as IViewHolder<CollBookBean>
        bookHolder.onBind(book, position)
        // The cover frame, so a book opens by growing out of its cover.
        val cover = ItemCollBookBinding.bind(holder.itemView).collBookIvCover.parent as View
        holder.itemView.setOnClickListener {
            bookHolder.onClick()
            onBookClick?.invoke(cover, book)
        }
        holder.itemView.setOnLongClickListener { view ->
            onBookLongClick?.invoke(view, book) == true
        }
    }

    private fun bookRecordFor(book: CollBookBean): BookRecordBean? {
        val bookId = book.get_id()
        return if (bookId == null) nullIdBookRecord else bookRecordsById[bookId]
    }

    private fun bindMedia(holder: MediaHolder, item: MediaShelfItem) {
        holder.bind(item, isEditMode, selectedMediaIds.contains(item.id))
        holder.itemView.setOnClickListener { view -> onMediaClick?.invoke(view, item) }
        holder.itemView.setOnLongClickListener { view -> onMediaLongClick?.invoke(view, item) == true }
    }

    private class MediaHolder(
        private val binding: ItemHomeShelfMediaBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaShelfItem, editMode: Boolean, selected: Boolean) {
            val context = binding.root.context
            val kind = item.mediaKind
            binding.homeShelfMediaTitle.text = item.title
            (binding.homeShelfMediaCover.parent as CoverFrameLayout).setVolumeSeed(item.title.hashCode())
            binding.homeShelfMediaProgress.text = mediaProgressText(item, context)
            binding.homeShelfMediaBadge.text = when (kind) {
                ReaderMediaKind.AUDIO -> "听书"
                ReaderMediaKind.COMIC -> "漫画"
                else -> "媒体"
            }
            BookCoverLoader.load(
                listOfNotNull(item.coverUrl.takeIf { it.isNotBlank() }),
                binding.homeShelfMediaCover,
                R.drawable.ic_book_cover_placeholder
            )
            binding.homeShelfMediaSelected.visibility = if (editMode) View.VISIBLE else View.GONE
            binding.homeShelfMediaSelected.isChecked = selected
            binding.root.alpha = 1f
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
                    HomeShelfMediaLabels.comicProgressText(
                        item.currentChapterTitle,
                        item.currentChapterIndex,
                        savedPage
                    )
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
        private const val TYPE_HERO = 3
    }
}

sealed class HomeShelfItem {
    data class Book(val book: CollBookBean) : HomeShelfItem()
    data class Media(val item: MediaShelfItem) : HomeShelfItem()
}
