package dji.sampleV5.aircraft

import android.content.Context
import dagger.hilt.android.HiltAndroidApp

/**
 * DJI Mini 4 Pro 任務控制 App 的 Application 進入點。
 *
 * @author Hoker
 * @date 2022/3/2
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
@HiltAndroidApp
class DJIAircraftApplication : DJIApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }
}