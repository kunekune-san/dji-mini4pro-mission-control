package dji.sampleV5.aircraft.mqtt

import android.util.Log
import org.json.JSONObject

// ── 收到的指令（Inbound） ─────────────────────────────

/** 飛行指令 payload（takeoff/rtl/cancel/reset） */
data class CommandPayload(
    val msgId: String,
    val cmd: String,           // "takeoff" | "rtl" | "cancel" | "reset"
    val timestamp: Long
) {
    companion object {
        /** 從 JSON 字串解析飛行指令 */
        fun fromJson(json: String): CommandPayload? = try {
            val o = JSONObject(json)
            val msgId = o.optString("msg_id", "")
            val cmd = o.optString("cmd", "")
            if (msgId.isEmpty() || cmd.isEmpty()) {
                Log.e("MqttPayload", "CommandPayload.fromJson: missing msg_id or cmd, json=$json")
                null
            } else {
                CommandPayload(
                    msgId     = msgId,
                    cmd       = cmd,
                    timestamp = o.optLong("timestamp", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            Log.e("MqttPayload", "CommandPayload.fromJson failed: ${e.message}, json=$json")
            null
        }
    }
}

/** 任務目標座標（經緯度+高度） */
data class MissionTarget(
    val lat: Double,
    val lng: Double,
    val alt: Double
)

/** 環繞飛行參數（半徑、高度、雲台角度） */
data class MissionOrbit(
    val radius: Double = 0.0,        // 公尺；0 = 原地旋轉
    val alt: Double? = null,          // 公尺；null = 使用 target.alt
    val gimbalPitch: Double = -45.0,  // 錄影時雲台俯仰角（度），預設 -45°
    val photoGimbalPitch: Double = -45.0,  // 拍照時雲台俯仰角（度），預設 -45°
    val maxAlt: Double? = null,       // Mission C/D: 螺旋結束高度 Z_max（公尺）
    val turns: Int? = null,           // Mission C/D: 旋轉圈數 (3-5)
    val radiusEnd: Double? = null,    // Mission D: 結束半徑（公尺），null = 不擴展（同 C 模式）
    val orbitSpeed: Double = 1.0,     // C/D 環繞速度倍率（1.0=標準/60s, 1.5=快/40s, 2.0=極快/30s）
    val finalHeading: Double? = null  // Mission C/D: 螺旋結束後繼續繞行至此方位角再抬升雲台（0°=北，順時針），null = 不繞行直接拉平
)

/** 單站任務啟動指令 */
data class MissionStartPayload(
    val msgId: String,
    val mission: String,
    val target: MissionTarget,
    val orbit: MissionOrbit?,       // null = JSON 中無 orbit 區塊
    val photoCount: Int,
    val timestamp: Long,
    val rawJson: String = ""        // 原始 MQTT JSON，供飛行紀錄使用
) {
    companion object {
        /** 從 JSON 字串解析任務啟動指令 */
        fun fromJson(json: String): MissionStartPayload? = try {
            val o = JSONObject(json)
            val msgId = o.optString("msg_id", "")
            if (msgId.isEmpty()) null
            else {
                val t = o.getJSONObject("target")
                val orbitJson = o.optJSONObject("orbit")
                MissionStartPayload(
                    msgId      = msgId,
                    mission    = o.getString("mission"),
                    target     = MissionTarget(
                        lat = t.getDouble("lat"),
                        lng = t.getDouble("lng"),
                        alt = t.getDouble("alt")
                    ),
                    orbit      = orbitJson?.let {
                        MissionOrbit(
                            radius           = it.optDouble("radius", 0.0).takeIf { v -> !v.isNaN() } ?: 0.0,
                            alt              = if (it.has("alt")) it.optDouble("alt", 0.0).takeIf { v -> !v.isNaN() } else null,
                            gimbalPitch      = it.optDouble("gimbal_pitch", -45.0).takeIf { v -> !v.isNaN() } ?: -45.0,
                            photoGimbalPitch = it.optDouble("photo_gimbal_pitch", -45.0).takeIf { v -> !v.isNaN() } ?: -45.0,
                            maxAlt           = if (it.has("max_alt")) it.optDouble("max_alt", 0.0).takeIf { v -> !v.isNaN() } else null,
                            turns            = if (it.has("turns")) it.optInt("turns", 0).takeIf { v -> v > 0 } else null,
                            radiusEnd        = if (it.has("radius_end")) it.optDouble("radius_end", 0.0).takeIf { v -> !v.isNaN() } else null,
                            orbitSpeed       = it.optDouble("orbit_speed", 1.0).takeIf { v -> !v.isNaN() } ?: 1.0,
                            finalHeading     = if (it.has("final_heading")) it.optDouble("final_heading", 0.0).takeIf { v -> !v.isNaN() } else null
                        )
                    },
                    photoCount = o.optInt("photo_count", 7),  // 預設 7 張（6 張環景 + 1 張天底）
                    timestamp  = o.optLong("timestamp", System.currentTimeMillis()),
                    rawJson    = json
                )
            }
        } catch (e: Exception) {
            Log.e("MqttPayload", "MissionStartPayload.fromJson failed: ${e.message}, json=$json")
            null
        }
    }
}

// ── 發出的訊息（Outbound） ────────────────────────────

/** 指令執行結果回報 */
data class AckPayload(
    val msgId: String,
    val action: String,
    val status: String,        // "success" | "failed"
    val errorMsg: String = ""
) {
    /** 轉換為 JSON 字串 */
    fun toJson(): String = JSONObject().apply {
        put("msg_id",     msgId)
        put("action",     action)
        put("status",     status)
        put("error_msg",  errorMsg)
        put("timestamp",  System.currentTimeMillis())
    }.toString()
}

/** 無人機即時狀態（座標、電池、GPS） */
data class DroneStatusPayload(
    val state: String,
    val lat: Double?,
    val lng: Double?,
    val alt: Double,
    val battery: Int,
    val heading: Double,
    val gpsSatellites: Int?,
    val reason: String = ""
) {
    /** 轉換為 JSON 字串 */
    fun toJson(): String = JSONObject().apply {
        put("state",          state)
        if (lat != null) put("lat", lat) else put("lat", JSONObject.NULL)
        if (lng != null) put("lng", lng) else put("lng", JSONObject.NULL)
        put("alt",            alt)
        put("battery",        battery)
        put("heading",        heading)
        if (gpsSatellites != null) put("gps_satellites", gpsSatellites) else put("gps_satellites", JSONObject.NULL)
        if (reason.isNotEmpty()) put("reason", reason)
        put("timestamp",      System.currentTimeMillis())
    }.toString()
}

/** 心跳訊息 */
data class HeartbeatPayload(val online: Boolean = true) {
    /** 轉換為 JSON 字串 */
    fun toJson(): String = JSONObject().apply {
        put("online",    online)
        put("timestamp", System.currentTimeMillis())
    }.toString()
}

// ── Multi-Stop Mission (Inbound) ─────────────────────────

/** 多站任務的單站定義 */
data class StopDefinition(
    val mission: String,           // "A" or "B" or "C" or "D"
    val target: MissionTarget,
    val orbit: MissionOrbit? = null
) {
    companion object {
        fun fromJson(o: JSONObject): StopDefinition? = try {
            val t = o.getJSONObject("target")
            val orbitJson = o.optJSONObject("orbit")
            StopDefinition(
                mission = o.getString("mission"),
                target  = MissionTarget(
                    lat = t.getDouble("lat"),
                    lng = t.getDouble("lng"),
                    alt = t.getDouble("alt")
                ),
                orbit   = orbitJson?.let {
                    MissionOrbit(
                        radius           = it.optDouble("radius", 0.0).takeIf { v -> !v.isNaN() } ?: 0.0,
                        alt              = if (it.has("alt")) it.optDouble("alt", 0.0).takeIf { v -> !v.isNaN() } else null,
                        gimbalPitch      = it.optDouble("gimbal_pitch", -45.0).takeIf { v -> !v.isNaN() } ?: -45.0,
                        photoGimbalPitch = it.optDouble("photo_gimbal_pitch", -45.0).takeIf { v -> !v.isNaN() } ?: -45.0,
                        maxAlt           = if (it.has("max_alt")) it.optDouble("max_alt", 0.0).takeIf { v -> !v.isNaN() } else null,
                        turns            = if (it.has("turns")) it.optInt("turns", 0).takeIf { v -> v > 0 } else null,
                        radiusEnd        = if (it.has("radius_end")) it.optDouble("radius_end", 0.0).takeIf { v -> !v.isNaN() } else null,
                        orbitSpeed       = it.optDouble("orbit_speed", 1.0).takeIf { v -> !v.isNaN() } ?: 1.0,
                        finalHeading     = if (it.has("final_heading")) it.optDouble("final_heading", 0.0).takeIf { v -> !v.isNaN() } else null
                    )
                }
            )
        } catch (e: Exception) {
            Log.e("MqttPayload", "StopDefinition.fromJson failed: ${e.message}")
            null
        }
    }
}

/** 多站任務啟動指令 */
data class MultiStopPayload(
    val msgId: String,
    val stops: List<StopDefinition>,
    val timestamp: Long,
    val rawJson: String = ""        // 原始 MQTT JSON，供飛行紀錄使用
) {
    companion object {
        fun fromJson(json: String): MultiStopPayload? = try {
            val o = JSONObject(json)
            val msgId = o.optString("msg_id", "")
            if (msgId.isEmpty()) null
            else {
                val arr = o.getJSONArray("stops")
                val rawStops = (0 until arr.length()).map { arr.getJSONObject(it) }
                val parsedStops = rawStops.map { StopDefinition.fromJson(it) }
                val failedCount = parsedStops.count { it == null }
                if (failedCount > 0) {
                    Log.e("MqttPayload", "MultiStopPayload: $failedCount/${rawStops.size} stops failed to parse, rejecting entire payload")
                    null
                } else {
                    val stops = parsedStops.filterNotNull()
                    if (stops.size < 2) {
                        Log.e("MqttPayload", "MultiStopPayload: need at least 2 stops, got ${stops.size}")
                        null
                    } else MultiStopPayload(
                        msgId     = msgId,
                        stops     = stops,
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        rawJson   = json
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MqttPayload", "MultiStopPayload.fromJson failed: ${e.message}, json=$json")
            null
        }
    }
}

