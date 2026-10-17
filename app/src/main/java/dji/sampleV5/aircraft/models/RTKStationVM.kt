package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sampleV5.aircraft.data.DJIRTKBaseStationConnectInfo

import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.rtkbasestation.RTKBaseStationResetPasswordInfo
import dji.sdk.keyvalue.value.rtkbasestation.RTKStationConnetState
import dji.sdk.keyvalue.value.rtkbasestation.RTKStationInfo

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.rtk.RTKCenter
import dji.v5.manager.aircraft.rtk.RTKLocationInfo
import dji.v5.manager.aircraft.rtk.station.*
import dji.v5.utils.common.LogUtils
import java.util.ArrayList

/**
 * 基站 RTK 資料儲存與管理 ViewModel：搜尋、連線基站，管理基站位置與密碼，並監聽狀態。
 *
 * @author: Byte.Cai
 *  date : 2022/3/4
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class RTKStationVM : DJIViewModel() {
    private val TAG = "StationRTKVM"
    private val rtkStationManager = RTKCenter.getInstance().rtkStationManager

    val stationListLD = MutableLiveData<List<DJIRTKBaseStationConnectInfo>>()

    val appStationConnectStatusLD = MutableLiveData<RTKStationConnetState>()

    val appStationConnectedInfoLD = MutableLiveData<ConnectedRTKStationInfo>()

    val stationPositionLD = MutableLiveData<LocationCoordinate3D?>()

    val rtkLocationLD = MutableLiveData<RTKLocationInfo>()


    private val searchStationListener =
        SearchRTKStationListener { newConnectInfoList ->
            val convertToDJIRTKBaseStationConnectInfo = convertToDJIRTKBaseStationConnectInfo(newConnectInfoList)
            stationListLD.postValue(convertToDJIRTKBaseStationConnectInfo)
        }

    private val stationConnectStatusListener =
        RTKStationConnectStatusListener { newRtkBaseStationConnectState ->
            appStationConnectStatusLD.postValue(newRtkBaseStationConnectState)
        }

    private val connectedRTKStationInfoListener =
        ConnectedRTKStationInfoListener { newValue ->
            appStationConnectedInfoLD.postValue(newValue)
        }

    /**
     * 開始搜尋 RTK 基站。
     */
    fun startSearchStation() {
        LogUtils.i(TAG, "startSearchStation")
        rtkStationManager.startSearchRTKStation(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("Searing rtk station..."))
            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))

            }

        })
    }

    /**
     * 停止搜尋 RTK 基站。
     */
    fun stopSearchStation() {
        rtkStationManager.stopSearchRTKStation(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("Stop search station  success"))

            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))
            }

        })
    }

    /**
     * 加入 RTK 基站搜尋結果監聽器。
     */
    fun addSearchRTKStationListener() {
        rtkStationManager.addSearchRTKStationListener(searchStationListener)
    }

    /** 移除 RTK 基站搜尋結果監聽器。 */
    fun removeSearchRTKStationListener() {
        rtkStationManager.removeSearchRTKStationListener(searchStationListener)
    }

    /**
     * 加入 RTK 基站連線狀態監聽器。
     */
    fun addStationConnectStatusListener() {
        rtkStationManager.addRTKStationConnectStatusListener(stationConnectStatusListener)
    }

    /**
     * 加入 RTK 定位資訊監聽器。
     */
    fun addRTKLocationListener() {
        RTKCenter.getInstance().addRTKLocationInfoListener {
            rtkLocationLD.postValue(it)
        }
    }

    /** 移除 RTK 基站連線狀態監聽器。 */
    fun removeStationConnectStatusListener() {
        rtkStationManager.removeRTKStationConnectStatusListener(stationConnectStatusListener)
    }

    /**
     * 加入已連線 RTK 基站資訊監聽器。
     */
    fun addConnectedRTKStationInfoListener() {
        rtkStationManager.addConnectedRTKStationInfoListener(connectedRTKStationInfoListener)
    }

    /** 移除已連線 RTK 基站資訊監聽器。 */
    fun removeConnectedRTKStationInfoListener() {
        rtkStationManager.removeConnectedRTKStationInfoListener(connectedRTKStationInfoListener)
    }

    /** 清除所有已連線 RTK 基站資訊監聽器。 */
    fun clearAllConnectedRTKStationInfoListener() {
        rtkStationManager.clearAllConnectedRTKStationInfoListener()
    }

    /** 清除所有 RTK 基站搜尋監聽器。 */
    fun clearAllSearchRTKStationListener() {
        rtkStationManager.clearAllSearchRTKStationListener()
    }

    /** 清除所有 RTK 基站連線狀態監聽器。 */
    fun clearAllStationConnectStatusListener() {
        rtkStationManager.clearAllRTKStationConnectStatusListener()
    }

    /**
     * 開始連線指定的 RTK 基站。
     */
    fun startConnectToRTKStation(stationId: Int) {
        rtkStationManager.startConnectToRTKStation(stationId, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("Station connecting..."))
            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))
            }

        })
    }

    /**
     * 以管理員身分登入 RTK 基站。
     */
    fun loginAsAdmin(password: String, callbacks: CommonCallbacks.CompletionCallback) {
        rtkStationManager.loginRTKStation(password, callbacks)
    }

    /**
     * 設定 RTK 基站的參考位置。
     */
    fun setRTKStationPosition(locationCoordinate3D: LocationCoordinate3D) {
        rtkStationManager.setRTKStationReferencePosition(
            locationCoordinate3D,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    sendToastMsg(DJIToastResult.success("SetRTKStationPosition success"))
                }

                override fun onFailure(error: IDJIError) {
                    sendToastMsg(DJIToastResult.failed(error.toString()))
                }

            })
    }

    /**
     * 取得 RTK 基站的參考位置。
     */
    fun getRTKStationPosition() {
        rtkStationManager.getRTKStationReferencePosition(object :
            CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D> {
            override fun onSuccess(locationCoordinate3D: LocationCoordinate3D?) {
                stationPositionLD.postValue(locationCoordinate3D)
            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))
            }

        })
    }

    /**
     * 重設 RTK 基站的參考位置。
     */
    fun resetRTKStationPosition() {
        rtkStationManager.resetRTKStationReferencePosition(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("Reset station position success"))

            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))

            }


        })
    }

    /**
     * 重設 RTK 基站的密碼。
     */
    fun resetStationPassword(passwordParam: RTKBaseStationResetPasswordInfo) {
        rtkStationManager.resetRTKStationPassword(passwordParam, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("Reset station password success"))

            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))

            }


        })
    }

    /**
     * 設定 RTK 基站名稱。
     */
    fun setStationName(name: String) {
        rtkStationManager.setRTKStationName(name, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("Set station name success"))
            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))

            }


        })
    }


    private fun convertToDJIRTKBaseStationConnectInfo(list: List<RTKStationInfo>?): ArrayList<DJIRTKBaseStationConnectInfo> {
        val djiRTKBaseStationConnectInfoList = ArrayList<DJIRTKBaseStationConnectInfo>()
        list?.let {
            for (i in list) {
                val djirtkBaseStationConnectInfo = i.toDJIRTKBaseStationConnectInfo()
                djiRTKBaseStationConnectInfoList.add(djirtkBaseStationConnectInfo)
            }
        }
        return djiRTKBaseStationConnectInfoList

    }


    private fun RTKStationInfo.toDJIRTKBaseStationConnectInfo(): DJIRTKBaseStationConnectInfo {
        val baseStationId = this.stationId
        val name = this.stationName
        val signalLevel = this.signalLevel
        return DJIRTKBaseStationConnectInfo(baseStationId, signalLevel, name)
    }

    private fun sendToastMsg(djiToastResult: DJIToastResult) {
        toastResult?.postValue(djiToastResult)
    }

}