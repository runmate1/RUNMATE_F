package com.eunsun.runmate

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk

class RunMateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val appKey = BuildConfig.KAKAO_MAP_APP_KEY
        if (appKey.isNotBlank()) KakaoMapSdk.init(this, appKey)
    }
}
