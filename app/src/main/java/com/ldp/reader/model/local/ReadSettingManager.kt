package com.ldp.reader.model.local

import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ScreenUtils
import com.ldp.reader.widget.page.PageMode
import com.ldp.reader.widget.page.PageStyle

class ReadSettingManager private constructor() {
    private val sharedPreUtils = SharedPreUtils.getInstance()

    var pageStyle: PageStyle
        get() {
            val style = sharedPreUtils.getInt(SHARED_READ_BG, PageStyle.BG_0.ordinal)
            return PageStyle.values()[style]
        }
        set(pageStyle) {
            sharedPreUtils.putInt(SHARED_READ_BG, pageStyle.ordinal)
        }

    var brightness: Int
        get() = sharedPreUtils.getInt(SHARED_READ_BRIGHTNESS, 40)
        set(progress) {
            sharedPreUtils.putInt(SHARED_READ_BRIGHTNESS, progress)
        }

    var isBrightnessAuto: Boolean
        get() = sharedPreUtils.getBoolean(SHARED_READ_IS_BRIGHTNESS_AUTO, false)
        set(isAuto) {
            sharedPreUtils.putBoolean(SHARED_READ_IS_BRIGHTNESS_AUTO, isAuto)
        }

    fun setAutoBrightness(isAuto: Boolean) {
        isBrightnessAuto = isAuto
    }

    var isDefaultTextSize: Boolean
        get() = sharedPreUtils.getBoolean(SHARED_READ_IS_TEXT_DEFAULT, false)
        set(isDefault) {
            sharedPreUtils.putBoolean(SHARED_READ_IS_TEXT_DEFAULT, isDefault)
        }

    var textSize: Int
        get() {
            val defaultSize = ReaderTextSize.defaultStoredPx(
                ScreenUtils.getDisplayMetrics().scaledDensity
            )
            if (isDefaultTextSize) return defaultSize
            return sharedPreUtils.getInt(SHARED_READ_TEXT_SIZE, defaultSize)
        }
        set(textSize) {
            sharedPreUtils.putInt(SHARED_READ_TEXT_SIZE, textSize)
        }

    var pageMode: PageMode
        get() {
            val mode = sharedPreUtils.getInt(SHARED_READ_PAGE_MODE, PageMode.SIMULATION.ordinal)
            return PageMode.values()[mode]
        }
        set(mode) {
            sharedPreUtils.putInt(SHARED_READ_PAGE_MODE, mode.ordinal)
        }

    var isNightMode: Boolean
        get() = sharedPreUtils.getBoolean(SHARED_READ_NIGHT_MODE, false)
        set(isNight) {
            sharedPreUtils.putBoolean(SHARED_READ_NIGHT_MODE, isNight)
        }

    var isVolumeTurnPage: Boolean
        get() = sharedPreUtils.getBoolean(SHARED_READ_VOLUME_TURN_PAGE, false)
        set(isTurn) {
            sharedPreUtils.putBoolean(SHARED_READ_VOLUME_TURN_PAGE, isTurn)
        }

    var isFullScreen: Boolean
        get() = sharedPreUtils.getBoolean(SHARED_READ_FULL_SCREEN, false)
        set(isFullScreen) {
            sharedPreUtils.putBoolean(SHARED_READ_FULL_SCREEN, isFullScreen)
        }

    var convertType: Int
        get() = sharedPreUtils.getInt(SHARED_READ_CONVERT_TYPE, 0)
        set(convertType) {
            sharedPreUtils.putInt(SHARED_READ_CONVERT_TYPE, convertType)
        }

    companion object {
        const val READ_BG_DEFAULT = 0
        const val READ_BG_1 = 1
        const val READ_BG_2 = 2
        const val READ_BG_3 = 3
        const val READ_BG_4 = 4
        const val NIGHT_MODE = 5

        const val SHARED_READ_BG = "shared_read_bg"
        const val SHARED_READ_BRIGHTNESS = "shared_read_brightness"
        const val SHARED_READ_IS_BRIGHTNESS_AUTO = "shared_read_is_brightness_auto"
        const val SHARED_READ_TEXT_SIZE = "shared_read_text_size"
        const val SHARED_READ_IS_TEXT_DEFAULT = "shared_read_text_default"
        const val SHARED_READ_PAGE_MODE = "shared_read_mode"
        const val SHARED_READ_NIGHT_MODE = "shared_night_mode"
        const val SHARED_READ_VOLUME_TURN_PAGE = "shared_read_volume_turn_page"
        const val SHARED_READ_FULL_SCREEN = "shared_read_full_screen"
        const val SHARED_READ_CONVERT_TYPE = "shared_read_convert_type"

        @Volatile
        private var sInstance: ReadSettingManager? = null

        @JvmStatic
        fun getInstance(): ReadSettingManager {
            if (sInstance == null) {
                synchronized(ReadSettingManager::class.java) {
                    if (sInstance == null) {
                        sInstance = ReadSettingManager()
                    }
                }
            }
            return sInstance!!
        }
    }
}
