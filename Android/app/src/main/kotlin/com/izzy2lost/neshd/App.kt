package com.izzy2lost.neshd

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    companion object {
        const val PREF_NIGHT_MODE = "night_mode"
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(NativeLib.PREFS_NAME, MODE_PRIVATE)
        val savedMode = prefs.getInt(PREF_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)
    }
}
