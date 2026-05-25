package dji.sampleV5.aircraft.safety

/** 安全異常類型（電池低電量、GPS 訊號異常等） */
enum class SafetyAnomalyType(val mqttReason: String) {
    LOW_BATTERY_RTH("firmware_low_battery_rth"),
    BATTERY_FORCED_LAND("firmware_forced_landing"),
    GPS_SIGNAL_WEAK("gps_signal_degraded"),
    GPS_SIGNAL_LOST("gps_signal_lost")
}
