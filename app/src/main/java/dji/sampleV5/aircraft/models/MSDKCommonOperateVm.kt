package dji.sampleV5.aircraft.models

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult
import dji.sampleV5.aircraft.data.FragmentPageItemList
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.ldm.LDMExemptModule
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.SDKManager
import dji.v5.manager.ldm.LDMManager
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.DeviceInfoUtil.getPackageName
import dji.v5.utils.common.FileUtils
import java.io.File


/**
 * MSDK 常用操作 ViewModel。提供 LDM 啟用/停用、App 註冊與主頁選單資訊載入等功能。
 *
 * @author Hoker
 * @date 2021/5/10
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class MSDKCommonOperateVm : DJIViewModel() {

    val mainPageInfoList = MutableLiveData<LinkedHashSet<FragmentPageItemList>>()

    /** 載入主頁的選單項目清單。 */
    fun loaderItem(itemList: LinkedHashSet<FragmentPageItemList>) {
        mainPageInfoList.postValue(itemList)
    }

    /** 啟用 LDM，並設定需要豁免的模組清單。 */
    fun enableLDM(context: Context, callback: CommonCallbacks.CompletionCallback, ldmExemptModuleList: Array<LDMExemptModule?>) {
        LDMManager.getInstance().enableLDM(context, callback, * ldmExemptModuleList)
    }

    /** 停用 LDM。 */
    fun disableLDM(callback: CommonCallbacks.CompletionCallback) {
        LDMManager.getInstance().disableLDM(callback);
    }

    /** 註冊 App 至 SDK。 */
    fun registerApp() {
        SDKManager.getInstance().registerApp()
    }
}