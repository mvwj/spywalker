package com.spywalker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class SpyWalkerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleManager.applySavedLocale(this)
        // ÐÐ°ÑÑ‚Ñ€Ð¾Ð¹ÐºÐ° OSMDroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
    }
}

