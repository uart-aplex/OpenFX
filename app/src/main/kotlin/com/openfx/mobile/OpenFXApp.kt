package com.openfx.mobile

import android.app.Application
import android.content.Intent
import com.openfx.app.platform.PlatformDetector
import com.openfx.app.ui.tv.remote.RemoteControlService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OpenFXApp : Application() {

    @Inject
    lateinit var platformDetector: PlatformDetector

    override fun onCreate() {
        super.onCreate()

        // TV 模式下自動啟動遠端控制服務
        // 讓配對的手機可以遠端操控 TV 上的檔案
        if (platformDetector.isTv) {
            startService(Intent(this, RemoteControlService::class.java))
        }
    }
}
