package com.ldp.reader.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import com.ldp.reader.App

/**
 * Created by ldp on 17-5-11.
 */
object NetworkUtils {
    @JvmStatic
    fun getNetworkInfo(): NetworkInfo? {
        val cm = App.getContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo
    }

    @JvmStatic
    fun isAvailable(): Boolean {
        val info = getNetworkInfo()
        return info != null && info.isAvailable
    }

    @JvmStatic
    fun isConnected(): Boolean {
        val info = getNetworkInfo()
        return info != null && info.isConnected
    }

    @JvmStatic
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = cm.activeNetworkInfo
        return info != null && info.type == ConnectivityManager.TYPE_WIFI
    }
}
