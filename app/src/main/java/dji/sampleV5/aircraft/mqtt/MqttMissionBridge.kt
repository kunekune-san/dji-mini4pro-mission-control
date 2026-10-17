package dji.sampleV5.aircraft.mqtt

import android.util.Log
import dji.sampleV5.aircraft.util.AppLog
import dji.sampleV5.aircraft.config.AppConfig
import dji.sampleV5.aircraft.dji.FlightManager
import dji.sampleV5.aircraft.mission.MissionStateMachine
import dji.sampleV5.aircraft.safety.SafetyAnomalyType
import dji.sampleV5.aircraft.util.GeoMath
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

/** MQTT 指令到任務執行的橋接器，負責解析指令並呼叫對應的任務函式 */
object MqttMissionBridge {

    private const val TAG = "MqttMissionBridge"

    /** 記錄驗證失敗事件（含 MQTT Payload）到飛行紀錄 */
    private fun logValidationFail(reason: String, rawJson: String?) {
        MissionStateMachine.logMissionEvent(SafetyAnomalyType.MISSION_VALIDATION_FAIL, reason, rawJson)
    }

    /** 初始化 MQTT 指令回調（註冊 command、mission/start 與 mission/multi 處理器） */
    fun init() {
        AppLog.d(TAG, "init() — registering MQTT command callbacks")

        MqttClientManager.onCommandReceived = { payload ->
            handleCommand(payload)
        }

        MqttClientManager.onMissionStartReceived = { payload ->
            handleMissionStart(payload)
        }

        MqttClientManager.onMultiStartReceived = { payload ->
            handleMultiStart(payload)
        }
    }

