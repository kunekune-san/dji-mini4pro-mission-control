package dji.sampleV5.aircraft

import android.app.Application
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels

/**
 * 應用程式進入點。
 * 透過 MSDKManagerVM 在啟動時初始化 DJI Mobile SDK。
 *
 * @author Hoker
 * @date 2022/3/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class DJIApplication : Application() {

    private val msdkManagerVM: MSDKManagerVM by globalViewModels()

    override fun onCreate() {
        super.onCreate()

        // 確保初始化在最前面被呼叫
        msdkManagerVM.initMobileSDK(this)
    }

}
