package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.aircraft.uas.AreaStrategy
import dji.v5.manager.aircraft.uas.ElectronicIDStatus
import dji.sampleV5.aircraft.util.ToastUtils

/**
 * Description : 法國無人機遠端識別 VM。管理電子識別（Electronic ID）的啟用與狀態監聽。
 *
 * @author: Byte.Cai
 *  date : 2022/6/27
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class UASFranceVM : UASEuropeanVM() {

    val electronicIDStatus = MutableLiveData<ElectronicIDStatus>()

    init {
        val error = uasRemoteIDManager.setUASRemoteIDAreaStrategy(AreaStrategy.FRANCE_STRATEGY)
        error?.apply {
            ToastUtils.showToast(toString())
        }
    }

    /** 設定電子識別（Electronic ID）功能是否啟用。 */
    fun setElectronicIDEnabled(boolean: Boolean, callbacks: CommonCallbacks.CompletionCallback) {
        uasRemoteIDManager.setElectronicIDEnabled(boolean, callbacks)
    }

    /** 加入電子識別（Electronic ID）狀態監聽器。 */
    fun addElectronicIDStatusListener() {
        uasRemoteIDManager.addElectronicIDStatusListener {
            electronicIDStatus.postValue(it)
        }
    }

    /** 清除所有電子識別與遠端識別狀態監聽器。 */
    fun clearAllListener() {
        uasRemoteIDManager.clearAllElectronicIDStatusListener()
        uasRemoteIDManager.clearUASRemoteIDStatusListener()
    }
}