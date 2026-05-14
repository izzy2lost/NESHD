package com.izzy2lost.neshd

import android.content.SharedPreferences

/**
 * JNI bridge to the native NES core (libMesenCore.so).
 *
 * Button IDs map directly to the NesKeyCode constants defined in android_key_manager.h:
 *   A=1, B=2, Select=3, Start=4, Up=5, Down=6, Left=7, Right=8,
 *   TurboA=9, TurboB=10
 *
 * VideoFilter values map to the native VideoFilterType enum.
 */
object NativeLib {
    const val PREFS_NAME = "neshd_prefs"

    // Button IDs
    const val BTN_A      = 1
    const val BTN_B      = 2
    const val BTN_SELECT = 3
    const val BTN_START  = 4
    const val BTN_UP     = 5
    const val BTN_DOWN   = 6
    const val BTN_LEFT   = 7
    const val BTN_RIGHT  = 8
    const val BTN_TURBO_A = 9
    const val BTN_TURBO_B = 10

    // Video filter IDs (VideoFilterType enum in SettingTypes.h)
    const val FILTER_NONE         = 0
    const val FILTER_NTSC         = 1  // NtscBlargg
    const val FILTER_BISQWIT_NTSC = 2  // NtscBisqwit
    const val FILTER_LCD_GRID     = 3
    const val FILTER_XBRZ_2X      = 4
    const val FILTER_XBRZ_3X      = 5
    const val FILTER_XBRZ_4X      = 6
    const val FILTER_XBRZ_5X      = 7
    const val FILTER_XBRZ_6X      = 8
    const val FILTER_HQ2X         = 9
    const val FILTER_HQ3X         = 10
    const val FILTER_HQ4X         = 11
    const val FILTER_SCALE2X      = 12
    const val FILTER_SCALE3X      = 13
    const val FILTER_SCALE4X      = 14
    const val FILTER_2XSAI        = 15
    const val FILTER_SUPER_2XSAI  = 16
    const val FILTER_SUPER_EAGLE  = 17
    const val FILTER_PRESCALE_2X  = 18
    const val FILTER_PRESCALE_3X  = 19
    const val FILTER_PRESCALE_4X  = 20
    const val FILTER_PRESCALE_6X  = 21
    const val FILTER_PRESCALE_8X  = 22
    const val FILTER_PRESCALE_10X = 23

    const val PREF_VIDEO_FILTER_TYPE = "video_filter_type"
    const val PREF_ASPECT_RATIO = "aspect_ratio"
    const val PREF_HD_PACKS = "hd_packs"
    private const val PREF_LEGACY_VIDEO_FILTER_INDEX = "video_filter"
    const val DEFAULT_VIDEO_FILTER = FILTER_NONE
    const val ASPECT_NATIVE = 0
    const val ASPECT_STANDARD = 4
    const val ASPECT_WIDESCREEN = 5
    const val DEFAULT_ASPECT_RATIO = ASPECT_NATIVE

    data class VideoFilterOption(val filterType: Int, val label: String)
    data class AspectRatioOption(val aspectRatio: Int, val label: String)

    val VIDEO_FILTER_OPTIONS = arrayOf(
        VideoFilterOption(FILTER_NONE, "Native 256x240"),
        VideoFilterOption(FILTER_PRESCALE_4X, "Sharp pixels 4x"),
        VideoFilterOption(FILTER_PRESCALE_3X, "Sharp pixels 3x"),
        VideoFilterOption(FILTER_PRESCALE_2X, "Sharp pixels 2x"),
        VideoFilterOption(FILTER_PRESCALE_6X, "Sharp pixels 6x"),
        VideoFilterOption(FILTER_XBRZ_2X, "xBRZ 2x (smooth)"),
        VideoFilterOption(FILTER_XBRZ_3X, "xBRZ 3x (smooth)"),
        VideoFilterOption(FILTER_XBRZ_4X, "xBRZ 4x (smooth, slower)"),
        VideoFilterOption(FILTER_SCALE2X, "Scale2x 2x"),
        VideoFilterOption(FILTER_SCALE3X, "Scale2x 3x"),
        VideoFilterOption(FILTER_SCALE4X, "Scale2x 4x"),
        VideoFilterOption(FILTER_HQ2X, "HQ2x (smooth)"),
        VideoFilterOption(FILTER_HQ3X, "HQ3x (smooth, slower)"),
        VideoFilterOption(FILTER_NTSC, "NTSC TV filter"),
        VideoFilterOption(FILTER_BISQWIT_NTSC, "NTSC Bisqwit"),
        VideoFilterOption(FILTER_2XSAI, "2xSai"),
        VideoFilterOption(FILTER_SUPER_2XSAI, "Super 2xSai"),
        VideoFilterOption(FILTER_SUPER_EAGLE, "SuperEagle"),
        VideoFilterOption(FILTER_LCD_GRID, "LCD grid"),
        VideoFilterOption(FILTER_PRESCALE_8X, "Sharp pixels 8x"),
        VideoFilterOption(FILTER_PRESCALE_10X, "Sharp pixels 10x")
    )

