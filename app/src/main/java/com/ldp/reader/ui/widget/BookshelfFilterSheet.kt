package com.ldp.reader.ui.widget

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.ldp.reader.databinding.SheetBookshelfFilterBinding
import com.ldp.reader.ui.fragment.BookShelfViewModel.FilterKey

/** Bottom sheet listing every shelf filter as one single-choice set of chips. */
class BookshelfFilterSheet(
    context: Context,
    selected: FilterKey,
    private val onFilterSelected: (FilterKey) -> Unit
) {
    private val binding = SheetBookshelfFilterBinding.inflate(LayoutInflater.from(context))
    private val dialog = BottomSheetDialog(context)
    private val options: List<Pair<Chip, FilterKey>> = listOf(
        binding.bookshelfFilterAll to FilterKey.ALL,
        binding.bookshelfFilterLocal to FilterKey.LOCAL,
        binding.bookshelfFilterStatus3Days to FilterKey.UPDATED_3_DAYS,
        binding.bookshelfFilterStatus7Days to FilterKey.UPDATED_7_DAYS,
        binding.bookshelfFilterProgressUnread to FilterKey.UNREAD,
        binding.bookshelfFilterProgressReading to FilterKey.READING,
        binding.bookshelfFilterProgressFinished to FilterKey.FINISHED
    )

    init {
        options.forEach { (chip, key) ->
            chip.isChecked = key == selected
            chip.setOnClickListener { select(key) }
        }
        dialog.setContentView(binding.root)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun show() {
        dialog.show()
    }

    private fun select(key: FilterKey) {
        options.forEach { (chip, optionKey) -> chip.isChecked = optionKey == key }
        // Let the checked state settle visibly before the sheet slides away.
        binding.root.postDelayed({
            dialog.dismiss()
            onFilterSelected(key)
        }, SELECTION_SETTLE_MS)
    }

    private companion object {
        const val SELECTION_SETTLE_MS = 160L
    }
}
