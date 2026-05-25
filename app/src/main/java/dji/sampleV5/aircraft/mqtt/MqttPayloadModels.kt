package dji.sampleV5.aircraft.mqtt

import org.json.JSONObject

// ── 收到的指令（Inbound） ─────────────────────────────

data class CommandPayload(
    val msgId: String,
    val cmd: String,           // "takeoff" | "rtl" | "cancel"
    val timestamp: Long
) {
    companion object {
        /** 從 JSON 字串解析飛行指令 */
        fun fromJson(json: String): CommandPayload? = try {
            val o = JSONObject(json)
            CommandPayload(
                msgId     = o.getString("msg_id"),
                cmd       = o.getString("cmd"),
                timestamp = o.getLong("timestamp")
            )
        } catch (e: Exception) { null }
    }
}

data class MissionTarget(
    val lat: Double,
    val lng: Double,
    val alt: Double
)

data class MissionOrbit(
    val radius: Double = 0.0,        // 公尺；0 = 原地旋轉
    val alt: Double? = null,          // 公尺；null = 使用 target.alt
    val gimbalPitch: Double = -45.0,  // 錄影時雲台俯仰角（度），預設 -45°
    val photoGimbalPitch: Double = -45.0  // 拍照時雲台俯仰角（度），預設 -45°
)

data class MissionStartPayload(
    val msgId: String,
    val mission: String,
    val target: MissionTarget,
    val orbit: MissionOrbit?,       // null = JSON 中無 orbit 區塊
    val photoCount: Int,
    val timestamp: Long
) {
    companion object {
        /** 從 JSON 字串解析任務啟動指令 */
        fun fromJson(json: String): MissionStartPayload? = try {
            val o = JSONObject(json)
            val t = o.getJSONObject("target")
            val orbitJson = o.optJSONObject("orbit")
            MissionStartPayload(
                msgId      = o.optString("msg_id", ""),
                mission    = o.getString("mission"),
                target     = MissionTarget(
                    lat = t.getDouble("lat"),
                    lng = t.getDouble("lng"),
                    alt = t.getDouble("alt")
                ),
                orbit      = orbitJson?.let {
                    MissionOrbit(
                        radius           = it.optDouble("radius", 0.0),
                        alt              = if (it.has("alt")) it.optDouble("alt", 0.0) else null,
                        gimbalPitch      = it.optDouble("gimbal_pitch", -45.0),
                        photoGimbalPitch = it.optDouble("photo_gimbal_pitch", -45.0)
                    )
                },
                photoCount = o.optInt("photo_count", 5),
                timestamp  = o.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (e: Exception) { null }
    }
}

// ── 發出的訊息（Outbound） ────────────────────────────

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
        put("lat",            lat ?: 0.0)
        put("lng",            lng ?: 0.0)
        put("alt",            alt)
        put("battery",        battery)
        put("heading",        heading)
        put("gps_satellites", gpsSatellites ?: 0)
        if (reason.isNotEmpty()) put("reason", reason)
        put("timestamp",      System.currentTimeMillis())
    }.toString()
}

data class HeartbeatPayload(val online: Boolean = true) {
    /** 轉換為 JSON 字串 */
    fun toJson(): String = JSONObject().apply {
        put("online",    online)
        put("timestamp", System.currentTimeMillis())
    }.toString()
}

// ── Safety Anomaly Reasons ──────────────────────────────

object SafetyReasons {
    const val FIRMWARE_LOW_BATTERY_RTH = "firmware_low_battery_rth"
    const val FIRMWARE_FORCED_LANDING = "firmware_forced_landing"
    const val GPS_SIGNAL_DEGRADED = "gps_signal_degraded"
    const val GPS_SIGNAL_LOST = "gps_signal_lost"
    const val GPS_RESTORED = "gps_signal_restored"
}
