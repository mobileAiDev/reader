package com.ldp.reader.utils

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.ldp.reader.App
import com.ldp.reader.R
import com.ldp.reader.ui.theme.AppearanceMode
import com.ldp.reader.ui.theme.InkThemes

/** App-styled toast: a quiet pill near the bottom that follows light/dark mode. */
object ToastUtils {
    private var current: Toast? = null

    @JvmStatic
    fun show(msg: String?) {
        show(msg, Toast.LENGTH_SHORT)
    }

    @JvmStatic
    fun showLong(msg: String?) {
        show(msg, Toast.LENGTH_LONG)
    }

    @SuppressLint("InflateParams")
    @Suppress("DEPRECATION")
    private fun show(msg: String?, duration: Int) {
        val context = InkThemes.themed(AppearanceMode.appContext(App.getContext()))
        val view = LayoutInflater.from(context).inflate(R.layout.view_ink_toast, null) as TextView
        view.text = msg
        current?.cancel()
        current = Toast(context).apply {
            this.duration = duration
            this.view = view
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(context, 96))
            show()
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
