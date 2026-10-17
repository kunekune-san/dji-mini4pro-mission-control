package dji.sampleV5.aircraft.models

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create

/** 飛行器基本控制 ViewModel：透過 FlightControllerKey 觸發起飛與自動降落。 */
class BasicAircraftControlVM : DJIViewModel() {

    /** 觸發飛行器自動起飛，並將結果回傳給呼叫端。 */
    fun startTakeOff(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        FlightControllerKey.KeyStartTakeoff.create().action({
            callback.onSuccess(it)
        }, { e: IDJIError ->
            callback.onFailure(e)
        })
    }

    /** 觸發飛行器自動降落，並將結果回傳給呼叫端。 */
    fun startLanding(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        FlightControllerKey.KeyStartAutoLanding.create().action({
            callback.onSuccess(it)
        }, { e: IDJIError ->
            callback.onFailure(e)
        })
    }
}