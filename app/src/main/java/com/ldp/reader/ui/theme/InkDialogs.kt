package com.ldp.reader.ui.theme

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ldp.reader.R

/** Consistent confirmation dialogs; destructive actions get the danger colour. */
object InkDialogs {
    fun confirm(
        context: Context,
        title: CharSequence,
        message: CharSequence?,
        confirmText: CharSequence,
        destructive: Boolean,
        onConfirm: () -> Unit
    ): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.nb_common_cancel, null)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .show()
        if (destructive) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(context, R.color.ink_danger))
        }
        return dialog
    }
}
