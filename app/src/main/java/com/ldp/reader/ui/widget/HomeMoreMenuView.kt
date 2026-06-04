package com.ldp.reader.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.ldp.reader.R
import com.ldp.reader.databinding.ViewHomeMoreMenuBinding

class HomeMoreMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val binding = ViewHomeMoreMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val animDuration = resources.getInteger(R.integer.home_more_menu_anim_duration).toLong()
    private var dismissing = false

    var onImportClick: (() -> Unit)? = null
    var onImportSourceClick: (() -> Unit)? = null
    var onSyncClick: (() -> Unit)? = null
    var onAccountClick: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        visibility = INVISIBLE
        binding.homeMenuScrim.setOnClickListener { dismiss() }
        binding.homeMenuPopup.setOnClickListener { }
        binding.homeMenuImport.setOnClickListener {
            dismiss {
                onImportClick?.invoke()
            }
        }
        binding.homeMenuImportSource.setOnClickListener {
            dismiss {
                onImportSourceClick?.invoke()
            }
        }
        binding.homeMenuSync.setOnClickListener {
            dismiss {
                onSyncClick?.invoke()
            }
        }
        binding.homeMenuAccount.setOnClickListener {
            dismiss {
                onAccountClick?.invoke()
            }
        }
    }

    fun setAccountTitle(title: String) {
        binding.homeMenuAccountTitle.text = title
    }

    fun showFrom(anchor: View?) {
        requestFocus()
        post {
            positionByAnchor(anchor)
            visibility = VISIBLE
            startShowAnimation()
        }
    }

    fun dismiss(afterDismiss: (() -> Unit)? = null) {
        if (dismissing) {
            return
        }
        dismissing = true
        binding.homeMenuPopup.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationY(-dp(8).toFloat())
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
        binding.homeMenuArrow.animate()
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

    private fun startShowAnimation() {
        alpha = 0f
        val popup = binding.homeMenuPopup
        popup.alpha = 0f
        popup.scaleX = 0.96f
        popup.scaleY = 0.96f
        popup.translationY = -dp(8).toFloat()
        popup.pivotX = popup.width.toFloat() - dp(28)
        popup.pivotY = 0f
        binding.homeMenuArrow.alpha = 0f
        binding.homeMenuArrow.translationY = -dp(8).toFloat()

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
        binding.homeMenuArrow.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun positionByAnchor(anchor: View?) {
        val popup = binding.homeMenuPopup
        val arrow = binding.homeMenuArrow
        val arrowWidth = dp(14)
        val popupTop = resolvePopupTop(anchor)
        val anchorCenterX = resolveAnchorCenterX(anchor)
        popup.layoutParams = (popup.layoutParams as LayoutParams).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val popupWidth = measurePopupWidth(popup)
        val popupRight = (anchorCenterX + dp(18)).coerceAtMost(width - dp(15))
        val left = (popupRight - popupWidth)
            .coerceAtLeast(dp(8))
            .coerceAtMost((width - popupWidth - dp(8)).coerceAtLeast(dp(8)))

        popup.layoutParams = (popup.layoutParams as LayoutParams).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            width = popupWidth
            topMargin = popupTop
            leftMargin = left
            rightMargin = 0
        }

        arrow.layoutParams = (arrow.layoutParams as LayoutParams).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            topMargin = popupTop - dp(7)
            leftMargin = (anchorCenterX - arrowWidth / 2)
                .coerceAtLeast(left + dp(20))
                .coerceAtMost(left + popupWidth - arrowWidth - dp(8))
            rightMargin = 0
        }
    }

    private fun measurePopupWidth(popup: View): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (width - dp(16)).coerceAtLeast(dp(96)),
            View.MeasureSpec.AT_MOST
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
        popup.measure(widthSpec, heightSpec)
        return popup.measuredWidth.takeIf { it > 0 } ?: dp(134)
    }

    private fun resolvePopupTop(anchor: View?): Int {
        if (anchor == null) {
            return dp(84)
        }
        val anchorRect = Rect()
        val rootRect = Rect()
        anchor.getGlobalVisibleRect(anchorRect)
        getGlobalVisibleRect(rootRect)
        return anchorRect.bottom - rootRect.top - dp(6)
    }

    private fun resolveAnchorCenterX(anchor: View?): Int {
        if (anchor == null) {
            return width - dp(38)
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
