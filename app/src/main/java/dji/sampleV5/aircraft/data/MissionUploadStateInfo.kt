package dji.sampleV5.aircraft.data

import dji.v5.common.error.IDJIError




/**
 * 任務上傳狀態資訊資料類別，包含提示文字、更新進度與錯誤資訊
 */
data class MissionUploadStateInfo(
    var tips: String = ""
    , var updateProgress: Double = 0.0
    , val error: IDJIError? = null
) {


}