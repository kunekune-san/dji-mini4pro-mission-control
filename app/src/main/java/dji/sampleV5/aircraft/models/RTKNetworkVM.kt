package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.rtk.CoordinateSystem
import dji.sdk.keyvalue.value.rtkbasestation.RTKCustomNetworkSetting
import dji.sdk.keyvalue.value.rtkbasestation.RTKReferenceStationSource
import dji.sdk.keyvalue.value.rtkbasestation.RTKServiceState
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.rtk.RTKCenter
import dji.v5.manager.aircraft.rtk.network.INetworkServiceInfoListener
import dji.v5.utils.common.LogUtils
import dji.v5.ux.accessory.RTKUtil

/**
 * 網路 RTK 服務 ViewModel：管理自訂網路、千尋（QX）與移動（CMCC）RTK 服務的啟動、停止與設定。
 *
 * @author Hoker
 * @date 2021/7/23
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class RTKNetworkVM : DJIViewModel() {
    companion object {
        const val TAG = "RTKNetworkVM"
        const val CUSTOM_RTK_SETTING_CACHE = "customRTKSettingChache"
    }

    val currentRTKState = MutableLiveData(RTKServiceState.UNKNOWN)
    val currentRTKErrorMsg = MutableLiveData("")
    val currentCustomNetworkRTKSettings = MutableLiveData<RTKCustomNetworkSetting>()
    val currentQxNetworkCoordinateSystem = MutableLiveData<CoordinateSystem>()


    private val networkServiceInfoListener: INetworkServiceInfoListener = object :
        INetworkServiceInfoListener {
        override fun onServiceStateUpdate(state: RTKServiceState?) {
            state?.let {
                currentRTKState.postValue(state)
                // 啟動成功，清除歷史遺留的錯誤資訊
                if (it == RTKServiceState.TRANSMITTING) {
                    currentRTKErrorMsg.postValue("")
                }
            }
        }

        override fun onErrorCodeUpdate(code: IDJIError?) {
            code?.let {
                currentRTKErrorMsg.postValue(code.toString())
            }
        }
    }

    /**
     * 取得目前已儲存的自訂網路 RTK 設定快取值。
     */
    fun getCurrentCustomNetworkRTKSettingCache(): String {
        return RTKUtil.getRtkCustomNetworkSetting().toString()
    }

    /**
     * 加入自訂、千尋（QX）與移動（CMCC）網路 RTK 服務狀態與錯誤監聽。
     */
    fun addNetworkRTKServiceInfoCallback() {
        RTKCenter.getInstance().customRTKManager.addNetworkRTKServiceInfoListener(
            networkServiceInfoListener
        )
        RTKCenter.getInstance().qxrtkManager.addNetworkRTKServiceInfoListener(
            networkServiceInfoListener
        )
        RTKCenter.getInstance().cmccrtkManager.addNetworkRTKServiceInfoListener(
            networkServiceInfoListener
        )
    }

    /** 移除自訂、千尋（QX）與移動（CMCC）網路 RTK 服務的狀態與錯誤監聽。 */
    fun removeNetworkServiceInfoListener() {
        RTKCenter.getInstance().customRTKManager.removeNetworkRTKServiceInfoListener(
            networkServiceInfoListener
        )
        RTKCenter.getInstance().qxrtkManager.removeNetworkRTKServiceInfoListener(
            networkServiceInfoListener
        )
        RTKCenter.getInstance().cmccrtkManager.removeNetworkRTKServiceInfoListener(
            networkServiceInfoListener
        )
    }


    // 自訂網路
    /**
     * 啟動自訂網路 RTK 服務。
     */
    fun startCustomNetworkRTKService(callback: CommonCallbacks.CompletionCallback) {
        RTKCenter.getInstance().customRTKManager.startNetworkRTKService(null, callback)
    }

    /**
     * 停止自訂網路 RTK 服務。
     */
    fun stopCustomNetworkRTKService(callback: CommonCallbacks.CompletionCallback) {
        RTKCenter.getInstance().customRTKManager.stopNetworkRTKService(callback)
    }

    /**
     * 設定自訂網路 RTK 設定並儲存快取。
     */
    fun setCustomNetworkRTKSettings(settings: RTKCustomNetworkSetting) {
        RTKCenter.getInstance().customRTKManager.customNetworkRTKSettings = settings
        currentCustomNetworkRTKSettings.postValue(settings)
        RTKUtil.saveRtkCustomNetworkSetting(settings)
    }


    // 千尋（QX）網路
    /**
     * 啟動千尋（QX）網路 RTK 服務。
     */
    fun startQXNetworkRTKService(coordinateSystem: CoordinateSystem, callback: CommonCallbacks.CompletionCallback) {
        RTKCenter.getInstance().qxrtkManager.startNetworkRTKService(coordinateSystem, callback)
        RTKUtil.saveRTKCoordinateSystem(RTKReferenceStationSource.QX_NETWORK_SERVICE, coordinateSystem)
    }

    /**
     * 停止千尋（QX）網路 RTK 服務。
     */
    fun stopQXNetworkRTKService(callback: CommonCallbacks.CompletionCallback) {
        RTKCenter.getInstance().qxrtkManager.stopNetworkRTKService(callback)
    }

    /**
     * 取得千尋（QX）網路 RTK 的座標系統。
     */
    fun getQXNetworkRTKCoordinateSystem() {
        RTKCenter.getInstance().qxrtkManager.getNetworkRTKCoordinateSystem(object :
            CommonCallbacks.CompletionCallbackWithParam<CoordinateSystem> {
            override fun onSuccess(coordinateSystem: CoordinateSystem?) {
                coordinateSystem?.let {
                    currentQxNetworkCoordinateSystem.postValue(it)
                }
            }

            override fun onFailure(error: IDJIError) {
                LogUtils.i(TAG, "getQXNetworkRTKCoordinateSystem onFailure $error")
            }
        })
    }

    // 移動（CMCC）RTK
    /**
     * 啟動移動（CMCC）網路 RTK 服務。
     */
    fun startCMCCRTKService(coordinateSystem: CoordinateSystem, callback: CommonCallbacks.CompletionCallback) {
        RTKCenter.getInstance().cmccrtkManager.startNetworkRTKService(coordinateSystem, callback)
        RTKUtil.saveRTKCoordinateSystem(RTKReferenceStationSource.NTRIP_NETWORK_SERVICE, coordinateSystem)
    }

    /**
     * 停止移動（CMCC）網路 RTK 服務。
     */
    fun stopCMCCRTKService(callback: CommonCallbacks.CompletionCallback) {
        RTKCenter.getInstance().cmccrtkManager.stopNetworkRTKService(callback)
    }
}