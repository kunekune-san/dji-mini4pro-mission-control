package dji.sampleV5.aircraft.data

import dji.sdk.keyvalue.value.rtkbasestation.BaseStationDeviceType
import dji.sdk.keyvalue.value.rtkbasestation.RTKStationInfo
import dji.sdk.keyvalue.value.rtkbasestation.RTKStationConnetState

/**
 * 描述：驅動 UI 的資料模型，基於 RTKBaseStationConnectInfo 新增一個連線狀態屬性
 *
 * @author: Byte.Cai
 *  date : 2022/3/6
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class DJIRTKBaseStationConnectInfo(
    var baseStationId: Int,
    var signalLevel: Int,
    var rtkStationName: String,
    var connectStatus: RTKStationConnetState = RTKStationConnetState.IDLE
) : RTKStationInfo(baseStationId, signalLevel, rtkStationName, BaseStationDeviceType.BS_RTK2) {
    constructor() : this(0, 0, "")
}