package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.v5.manager.diagnostic.*

/**
 * 診斷相關 ViewModel。監聽裝置健康狀態與裝置狀態的變化，並以 LiveData 提供給介面顯示。
 *
 * @author Hoker
 * @date 2021/6/30
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class DiagnosticVm : DJIViewModel() {

    val deviceHealthInfos = MutableLiveData(ArrayList<DJIDeviceHealthInfo>())

    val lastDeviceStatus = MutableLiveData(DJIDeviceStatus.NORMAL)
    val currentDeviceStatus = MutableLiveData(DJIDeviceStatus.NORMAL)

    private val deviceHealthInfoChangeListener = DJIDeviceHealthInfoChangeListener {
        updateDeviceHealthInfo(it as ArrayList<DJIDeviceHealthInfo>)
    }

    private val deviceStatusChangeListener = DJIDeviceStatusChangeListener { from, to ->
        updateDeviceStatus(from, to)
    }

    private fun updateDeviceHealthInfo(infos: ArrayList<DJIDeviceHealthInfo>) {
        deviceHealthInfos.value?.clear()
        deviceHealthInfos.value?.addAll(infos)
        deviceHealthInfos.postValue(deviceHealthInfos.value)
    }

    private fun updateDeviceStatus(form: DJIDeviceStatus, to: DJIDeviceStatus) {
        lastDeviceStatus.value = form
        currentDeviceStatus.value = to
    }

    override fun onCleared() {
        stopListenDeviceHealthInfoChange()
        stopListenDeviceStatusChange()
    }

    /** 開始監聽裝置健康資訊的變化。 */
    fun startListenDeviceHealthInfoChange() {
        DeviceHealthManager.getInstance()
            .addDJIDeviceHealthInfoChangeListener(deviceHealthInfoChangeListener)
    }

    /** 停止監聽裝置健康資訊的變化。 */
    fun stopListenDeviceHealthInfoChange() {
        DeviceHealthManager.getInstance()
            .removeDJIDeviceHealthInfoChangeListener(deviceHealthInfoChangeListener)
    }

    /** 取得目前的裝置健康資訊並更新到 LiveData。 */
    fun getCurrentDeviceHealthInfos() {
        updateDeviceHealthInfo(DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos as ArrayList<DJIDeviceHealthInfo>)
    }

    /** 開始監聽裝置狀態的變化。 */
    fun startListenDeviceStatusChange() {
        DeviceStatusManager.getInstance()
            .addDJIDeviceStatusChangeListener(deviceStatusChangeListener)
    }

    /** 停止監聽裝置狀態的變化。 */
    fun stopListenDeviceStatusChange() {
        DeviceStatusManager.getInstance()
            .removeDJIDeviceStatusChangeListener(deviceStatusChangeListener)
    }

    /** 取得目前的裝置狀態並更新到 LiveData。 */
    fun getCurrentDeviceStatus() {
        currentDeviceStatus.value = DeviceStatusManager.getInstance().currentDJIDeviceStatus
    }
}