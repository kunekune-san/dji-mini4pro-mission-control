package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.uas.AreaStrategy
import dji.v5.manager.aircraft.uas.UARegistrationNumberStatus
import dji.v5.manager.aircraft.uas.UASRemoteIDManager
import dji.v5.manager.aircraft.uas.UASRemoteIDStatus
import dji.sampleV5.aircraft.util.ToastUtils

/**
 * Description : 日本無人機遠端識別 VM。管理註冊編號的設定/取得與遠端識別狀態監聽。
 *
 * @author: Byte.Cai
 *  date : 2022/6/27
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class UASJapanVM : DJIViewModel() {
    private val instance = UASRemoteIDManager.getInstance()
    val uaRegNumberStatus = MutableLiveData<UARegistrationNumberStatus>()
    val uasRemoteIDStatus = MutableLiveData<UASRemoteIDStatus>()
    val uaRegistrationNumber = MutableLiveData<String?>()

    init {
        val error = instance.setUASRemoteIDAreaStrategy(AreaStrategy.JAPAN_STRATEGY)
        error?.apply {
            ToastUtils.showToast(toString())
        }
    }

    /** 設定無人機註冊編號。 */
    fun setUARegistrationNumber(number: String) {

        instance.setUARegistrationNumber(number,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    toastResult?.postValue(DJIToastResult.success("setUARegistrationNumber success"))

                }

                override fun onFailure(error: IDJIError) {
                    toastResult?.postValue(DJIToastResult.failed(error.toString()))

                }

            })
    }

    /** 取得目前設定的無人機註冊編號。 */
    fun getUARegistrationNumber() {
        instance.getUARegistrationNumber(object : CommonCallbacks.CompletionCallbackWithParam<String> {
            override fun onSuccess(t: String?) {
                toastResult?.postValue(DJIToastResult.success("getUARegistrationNumber success"))
                uaRegistrationNumber.postValue(t)
            }

            override fun onFailure(error: IDJIError) {
                uaRegistrationNumber.postValue(error.toString())
            }

        })
    }

    /** 加入遠端識別狀態監聽器。 */
    fun addUASRemoteIDStatusListener() {
        instance.addUASRemoteIDStatusListener {
            uasRemoteIDStatus.postValue(it)
        }
    }

    /** 加入註冊編號狀態監聽器。 */
    fun addUARegistrationNumberStatusListener() {
        instance.addUARegistrationNumberStatusListener {
            uaRegNumberStatus.postValue(it)
        }
    }

    /** 清除所有遠端識別與註冊編號狀態監聽器。 */
    fun clearAllListener() {
        instance.clearUASRemoteIDStatusListener()
        instance.clearAllUARegistrationNumberStatusListener()
    }
}