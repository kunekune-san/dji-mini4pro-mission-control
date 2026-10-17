package dji.sampleV5.aircraft

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dji.sampleV5.aircraft.data.DJIToastResult

/**
 * TestingToolsActivity 對應的 ViewModel。
 * 主要用於建立 djiToastResult，統一發送與觀察需要顯示 Toast 的內容。
 *
 * @author: Byte.Cai
 *  date : 2022/6/16
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class TestToolsVM : ViewModel() {
    val djiToastResult = MutableLiveData<DJIToastResult>()

}