    val ASPECT_RATIO_OPTIONS = arrayOf(
        AspectRatioOption(ASPECT_NATIVE, "Native"),
        AspectRatioOption(ASPECT_STANDARD, "4:3"),
        AspectRatioOption(ASPECT_WIDESCREEN, "16:9 Widescreen")
    )

    private val LEGACY_VIDEO_FILTER_IDS = intArrayOf(
        FILTER_NONE,
        FILTER_NTSC,
        FILTER_BISQWIT_NTSC,
        FILTER_XBRZ_2X,
        FILTER_XBRZ_3X,
        FILTER_XBRZ_4X,
        FILTER_XBRZ_5X,
        FILTER_XBRZ_6X,
        FILTER_HQ2X,
        FILTER_HQ3X,
        FILTER_HQ4X,
        FILTER_SCALE2X,
        FILTER_SCALE3X,
        FILTER_SCALE4X,
        FILTER_2XSAI,
        FILTER_SUPER_2XSAI,
        FILTER_SUPER_EAGLE,
        FILTER_PRESCALE_2X,
        FILTER_PRESCALE_3X,
        FILTER_PRESCALE_4X,
        FILTER_PRESCALE_6X,
        FILTER_PRESCALE_8X,
        FILTER_PRESCALE_10X
    )

    fun getSavedVideoFilter(prefs: SharedPreferences): Int {
        if (prefs.contains(PREF_VIDEO_FILTER_TYPE)) {
            return coerceVideoFilter(prefs.getInt(PREF_VIDEO_FILTER_TYPE, DEFAULT_VIDEO_FILTER))
        }

        val legacyIndex = prefs.getInt(PREF_LEGACY_VIDEO_FILTER_INDEX, -1)
        if (legacyIndex >= 0) {
            return LEGACY_VIDEO_FILTER_IDS.getOrElse(legacyIndex) { DEFAULT_VIDEO_FILTER }
        }

        return DEFAULT_VIDEO_FILTER
    }

    fun saveVideoFilter(prefs: SharedPreferences, filterType: Int, hdPacksEnabled: Boolean) {
        prefs.edit()
            .putInt(PREF_VIDEO_FILTER_TYPE, coerceVideoFilter(filterType))
            .putBoolean(PREF_HD_PACKS, hdPacksEnabled)
            .remove(PREF_LEGACY_VIDEO_FILTER_INDEX)
            .commit()
    }

    fun getSavedAspectRatio(prefs: SharedPreferences): Int =
        coerceAspectRatio(prefs.getInt(PREF_ASPECT_RATIO, DEFAULT_ASPECT_RATIO))

    fun saveAspectRatio(prefs: SharedPreferences, aspectRatio: Int) {
        prefs.edit()
            .putInt(PREF_ASPECT_RATIO, coerceAspectRatio(aspectRatio))
            .apply()
    }

    fun applyVideoSettings(filterType: Int, hdPacksEnabled: Boolean, aspectRatio: Int) {
        setVideoFilter(coerceVideoFilter(filterType))
        setHdPacksEnabled(hdPacksEnabled)
        setAspectRatio(coerceAspectRatio(aspectRatio))
    }

    private fun coerceVideoFilter(filterType: Int): Int {
        return if (VIDEO_FILTER_OPTIONS.any { it.filterType == filterType }) {
            filterType
        } else {
            DEFAULT_VIDEO_FILTER
        }
    }

    private fun coerceAspectRatio(aspectRatio: Int): Int {
        return if (ASPECT_RATIO_OPTIONS.any { it.aspectRatio == aspectRatio }) {
            aspectRatio
        } else {
            DEFAULT_ASPECT_RATIO
        }
    }

    // Lifecycle
    external fun initialize(homeFolder: String)
    external fun release()

    // ROM control
    external fun loadRom(romPath: String): Boolean
    external fun stopRom()
    external fun flushBatterySave()
    external fun pause()
    external fun resume()
    external fun isRunning(): Boolean
    external fun getRomCheatHash(): String
    external fun setCheats(codes: Array<String>)
    external fun clearCheats()
    external fun saveState(slot: Int): Boolean
    external fun loadState(slot: Int): Boolean
    external fun getSaveStatePath(slot: Int): String
    external fun getSaveStatePreview(slot: Int): ByteArray?
    external fun switchFdsDiskSide()
    external fun insertNextFdsDisk()

    // Input
    external fun setButtonState(buttonId: Int, pressed: Boolean)

    // Settings
    external fun setVideoFilter(filterType: Int)
    external fun setHdPacksEnabled(enabled: Boolean)
    external fun setAspectRatio(aspectRatio: Int)

    // OpenGL (called on the GL thread)
    external fun glInit()
    external fun glDrawFrame(viewWidth: Int, viewHeight: Int)
    external fun glDestroy()

    // Debug
    external fun getLog(): String
    external fun getRenderedFrameSize(): String

    init {
        System.loadLibrary("MesenCore")
    }
}
