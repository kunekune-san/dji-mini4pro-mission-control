package dji.sampleV5.aircraft.safety

/** 安全異常事件記錄（含座標、電池、GPS 資訊） */
data class SafetyAnomalyRecord(
    val timestamp: Long,
    val eventType: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val batteryPercent: Int,
    val gpsSignalLevel: Int,
    val gpsSatellites: Int,
    val reason: String
)
