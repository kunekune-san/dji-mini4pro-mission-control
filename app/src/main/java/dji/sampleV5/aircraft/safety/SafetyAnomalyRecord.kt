package dji.sampleV5.aircraft.safety

/** 安全異常事件記錄（含座標、電池、GPS 資訊、MQTT Payload） */
data class SafetyAnomalyRecord(
    val timestamp: Long,
    val eventType: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val batteryPercent: Int,
    val gpsSignalLevel: Int,
    val gpsSatellites: Int,
    val reason: String,
    val mqttPayload: String? = null  // 觸發任務的 MQTT 原始 JSON
)
