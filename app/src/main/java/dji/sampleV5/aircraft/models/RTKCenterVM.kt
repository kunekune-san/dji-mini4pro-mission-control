package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sdk.keyvalue.value.rtkbasestation.RTKReferenceStationSource
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.rtk.*


/**
 * RTK 中心 ViewModel：管理 RTK 模組啟用、參考站來源、定位與系統狀態監聽，以及精度維持設定。
 *
 * @author: Byte.Cai
 *  date : 2022/3/19
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class RTKCenterVM : DJIViewModel() {
    val aircraftRTKModuleEnabledLD = MutableLiveData<Boolean?>()
    val rtkLocationInfoLD = MutableLiveData<RTKLocationInfo>()
    val rtkSystemStateLD = MutableLiveData<RTKSystemState>()
    val rtkAccuracyMaintainLD = MutableLiveData<Boolean?>()

    private val rtkLocationInfoListener = RTKLocationInfoListener {
        rtkLocationInfoLD.postValue(it)
    }
    private val rtkSystemStateListener = RTKSystemStateListener {
        rtkSystemStateLD.postValue(it)
    }

    /**
     * 設定機載 RTK 模組是否啟用。
     */
    fun setAircraftRTKModuleEnabled(boolean: Boolean) {
        RTKCenter.getInstance().setAircraftRTKModuleEnabled(boolean, object :CommonCallbacks.CompletionCallback{
            override fun onSuccess() {
                // 結果以加入的 listener 結果為準
            }

            override fun onFailure(error: IDJIError) {
                aircraftRTKModuleEnabledLD.postValue(false)
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }

        })
    }


    /**
     * 設定 RTK 參考站來源。
     */
    fun setRTKReferenceStationSource(source: RTKReferenceStationSource) {
        RTKCenter.getInstance().setRTKReferenceStationSource(source, null)
    }

    /**
     * 加入 RTK 定位資訊監聽器。
     */
    fun addRTKLocationInfoListener() {
        RTKCenter.getInstance().addRTKLocationInfoListener(rtkLocationInfoListener)
    }

    /**
     * 加入 RTK 系統狀態監聽器。
     */
    fun addRTKSystemStateListener() {
        RTKCenter.getInstance().addRTKSystemStateListener(rtkSystemStateListener)
    }


    /** 移除 RTK 定位資訊監聽器。 */
    fun removeRTKLocationInfoListener() {
        RTKCenter.getInstance().removeRTKLocationInfoListener(rtkLocationInfoListener)
    }

    /** 移除 RTK 系統狀態監聽器。 */
    fun removeRTKSystemStateListener() {
        RTKCenter.getInstance().removeRTKSystemStateListener(rtkSystemStateListener)
    }


    /**
     * 設定 RTK 精度維持功能是否啟用。
     */
    fun setRTKMaintainAccuracyEnabled(enable: Boolean) {
        RTKCenter.getInstance().setRTKMaintainAccuracyEnabled(enable, null)
    }

}