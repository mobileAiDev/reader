package com.ldp.reader.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.ldp.reader.R
import com.ldp.reader.databinding.ViewBookshelfFilterMenuBinding
import com.ldp.reader.ui.fragment.BookShelfViewModel.FilterKey

class BookshelfFilterMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val binding =
        ViewBookshelfFilterMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val animDuration = resources.getInteger(R.integer.home_more_menu_anim_duration).toLong()
    private var selectedKey = FilterKey.ALL
    private var dismissing = false

    var onFilterSelected: ((FilterKey) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        visibility = INVISIBLE
        binding.bookshelfFilterScrim.setOnClickListener { dismiss() }
        binding.bookshelfFilterPopup.setOnClickListener { }
        binding.bookshelfFilterAll.setOnClickListener {
            selectAndDismiss(FilterKey.ALL)
        }
        binding.bookshelfFilterStatus3Days.setOnClickListener {
            selectAndDismiss(FilterKey.UPDATED_3_DAYS)
        }
        binding.bookshelfFilterStatus7Days.setOnClickListener {
            selectAndDismiss(FilterKey.UPDATED_7_DAYS)
        }
        binding.bookshelfFilterProgressUnread.setOnClickListener {
            selectAndDismiss(FilterKey.UNREAD)
        }
        binding.bookshelfFilterProgressReading.setOnClickListener {
            selectAndDismiss(FilterKey.READING)
        }
        binding.bookshelfFilterProgressFinished.setOnClickListener {
            selectAndDismiss(FilterKey.FINISHED)
        }
        binding.bookshelfFilterLocal.setOnClickListener {
            selectAndDismiss(FilterKey.LOCAL)
        }
    }

    fun showFrom(anchor: View?, key: FilterKey) {
        selectedKey = key
        updateSelection()
        requestFocus()
        post {
            positionByAnchor(anchor)
            visibility = VISIBLE
            startShowAnimation(anchor)
        }
    }

    fun dismiss(afterDismiss: (() -> Unit)? = null) {
        if (dismissing) {
            return
        }
        dismissing = true
        binding.bookshelfFilterPopup.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .translationY(-dp(8).toFloat())
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
        binding.bookshelfFilterArrow.animate()
            .alpha(0f)
            .translationY(-dp(8).toFloat())
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
        animate()
            .alpha(0f)
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                (parent as? android.view.ViewGroup)?.removeView(this)
                onDismiss?.invoke()
                afterDismiss?.invoke()
            }
            .start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun selectAndDismiss(key: FilterKey) {
        selectedKey = key
        updateSelection()
        dismiss {
            onFilterSelected?.invoke(key)
        }
    }

    private fun updateSelection() {
        bindOption(binding.bookshelfFilterAll, selectedKey == FilterKey.ALL)
        bindOption(
            binding.bookshelfFilterStatus3Days,
            selectedKey == FilterKey.UPDATED_3_DAYS
        )
        bindOption(
            binding.bookshelfFilterStatus7Days,
            selectedKey == FilterKey.UPDATED_7_DAYS
        )
        bindOption(binding.bookshelfFilterProgressUnread, selectedKey == FilterKey.UNREAD)
        bindOption(
            binding.bookshelfFilterProgressReading,
            selectedKey == FilterKey.READING
        )
        bindOption(
            binding.bookshelfFilterProgressFinished,
            selectedKey == FilterKey.FINISHED
        )
        bindOption(binding.bookshelfFilterLocal, selectedKey == FilterKey.LOCAL)
    }

    private fun bindOption(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setBackgroundResource(
            if (selected) {
                R.drawable.bg_bookshelf_filter_option_selected
            } else {
                R.drawable.bg_bookshelf_filter_option
            }
        )
    }

    private fun startShowAnimation(anchor: View?) {
        alpha = 0f
        val popup = binding.bookshelfFilterPopup
        popup.alpha = 0f
        popup.scaleX = 0.97f
        popup.scaleY = 0.97f
        popup.translationY = -dp(8).toFloat()
        popup.pivotX = resolveAnchorCenterX(anchor).toFloat() - popup.left
        popup.pivotY = 0f
        binding.bookshelfFilterArrow.alpha = 0f
        binding.bookshelfFilterArrow.translationY = -dp(8).toFloat()

        animate()
            .alpha(1f)
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
        popup.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
        binding.bookshelfFilterArrow.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun positionByAnchor(anchor: View?) {
        val popup = binding.bookshelfFilterPopup
        val arrow = binding.bookshelfFilterArrow
        val popupTop = resolvePopupTop(anchor)
        val popupSideMargin = dp(18)
        val popupWidth = (width - popupSideMargin * 2).coerceAtLeast(dp(280))
        val arrowWidth = dp(18)
        val anchorCenterX = resolveAnchorCenterX(anchor)

        popup.layoutParams = (popup.layoutParams as LayoutParams).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            width = popupWidth
            topMargin = popupTop
            leftMargin = popupSideMargin
            rightMargin = 0
        }

        arrow.layoutParams = (arrow.layoutParams as LayoutParams).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            topMargin = popupTop - dp(10) + dp(1)
            leftMargin = (anchorCenterX - arrowWidth / 2)
                .coerceAtLeast(popupSideMargin + dp(22))
                .coerceAtMost(popupSideMargin + popupWidth - arrowWidth - dp(22))
            rightMargin = 0
        }
    }

    private fun resolvePopupTop(anchor: View?): Int {
        if (anchor == null) {
            return dp(132)
        }
        val anchorRect = Rect()
        val rootRect = Rect()
        anchor.getGlobalVisibleRect(anchorRect)
        getGlobalVisibleRect(rootRect)
        return anchorRect.bottom - rootRect.top + dp(18)
    }

    private fun resolveAnchorCenterX(anchor: View?): Int {
        if (anchor == null) {
            return width - dp(90)
        }
        val anchorRect = Rect()
        val rootRect = Rect()
        anchor.getGlobalVisibleRect(anchorRect)
        getGlobalVisibleRect(rootRect)
        return anchorRect.centerX() - rootRect.left
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
