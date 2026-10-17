package dji.sampleV5.aircraft.util

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DJIToastResult

/**
 * Description : djiToastLD 的儲存與提供工具類，用於向其他 ViewModel 提供 djiToastLD
 *
 * @author: Byte.Cai
 * date : 2022/6/16
 *
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
object DJIToastUtil {
    var dJIToastLD: MutableLiveData<DJIToastResult>? = null
}