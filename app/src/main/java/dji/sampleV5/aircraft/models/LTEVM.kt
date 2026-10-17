package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sdk.keyvalue.value.airlink.WlmDongleInfo
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.lte.*

/**
 * LTE 增強圖傳相關 ViewModel。負責 LTE 認證、傳輸模式設定、私有化伺服器設定，以及監聽 LTE 資訊與網卡資訊。
 *
 * @author Hoker
 * @date 2022/8/12
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class LTEVM : DJIViewModel() {

    val lteAuthenticationInfo = MutableLiveData<LTEAuthenticationInfo>()
    val lteLinkInfo = MutableLiveData<LTELinkInfo>()
    val acWlmDongleInfo = MutableLiveData<MutableList<WlmDongleInfo>>()
    val rcWlmDongleInfo = MutableLiveData<MutableList<WlmDongleInfo>>()

    private val lteAuthenticationInfoListener = LTEAuthenticationInfoListener { info -> lteAuthenticationInfo.postValue(info) }

    private val lteLinkInfoListener = LTELinkInfoListener { info: LTELinkInfo -> lteLinkInfo.postValue(info) }

    private val lteDongleInfoListener = object :
        LTEDongleInfoListener {
        override fun onLTEAircraftDongleInfoUpdate(aircraftDongleInfos: MutableList<WlmDongleInfo>) {
            acWlmDongleInfo.postValue(aircraftDongleInfos)
        }

        override fun onLTERemoteControllerDongleInfoUpdate(remoteControllerDongleInfos: MutableList<WlmDongleInfo>) {
            rcWlmDongleInfo.postValue(remoteControllerDongleInfos)
        }
    }

    /** 初始化 LTE 認證、連線與網卡資訊的監聽。 */
    fun initListener() {
        LTEManager.getInstance().addLTEAuthenticationInfoListener(lteAuthenticationInfoListener)
        LTEManager.getInstance().addLTELinkInfoListener(lteLinkInfoListener)
        LTEManager.getInstance().addLTEDongleInfoListener(lteDongleInfoListener)
    }

    override fun onCleared() {
        KeyManager.getInstance().cancelListen(this)
        LTEManager.getInstance().removeLTEAuthenticationInfoListener(lteAuthenticationInfoListener)
        LTEManager.getInstance().removeLTELinkInfoListener(lteLinkInfoListener)
        LTEManager.getInstance().removeLTEDongleInfoListener(lteDongleInfoListener)
    }

    /** 更新 LTE 認證資訊。 */
    fun updateLTEAuthenticationInfo() {
        LTEManager.getInstance().updateLTEAuthenticationInfo(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 取得 LTE 認證的驗證碼。 */
    fun getLTEAuthenticationVerificationCode(phoneAreaCode: String, phoneNumber: String) {
        LTEManager.getInstance().getLTEAuthenticationVerificationCode(phoneAreaCode, phoneNumber, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 使用電話號碼與驗證碼開始 LTE 認證。 */
    fun startLTEAuthentication(phoneAreaCode: String, phoneNumber: String, verificationCode: String) {
        LTEManager.getInstance().startLTEAuthentication(phoneAreaCode, phoneNumber, verificationCode, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 設定 LTE 增強圖傳的傳輸類型。 */
    fun setLTEEnhancedTransmissionType(lteLinkType: LTELinkType) {
        LTEManager.getInstance().setLTEEnhancedTransmissionType(lteLinkType, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 取得目前的 LTE 增強圖傳傳輸類型。 */
    fun getLTEEnhancedTransmissionType() {
        LTEManager.getInstance().getLTEEnhancedTransmissionType(object :
            CommonCallbacks.CompletionCallbackWithParam<LTELinkType> {
            override fun onSuccess(lteLinkType: LTELinkType) {
                toastResult?.postValue(DJIToastResult.success(lteLinkType.name))
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 設定飛行器的 LTE 私有化伺服器資訊。 */
    fun setLTEAcPrivatizationServerMsg(serverInfo: LTEPrivatizationServerInfo) {
        LTEManager.getInstance().setLTEAircraftPrivatizationServerInfo(serverInfo, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed("$error"))
            }
        })
    }

    /** 設定遙控器的 LTE 私有化伺服器資訊。 */
    fun setLTERcPrivatizationServerMsg(serverInfo: LTEPrivatizationServerInfo) {
        LTEManager.getInstance().setLTERemoteControllerPrivatizationServerInfo(serverInfo, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed("$error"))
            }
        })
    }

    /** 清除飛行器的 LTE 私有化伺服器資訊。 */
    fun clearLTEAcPrivatizationServerMsg() {
        LTEManager.getInstance().clearLTEAircraftPrivatizationServer(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }

    /** 清除遙控器的 LTE 私有化伺服器資訊。 */
    fun clearLTERcPrivatizationServerMsg() {
        LTEManager.getInstance().clearLTERemoteControllerPrivatizationServer(object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                toastResult?.postValue(DJIToastResult.success())
            }

            override fun onFailure(error: IDJIError) {
                toastResult?.postValue(DJIToastResult.failed(error.toString()))
            }
        })
    }
}