    /** 處理飛行指令（takeoff/rtl/cancel/reset） */
    private fun handleCommand(payload: CommandPayload) {
        AppLog.d(TAG, "handleCommand: cmd=${payload.cmd}, msgId=${payload.msgId}")
        try {
            when (payload.cmd) {
                "takeoff" -> {
                    if (MissionStateMachine.state.value != MissionStateMachine.MissionState.IDLE) {
                        MqttClientManager.publishAck(
                            AckPayload(
                                msgId = payload.msgId, action = "takeoff", status = "failed",
                                errorMsg = "Cannot takeoff: mission in progress (${MissionStateMachine.state.value})"
                            )
                        )
                        return
                    }
                    // 電池預檢
                    val currentBattery = FlightManager.batteryPercent.value
                    val threshold = FlightManager.effectiveThreshold.value
                    if (currentBattery <= threshold) {
                        MqttClientManager.publishAck(
                            AckPayload(
                                msgId = payload.msgId, action = "takeoff", status = "failed",
                                errorMsg = "Battery too low: $currentBattery% <= $threshold% threshold"
                            )
                        )
                        return
                    }
                    MqttClientManager.publishAck(
                        AckPayload(msgId = payload.msgId, action = "takeoff", status = "accepted")
                    )
                    FlightManager.takeOff(object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(value: EmptyMsg) {
                            MqttClientManager.publishAck(
                                AckPayload(msgId = payload.msgId, action = "takeoff", status = "success")
                            )
                        }
                        override fun onFailure(error: IDJIError) {
                            MqttClientManager.publishAck(
                                AckPayload(msgId = payload.msgId, action = "takeoff", status = "failed", errorMsg = error.toString())
                            )
                        }
                    })
                }
                "rtl" -> {
                    val currentState = MissionStateMachine.state.value

                    // 唯一阻擋：在地面且未飛行 → RTH 無意義
                    if (currentState == MissionStateMachine.MissionState.IDLE && !FlightManager.isFlying.value) {
                        MqttClientManager.publishAck(
                            AckPayload(msgId = payload.msgId, action = "rtl", status = "failed", errorMsg = "Already on ground (IDLE)")
                        )
                        return
                    }

                    // 其餘所有狀態（IDLE+已起飛、任何任務中、ERROR、甚至已在 RETURNING_HOME）
                    // → 一律接受 rtl，最高優先權強制返航
                    MqttClientManager.publishAck(
                        AckPayload(msgId = payload.msgId, action = "rtl", status = "accepted")
                    )

                    // 清除任何進行中的任務，用 cancelMission 做完整清理後啟動 RTH
                    MissionStateMachine.cancelMission(
                        reason = "MQTT rtl command (${payload.msgId})",
                        mqttMsgId = payload.msgId,
                        mqttAction = "rtl"
                    )
                }
                "cancel" -> {
                    val currentState = MissionStateMachine.state.value
                    if (currentState == MissionStateMachine.MissionState.IDLE) {
                        MqttClientManager.publishAck(
                            AckPayload(msgId = payload.msgId, action = payload.cmd, status = "failed", errorMsg = "Already on ground (IDLE)")
                        )
                        return
                    }
                    if (currentState == MissionStateMachine.MissionState.RETURNING_HOME) {
                        MqttClientManager.publishAck(
                            AckPayload(msgId = payload.msgId, action = payload.cmd, status = "failed", errorMsg = "Already returning home")
                        )
                        return
                    }
                    if (currentState == MissionStateMachine.MissionState.ERROR) {
                        MqttClientManager.publishAck(
                            AckPayload(msgId = payload.msgId, action = payload.cmd, status = "failed", errorMsg = "In ERROR state, use reset first")
                        )
                        return
                    }
                    MqttClientManager.publishAck(
                        AckPayload(msgId = payload.msgId, action = payload.cmd, status = "accepted")
                    )
                    MissionStateMachine.cancelMission(
                        reason = "MQTT cancel command (${payload.msgId})",
                        mqttMsgId = payload.msgId,
                        mqttAction = payload.cmd
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
        AppLog.d(TAG, "handleMissionStart: mission=${payload.mission}, msgId=${payload.msgId}")

        // ── 集中驗證（MissionValidator 統一處理） ──
        MissionValidator.validateSingleMission(payload, maxAlt = AppConfig.getMaxFlightHeight().toDouble())?.let { error ->
            Log.w(TAG, error)
            logValidationFail(error, payload.rawJson)
            publishErrorAck(payload.msgId, error)
            return
        }

        // ── 任務狀態檢查 ──
        if (MissionStateMachine.state.value != MissionStateMachine.MissionState.IDLE) {
            Log.w(TAG, "Mission already running")
            logValidationFail("Mission already in progress", payload.rawJson)
            MqttClientManager.publishAck(
                AckPayload(
                    msgId = payload.msgId, action = "mission_start",
                    status = "failed", errorMsg = "Mission already in progress"
                )
            )
            return
        }

        // ── 距離預檢（需 GPS） ──
        val currentLoc = FlightManager.getCurrentLocation()
        if (currentLoc != null) {
            val dist = GeoMath.haversine(currentLoc.latitude, currentLoc.longitude, payload.target.lat, payload.target.lng)
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
            // 第一階段：指令已收到並開始執行
            MqttClientManager.publishAck(
                AckPayload(msgId = payload.msgId, action = "mission_start", status = "accepted")
            )
            MissionStateMachine.startMission(
                targetLat = payload.target.lat,
                targetLng = payload.target.lng,
                targetAlt = payload.target.alt,
                missionType = payload.mission,
                orbitRadius = payload.orbit?.radius ?: 0.0,
                orbitAlt = payload.orbit?.alt,
                orbitGimbalPitch = if (payload.mission == "A" && payload.orbit == null) 0.0
                                   else payload.orbit?.gimbalPitch ?: -45.0,
                photoGimbalPitch = payload.orbit?.photoGimbalPitch ?: -45.0,
                photoCount = payload.photoCount,
                maxAlt = payload.orbit?.maxAlt,
                turns = payload.orbit?.turns,
                radiusEnd = payload.orbit?.radiusEnd,
                orbitSpeed = payload.orbit?.orbitSpeed ?: 1.0,
                finalHeading = payload.orbit?.finalHeading,
                mqttPayload = payload.rawJson,
                msgId = payload.msgId
            )
            // 第二階段（success/failed）由 MissionStateMachine 在任務實際完成/失敗時發送
        } catch (e: Exception) {
            Log.e(TAG, "startMission failed: ${e.message}", e)
            logValidationFail("startMission exception: ${e.message}", payload.rawJson)
            MqttClientManager.publishAck(
                AckPayload(
                    msgId = payload.msgId, action = "mission_start",
                    status = "failed", errorMsg = e.message ?: "Unknown error"
                )
            )
        }
    }

    /** 處理多站任務啟動指令 */
    private fun handleMultiStart(payload: MultiStopPayload) {
        AppLog.d(TAG, "handleMultiStart: ${payload.stops.size} stops, msgId=${payload.msgId}")

        if (payload.stops.size < 2) {
            publishErrorAck(payload.msgId, "Multi-stop requires at least 2 stops", "multi_start")
            return
        }
        if (payload.stops.size > AppConfig.MAX_MULTI_STOPS) {
            publishErrorAck(payload.msgId, "Too many stops: ${payload.stops.size} (max ${AppConfig.MAX_MULTI_STOPS})", "multi_start")
            return
        }

        // ── 集中驗證每個站點（MissionValidator 統一處理） ──
        for ((i, stop) in payload.stops.withIndex()) {
            MissionValidator.validateStop(stop, maxAlt = AppConfig.getMaxFlightHeight().toDouble())?.let { error ->
                publishErrorAck(payload.msgId, "Stop ${i + 1}: $error", "multi_start")
                return
            }
        }

        // 距離預檢（需 GPS）
        val currentLoc = FlightManager.getCurrentLocation()
        if (currentLoc != null) {
            val maxRadius = AppConfig.getMaxFlightRadius().toDouble()
            // 檢查目前位置到第一站的距離
            val distToFirst = GeoMath.haversine(
                currentLoc.latitude, currentLoc.longitude,
                payload.stops[0].target.lat, payload.stops[0].target.lng
            )
            if (distToFirst > maxRadius) {
                Log.w(TAG, "Stop 1 too far: ${"%.0f".format(distToFirst)}m > ${maxRadius}m")
                publishErrorAck(payload.msgId, "Stop 1 too far: ${"%.0f".format(distToFirst)}m > ${"%.0f".format(maxRadius)}m", "multi_start")
                return
            }
            // 檢查站間距離
            for (i in 0 until payload.stops.size - 1) {
                val from = payload.stops[i].target
                val to = payload.stops[i + 1].target
                val interDist = GeoMath.haversine(from.lat, from.lng, to.lat, to.lng)
                if (interDist > maxRadius) {
                    Log.w(TAG, "Stop ${i + 1}→${i + 2} too far: ${"%.0f".format(interDist)}m > ${maxRadius}m")
                    publishErrorAck(payload.msgId, "Stop ${i + 1}→${i + 2} too far: ${"%.0f".format(interDist)}m > ${"%.0f".format(maxRadius)}m", "multi_start")
                    return
                }
            }
        } else {
            Log.w(TAG, "GPS not ready, skipping multi-stop distance pre-check (will be checked per-stop during flight)")
        }

        // 多站任務電池預檢
        val currentBattery = FlightManager.batteryPercent.value
        val threshold = FlightManager.effectiveThreshold.value
        if (currentBattery <= threshold) {
            publishErrorAck(payload.msgId, "Battery too low: $currentBattery% <= $threshold% threshold", "multi_start")
            return
        }

        // 起飛前電量提示：估算全站鏈總距離，若可能不足僅發布 WARNING，不阻擋
        try {
            val homeLoc = FlightManager.getCurrentLocation()
            if (homeLoc != null && payload.stops.isNotEmpty()) {
                var totalDist = GeoMath.haversine(homeLoc.latitude, homeLoc.longitude,
                    payload.stops.first().target.lat, payload.stops.first().target.lng)
                for (j in 0 until payload.stops.size - 1) {
                    totalDist += GeoMath.haversine(
                        payload.stops[j].target.lat, payload.stops[j].target.lng,
                        payload.stops[j + 1].target.lat, payload.stops[j + 1].target.lng
                    )
                }
                totalDist += GeoMath.haversine(
                    payload.stops.last().target.lat, payload.stops.last().target.lng,
                    homeLoc.latitude, homeLoc.longitude
                )
                val maxRadius = AppConfig.getMaxFlightRadius().toDouble()
                val estBattery = (totalDist / (maxRadius * 2.0) * 100).toInt().coerceIn(20, 70)
                if (currentBattery < estBattery + AppConfig.BATTERY_SAFETY_MARGIN) {
                    Log.w(TAG, "Multi-stop battery warning: totalDist=${"%.0f".format(totalDist)}m, estNeed=$estBattery%, have=$currentBattery% — proceeding anyway")
                    MqttClientManager.publish(MqttTopics.STATUS,
                        """{"state":"WARNING","message":"Battery may be insufficient for all stops","est_need_pct":$estBattery,"current_pct":$currentBattery,"total_dist_m":${"%.0f".format(totalDist)},"stops":${payload.stops.size}}"""
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Multi-stop battery estimation failed: ${e.message} — skipping warning")
        }

        if (MissionStateMachine.state.value != MissionStateMachine.MissionState.IDLE) {
            publishErrorAck(payload.msgId, "Mission already in progress", "multi_start")
            return
        }

        try {
        // 第一階段：指令已收到並開始執行
            MqttClientManager.publishAck(
                AckPayload(msgId = payload.msgId, action = "multi_start", status = "accepted")
            )
            MissionStateMachine.startMultiMission(payload.stops, mqttPayload = payload.rawJson, msgId = payload.msgId)
            // 第二階段（success/failed）由 MissionStateMachine 在任務實際完成/失敗時發送
        } catch (e: Exception) {
            Log.e(TAG, "startMultiMission failed: ${e.message}", e)
            publishErrorAck(payload.msgId, e.message ?: "Unknown error", "multi_start")
        }
    }

    /** 發布錯誤 ACK 給後端 */
    private fun publishErrorAck(msgId: String, errorMsg: String, action: String = "mission_start") {
        MqttClientManager.publishAck(
            AckPayload(
                msgId = msgId, action = action,
                status = "failed", errorMsg = errorMsg
            )
        )
    }
}
