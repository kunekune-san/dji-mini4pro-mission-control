package dji.sampleV5.aircraft.mqtt

import android.util.Log
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.safety.SafetyAnomalyRecord
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import kotlinx.coroutines.*

object MqttStatusReporter {

    private const val TAG = "MqttStatusReporter"

    private var reporterJob: Job? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 狀態回報：飛行中每 30 秒，待機每 60 秒（連線確認用）
    /** 啟動狀態回報（飛行中每 30 秒，待機每 60 秒） */
    fun startReporting() {
        reporterJob?.cancel()
        reporterJob = scope.launch {
            while (isActive) {
                val isFlying = FlightManager.isFlying.value
                val intervalMs = if (isFlying) 30000L else 60000L

                publishCurrentStatus()
                delay(intervalMs)
            }
        }
        Log.d(TAG, "Status reporting started")
    }

    // 任務關鍵時刻：事件觸發立即回報
    /** 啟動心跳發送（每 5 秒） */
    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                MqttClientManager.publishHeartbeat()
                delay(5000L)
            }
        }
    }

    /** 發布當前無人機狀態（座標、電池、GPS 等） */
    fun publishCurrentStatus() {
        val payload = DroneStatusPayload(
            state         = MissionStateMachine.state.value.name,
            lat           = FlightManager.currentLat.value,
            lng           = FlightManager.currentLng.value,
            alt           = FlightManager.altitude.value,
            battery       = FlightManager.batteryPercent.value,
            heading       = FlightManager.heading.value,
            gpsSatellites = FlightManager.gpsSatellites.value
        )
        MqttClientManager.publishStatus(payload)
    }

    /** 發布安全異常事件（即時通知後端） */
    fun publishSafetyAnomaly(anomalyType: SafetyAnomalyType, record: SafetyAnomalyRecord) {
        val state = when (anomalyType) {
            SafetyAnomalyType.LOW_BATTERY_RTH,
            SafetyAnomalyType.BATTERY_FORCED_LAND -> "RETURNING_HOME"
            SafetyAnomalyType.GPS_SIGNAL_WEAK,
            SafetyAnomalyType.GPS_SIGNAL_LOST -> MissionStateMachine.state.value.name
        }
        val payload = DroneStatusPayload(
            state         = state,
            lat           = record.latitude,
            lng           = record.longitude,
            alt           = record.altitude,
            battery       = record.batteryPercent,
            heading       = FlightManager.heading.value,
            gpsSatellites = record.gpsSatellites,
            reason        = anomalyType.mqttReason
        )
        MqttClientManager.publishStatus(payload)
        Log.d(TAG, "Safety anomaly published: ${anomalyType.name} reason=${anomalyType.mqttReason}")
    }

    /** 停止狀態回報與心跳 */
    fun stop() {
        reporterJob?.cancel()
        heartbeatJob?.cancel()
        reporterJob = null
        heartbeatJob = null
        Log.d(TAG, "Status reporting + heartbeat stopped")
    }
}
