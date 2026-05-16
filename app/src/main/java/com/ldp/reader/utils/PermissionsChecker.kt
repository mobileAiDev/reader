package com.ldp.reader.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Created by ldp on 2017/10/8.
 */
class PermissionsChecker(context: Context) {
    private val mContext: Context = context.applicationContext

    fun lacksPermissions(vararg permissions: String): Boolean {
        for (permission in permissions) {
            if (lacksPermission(permission)) {
                return true
            }
        }
        return false
    }

    private fun lacksPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(mContext, permission) ==
            PackageManager.PERMISSION_DENIED
    }
}
