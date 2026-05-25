package dji.sampleV5.aircraft.mqtt

import android.util.Log
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object MqttMissionBridge {

    private const val TAG = "MqttMissionBridge"

    /** 初始化 MQTT 指令回調（註冊 command 和 mission/start 處理器） */
    fun init() {
        Log.d(TAG, "init() — registering MQTT command callbacks")

        MqttClientManager.onCommandReceived = { payload ->
            handleCommand(payload)
        }

        MqttClientManager.onMissionStartReceived = { payload ->
            handleMissionStart(payload)
        }
    }

    /** 處理飛行指令（takeoff/rtl/cancel/reset） */
    private fun handleCommand(payload: CommandPayload) {
        Log.d(TAG, "handleCommand: cmd=${payload.cmd}, msgId=${payload.msgId}")
        try {
            when (payload.cmd) {
                "takeoff" -> {
                    FlightManager.takeOff()
                    MqttClientManager.publishAck(
                        AckPayload(msgId = payload.msgId, action = payload.cmd, status = "success")
                    )
                }
                "rtl" -> {
                    FlightManager.startRTH()
                    MqttClientManager.publishAck(
                        AckPayload(msgId = payload.msgId, action = payload.cmd, status = "success")
                    )
                }
                "cancel" -> {
                    MissionStateMachine.cancelMission()
                    MqttClientManager.publishAck(
                        AckPayload(msgId = payload.msgId, action = payload.cmd, status = "success")
                    )
                }
                "reset" -> {
                    MissionStateMachine.resetToIdle()
                    MqttClientManager.publishAck(
                        AckPayload(
                            msgId    = payload.msgId,
                            action   = "reset",
                            status   = "success",
                            errorMsg = ""
                        )
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown cmd: ${payload.cmd}")
                    MqttClientManager.publishAck(
                        AckPayload(
                            msgId = payload.msgId, action = payload.cmd,
                            status = "failed", errorMsg = "Unknown cmd: ${payload.cmd}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleCommand failed: ${e.message}", e)
            MqttClientManager.publishAck(
                AckPayload(
                    msgId = payload.msgId, action = payload.cmd,
                    status = "failed", errorMsg = e.message ?: "Unknown error"
                )
            )
        }
    }

    /** 處理任務啟動指令（含座標驗證與任務類型檢查） */
    private fun handleMissionStart(payload: MissionStartPayload) {
        Log.d(TAG, "handleMissionStart: mission=${payload.mission}, msgId=${payload.msgId}")

        // Mission type validation
        if (payload.mission != "A" && payload.mission != "B") {
            Log.w(TAG, "Unknown mission type: ${payload.mission}")
            MqttClientManager.publishAck(
                AckPayload(
                    msgId = payload.msgId, action = "mission_start",
                    status = "failed", errorMsg = "Unknown mission: ${payload.mission}"
                )
            )
            return
        }

        // Mission B requires orbit block with radius > 0
        if (payload.mission == "B") {
            val orbit = payload.orbit
            if (orbit == null || orbit.radius <= 0.0) {
                Log.w(TAG, "Mission B requires orbit.radius > 0")
                MqttClientManager.publishAck(
                    AckPayload(
                        msgId = payload.msgId, action = "mission_start",
                        status = "failed",
                        errorMsg = "Mission B requires orbit block with radius > 0"
                    )
                )
                return
            }
        }

        if (MissionStateMachine.state.value != MissionStateMachine.MissionState.IDLE) {
            Log.w(TAG, "Mission already running")
            MqttClientManager.publishAck(
                AckPayload(
                    msgId = payload.msgId, action = "mission_start",
                    status = "failed", errorMsg = "Mission already in progress"
                )
            )
            return
        }

        // ===== P0-06: GPS coordinate validation =====

        // 1. Lat/Lng range check
        if (payload.target.lat < -90 || payload.target.lat > 90) {
            Log.w(TAG, "Invalid latitude: ${payload.target.lat}")
            publishErrorAck(payload.msgId, "Invalid latitude: ${payload.target.lat}")
            return
        }
        if (payload.target.lng < -180 || payload.target.lng > 180) {
            Log.w(TAG, "Invalid longitude: ${payload.target.lng}")
            publishErrorAck(payload.msgId, "Invalid longitude: ${payload.target.lng}")
            return
        }

        // 2. Altitude reasonableness check
        val maxAlt = AppConfig.getMaxFlightHeight().toDouble()
        if (payload.target.alt < 0 || payload.target.alt > maxAlt) {
            Log.w(TAG, "Invalid altitude: ${payload.target.alt} (max: $maxAlt)")
            publishErrorAck(payload.msgId, "Invalid altitude: ${payload.target.alt}")
            return
        }

        // 3. Distance pre-check (requires GPS)
        val currentLoc = FlightManager.getCurrentLocation()
        if (currentLoc != null) {
            val dist = haversine(currentLoc.latitude, currentLoc.longitude, payload.target.lat, payload.target.lng)
            val maxRadius = AppConfig.getMaxFlightRadius().toDouble()
            if (dist > maxRadius) {
                Log.w(TAG, "Target too far: ${"%.0f".format(dist)}m > ${maxRadius}m")
                publishErrorAck(payload.msgId, "Target too far: ${"%.0f".format(dist)}m > ${"%.0f".format(maxRadius)}m")
                return
            }
        } else {
            Log.w(TAG, "GPS not ready, skipping distance pre-check (will be checked in executeMission)")
        }

        try {
            MissionStateMachine.startMission(
                targetLat = payload.target.lat,
                targetLng = payload.target.lng,
                targetAlt = payload.target.alt,
                missionType = payload.mission,
                orbitRadius = payload.orbit?.radius ?: 0.0,
                orbitAlt = payload.orbit?.alt,
                orbitGimbalPitch = if (payload.mission == "A" && payload.orbit == null) 0.0
                                   else payload.orbit?.gimbalPitch ?: -45.0,
                photoGimbalPitch = payload.orbit?.photoGimbalPitch ?: -45.0
            )
            MqttClientManager.publishAck(
                AckPayload(msgId = payload.msgId, action = "mission_start", status = "success")
            )
        } catch (e: Exception) {
            Log.e(TAG, "startMission failed: ${e.message}", e)
            MqttClientManager.publishAck(
                AckPayload(
                    msgId = payload.msgId, action = "mission_start",
                    status = "failed", errorMsg = e.message ?: "Unknown error"
                )
            )
        }
    }

    /** 發布錯誤 ACK 給後端 */
    private fun publishErrorAck(msgId: String, errorMsg: String) {
        MqttClientManager.publishAck(
            AckPayload(
                msgId = msgId, action = "mission_start",
                status = "failed", errorMsg = errorMsg
            )
        )
    }

    /** Haversine 公式：計算兩 GPS 座標間的距離（公尺） */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
