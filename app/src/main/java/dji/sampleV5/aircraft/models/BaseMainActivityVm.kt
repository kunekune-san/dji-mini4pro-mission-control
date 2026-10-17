package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.R
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.PairingState
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.SDKManager
import dji.v5.utils.common.StringUtils

/**
 * 主頁面 BaseActivity 使用的 ViewModel。負責顯示 SDK 新聞資訊，以及控制遙控器進入或停止配對狀態。
 *
 * @author Hoker
 * @date 2022/2/14
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class BaseMainActivityVm : DJIViewModel() {

    val sdkNews = MutableLiveData<SDKNews>()

    init {
        updateNews()
    }

    private fun updateNews() {
        sdkNews.postValue(SDKNews(R.string.news_title, R.string.news_description, StringUtils.getResStr(R.string.news_date)))
    }

    /** 依目前配對狀態觸發開始或停止配對，並將結果文字透過 callback 回傳。 */
    fun doPairing(callback: ((String) -> Unit)? = null) {
        if (!SDKManager.getInstance().isRegistered) {
            return
        }
        RemoteControllerKey.KeyPairingStatus.create().get({
            if (it == PairingState.PAIRING) {
                RemoteControllerKey.KeyStopPairing.create().action()
                callback?.invoke(StringUtils.getResStr(R.string.stop_pairing))
            } else {
                RemoteControllerKey.KeyRequestPairing.create().action()
                callback?.invoke(StringUtils.getResStr(R.string.start_pairing))
            }
        }) {
            callback?.invoke(it.toString())
        }
    }

    /** SDK 新聞資訊資料模型（標題、描述與日期的資源 ID）。 */
    data class SDKNews(
        var title: Int,
        var description: Int,
        var date: String,
    )
}