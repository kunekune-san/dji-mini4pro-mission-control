package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sdk.keyvalue.value.payload.WidgetValue
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.data.PayloadBasicInfo
import dji.v5.manager.aircraft.payload.data.PayloadWidgetInfo
import dji.v5.manager.aircraft.payload.listener.PayloadBasicInfoListener
import dji.v5.manager.aircraft.payload.listener.PayloadWidgetInfoListener


/**
 * 酬載 Widget 管理 ViewModel：設定 Widget 值、拉取 Widget 資訊，並監聽酬載基本資訊與 Widget 資訊。
 *
 * @author: Byte.Cai
 *  date : 2022/12/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class PayloadWidgetVM : DJIViewModel() {
    private lateinit var payloadIndexType: PayloadIndexType
    private val payloadManagerMap = PayloadCenter.getInstance().payloadManager
    val payloadBasicInfo = MutableLiveData<PayloadBasicInfo>()
    val payloadWidgetInfo = MutableLiveData<PayloadWidgetInfo>()

    private val payloadBasicInfoListener: PayloadBasicInfoListener = PayloadBasicInfoListener { info -> payloadBasicInfo.postValue(info) }
    private val payloadWidgetInfoListener: PayloadWidgetInfoListener = PayloadWidgetInfoListener {
            info -> payloadWidgetInfo.postValue(info)
    }

    /**
     * 設定酬載 Widget 的值。
     */
    fun setWidgetValue(value: WidgetValue) {
        for ((key, payloadManager) in payloadManagerMap) {
            if (key == payloadIndexType) {
                payloadManager.setWidgetValue(value, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        sendToastMsg(DJIToastResult.success("setWidgetValue success"))
                    }

                    override fun onFailure(error: IDJIError) {
                        sendToastMsg(DJIToastResult.failed(error.toString()))
                    }
                })
            }
        }
    }

    /**
     * 初始化酬載基本資訊與 Widget 資訊監聽器。
     */
    fun initListener(payloadIndexType: PayloadIndexType) {
        this.payloadIndexType = payloadIndexType
        val iPayloadManager = payloadManagerMap[payloadIndexType]
        iPayloadManager?.addPayloadBasicInfoListener(payloadBasicInfoListener)
        iPayloadManager?.addPayloadWidgetInfoListener(payloadWidgetInfoListener)
    }

    /**
     * 從酬載端拉取 Widget 資訊。
     */
    fun pullWidgetInfo() {
        val iPayloadManager = payloadManagerMap[payloadIndexType]
        iPayloadManager?.pullWidgetInfoFromPayload(object :CommonCallbacks.CompletionCallback{
            override fun onSuccess() {
                sendToastMsg(DJIToastResult.success("pullWidgetInfoFromPayload success"))
            }

            override fun onFailure(error: IDJIError) {
                sendToastMsg(DJIToastResult.failed(error.toString()))
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        val iPayloadManager = payloadManagerMap[payloadIndexType]
        iPayloadManager?.removePayloadWidgetInfoListener(payloadWidgetInfoListener)
        iPayloadManager?.removePayloadBasicInfoListener(payloadBasicInfoListener)
    }

    private fun sendToastMsg(djiToastResult: DJIToastResult) {
        toastResult?.postValue(djiToastResult)
    }
}