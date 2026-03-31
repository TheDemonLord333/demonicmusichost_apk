package com.demonicmusichost.app

import android.app.Application
import com.demonicmusichost.app.data.PrefsManager

class DmhApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrefsManager.init(this)
    }
}
