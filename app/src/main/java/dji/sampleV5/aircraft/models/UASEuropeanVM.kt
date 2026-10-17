package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.utils.ProductUtil
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.aircraft.uas.AreaStrategy
import dji.v5.manager.aircraft.uas.CClassStatus
import dji.v5.manager.aircraft.uas.CClassStatusListener
import dji.v5.manager.aircraft.uas.OperatorRegistrationNumberStatus
import dji.v5.manager.aircraft.uas.OperatorRegistrationNumberStatusListener
import dji.v5.manager.aircraft.uas.UASRemoteIDManager
import dji.v5.manager.aircraft.uas.UASRemoteIDStatus
import dji.v5.manager.aircraft.uas.UASRemoteIDStatusListener
import dji.v5.utils.common.StringUtils

/**
 * 歐盟無人機遠端識別 ViewModel：管理操作者註冊編號、C 類別狀態與遠端識別狀態監聽。
 *
 * @author: Byte.Cai
 *  date : 2022/6/27
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class UASEuropeanVM : DJIViewModel() {

    val uasRemoteIDStatus = MutableLiveData(UASRemoteIDStatus())
    val operatorRegistrationNumberStatus = MutableLiveData(OperatorRegistrationNumberStatus())
    val currentOperatorRegistrationNumber = MutableLiveData("")
    val currentCClassStatus = MutableLiveData(CClassStatus.UNKNOWN)
    val M3ELittleBatteryType = MutableLiveData("")

    private val uasRemoteIDStatusListener = UASRemoteIDStatusListener {
        uasRemoteIDStatus.postValue(it)
    }

    private val operatorRegistrationNumberStatusListener = OperatorRegistrationNumberStatusListener {
        operatorRegistrationNumberStatus.postValue(it)
    }

    private val cClassStatusListener = CClassStatusListener {
        currentCClassStatus.postValue(it)
    }

    protected val uasRemoteIDManager: UASRemoteIDManager = UASRemoteIDManager.getInstance()

    init {
        val error = uasRemoteIDManager.setUASRemoteIDAreaStrategy(AreaStrategy.EUROPEAN_STRATEGY)
        error?.apply {
            ToastUtils.showToast(toString())
        }
        BatteryKey.KeySerialNumber.create().get(onSuccess = {
            checkIsM3ELittleBattery()
        }, onFailure = {

        })
    }

    /**
     * 設定操作者註冊編號。
     */
    fun setOperatorRegistrationNumber(number: String) {
        uasRemoteIDManager.setOperatorRegistrationNumber(number, object :
            CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                ToastUtils.showToast("Set Operator Registration Number Success")
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast(error.toString())
            }
        })
    }

    /** 取得目前的操作者註冊編號。 */
    fun getOperatorRegistrationNumber() {
        uasRemoteIDManager.getOperatorRegistrationNumber(object :
            CommonCallbacks.CompletionCallbackWithParam<String> {
            override fun onSuccess(str: String) {
                ToastUtils.showToast("Get Operator Registration Number Success")
                currentOperatorRegistrationNumber.postValue(str)
            }

            override fun onFailure(error: IDJIError) {
                ToastUtils.showToast(error.toString())
            }
        })
    }

    /** 加入操作者註冊編號狀態監聽器。 */
    fun addOperatorRegistrationNumberStatusListener() {
        uasRemoteIDManager.addOperatorRegistrationNumberStatusListener(operatorRegistrationNumberStatusListener)
    }

    /** 清除所有操作者註冊編號狀態監聽器。 */
    fun removeOperatorRegistrationNumberStatusListener() {
        uasRemoteIDManager.clearAllOperatorRegistrationNumberStatusListener()
    }

    /** 加入 C 類別狀態監聽器。 */
    fun addCClassStatusListener() {
        uasRemoteIDManager.addCClassStatusListener(cClassStatusListener)
    }

    /** 清除所有 C 類別狀態監聽器。 */
    fun clearAllCClassStatusListener() {
        uasRemoteIDManager.clearAllCClassStatusListener()
    }

    /** 加入遠端識別狀態監聽器。 */
    fun addRemoteIdStatusListener() {
        uasRemoteIDManager.addUASRemoteIDStatusListener(uasRemoteIDStatusListener)
    }

    /** 清除所有遠端識別狀態監聽器。 */
    fun clearRemoteIdStatusListener() {
        uasRemoteIDManager.clearUASRemoteIDStatusListener()
    }

    private fun checkIsM3ELittleBattery() {
        if (ProductUtil.isM3ELittleBattery()) {
            M3ELittleBatteryType.postValue(StringUtils.getResStr(R.string.m3e_little_battery_connceted))
        }
    }
}
