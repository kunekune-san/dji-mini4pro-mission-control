package dji.sampleV5.aircraft.models

import dji.v5.common.register.PackageProductCategory
import dji.v5.utils.inner.SDKConfig

/**
 * 封裝與 MSDK 互動的邏輯，讀取 MSDK Info 的相關資訊。
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class MSDKInfoModel {

    fun isDebug(): Boolean {
        return SDKConfig.getInstance().isDebug
    }

    fun getPackageProductCategory(): PackageProductCategory {
        return SDKConfig.getInstance().packageProductCategory
    }

    fun getSDKVersion(): String {
        return SDKConfig.getInstance().registrationSDKVersion
    }

    fun getBuildVersion(): String {
        return SDKConfig.getInstance().buildVersion
    }

    fun getCoreInfo(): SDKConfig.CoreInfo {
        return SDKConfig.getInstance().coreInfo
    }
}