package dji.sampleV5.aircraft.models

import androidx.lifecycle.ViewModel
import dji.sampleV5.aircraft.util.DJIToastUtil
import dji.v5.utils.common.LogUtils

/**
 * 所有 ViewModel 的基底類別，提供 Toast 結果與 Log 標籤的共用功能。
 *
 * @author Hoker
 * @date 2021/7/5
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
open class DJIViewModel : ViewModel() {
    val toastResult
        get() = DJIToastUtil.dJIToastLD

    val logTag = LogUtils.getTag(this)

}