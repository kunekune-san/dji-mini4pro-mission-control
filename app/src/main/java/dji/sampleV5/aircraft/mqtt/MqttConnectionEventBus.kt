package dji.sampleV5.aircraft.mqtt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * MQTT 連線狀態事件匯流排。
 *
 * 解除 MqttClientManager → MissionStateMachine 的循環依賴：
 * MqttClientManager 發送事件到此匯流排，
 * MissionStateMachine 收集事件並做出反應。
 */
object MqttConnectionEventBus {

    private val _events = MutableSharedFlow<Boolean>(
        replay = 1,               // 新訂閱者立即收到最新狀態
        extraBufferCapacity = 2   // 允許少量緩衝
    )

    /** 連線狀態事件流：true = 已連線，false = 已斷線 */
    val events: SharedFlow<Boolean> = _events.asSharedFlow()

    /** 由 MqttClientManager 呼叫，發送連線狀態變更事件 */
    fun onConnectionChanged(connected: Boolean) {
        _events.tryEmit(connected)
    }
}
