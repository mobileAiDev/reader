package com.ldp.reader.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import com.ldp.reader.databinding.ViewHomeMoreMenuBinding
import com.ldp.reader.ui.motion.SpringValue

class HomeMoreMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val binding = ViewHomeMoreMenuBinding.inflate(LayoutInflater.from(context), this, true)
    /** 0 folded into the anchor, 1 open; the menu grows out of the button that opened it. */
    private val openSpring = SpringValue(context, response = 0.34f, dampingRatio = 0.78f, restDelta = 0.002f)
    private var dismissing = false
    private var afterDismiss: (() -> Unit)? = null

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
        openSpring.listener = SpringValue.Listener { spring ->
            val popup = binding.homeMenuPopup
            val scale = FOLDED_SCALE + (1f - FOLDED_SCALE) * spring.value
            popup.scaleX = scale
            popup.scaleY = scale
            popup.alpha = spring.value.coerceIn(0f, 1f)
            val host = parent as? ViewGroup
            if (dismissing && !spring.isRunning && host != null) {
                host.removeView(this)
                onDismiss?.invoke()
                afterDismiss?.invoke()
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
        this.afterDismiss = afterDismiss
        // A stiffer, non-bouncing spring on the way out: closing should never wobble.
        openSpring.setSpring(response = 0.24f, dampingRatio = 1f)
        openSpring.animateTo(0f)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startShowAnimation() {
        val popup = binding.homeMenuPopup
        popup.pivotX = popup.width.toFloat() - dp(20)
        popup.pivotY = 0f
        openSpring.jumpTo(0f)
        openSpring.animateTo(1f)
    }

    /** Opens the card just under the anchor, right edges aligned, like a native overflow menu. */
    private fun positionByAnchor(anchor: View?) {
        val anchorRect = Rect()
        val rootRect = Rect()
        getGlobalVisibleRect(rootRect)
        anchor?.getGlobalVisibleRect(anchorRect)
        binding.homeMenuPopup.layoutParams = (binding.homeMenuPopup.layoutParams as LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            topMargin = anchorRect.bottom - rootRect.top - dp(4)
            rightMargin = (rootRect.right - anchorRect.right).coerceAtLeast(dp(12))
        }
    }

    override fun onDetachedFromWindow() {
        openSpring.stop()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private companion object {
        const val FOLDED_SCALE = 0.86f
    }
}
