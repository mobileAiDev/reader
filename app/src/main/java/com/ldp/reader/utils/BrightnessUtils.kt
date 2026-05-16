package com.ldp.reader.utils

import android.app.Activity
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

object BrightnessUtils {
    private const val TAG = "BrightnessUtils"

    @JvmStatic
    fun isAutoBrightness(activity: Activity): Boolean {
        var isAuto = false
        try {
            isAuto = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }
        return isAuto
    }

    @JvmStatic
    fun getScreenBrightness(activity: Activity): Int {
        return if (isAutoBrightness(activity)) {
            getAutoScreenBrightness(activity)
        } else {
            getManualScreenBrightness(activity)
        }
    }

    @JvmStatic
    fun getManualScreenBrightness(activity: Activity): Int {
        var nowBrightnessValue = 0
        val resolver = activity.contentResolver
        try {
            nowBrightnessValue = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return nowBrightnessValue
    }

    @JvmStatic
    fun getAutoScreenBrightness(activity: Activity): Int {
        var nowBrightnessValue = 0f
        val resolver = activity.contentResolver
        try {
            nowBrightnessValue = Settings.System.getFloat(resolver, Settings.System.SCREEN_BRIGHTNESS)
            Log.d(TAG, "getAutoScreenBrightness: $nowBrightnessValue")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val value = nowBrightnessValue * 225.0f
        Log.d(TAG, "brightness: $value")
        return value.toInt()
    }

    @JvmStatic
    fun setBrightness(activity: Activity, brightness: Int) {
        try {
            val lp = activity.window.attributes
            lp.screenBrightness = brightness.toFloat() * (1f / 255f)
            Log.d(TAG, "lp.screenBrightness == " + lp.screenBrightness)
            activity.window.attributes = lp
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    @JvmStatic
    fun setDefaultBrightness(activity: Activity) {
        try {
            val lp = activity.window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = lp
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}
