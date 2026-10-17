package dji.sampleV5.aircraft.data

/**
 * @author feel.feng
 * @time 2022/09/14 2:32 下午
 * @description: key 的檢查結果資訊
 */
data class KeyCheckInfo(
    var keyName:String  ,
    var isPass : Boolean ,
    var failedReson: String
) {}