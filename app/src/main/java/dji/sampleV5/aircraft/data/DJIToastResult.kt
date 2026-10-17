package dji.sampleV5.aircraft.data


/**
 * 描述：面向 Model 使用的執行結果封裝類別，簡單封裝了外部需要 Toast 的資訊
 *
 * @author: Byte.Cai
 *  date : 2022/2/22
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class DJIToastResult(var isSuccess: Boolean, var msg: String? = null) {

    companion object {
        /**
         * 建立執行成功的結果物件
         */
        fun success(msg: String? = null): DJIToastResult {
            return DJIToastResult(true, "success ${msg ?: ""}")
        }

        /**
         * 建立執行失敗的結果物件
         */
        fun failed(msg: String): DJIToastResult {
            return DJIToastResult(false, msg)
        }
    }
}



