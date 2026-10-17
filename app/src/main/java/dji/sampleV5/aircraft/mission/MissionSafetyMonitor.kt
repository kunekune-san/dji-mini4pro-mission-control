package dji.sampleV5.aircraft.mission

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mqtt.MqttClientManager
import dji.sampleV5.aircraft.mqtt.MqttConnectionEventBus
import dji.sampleV5.aircraft.mqtt.MqttStatusReporter
import dji.sampleV5.aircraft.safety.SafetyAnomalyRecord
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import dji.sampleV5.aircraft.safety.SafetyEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 安全監控模組 — 從 MissionStateMachine 提取。
 *
 * 負責：GPS/電池異常偵測、MQTT 斷線監控、卡在返航中偵測、
 * 安全事件 CSV 紀錄。
 *
 * @param onCancelMission 觸發任務取消（由 MissionStateMachine 注入）
 * @param onResetToIdle    重置到 IDLE（需經 isDroneOnGround 檢查）
 * @param isOnGround       檢查無人機是否已落地
 */
internal class MissionSafetyMonitor(
    private val getScope: () -> CoroutineScope,
    private val getState: () -> MissionStateMachine.MissionState,
    private val setState: (MissionStateMachine.MissionState, String?) -> Unit,
    private val onCancelMission: (String) -> Unit,
    private val onResetToIdle: () -> Unit,
    private val isOnGround: () -> Boolean,
    private val onCancelMissionJob: () -> Unit,
    private val onDisableVS: () -> Unit,
) {
    companion object {
        private const val TAG = "MissionSM"
    }

    /** 安全事件記錄器（CSV 寫入），由 MissionStateMachine 注入 */
    var safetyEventLogger: SafetyEventLogger? = null
    /** GPS 弱訊號起始時間，用於 GPS_WEAK_TIMEOUT_MS 超時判定 */
    private var gpsWeakStartTime: Long = 0L
    /** 最近一次 MQTT payload，異常記錄時隨附供後端診斷 */
    var lastMqttPayload: String? = null

    /** 初始化安全監控：綁定電池/GPS 異常事件回調 + MQTT 斷線/H9 監控 */
    fun setup(logger: SafetyEventLogger) {
        safetyEventLogger = logger
        FlightManager.onSafetyAnomaly = { anomalyType ->
            getScope().launch { handleSafetyAnomaly(anomalyType) }
        }
        FlightManager.onGpsRecovered = { resetGpsWeakTimer() }

        // MQTT 連線狀態事件收集
        var mqttDisconnectStartTime = 0L
        getScope().launch {
            MqttConnectionEventBus.events.collect { connected ->
                if (!connected && FlightManager.isFlying.value) {
                    mqttDisconnectStartTime = System.currentTimeMillis()
                    AppLog.w(TAG, "MQTT disconnected while flying, timer started")
                } else {
                    mqttDisconnectStartTime = 0L
                }
            }
        }

        // H8: MQTT 斷線超時返航檢查（每 10 秒）
        getScope().launch {
            while (isActive) {
                delay(10_000L)
                if (mqttDisconnectStartTime > 0 &&
                    FlightManager.isFlying.value &&
                    System.currentTimeMillis() - mqttDisconnectStartTime > AppConfig.MQTT_DISCONNECT_RTH_TIMEOUT_MS
                ) {
                    Log.w(TAG, "MQTT disconnected for >${AppConfig.MQTT_DISCONNECT_RTH_TIMEOUT_MS / 1000}s while flying, auto RTH")
                    mqttDisconnectStartTime = 0L
                    try { onCancelMission("MQTT disconnected >${AppConfig.MQTT_DISCONNECT_RTH_TIMEOUT_MS / 1000}s") } catch (_: Exception) {}
                }
            }
        }

        // H9: 卡在返航中偵測（每 10 秒檢查一次，若已降落則強制回 IDLE）
        getScope().launch {
            while (isActive) {
                delay(10_000L)
                if (getState() == MissionStateMachine.MissionState.RETURNING_HOME && isOnGround()) {
                    Log.w(TAG, "Stuck in RETURNING_HOME but drone confirmed on ground, forcing IDLE")
                    onResetToIdle()
                }
            }
        }

        AppLog.d(TAG, "Safety monitoring setup complete")
    }

    /** 處理安全異常事件：記錄座標、更新狀態、MQTT 通知後端 */
    suspend fun handleSafetyAnomaly(anomalyType: SafetyAnomalyType) {
        val record = SafetyAnomalyRecord(
            timestamp = System.currentTimeMillis(),
            eventType = anomalyType.name,
            latitude = FlightManager.currentLat.value ?: 0.0,
            longitude = FlightManager.currentLng.value ?: 0.0,
            altitude = FlightManager.altitude.value,
            batteryPercent = FlightManager.batteryPercent.value,
            gpsSignalLevel = FlightManager.gpsSignalLevel.value ?: 0,
            gpsSatellites = FlightManager.gpsSatellites.value ?: 0,
            reason = anomalyType.mqttReason,
            mqttPayload = lastMqttPayload
        )

        safetyEventLogger?.logAnomaly(record)

        val currentState = getState()
        if (currentState != MissionStateMachine.MissionState.IDLE &&
            currentState != MissionStateMachine.MissionState.ERROR) {
            when (anomalyType) {
                SafetyAnomalyType.LOW_BATTERY_RTH -> {
                    Log.w(TAG, "Safety: Battery low ${record.batteryPercent}%, cancelling mission for RTH")
                    onCancelMission("Battery low RTH: ${record.batteryPercent}%")
                }
                SafetyAnomalyType.BATTERY_FORCED_LAND -> {
                    Log.w(TAG, "Safety: Battery critical ${record.batteryPercent}%, forcing RTH")
                    onCancelMission("Battery critical: ${record.batteryPercent}%")
                }
                SafetyAnomalyType.FIRMWARE_RTH -> {
                    Log.w(TAG, "Safety: Firmware-initiated RTH detected during ${getState()}, cancelling mission")
                    logEvent(SafetyAnomalyType.FIRMWARE_RTH,
                        "Firmware RTH during ${getState()} (battery=${record.batteryPercent}%, alt=${"%.1f".format(record.altitude)}m)")
                    onCancelMissionJob()
                    onDisableVS()
                    setState(MissionStateMachine.MissionState.RETURNING_HOME, anomalyType.mqttReason)
                }
                SafetyAnomalyType.GPS_SIGNAL_WEAK,
                SafetyAnomalyType.GPS_SIGNAL_LOST -> {
                    if (gpsWeakStartTime == 0L) {
                        gpsWeakStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - gpsWeakStartTime > AppConfig.GPS_WEAK_TIMEOUT_MS) {
                        Log.e(TAG, "Safety: GPS signal lost for ${AppConfig.GPS_WEAK_TIMEOUT_MS / 1000}s, aborting mission")
                        setState(MissionStateMachine.MissionState.ERROR, "gps_signal_lost_timeout")
                        gpsWeakStartTime = 0L
                    }
                }
                else -> { /* 任務異常類型不影響狀態機 */ }
            }
        }

        MqttStatusReporter.publishSafetyAnomaly(anomalyType, record)
    }

    /** 記錄任務異常事件（公開 API，供外部呼叫） */
    fun logEvent(anomalyType: SafetyAnomalyType, reason: String, mqttPayload: String? = null) {
        logEventInternal(anomalyType, reason, mqttPayload ?: lastMqttPayload)
    }

    /** 內部異常記錄共用實作：建構 SafetyAnomalyRecord（座標/電池/GPS 快照）並寫入 CSV */
    private fun logEventInternal(anomalyType: SafetyAnomalyType, reason: String, payload: String?) {
        val record = SafetyAnomalyRecord(
            timestamp = System.currentTimeMillis(),
            eventType = anomalyType.name,
            latitude = FlightManager.currentLat.value ?: 0.0,
            longitude = FlightManager.currentLng.value ?: 0.0,
            altitude = FlightManager.altitude.value,
            batteryPercent = FlightManager.batteryPercent.value,
            gpsSignalLevel = FlightManager.gpsSignalLevel.value ?: 0,
            gpsSatellites = FlightManager.gpsSatellites.value ?: 0,
            reason = reason,
            mqttPayload = payload
        )
        safetyEventLogger?.logAnomaly(record)
    }

    /** GPS 訊號恢復時重置弱訊號計時器 */
    fun resetGpsWeakTimer() {
        if (gpsWeakStartTime != 0L) {
            AppLog.d(TAG, "GPS signal recovered, resetting weak timer")
            gpsWeakStartTime = 0L
        }
    }
}
