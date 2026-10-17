package dji.sampleV5.aircraft.mqtt

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.safety.SafetyAnomalyRecord
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import kotlinx.coroutines.*

/** 狀態回報與心跳管理，定期發布無人機狀態給後端 */
object MqttStatusReporter {

    private const val TAG = "MqttStatusReporter"

    @Volatile private var reporterJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 狀態回報：飛行中每 10 秒，待機每 30 秒（連線確認用）
    /** 啟動狀態回報（飛行中每 10 秒，待機每 30 秒） */
    fun startReporting() {
        reporterJob?.cancel()
        reporterJob = scope.launch {
            while (isActive) {
                val isFlying = FlightManager.isFlying.value
                val intervalMs = if (isFlying) 10000L else 30000L

                publishCurrentStatus()
                delay(intervalMs)
            }
        }
        AppLog.d(TAG, "Status reporting started")
    }

    // 心跳：每 5 秒發送一次，確認連線存活
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
            else -> MissionStateMachine.state.value.name
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
        AppLog.d(TAG, "Safety anomaly published: ${anomalyType.name} reason=${anomalyType.mqttReason}")
    }

    /** 停止狀態回報與心跳 */
    fun stop() {
        reporterJob?.cancel()
        heartbeatJob?.cancel()
        reporterJob = null
        heartbeatJob = null
        AppLog.d(TAG, "Status reporting + heartbeat stopped")
    }
}
