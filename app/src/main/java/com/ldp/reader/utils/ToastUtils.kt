package com.ldp.reader.utils

import android.widget.Toast
import com.ldp.reader.App

object ToastUtils {
    @JvmStatic
    fun show(msg: String?) {
        Toast.makeText(App.getContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
