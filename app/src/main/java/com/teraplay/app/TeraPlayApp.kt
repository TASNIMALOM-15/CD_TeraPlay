package com.teraplay.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class TeraPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        S.res = resources
        Lang.apply(Lang.saved(this))
        Bg.init(this)
        AppCompatDelegate.setDefaultNightMode(
            if (Bg.dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
