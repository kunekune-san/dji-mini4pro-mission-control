package dji.sampleV5.aircraft.data

import dji.sdk.keyvalue.value.flightcontroller.AccessLockerDeviceType

/**
 * 描述：設備的鎖狀態
 *
 * @author: Byte.Cai
 *  date : 2022/8/10
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */

/**
 * 設備的鎖狀態
 */
data class DeviceLockStatus(
    var deviceIndex: AccessLockerDeviceType = AccessLockerDeviceType.UNKNOWN,
    //功能是否支援
    var isFeatureSupported: Boolean = false,
    //功能是否開啟
    var isFeatureEnabled: Boolean = false,
    //功能是否需要驗證
    var isFeatureNeedToBeVerified: Boolean = false,
)

/**
 * 修改密碼資料類別
 */
data class ModifyPasswordBean(val currentPassword: String, val newPassword: String){
}